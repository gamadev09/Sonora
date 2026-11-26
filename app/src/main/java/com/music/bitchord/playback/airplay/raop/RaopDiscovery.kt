/*
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 */
package com.music.bitchord.playback.airplay.raop

import android.content.Context
import android.net.wifi.WifiManager
import com.music.bitchord.playback.airplay.AirPlayLog
import com.music.bitchord.playback.airplay.AirPlayTxt
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

data class RaopDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** The receiver's MAC, from the "AABBCCDDEEFF@Name" service name. */
    val mac: String?,
    /** The whole TXT record (et, cn, md, pw, sf, am, tp, ...). */
    val txt: Map<String, String>,
) {
    val model: String? get() = txt["am"]
    val sampleRate: Int get() = txt["sr"]?.toIntOrNull() ?: 44100
    val encryptionTypes: Set<Int> get() = AirPlayTxt.intList(txt["et"])

    /** We can stream unencrypted (0) or with RSA/AES (1); FairPlay-only receivers are out of reach. */
    fun isSupported(): Boolean = encryptionTypes.isEmpty() || 0 in encryptionTypes || 1 in encryptionTypes
}

/** Browses `_raop._tcp` (AirPlay 1 audio) with JmDNS, which exposes full TXT records. */
class RaopDiscovery(private val context: Context) {
    @Volatile private var jmdns: JmDNS? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var stopped = false

    fun start(onDeviceFound: (RaopDevice) -> Unit, onDeviceLost: (String) -> Unit = {}) {
        stopped = false
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null) {
            runCatching {
                multicastLock = wifi.createMulticastLock("BitChordRaopDiscovery").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        }

        val localAddr = RaopUtils.getLocalIpAddress() ?: run {
            AirPlayLog.w(TAG, "Could not determine local IPv4 address for JmDNS")
            releaseLock()
            return
        }

        Thread(
            {
                try {
                    if (stopped) return@Thread
                    val dns = JmDNS.create(localAddr)
                    if (stopped) {
                        runCatching { dns.close() }
                        return@Thread
                    }
                    jmdns = dns
                    dns.addServiceListener(
                        "_raop._tcp.local.",
                        object : ServiceListener {
                            override fun serviceAdded(event: ServiceEvent) {
                                jmdns?.requestServiceInfo(event.type, event.name)
                            }

                            override fun serviceRemoved(event: ServiceEvent) {
                                AirPlayLog.d(TAG, "Service removed: ${event.name}")
                                onDeviceLost(event.name.substringAfter("@"))
                            }

                            override fun serviceResolved(event: ServiceEvent) {
                                val info = event.info
                                val host = info.inet4Addresses.firstOrNull()?.hostAddress
                                    ?: info.inetAddresses.firstOrNull()?.hostAddress ?: return
                                val txt = LinkedHashMap<String, String>()
                                val names = info.propertyNames
                                while (names.hasMoreElements()) {
                                    val key = names.nextElement()
                                    info.getPropertyString(key)?.let { txt[key] = it }
                                }
                                val mac = info.name.substringBefore("@", "").takeIf { it.length == 12 }
                                val name = info.name.substringAfter("@").ifBlank { info.name }
                                val device = RaopDevice(name, host, info.port, mac, txt)
                                AirPlayLog.d(TAG, "Found RAOP device: $name at $host:${info.port} txt=$txt")
                                if (device.isSupported()) {
                                    onDeviceFound(device)
                                } else {
                                    AirPlayLog.w(TAG, "Device $name only offers encryption types ${device.encryptionTypes}, skipping")
                                }
                            }
                        },
                    )
                    AirPlayLog.i(TAG, "JmDNS discovery started on $localAddr")
                } catch (e: Exception) {
                    AirPlayLog.w(TAG, "Failed to start JmDNS: ${e.message}", e)
                    releaseLock()
                }
            },
            "bitchord-raop-jmdns",
        ).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopped = true
        val dns = jmdns
        jmdns = null
        Thread(
            {
                try {
                    dns?.close()
                } catch (e: Exception) {
                    AirPlayLog.w(TAG, "Error stopping JmDNS: ${e.message}")
                } finally {
                    releaseLock()
                    AirPlayLog.i(TAG, "JmDNS discovery stopped")
                }
            },
            "bitchord-raop-stop",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    companion object {
        private const val TAG = "RaopDiscovery"
    }
}
