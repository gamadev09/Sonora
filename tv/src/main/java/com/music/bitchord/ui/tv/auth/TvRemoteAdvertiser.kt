package com.music.bitchord.ui.tv.auth

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Advertises Sonora's local TV remote control endpoint over both:
 * 1. mDNS/DNS-SD (`_sonora-remote._tcp.`)
 * 2. Fast UDP LAN broadcast responder on port [DISCOVERY_UDP_PORT] (8766)
 * so the Sonora phone app finds the TV in milliseconds even on routers/TVs where mDNS is filtered.
 */
object TvRemoteAdvertiser {
    private const val TAG = "TvRemoteAdvertiser"
    const val SERVICE_TYPE = "_sonora-remote._tcp."
    const val DISCOVERY_UDP_PORT = 8766
    const val DISCOVERY_PROBE = "SONORA_DISCOVER"

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var udpSocket: DatagramSocket? = null
    private val running = AtomicBoolean(false)

    private fun friendlyDeviceName(context: Context): String {
        val systemName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()
        if (!systemName.isNullOrBlank()) return systemName
        val model = Build.MODEL.orEmpty().trim().replace('_', ' ')
        return if (model.isNotBlank() && !model.equals("SMART TV", ignoreCase = true)) {
            model
        } else {
            "Sonora"
        }
    }

    fun start(context: Context, port: Int) {
        if (port <= 0) return
        stop()
        running.set(true)
        val serviceName = friendlyDeviceName(context)

        // 1. UDP broadcast discovery responder (sub-20ms LAN discovery)
        Thread {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_UDP_PORT))
                }
                udpSocket = socket
                val buf = ByteArray(256)
                Log.d(TAG, "UDP discovery responder listening on :$DISCOVERY_UDP_PORT for HTTP :$port")
                while (running.get() && !socket.isClosed) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                    if (msg == DISCOVERY_PROBE) {
                        val reply = JSONObject()
                            .put("name", serviceName)
                            .put("port", port)
                            .toString()
                            .toByteArray(Charsets.UTF_8)
                        val out = DatagramPacket(reply, reply.size, packet.address, packet.port)
                        socket.send(out)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "UDP discovery responder stopped", e)
            }
        }.apply {
            isDaemon = true
            name = "SonoraTvUdpDiscovery"
            start()
        }

        // 2. mDNS / DNS-SD advertisement
        val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Registered mDNS remote service: ${serviceInfo.serviceName} on port $port")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        nsdManager = mgr
        listener = regListener
        runCatching {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        }.onFailure {
            Log.w(TAG, "Failed to register mDNS service", it)
        }
    }

    fun stop() {
        running.set(false)
        runCatching { udpSocket?.close() }
        udpSocket = null
        val mgr = nsdManager
        val l = listener
        nsdManager = null
        listener = null
        if (mgr != null && l != null) {
            runCatching { mgr.unregisterService(l) }
        }
    }
}
