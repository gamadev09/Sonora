package com.music.bitchord.playback.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.music.bitchord.auth.TvPairingCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Discovers Sonora TV instances on the local Wi-Fi via mDNS (`_sonora-remote._tcp.`)
 * and controls their playback from the phone's AirPlay / Output sheet.
 */
object TvRemoteController {

    private const val TAG = "TvRemoteController"
    private const val SERVICE_TYPE = "_sonora-remote._tcp."
    private const val POLL_INTERVAL_MS = 1_500L

    data class TvDevice(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    data class TvStatus(
        val isPlaying: Boolean = false,
        val title: String = "Nothing playing",
        val artist: String = "Sonora",
        val artworkUrl: String? = null,
        val currentPositionMs: Long = 0L,
        val durationMs: Long = 0L,
        val volumePercent: Int = 50,
    )

    data class State(
        val devices: List<TvDevice> = emptyList(),
        val connectedDevice: TvDevice? = null,
        val status: TvStatus? = null,
        val error: String? = null,
    ) {
        val connected: Boolean get() = connectedDevice != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun discover(context: Context): () -> Unit {
        val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return {}

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { mgr.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { mgr.stopServiceDiscovery(this) }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("sonora-remote")) return
                @Suppress("DEPRECATION")
                mgr.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            if (!TvPairingCrypto.isPrivateLanIpv4(host) || port !in 1..65535) return
                            val cleanName = info.serviceName
                                .replace("Sonora TV", "Sonora")
                                .trim()
                                .ifBlank { "Sonora" }
                            val device = TvDevice(
                                id = "$host:$port",
                                name = cleanName,
                                host = host,
                                port = port,
                            )
                            _state.update { s ->
                                val updated = (s.devices.filterNot { it.id == device.id } + device)
                                    .sortedBy { it.name }
                                s.copy(devices = updated)
                            }
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                _state.update { s ->
                    s.copy(devices = s.devices.filterNot { it.name == lostName })
                }
            }
        }

        runCatching {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.w(TAG, "Failed to start mDNS discovery for TV remote", it)
        }

        return {
            runCatching { mgr.stopServiceDiscovery(discoveryListener) }
        }
    }

    fun connect(device: TvDevice) {
        _state.update { it.copy(connectedDevice = device, error = null) }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val fetched = fetchStatus(device)
                if (fetched != null) {
                    _state.update { it.copy(status = fetched, error = null) }
                } else {
                    _state.update { it.copy(error = "Unable to reach ${device.name}") }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        _state.update { it.copy(connectedDevice = null, status = null, error = null) }
    }

    fun playPause() = sendAction("play_pause")
    fun next() = sendAction("next")
    fun previous() = sendAction("previous")
    fun seek(ms: Long) = sendAction("seek", mapOf("ms" to ms.coerceAtLeast(0L).toString()))
    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _state.update { s -> s.copy(status = s.status?.copy(volumePercent = clamped)) }
        sendAction("volume", mapOf("level" to clamped.toString()))
    }

    private fun sendAction(action: String, extra: Map<String, String> = emptyMap()) {
        val device = _state.value.connectedDevice ?: return
        scope.launch {
            runCatching {
                val params = buildString {
                    append("action=").append(URLEncoder.encode(action, "UTF-8"))
                    extra.forEach { (k, v) ->
                        append('&').append(URLEncoder.encode(k, "UTF-8"))
                            .append('=').append(URLEncoder.encode(v, "UTF-8"))
                    }
                }
                val conn = (URL("http://${device.host}:${device.port}/api/action").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                }
                try {
                    conn.outputStream.use { it.write(params.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
                fetchStatus(device)?.let { refreshed ->
                    _state.update { it.copy(status = refreshed, error = null) }
                }
            }.onFailure { err ->
                Log.w(TAG, "Action $action failed", err)
            }
        }
    }

    private suspend fun fetchStatus(device: TvDevice): TvStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("http://${device.host}:${device.port}/api/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2_000
                readTimeout = 2_000
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                TvStatus(
                    isPlaying = json.optBoolean("isPlaying", false),
                    title = json.optString("title", "Nothing playing").ifBlank { "Nothing playing" },
                    artist = json.optString("artist", "Sonora").ifBlank { "Sonora" },
                    artworkUrl = json.optString("artworkUrl", "").takeIf { it.isNotBlank() },
                    currentPositionMs = json.optLong("currentPositionMs", 0L),
                    durationMs = json.optLong("durationMs", 0L),
                    volumePercent = json.optInt("volumePercent", 50).coerceIn(0, 100),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
