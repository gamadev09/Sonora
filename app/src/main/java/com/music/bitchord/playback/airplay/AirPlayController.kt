/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.airplay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.R
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.playback.airplay.airplay2.AirPlay2Client
import com.music.bitchord.playback.airplay.airplay2.HapCredentials
import com.music.bitchord.playback.airplay.raop.RaopClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Manages AirPlay 1 (RAOP) & AirPlay 2 discovery, pairing, metadata/artwork push,
 * DACP remote control, receiver volume sync, and audio transport lifecycle.
 */
object AirPlayController {
    private const val TAG = "AirPlayController"

    data class State(
        val devices: List<AirPlayDevice> = emptyList(),
        val connectedName: String? = null,
        val connectedHost: String? = null,
        val connectingHost: String? = null,
        val supportsAirPlay2: Boolean = false,
        val error: String? = null,
    ) {
        val connected: Boolean get() = connectedHost != null
        val connecting: Boolean get() = connectingHost != null
    }

    data class PairingPrompt(
        val host: String,
        val name: String,
        val kind: AirPlayAuthRequiredException.Kind,
    )

    data class TrackMetadata(
        val videoId: String?,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val positionMs: Long?,
        val artworkUrl: String?,
    )

    interface PlaybackDelegate {
        fun onAirPlayConnected()
        fun onAirPlayRouteChanged()
        fun onAirPlayVolumeChanged()
        fun onAirPlayRemoteCommand(command: String)
        fun currentTrackMetadata(): TrackMetadata?
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: StateFlow<PairingPrompt?> = _pairingPrompt.asStateFlow()

    /** Hot-path flag read directly by [AirPlayAudioSink] on the ExoPlayer audio thread. */
    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var delegate: PlaybackDelegate? = null
    @Volatile private var activeStreamer: AirPlayStreamer? = null
    @Volatile private var ptpMaster: PtpMaster? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rtspExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bitchord-airplay-rtsp").apply { isDaemon = true }
    }
    private val pairingMutex = Mutex()
    @Volatile private var pendingSecretDeferred: CompletableDeferred<String?>? = null

    private var discovery: AirPlayDiscovery? = null
    private var discoveryForwardJob: Job? = null
    private var discoverySubscribers = 0

    private var connectJob: Job? = null
    private var artworkJob: Job? = null

    private var dacpServer: DacpServer? = null
    private var dacpRegistration: NsdManager.RegistrationListener? = null

    private val pendingVolumeScheduled = AtomicBoolean(false)
    @Volatile private var pendingVolumeDb = AirPlayVolume.percentToDb((DEFAULT_VOLUME * 100).roundToInt())

    @Volatile private var lastSentMetaKey: String? = null
    @Volatile private var lastSentArtworkUrl: String? = null
    @Volatile private var lastSentArtworkBytes: ByteArray? = null
    @Volatile private var lastVolumeBeforeMute = DEFAULT_VOLUME

    private val securePrefs: SharedPreferences by lazy {
        val ctx = checkNotNull(appContext) { "AirPlayController.ensureStarted() not called" }
        EncryptedPrefs.open(ctx, "bitchord_airplay_secure", "bitchord_airplay_plain")
    }

    private val senderIdentity: AirPlaySenderIdentity by lazy {
        val ctx = checkNotNull(appContext) { "AirPlayController.ensureStarted() not called" }
        val prefs = securePrefs
        val dacpId = prefs.getString(KEY_DACP_ID, null) ?: run {
            val id = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
            prefs.edit().putString(KEY_DACP_ID, id).apply()
            id
        }
        val activeRemote = prefs.getString(KEY_ACTIVE_REMOTE, null) ?: run {
            val id = (10_000_000..99_999_999).random().toString()
            prefs.edit().putString(KEY_ACTIVE_REMOTE, id).apply()
            id
        }
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val rng = SecureRandom()
            val id = (1..6).joinToString(":") { "%02X".format(rng.nextInt(256)) }
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }
        val seedHex = prefs.getString(KEY_PAIRING_SEED, null)?.takeIf { it.length == 64 } ?: run {
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val hex = seed.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_PAIRING_SEED, hex).apply()
            hex
        }
        val pairingId = prefs.getString(KEY_PAIRING_ID, null) ?: run {
            val id = UUID.randomUUID().toString().uppercase()
            prefs.edit().putString(KEY_PAIRING_ID, id).apply()
            id
        }
        AirPlaySenderIdentity(
            deviceId = deviceId,
            dacpId = dacpId,
            activeRemote = activeRemote,
            name = runCatching { ctx.getString(R.string.app_name) }.getOrDefault("BitChord"),
            model = Build.MODEL ?: "Android",
            pairingSeed = ByteArray(32) { seedHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
            pairingId = pairingId,
        )
    }

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            val savedVol = securePrefs.getFloat(KEY_RECEIVER_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)
            _volume.value = savedVol
            started = true
        }
    }

    fun attach(target: PlaybackDelegate) {
        delegate = target
    }

    fun detach(target: PlaybackDelegate) {
        if (delegate === target) delegate = null
    }

    /** Starts scanning while any caller holds the returned stop callback. */
    fun discover(context: Context): () -> Unit {
        ensureStarted(context)
        val app = context.applicationContext
        synchronized(this) {
            discoverySubscribers++
            if (discoverySubscribers == 1) {
                val disc = AirPlayDiscovery(app)
                discovery = disc
                disc.start()
                discoveryForwardJob?.cancel()
                discoveryForwardJob = scope.launch {
                    disc.devices.collect { list ->
                        _state.update { it.copy(devices = list) }
                    }
                }
            }
        }
        return {
            synchronized(this) {
                discoverySubscribers = (discoverySubscribers - 1).coerceAtLeast(0)
                if (discoverySubscribers == 0) {
                    discoveryForwardJob?.cancel()
                    discoveryForwardJob = null
                    discovery?.stop()
                    discovery = null
                }
            }
        }
    }

    fun connect(device: AirPlayDevice) {
        val ctx = appContext ?: return
        ensureStarted(ctx)

        connectJob?.cancel()
        connectJob = scope.launch {
            teardownActiveStreamer()
            _state.update {
                it.copy(
                    connectingHost = device.host,
                    connectedHost = null,
                    connectedName = null,
                    supportsAirPlay2 = device.supportsAirPlay2,
                    error = null,
                )
            }
            startDacpServer(ctx)
            if (device.supportsAirPlay2 && ptpMaster == null) {
                ptpMaster = PtpMaster.tryCreate()
            }

            var failures = 0
            while (isActive) {
                val ended = CompletableDeferred<String>()
                val listener = object : AirPlaySessionListener {
                    override fun onRemoteVolume(db: Double) {
                        val pct = AirPlayVolume.dbToPercent(db).coerceIn(0, 100) / 100f
                        if (kotlin.math.abs(pct - _volume.value) >= 0.01f) {
                            _volume.value = pct
                            securePrefs.edit().putFloat(KEY_RECEIVER_VOLUME, pct).apply()
                            delegate?.onAirPlayVolumeChanged()
                        }
                    }

                    override fun onRemoteCommand(command: String) {
                        delegate?.onAirPlayRemoteCommand(command)
                    }

                    override fun onDisconnected(reason: String) {
                        ended.complete(reason)
                    }
                }

                val streamer = try {
                    openSession(device, listener)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AirPlayBusyException) {
                    val msg = runCatching { ctx.getString(R.string.airplay_busy) }
                        .getOrDefault("Speaker is busy with another sender")
                    AirPlayLog.w(TAG, "${device.name} busy: ${e.message}")
                    _state.update { it.copy(connectingHost = null, error = msg) }
                    stopDacpServer(ctx)
                    return@launch
                } catch (e: AirPlayAuthRequiredException) {
                    AirPlayLog.w(TAG, "${device.name} auth error: ${e.message}")
                    _state.update { it.copy(connectingHost = null, error = e.message) }
                    stopDacpServer(ctx)
                    return@launch
                } catch (e: Exception) {
                    AirPlayLog.w(TAG, "${device.name} connection failed (${failures + 1}/3): ${e.message}")
                    if (++failures >= 3) {
                        _state.update {
                            it.copy(
                                connectingHost = null,
                                error = e.message ?: "Unable to connect to ${device.name}",
                            )
                        }
                        stopDacpServer(ctx)
                        return@launch
                    }
                    delay((1000.0 * 2.0.pow(failures.toDouble())).toLong())
                    continue
                }

                if (streamer == null) {
                    // User cancelled PIN/password prompt
                    _state.update { it.copy(connectingHost = null) }
                    stopDacpServer(ctx)
                    return@launch
                }

                failures = 0
                activeStreamer = streamer
                isStreaming = true
                lastSentMetaKey = null
                _state.update {
                    it.copy(
                        connectingHost = null,
                        connectedHost = device.host,
                        connectedName = device.name,
                        supportsAirPlay2 = streamer is AirPlay2Client,
                        error = null,
                    )
                }
                delegate?.onAirPlayConnected()
                delegate?.onAirPlayRouteChanged()
                delegate?.currentTrackMetadata()?.let { onTrackChanged(it) }

                val healthJob = scope.launch {
                    val intervals = longArrayOf(2000L, 4000L, 8000L, 15000L)
                    var idx = 0
                    while (isActive && activeStreamer === streamer) {
                        val waitMs = if (idx < intervals.size) intervals[idx++] else 15000L
                        delay(waitMs)
                        if (activeStreamer === streamer) {
                            AirPlayLog.i(TAG, "${device.name} stats: ${streamer.statsSummary()}")
                        }
                    }
                }

                val dropReason = try {
                    coroutineScope { ended.await() }
                } finally {
                    healthJob.cancel()
                    isStreaming = false
                    if (activeStreamer === streamer) activeStreamer = null
                    withContext(NonCancellable) {
                        runCatching { streamer.teardown() }
                        runCatching { streamer.close() }
                    }
                }

                AirPlayLog.w(TAG, "${device.name} dropped: $dropReason")
                _state.update {
                    it.copy(
                        connectedHost = null,
                        connectedName = null,
                        connectingHost = device.host,
                    )
                }
                delegate?.onAirPlayRouteChanged()
                delay(1500)
            }
        }
    }

    fun disconnect(resumeHere: Boolean = true) {
        val wasConnected = _state.value.connected || _state.value.connecting
        isStreaming = false
        connectJob?.cancel()
        connectJob = null
        cancelPairingPrompt()
        val streamer = activeStreamer
        activeStreamer = null
        lastSentMetaKey = null

        _state.update {
            it.copy(
                connectedName = null,
                connectedHost = null,
                connectingHost = null,
                error = null,
            )
        }
        if (wasConnected) {
            delegate?.onAirPlayRouteChanged()
        }

        val ctx = appContext
        rtspExecutor.execute {
            runCatching { streamer?.teardown() }
            runCatching { streamer?.close() }
            if (ctx != null) stopDacpServer(ctx)
            ptpMaster?.stop()
            ptpMaster = null
        }
    }

    fun submitPairingSecret(secret: String) {
        pendingSecretDeferred?.complete(secret.trim())
    }

    fun cancelPairingPrompt() {
        pendingSecretDeferred?.complete(null)
        _pairingPrompt.value = null
    }

    fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        if (clamped > 0f) lastVolumeBeforeMute = clamped
        _volume.value = clamped
        securePrefs.edit().putFloat(KEY_RECEIVER_VOLUME, clamped).apply()
        delegate?.onAirPlayVolumeChanged()

        val db = AirPlayVolume.percentToDb((clamped * 100f).roundToInt())
        pendingVolumeDb = db
        if (pendingVolumeScheduled.compareAndSet(false, true)) {
            rtspExecutor.execute {
                pendingVolumeScheduled.set(false)
                val targetDb = pendingVolumeDb
                runCatching { activeStreamer?.setVolume(targetDb) }
            }
        }
    }

    fun toggleMute() {
        val current = _volume.value
        if (current > 0f) setVolume(0f) else setVolume(lastVolumeBeforeMute.coerceAtLeast(0.15f))
    }

    fun adjustVolumeSteps(delta: Int) {
        setVolume(_volume.value + delta * 0.05f)
    }

    fun writePcm(pcm: ByteArray) {
        activeStreamer?.writePcm(pcm)
    }

    fun flush() {
        val streamer = activeStreamer ?: return
        streamer.flushFifoOnly()
        rtspExecutor.execute {
            runCatching { streamer.sendRtspFlush() }
        }
    }

    fun onTrackChanged(metadata: TrackMetadata) {
        val streamer = activeStreamer ?: return
        val key = "${metadata.videoId}|${metadata.title}|${metadata.artist}|${metadata.album}"
        if (lastSentMetaKey != key) {
            lastSentMetaKey = key
            rtspExecutor.execute {
                runCatching {
                    streamer.setMetadata(
                        title = metadata.title,
                        artist = metadata.artist,
                        album = metadata.album,
                        durationMs = metadata.durationMs,
                    )
                }
            }
            val url = metadata.artworkUrl
            if (!url.isNullOrBlank()) {
                if (url == lastSentArtworkUrl && lastSentArtworkBytes != null) {
                    val bytes = lastSentArtworkBytes!!
                    rtspExecutor.execute { runCatching { streamer.setArtwork(bytes, sniffMime(bytes)) } }
                } else {
                    artworkJob?.cancel()
                    artworkJob = scope.launch {
                        val bytes = fetchArtworkBytes(url)
                        if (bytes != null && bytes.isNotEmpty()) {
                            lastSentArtworkUrl = url
                            lastSentArtworkBytes = bytes
                            if (activeStreamer === streamer) {
                                rtspExecutor.execute {
                                    runCatching { streamer.setArtwork(bytes, sniffMime(bytes)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        val duration = metadata.durationMs ?: return
        val pos = metadata.positionMs ?: return
        if (duration > 0) {
            rtspExecutor.execute {
                runCatching { streamer.setProgress(pos, duration) }
            }
        }
    }

    fun onProgressTick(positionMs: Long, durationMs: Long) {
        val streamer = activeStreamer ?: return
        if (!isStreaming || durationMs <= 0L) return
        rtspExecutor.execute {
            runCatching { streamer.setProgress(positionMs, durationMs) }
        }
    }

    private suspend fun openSession(
        device: AirPlayDevice,
        listener: AirPlaySessionListener,
    ): AirPlayStreamer? {
        val volDb = AirPlayVolume.percentToDb((_volume.value * 100f).roundToInt())
        val identity = senderIdentity
        val pairingKey = KEY_HAP_PAIRING_PREFIX + (device.mac ?: device.host)
        val pwKey = KEY_PASSWORD_PREFIX + device.host
        val legacyKey = KEY_RAOP_LEGACY_PREFIX + device.host

        var password = securePrefs.getString(pwKey, null)
        var pin: String? = null
        var tryAirPlay2 = device.airplay2Port > 0

        repeat(4) {
            try {
                if (tryAirPlay2) {
                    val client = AirPlay2Client(
                        host = device.host,
                        port = device.airplay2Port,
                        identity = identity,
                        txt = device.airplay2Txt,
                        credentials = HapCredentials.deserialize(securePrefs.getString(pairingKey, null)),
                        pin = pin,
                        password = password,
                        initialVolumeDb = volDb,
                        ptpMaster = ptpMaster,
                        listener = listener,
                        onPaired = { creds -> securePrefs.edit().putString(pairingKey, creds.serialize()).apply() },
                        onCredentialsRejected = { securePrefs.edit().remove(pairingKey).apply() },
                    )
                    try {
                        client.connect()
                        return client
                    } catch (e: AirPlayTimingUnsupportedException) {
                        AirPlayLog.i(TAG, "${device.name}: ${e.message}; falling back to AirPlay 1 (RAOP)")
                        tryAirPlay2 = false
                        val raop = RaopClient(
                            host = device.host,
                            port = device.raopPort.takeIf { it > 0 } ?: device.airplay2Port,
                            identity = identity,
                            txt = device.raopTxt.ifEmpty { FALLBACK_RAOP_TXT },
                            receiverMac = device.mac,
                            password = password,
                            legacyPaired = securePrefs.getBoolean(legacyKey, false),
                            initialVolumeDb = volDb,
                            listener = listener,
                        )
                        raop.connect()
                        return raop
                    }
                } else {
                    val raop = RaopClient(
                        host = device.host,
                        port = device.raopPort.takeIf { it > 0 } ?: device.airplay2Port,
                        identity = identity,
                        txt = device.raopTxt.ifEmpty { FALLBACK_RAOP_TXT },
                        receiverMac = device.mac,
                        password = password,
                        legacyPaired = securePrefs.getBoolean(legacyKey, false),
                        initialVolumeDb = volDb,
                        listener = listener,
                    )
                    raop.connect()
                    return raop
                }
            } catch (e: AirPlayAuthRequiredException) {
                AirPlayLog.i(TAG, "${device.name} requires ${e.kind}: ${e.message}")
                if (e.kind == AirPlayAuthRequiredException.Kind.PIN && !tryAirPlay2) {
                    securePrefs.edit().remove(legacyKey).apply()
                    runCatching {
                        RaopClient(
                            device.host,
                            device.raopPort.takeIf { it > 0 } ?: device.airplay2Port,
                            identity,
                        ).requestPinDisplay()
                    }
                }
                val secret = requestSecret(device, e.kind) ?: return null
                when (e.kind) {
                    AirPlayAuthRequiredException.Kind.PASSWORD -> {
                        password = secret
                        securePrefs.edit().putString(pwKey, secret).apply()
                    }
                    AirPlayAuthRequiredException.Kind.PIN -> {
                        if (tryAirPlay2) {
                            pin = secret
                        } else {
                            RaopClient(
                                device.host,
                                device.raopPort.takeIf { it > 0 } ?: device.airplay2Port,
                                identity,
                            ).pairWithPin(secret)
                            securePrefs.edit().putBoolean(legacyKey, true).apply()
                        }
                    }
                }
            }
        }
        throw AirPlayAuthRequiredException(
            AirPlayAuthRequiredException.Kind.PASSWORD,
            device.host,
            "Authentication rejected",
        )
    }

    private suspend fun requestSecret(
        device: AirPlayDevice,
        kind: AirPlayAuthRequiredException.Kind,
    ): String? = pairingMutex.withLock {
        val deferred = CompletableDeferred<String?>()
        pendingSecretDeferred = deferred
        _pairingPrompt.value = PairingPrompt(device.host, device.name, kind)
        try {
            withTimeoutOrNull(PAIRING_PROMPT_TIMEOUT_MS) { deferred.await() }?.takeIf { it.isNotEmpty() }
        } finally {
            _pairingPrompt.value = null
            pendingSecretDeferred = null
        }
    }

    private fun startDacpServer(context: Context) {
        if (dacpServer != null) return
        val identity = senderIdentity
        val server = DacpServer(identity.activeRemote) { cmd ->
            when (cmd) {
                DacpServer.Command.Play -> delegate?.onAirPlayRemoteCommand("play")
                DacpServer.Command.Pause -> delegate?.onAirPlayRemoteCommand("pause")
                DacpServer.Command.PlayPause -> delegate?.onAirPlayRemoteCommand("playpause")
                DacpServer.Command.Stop -> delegate?.onAirPlayRemoteCommand("pause")
                DacpServer.Command.Next -> delegate?.onAirPlayRemoteCommand("nextitem")
                DacpServer.Command.Previous -> delegate?.onAirPlayRemoteCommand("previtem")
                DacpServer.Command.VolumeUp -> adjustVolumeSteps(+1)
                DacpServer.Command.VolumeDown -> adjustVolumeSteps(-1)
                DacpServer.Command.MuteToggle -> toggleMute()
                is DacpServer.Command.SetVolume -> {
                    val pct = if (cmd.isPercent) cmd.value / 100.0 else AirPlayVolume.dbToPercent(cmd.value) / 100.0
                    setVolume(pct.toFloat())
                }
                DacpServer.Command.ShuffleToggle, DacpServer.Command.RepeatAdvance -> Unit
            }
        }
        val port = runCatching { server.start() }.getOrElse {
            AirPlayLog.w(TAG, "DACP server failed to start: ${it.message}")
            return
        }
        dacpServer = server
        runCatching {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = DacpServer.serviceName(identity.dacpId)
                serviceType = DacpServer.SERVICE_TYPE
                setPort(port)
                DacpServer.txtRecords(identity.dacpId).forEach { (k, v) -> setAttribute(k, v) }
            }
            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(si: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) = Unit
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            dacpRegistration = registration
        }
    }

    private fun stopDacpServer(context: Context) {
        dacpRegistration?.let { reg ->
            runCatching { (context.getSystemService(Context.NSD_SERVICE) as NsdManager).unregisterService(reg) }
        }
        dacpRegistration = null
        dacpServer?.stop()
        dacpServer = null
    }

    private fun teardownActiveStreamer() {
        isStreaming = false
        val old = activeStreamer
        activeStreamer = null
        if (old != null) {
            runCatching { old.teardown() }
            runCatching { old.close() }
        }
    }

    private suspend fun fetchArtworkBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext null
        val request = ImageRequest.Builder(ctx)
            .data(url)
            .size(ARTWORK_MAX_PX)
            .allowHardware(false)
            .build()
        val result = runCatching { SingletonImageLoader.get(ctx).execute(request) }.getOrNull()
        val bmp = (result as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        stream.toByteArray()
    }

    private fun sniffMime(bytes: ByteArray): String =
        if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"

    private const val DEFAULT_VOLUME = 0.7f
    private const val ARTWORK_MAX_PX = 600
    private const val PAIRING_PROMPT_TIMEOUT_MS = 180_000L

    private const val KEY_DACP_ID = "dacp_id"
    private const val KEY_ACTIVE_REMOTE = "active_remote"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PAIRING_SEED = "pairing_seed"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_RECEIVER_VOLUME = "receiver_volume"
    private const val KEY_HAP_PAIRING_PREFIX = "hap_"
    private const val KEY_RAOP_LEGACY_PREFIX = "raop_legacy_"
    private const val KEY_PASSWORD_PREFIX = "pw_"

    private val FALLBACK_RAOP_TXT = mapOf("md" to "0,1,2", "et" to "0,1", "cn" to "0,1")
}
