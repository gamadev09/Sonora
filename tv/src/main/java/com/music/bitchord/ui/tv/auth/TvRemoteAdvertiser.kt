package com.music.bitchord.ui.tv.auth

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Advertises Sonora's local TV remote control endpoint over mDNS/DNS-SD (`_sonora-remote._tcp.`)
 * so the Sonora phone app can discover and control TV playback from its AirPlay / Output sheet.
 */
object TvRemoteAdvertiser {
    private const val TAG = "TvRemoteAdvertiser"
    const val SERVICE_TYPE = "_sonora-remote._tcp."

    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null
    private val registered = AtomicBoolean(false)

    fun start(context: Context, port: Int) {
        if (port <= 0) return
        stop()
        val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val model = Build.MODEL.orEmpty().trim().replace('_', ' ')
        val serviceName = if (model.isNotBlank() && !model.equals("SMART TV", ignoreCase = true)) {
            "Sonora ($model)"
        } else {
            "Sonora TV"
        }
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registered.set(true)
                Log.d(TAG, "Registered mDNS remote service: ${serviceInfo.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
                Log.w(TAG, "mDNS registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registered.set(false)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered.set(false)
            }
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
        val mgr = nsdManager
        val l = listener
        nsdManager = null
        listener = null
        if (mgr != null && l != null) {
            runCatching { mgr.unregisterService(l) }
        }
        registered.set(false)
    }
}
