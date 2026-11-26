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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.music.bitchord.playback.airplay.raop.RaopDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

data class AirPlayDevice(
    val id: String,
    val name: String,
    val host: String,
    val airplay2Port: Int,
    val raopPort: Int,
    val mac: String? = null,
    val model: String? = null,
    val airplay2Txt: Map<String, String> = emptyMap(),
    val raopTxt: Map<String, String> = emptyMap(),
) {
    val supportsAirPlay2: Boolean get() = airplay2Port > 0
    val supportsRaop: Boolean get() = raopPort > 0 || airplay2Port > 0

    val requiresPassword: Boolean
        get() {
            if (airplay2Txt["pw"] == "true" || raopTxt["pw"] == "true") return true
            val flags = AirPlayTxt.flags(airplay2Txt["flags"] ?: airplay2Txt["sf"] ?: raopTxt["sf"])
            return (flags and AirPlayTxt.STATUS_PASSWORD_REQUIRED) != 0L
        }
}

/**
 * Discovers both AirPlay 2 (`_airplay._tcp` via [NsdManager]) and AirPlay 1 / RAOP
 * (`_raop._tcp.local.` via JmDNS) speakers, merging both protocols per receiver host.
 */
internal class AirPlayDiscovery(context: Context) {

    private val app = context.applicationContext
    private val nsdManager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _devices = MutableStateFlow<List<AirPlayDevice>>(emptyList())
    val devices: StateFlow<List<AirPlayDevice>> = _devices.asStateFlow()

    private val lock = Any()
    private val byHost = LinkedHashMap<String, MutableEntry>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private var activeResolves = 0

    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopDiscovery: RaopDiscovery? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    @Volatile private var running = false

    private data class MutableEntry(
        val host: String,
        var ap2Name: String? = null,
        var raopName: String? = null,
        var airplay2Port: Int = 0,
        var raopPort: Int = 0,
        var mac: String? = null,
        var model: String? = null,
        val airplay2Txt: LinkedHashMap<String, String> = LinkedHashMap(),
        val raopTxt: LinkedHashMap<String, String> = LinkedHashMap(),
    )

    fun start() {
        if (running) return
        running = true
        runCatching {
            multicastLock = wifiManager?.createMulticastLock("BitChordAirPlayDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // 1. AirPlay 1 / RAOP via JmDNS (exposes full et/cn/md/pw/sf TXT records)
        raopDiscovery = RaopDiscovery(app).apply {
            start(
                onDeviceFound = { raop ->
                    if (!running) return@start
                    synchronized(lock) {
                        val entry = byHost.getOrPut(raop.host) { MutableEntry(raop.host) }
                        entry.raopName = raop.name
                        entry.raopPort = raop.port
                        if (raop.mac != null) entry.mac = raop.mac
                        if (raop.model != null) entry.model = raop.model
                        entry.raopTxt.clear()
                        entry.raopTxt.putAll(raop.txt)
                        if (raop.mac != null) entry.raopTxt["id"] = raop.mac
                        publishLocked()
                    }
                },
                onDeviceLost = { rawName ->
                    synchronized(lock) {
                        val iter = byHost.entries.iterator()
                        var changed = false
                        while (iter.hasNext()) {
                            val entry = iter.next().value
                            if (entry.raopName == rawName) {
                                entry.raopName = null
                                entry.raopPort = 0
                                entry.raopTxt.clear()
                                if (entry.airplay2Port <= 0) iter.remove()
                                changed = true
                            }
                        }
                        if (changed) publishLocked()
                    }
                },
            )
        }

        // 2. AirPlay 2 via Android NsdManager (_airplay._tcp)
        val nsd = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                AirPlayLog.d(TAG, "NSD started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running) return
                resolveQueue.add(serviceInfo)
                processResolveQueue()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName ?: return
                synchronized(lock) {
                    val iter = byHost.entries.iterator()
                    var changed = false
                    while (iter.hasNext()) {
                        val entry = iter.next().value
                        if (entry.ap2Name == lostName) {
                            entry.ap2Name = null
                            entry.airplay2Port = 0
                            entry.airplay2Txt.clear()
                            if (entry.raopPort <= 0) iter.remove()
                            changed = true
                        }
                    }
                    if (changed) publishLocked()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                AirPlayLog.d(TAG, "NSD stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AirPlayLog.w(TAG, "NSD start failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AirPlayLog.w(TAG, "NSD stop failed ($errorCode)")
            }
        }
        nsdListener = listener
        try {
            nsd.discoverServices(SERVICE_AIRPLAY2, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            AirPlayLog.w(TAG, "NSD discoverServices failed: ${e.message}")
            nsdListener = null
        }
    }

    fun stop() {
        running = false
        raopDiscovery?.stop()
        raopDiscovery = null

        nsdListener?.let { listener ->
            runCatching { nsdManager?.stopServiceDiscovery(listener) }
        }
        nsdListener = null
        resolveQueue.clear()
        synchronized(lock) {
            activeResolves = 0
            byHost.clear()
        }
        _devices.value = emptyList()
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun processResolveQueue() {
        if (!running) return
        val serviceInfo = synchronized(lock) {
            if (activeResolves >= MAX_CONCURRENT_RESOLVES || resolveQueue.isEmpty()) return
            resolveQueue.poll()?.also { activeResolves++ } ?: return
        }
        val nsd = nsdManager ?: run {
            synchronized(lock) { activeResolves = (activeResolves - 1).coerceAtLeast(0) }
            return
        }
        try {
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { activeResolves = (activeResolves - 1).coerceAtLeast(0) }
                        if (!running) return
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                            scope.launch {
                                delay(400)
                                if (running) {
                                    resolveQueue.add(si)
                                    processResolveQueue()
                                }
                            }
                        } else {
                            processResolveQueue()
                        }
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        synchronized(lock) { activeResolves = (activeResolves - 1).coerceAtLeast(0) }
                        if (running) handleAirPlay2Resolved(si)
                        processResolveQueue()
                    }
                },
            )
        } catch (_: Exception) {
            synchronized(lock) { activeResolves = (activeResolves - 1).coerceAtLeast(0) }
            processResolveQueue()
        }
    }

    private fun handleAirPlay2Resolved(si: NsdServiceInfo) {
        val host = si.host?.hostAddress?.substringBefore('%') ?: return
        if (host == "127.0.0.1" || host == "::1" || host.contains("localhost")) return
        val name = si.serviceName?.takeIf { it.isNotBlank() } ?: return
        val port = si.port.takeIf { it > 0 } ?: 7000

        val txt = LinkedHashMap<String, String>()
        si.attributes?.forEach { (k, bytes) ->
            val v = bytes?.toString(Charsets.UTF_8)?.trim()
            if (!v.isNullOrEmpty()) txt[k] = v
        }
        val mac = txt["deviceid"]
        if (mac != null) txt["id"] = mac

        synchronized(lock) {
            val entry = byHost.getOrPut(host) { MutableEntry(host) }
            entry.ap2Name = name
            entry.airplay2Port = port
            if (mac != null) entry.mac = mac
            txt["model"]?.let { entry.model = it }
            entry.airplay2Txt.clear()
            entry.airplay2Txt.putAll(txt)
            publishLocked()
        }
    }

    private fun publishLocked() {
        val raw = byHost.values.mapNotNull { e ->
            val baseName = (e.ap2Name ?: e.raopName)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AirPlayDevice(
                id = e.mac ?: e.host,
                name = baseName,
                host = e.host,
                airplay2Port = e.airplay2Port,
                raopPort = e.raopPort,
                mac = e.mac,
                model = e.model,
                airplay2Txt = e.airplay2Txt.toMap(),
                raopTxt = e.raopTxt.toMap(),
            )
        }
        val duplicateNames = raw.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
        _devices.value = raw
            .map { d ->
                if (d.name in duplicateNames) d.copy(name = "${d.name} (${d.host})") else d
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.host }))
    }

    companion object {
        private const val TAG = "AirPlayDiscovery"
        private const val SERVICE_AIRPLAY2 = "_airplay._tcp"
        private const val MAX_CONCURRENT_RESOLVES = 1
    }
}
