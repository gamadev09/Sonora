/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.dlna

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.auth.EncryptedPrefs
import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Manages UPnP / DLNA MediaRenderer discovery, local HTTP WAV streaming,
 * SOAP AVTransport playback control, DIDL-Lite track metadata/artwork updates,
 * and RenderingControl volume sync.
 */
object DlnaController {
    private const val TAG = "DlnaController"
    private val SOAP_XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

    /** Fast LAN-tuned OkHttp client sharing the app's connection pool for SOAP keep-alive reuse. */
    private val soapClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class State(
        val devices: List<DlnaDevice> = emptyList(),
        val connectedName: String? = null,
        val connectedUdn: String? = null,
        val connectingUdn: String? = null,
        val error: String? = null,
    ) {
        val connected: Boolean get() = connectedUdn != null
        val connecting: Boolean get() = connectingUdn != null
    }

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
        fun onDlnaConnected()
        fun onDlnaRouteChanged()
        fun onDlnaVolumeChanged()
        fun currentTrackMetadata(): TrackMetadata?
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _volume = MutableStateFlow(DEFAULT_VOLUME)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** Hot-path flag read directly by [AirPlayAudioSink] / PCM routing sink. */
    @Volatile
    var isStreaming: Boolean = false
        private set

    @Volatile private var appContext: Context? = null
    @Volatile private var started = false
    @Volatile private var delegate: PlaybackDelegate? = null
    @Volatile private var activeDevice: DlnaDevice? = null

    private val httpServer = DlnaHttpServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var discovery: DlnaDiscovery? = null
    private var discoveryForwardJob: Job? = null
    private var discoverySubscribers = 0

    private var connectJob: Job? = null
    private var artworkJob: Job? = null
    private var lastSentMetaKey: String? = null
    private var lastSentArtKey: String? = null
    private var lastVolumeBeforeMute = DEFAULT_VOLUME

    private val pendingVolumeScheduled = AtomicBoolean(false)
    @Volatile private var pendingVolumePercent = (DEFAULT_VOLUME * 100f).roundToInt()

    private val prefs: SharedPreferences by lazy {
        val ctx = checkNotNull(appContext) { "DlnaController.ensureStarted() not called" }
        EncryptedPrefs.open(ctx, "bitchord_dlna_secure", "bitchord_dlna_plain")
    }

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            appContext = context.applicationContext
            val savedVol = prefs.getFloat(KEY_RECEIVER_VOLUME, DEFAULT_VOLUME).coerceIn(0f, 1f)
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

    fun discover(context: Context): () -> Unit {
        ensureStarted(context)
        val app = context.applicationContext
        synchronized(this) {
            discoverySubscribers++
            if (discoverySubscribers == 1) {
                // Reuse a single DlnaDiscovery instance so its warm LAN cache surfaces
                // previously discovered renderers immediately on sheet reopen.
                val disc = discovery ?: DlnaDiscovery(app).also { discovery = it }
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
                    discovery?.stop(keepCache = true)
                }
            }
        }
    }

    fun connect(device: DlnaDevice) {
        val ctx = appContext ?: return
        ensureStarted(ctx)

        connectJob?.cancel()
        connectJob = scope.launch {
            val connectStartMs = SystemClock.elapsedRealtime()
            teardownActiveSession(sendStop = activeDevice != null && activeDevice?.udn != device.udn)
            _state.update {
                it.copy(
                    connectingUdn = device.udn,
                    connectedUdn = null,
                    connectedName = null,
                    error = null,
                )
            }
            try {
                val localIp = getLocalIpAddress()
                    ?: error("No local Wi-Fi IPv4 address available for DLNA streaming")
                httpServer.start()
                val streamUrl = httpServer.streamUrl(localIp)
                val initialMeta = delegate?.currentTrackMetadata()
                // Do not block playback startup on artwork download/JPEG re-encoding:
                // pass the direct artwork URL immediately and cache local JPEG asynchronously.
                val fastMeta = initialMeta?.let {
                    DlnaProtocol.TrackMetadata(
                        title = it.title,
                        artist = it.artist,
                        album = it.album,
                        artworkUrl = it.artworkUrl?.takeIf { u ->
                            u.startsWith("http://", ignoreCase = true) ||
                                u.startsWith("https://", ignoreCase = true)
                        },
                    )
                }

                // Enable PCM streaming before Play so audio bytes are ready the moment
                // the renderer opens /stream.wav, cutting startup latency.
                activeDevice = device
                isStreaming = true

                val setUriStartMs = SystemClock.elapsedRealtime()
                postSoap(
                    controlUrl = device.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "SetAVTransportURI",
                    body = DlnaProtocol.setAvTransportUriEnvelope(streamUrl, fastMeta),
                )
                val playStartMs = SystemClock.elapsedRealtime()
                postSoap(
                    controlUrl = device.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "Play",
                    body = DlnaProtocol.playEnvelope(),
                )
                val playAckMs = SystemClock.elapsedRealtime() - playStartMs

                lastSentMetaKey = metaKey(initialMeta)
                lastSentArtKey = initialMeta?.artworkUrl
                _state.update {
                    it.copy(
                        connectingUdn = null,
                        connectedUdn = device.udn,
                        connectedName = device.name,
                        error = null,
                    )
                }
                delegate?.onDlnaConnected()
                delegate?.onDlnaRouteChanged()
                Log.i(
                    TAG,
                    "Connected DLNA ${device.name} (${device.host}) in " +
                        "${SystemClock.elapsedRealtime() - connectStartMs}ms " +
                        "(SetURI=${playStartMs - setUriStartMs}ms, PlayAck=${playAckMs}ms) -> $streamUrl",
                )

                // Sync volume and warm local artwork cache asynchronously without delaying audio start.
                val rcUrl = device.rcControlUrl
                if (rcUrl != null) {
                    scope.launch {
                        runCatching {
                            val volPercent = (_volume.value * 100f).roundToInt().coerceIn(0, 100)
                            postSoap(
                                controlUrl = rcUrl,
                                service = DlnaProtocol.SERVICE_RENDERING_CONTROL,
                                action = "SetVolume",
                                body = DlnaProtocol.setVolumeEnvelope(volPercent),
                            )
                        }
                    }
                }
                val artUrl = initialMeta?.artworkUrl
                if (!artUrl.isNullOrBlank()) {
                    artworkJob?.cancel()
                    artworkJob = scope.launch {
                        httpServer.artworkBytes = loadArtworkJpeg(ctx, artUrl)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "DLNA connection failed for ${device.name}: ${e.message}", e)
                teardownActiveSession(sendStop = false)
                _state.update {
                    it.copy(
                        connectingUdn = null,
                        connectedUdn = null,
                        connectedName = null,
                        error = e.message ?: "DLNA connection failed",
                    )
                }
                delegate?.onDlnaRouteChanged()
            }
        }
    }

    fun disconnect(resumeHere: Boolean = true) {
        val wasConnected = _state.value.connected || _state.value.connecting
        val prevDevice = activeDevice
        isStreaming = false
        activeDevice = null
        lastSentMetaKey = null
        lastSentArtKey = null
        artworkJob?.cancel()
        artworkJob = null
        connectJob?.cancel()
        connectJob = null
        httpServer.artworkBytes = null

        _state.update {
            it.copy(
                connectingUdn = null,
                connectedUdn = null,
                connectedName = null,
                error = null,
            )
        }
        if (wasConnected) {
            delegate?.onDlnaRouteChanged()
        }
        if (!resumeHere) {
            Log.d(TAG, "Disconnected DLNA silently for another output")
        }

        scope.launch {
            withContext(NonCancellable) {
                httpServer.stop()
                if (prevDevice != null) {
                    runCatching {
                        postSoap(
                            controlUrl = prevDevice.avControlUrl,
                            service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                            action = "Stop",
                            body = DlnaProtocol.stopEnvelope(),
                        )
                    }
                }
            }
        }
    }

    fun writePcm(pcm44k16Stereo: ByteArray) {
        if (!isStreaming) return
        httpServer.pushPcm(pcm44k16Stereo)
    }

    fun flush() {
        httpServer.flush()
    }

    fun setVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped > 0f) lastVolumeBeforeMute = clamped
        _volume.value = clamped
        if (started) {
            prefs.edit().putFloat(KEY_RECEIVER_VOLUME, clamped).apply()
        }
        delegate?.onDlnaVolumeChanged()

        pendingVolumePercent = (clamped * 100f).roundToInt().coerceIn(0, 100)
        if (pendingVolumeScheduled.compareAndSet(false, true)) {
            scope.launch {
                try {
                    var lastSent = -1
                    while (true) {
                        val target = pendingVolumePercent
                        if (target == lastSent) break
                        val dev = activeDevice
                        val rcUrl = dev?.rcControlUrl
                        if (!isStreaming || rcUrl == null) break
                        lastSent = target
                        runCatching {
                            postSoap(
                                controlUrl = rcUrl,
                                service = DlnaProtocol.SERVICE_RENDERING_CONTROL,
                                action = "SetVolume",
                                body = DlnaProtocol.setVolumeEnvelope(target),
                            )
                        }
                    }
                } finally {
                    pendingVolumeScheduled.set(false)
                }
            }
        }
    }

    fun adjustVolumeSteps(steps: Int) {
        if (steps == 0) return
        val step = 1f / 20f
        setVolume((_volume.value + steps * step).coerceIn(0f, 1f))
    }

    fun toggleMute() {
        if (_volume.value > 0f) {
            lastVolumeBeforeMute = _volume.value
            setVolume(0f)
        } else {
            setVolume(lastVolumeBeforeMute.takeIf { it > 0f } ?: DEFAULT_VOLUME)
        }
    }

    /**
     * Updates cached artwork on track change WITHOUT tearing down and re-issuing
     * `SetAVTransportURI` + `Play` over the continuous live `/stream.wav` stream,
     * eliminating the 2–5 second renderer reconnect/rebuffer stall on every track skip.
     */
    fun onTrackChanged(metadata: TrackMetadata) {
        if (!isStreaming || activeDevice == null) return
        val key = metaKey(metadata)
        if (key == lastSentMetaKey && metadata.artworkUrl == lastSentArtKey) return
        lastSentMetaKey = key
        lastSentArtKey = metadata.artworkUrl

        val ctx = appContext ?: return
        val artUrl = metadata.artworkUrl
        artworkJob?.cancel()
        if (artUrl.isNullOrBlank()) {
            httpServer.artworkBytes = null
            return
        }
        artworkJob = scope.launch {
            httpServer.artworkBytes = loadArtworkJpeg(ctx, artUrl)
        }
    }

    private suspend fun loadArtworkJpeg(context: Context, artworkUrl: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val loader = SingletonImageLoader.get(context)
                val req = ImageRequest.Builder(context)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(640, 640)
                    .build()
                val res = loader.execute(req) as? SuccessResult ?: return@runCatching null
                val bmp = res.image.toBitmap()
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                baos.toByteArray()
            }.getOrNull()
        }

    private fun postSoap(
        controlUrl: String,
        service: String,
        action: String,
        body: String,
    ) {
        val request = Request.Builder()
            .url(controlUrl)
            .header("User-Agent", "BitChord/1.0 UPnP/1.0 DLNADOC/1.50")
            .header("SOAPACTION", "\"$service#$action\"")
            .post(body.toRequestBody(SOAP_XML_MEDIA_TYPE))
            .build()
        soapClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errText = runCatching { response.body?.string() }.getOrNull()
                error("SOAP $action HTTP ${response.code}: ${errText.orEmpty().take(200)}")
            }
            // Drain body so OkHttp returns the keep-alive socket to the pool immediately.
            response.body?.bytes()
            Log.d(TAG, "SOAP $action -> HTTP ${response.code} ($controlUrl)")
        }
    }

    private fun teardownActiveSession(sendStop: Boolean) {
        artworkJob?.cancel()
        artworkJob = null
        val prev = activeDevice
        isStreaming = false
        activeDevice = null
        lastSentMetaKey = null
        lastSentArtKey = null
        httpServer.artworkBytes = null
        httpServer.stop()
        if (sendStop && prev != null) {
            runCatching {
                postSoap(
                    controlUrl = prev.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "Stop",
                    body = DlnaProtocol.stopEnvelope(),
                )
            }
        }
    }

    private fun getLocalIpAddress(): String? = runCatching {
        val list = mutableListOf<Inet4Address>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val name = iface.name.lowercase()
            if (name.contains("tun") || name.contains("ppp") || name.contains("tap") || name.contains("docker")) {
                continue
            }
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address) list.add(addr)
            }
        }
        list.firstOrNull { it.hostAddress?.startsWith("192.168.") == true }?.hostAddress
            ?: list.firstOrNull { it.hostAddress?.startsWith("10.") == true }?.hostAddress
            ?: list.firstOrNull { it.hostAddress?.startsWith("172.") == true }?.hostAddress
            ?: list.firstOrNull()?.hostAddress
    }.getOrNull()

    private fun metaKey(meta: TrackMetadata?): String =
        if (meta == null) "" else "${meta.videoId}|${meta.title}|${meta.artist}|${meta.album}"

    private const val KEY_RECEIVER_VOLUME = "dlna_receiver_volume"
    private const val DEFAULT_VOLUME = 0.7f
}
