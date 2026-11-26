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
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.bitchord.auth.EncryptedPrefs
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
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Manages UPnP / DLNA MediaRenderer discovery, local HTTP WAV streaming,
 * SOAP AVTransport playback control, DIDL-Lite track metadata/artwork updates,
 * and RenderingControl volume sync.
 */
object DlnaController {
    private const val TAG = "DlnaController"

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
                val disc = DlnaDiscovery(app)
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

    fun connect(device: DlnaDevice) {
        val ctx = appContext ?: return
        ensureStarted(ctx)

        connectJob?.cancel()
        connectJob = scope.launch {
            teardownActiveSession(sendStop = true)
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
                val resolvedMeta = resolveMetadataForDidl(ctx, initialMeta, localIp)

                runCatching {
                    postSoap(
                        controlUrl = device.avControlUrl,
                        service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                        action = "Stop",
                        body = DlnaProtocol.stopEnvelope(),
                    )
                }

                postSoap(
                    controlUrl = device.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "SetAVTransportURI",
                    body = DlnaProtocol.setAvTransportUriEnvelope(streamUrl, resolvedMeta),
                )
                postSoap(
                    controlUrl = device.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "Play",
                    body = DlnaProtocol.playEnvelope(),
                )

                activeDevice = device
                isStreaming = true
                lastSentMetaKey = metaKey(initialMeta)
                _state.update {
                    it.copy(
                        connectingUdn = null,
                        connectedUdn = device.udn,
                        connectedName = device.name,
                        error = null,
                    )
                }
                val rcUrl = device.rcControlUrl
                if (rcUrl != null) {
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
                delegate?.onDlnaConnected()
                delegate?.onDlnaRouteChanged()
                Log.i(TAG, "Connected DLNA renderer ${device.name} (${device.host}) -> $streamUrl")
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
                    val dev = activeDevice
                    val rcUrl = dev?.rcControlUrl
                    if (isStreaming && rcUrl != null) {
                        val target = pendingVolumePercent
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

    fun onTrackChanged(metadata: TrackMetadata) {
        val dev = activeDevice ?: return
        if (!isStreaming) return
        val key = metaKey(metadata)
        if (key == lastSentMetaKey && metadata.artworkUrl == lastSentArtKey) return
        lastSentMetaKey = key
        lastSentArtKey = metadata.artworkUrl

        val ctx = appContext ?: return
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val localIp = getLocalIpAddress() ?: return@launch
            if (httpServer.port <= 0) httpServer.start()
            val streamUrl = httpServer.streamUrl(localIp)
            val resolvedMeta = resolveMetadataForDidl(ctx, metadata, localIp)
            if (!isStreaming || activeDevice?.udn != dev.udn) return@launch
            runCatching {
                postSoap(
                    controlUrl = dev.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "SetAVTransportURI",
                    body = DlnaProtocol.setAvTransportUriEnvelope(streamUrl, resolvedMeta),
                )
                postSoap(
                    controlUrl = dev.avControlUrl,
                    service = DlnaProtocol.SERVICE_AV_TRANSPORT,
                    action = "Play",
                    body = DlnaProtocol.playEnvelope(),
                )
            }.onFailure {
                Log.w(TAG, "DLNA metadata update failed: ${it.message}")
            }
        }
    }

    private suspend fun resolveMetadataForDidl(
        context: Context,
        metadata: TrackMetadata?,
        localIp: String,
    ): DlnaProtocol.TrackMetadata? {
        if (metadata == null) return null
        val url = metadata.artworkUrl
        var artForDidl: String? = url?.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        }

        if (!url.isNullOrBlank()) {
            val bytes = loadArtworkJpeg(context, url)
            if (bytes != null) {
                httpServer.artworkBytes = bytes
                artForDidl = httpServer.artworkUrl(localIp, bytes)
            }
        } else {
            httpServer.artworkBytes = null
        }

        return DlnaProtocol.TrackMetadata(
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            artworkUrl = artForDidl,
        )
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
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                baos.toByteArray()
            }.getOrNull()
        }

    private fun postSoap(
        controlUrl: String,
        service: String,
        action: String,
        body: String,
    ) {
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("User-Agent", "BitChord/1.0 UPnP/1.0 DLNADOC/1.50")
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$service#$action\"")
        }
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val errText = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                error("SOAP $action HTTP $code: ${errText.orEmpty().take(200)}")
            }
            conn.inputStream.use { it.readBytes() }
            Log.d(TAG, "SOAP $action -> HTTP $code ($controlUrl)")
        } finally {
            conn.disconnect()
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
