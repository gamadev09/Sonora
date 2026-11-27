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
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SSDP discovery for UPnP / DLNA MediaRenderer devices on the local Wi-Fi LAN.
 *
 * Optimisations:
 * - Retains a warm cache across sheet reopens so known renderers appear immediately.
 * - Probes active LAN interfaces concurrently with a fast initial MX=1 window.
 * - Reuses OkHttp's connection pool with tight 2s LAN timeouts for XML descriptions.
 * - Tracks `lastSeenMs` per UDN to prune vanished/offline devices cleanly.
 */
class DlnaDiscovery(private val context: Context) {
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val foundDevices = LinkedHashMap<String, DlnaDevice>()
    private val lastSeenMs = ConcurrentHashMap<String, Long>()
    private val fetchedLocations = Collections.synchronizedSet(HashSet<String>())
    private val locationToUdn = ConcurrentHashMap<String, String>()

    private val descClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    @Synchronized
    fun start() {
        if (scanJob?.isActive == true) return
        pruneStaleDevices(CACHE_RETENTION_MS)
        acquireMulticastLock()
        scanJob = scope.launch {
            val ssdpAddr = runCatching { InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS) }.getOrNull()
                ?: return@launch
            Log.i(TAG, "Starting DLNA SSDP discovery (cached=${foundDevices.size})")
            var cycle = 0
            while (isActive) {
                val localAddrs = activeLanIpv4Addresses()
                val windowMs = if (cycle == 0) INITIAL_LISTEN_MS else STEADY_LISTEN_MS
                val mx = if (cycle == 0) 1 else 2
                if (localAddrs.isNotEmpty()) {
                    coroutineScope {
                        localAddrs.map { localAddr ->
                            async { sendSsdpBurstAndListen(ssdpAddr, localAddr, windowMs, mx) }
                        }.awaitAll()
                    }
                } else {
                    sendSsdpBurstAndListen(ssdpAddr, null, windowMs, mx)
                }
                if (cycle > 0) {
                    pruneStaleDevices(STALE_DEVICE_TTL_MS)
                }
                cycle++
                delay(if (cycle == 1) 1500L else RESCAN_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun stop(keepCache: Boolean = false) {
        scanJob?.cancel()
        scanJob = null
        releaseMulticastLock()
        if (!keepCache) {
            synchronized(foundDevices) { foundDevices.clear() }
            lastSeenMs.clear()
            fetchedLocations.clear()
            locationToUdn.clear()
            _devices.value = emptyList()
        }
    }

    private fun pruneStaleDevices(maxAgeMs: Long) {
        val now = SystemClock.elapsedRealtime()
        val staleUdns = lastSeenMs.entries
            .filter { now - it.value > maxAgeMs }
            .map { it.key }
        if (staleUdns.isEmpty()) return
        synchronized(foundDevices) {
            var changed = false
            for (udn in staleUdns) {
                val removed = foundDevices.remove(udn)
                lastSeenMs.remove(udn)
                if (removed != null) {
                    fetchedLocations.remove(removed.location)
                    locationToUdn.remove(removed.location)
                    changed = true
                }
            }
            if (changed) {
                _devices.value = foundDevices.values.sortedBy { it.name.lowercase(Locale.ROOT) }
            }
        }
    }

    private fun sendSsdpBurstAndListen(
        ssdpAddr: InetAddress,
        bindAddress: InetAddress?,
        listenWindowMs: Long,
        mxSeconds: Int,
    ) {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                if (bindAddress != null) {
                    socket.bind(InetSocketAddress(bindAddress, 0))
                } else {
                    socket.bind(InetSocketAddress(0))
                }
                socket.soTimeout = SOCKET_SO_TIMEOUT_MS
                for (st in DlnaProtocol.SEARCH_TARGETS) {
                    val queryBytes = DlnaProtocol.buildSsdpQuery(st, mxSeconds = mxSeconds)
                        .toByteArray(Charsets.US_ASCII)
                    socket.send(
                        DatagramPacket(queryBytes, queryBytes.size, ssdpAddr, DlnaProtocol.SSDP_PORT),
                    )
                }
                val buffer = ByteArray(4096)
                val endAt = SystemClock.elapsedRealtime() + listenWindowMs
                while (SystemClock.elapsedRealtime() < endAt) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        if (SystemClock.elapsedRealtime() >= endAt) break else continue
                    }
                    val host = packet.address?.hostAddress ?: continue
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = DlnaProtocol.extractSsdpLocation(response) ?: continue
                    locationToUdn[location]?.let { udn ->
                        lastSeenMs[udn] = SystemClock.elapsedRealtime()
                    }
                    if (fetchedLocations.add(location)) {
                        Log.i(TAG, "SSDP reply from $host -> $location")
                        scope.launch { resolveDevice(location, host) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP query failed (bind=${bindAddress?.hostAddress}): ${e.message}")
        }
    }

    private fun activeLanIpv4Addresses(): List<Inet4Address> = runCatching {
        val preferred = mutableListOf<Inet4Address>()
        val others = mutableListOf<Inet4Address>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching emptyList()
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val name = iface.name.lowercase(Locale.ROOT)
            if (name.contains("tun") || name.contains("ppp") || name.contains("tap") ||
                name.contains("docker") || name.contains("rmnet") || name.contains("ccmni") ||
                name.contains("p2p")
            ) {
                continue
            }
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en")) {
                        preferred.add(addr)
                    } else {
                        others.add(addr)
                    }
                }
            }
        }
        (preferred + others).distinctBy { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun resolveDevice(location: String, hostAddress: String) {
        try {
            val locationUri = URI(location)
            val scheme = locationUri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") return
            if (locationUri.host == null || locationUri.host != hostAddress) {
                Log.d(TAG, "Ignoring SSDP LOCATION with mismatched host: $location vs $hostAddress")
                return
            }

            val req = Request.Builder().url(location).get().build()
            val xml = descClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    fetchedLocations.remove(location)
                    return
                }
                val body = resp.body ?: return
                val source = body.source()
                source.request(MAX_DESCRIPTION_BYTES.toLong())
                val byteCount = minOf(source.buffer.size, MAX_DESCRIPTION_BYTES.toLong())
                source.buffer.clone().readUtf8(byteCount)
            }

            val parsed = DlnaProtocol.parseRemoteDescription(xml) ?: return
            val avUrl = DlnaProtocol.resolveTrustedControlUrl(parsed.avControl, locationUri, hostAddress)
                ?: return
            val rcUrl = parsed.rcControl?.let {
                DlnaProtocol.resolveTrustedControlUrl(it, locationUri, hostAddress)
            }

            val udn = parsed.udn ?: "$hostAddress|${parsed.friendlyName}"
            val baseDevice = DlnaDevice(
                udn = udn,
                name = parsed.friendlyName,
                host = hostAddress,
                avControlUrl = avUrl,
                rcControlUrl = rcUrl,
                location = location,
            )

            locationToUdn[location] = udn
            lastSeenMs[udn] = SystemClock.elapsedRealtime()
            synchronized(foundDevices) {
                val clash = foundDevices.any { (existingUdn, existing) ->
                    existingUdn != udn && existing.name == parsed.friendlyName && existing.host != hostAddress
                }
                foundDevices[udn] = if (clash) {
                    baseDevice.copy(name = "${parsed.friendlyName} ($hostAddress)")
                } else {
                    baseDevice
                }
                _devices.value = foundDevices.values.sortedBy { it.name.lowercase(Locale.ROOT) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed resolving DLNA device at $location: ${e.message}")
            fetchedLocations.remove(location)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val lock = wifi.createMulticastLock("BitChord-DLNA").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock acquire failed", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            multicastLock = null
        }
    }

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val MAX_DESCRIPTION_BYTES = 262_144
        private const val SOCKET_SO_TIMEOUT_MS = 400
        private const val INITIAL_LISTEN_MS = 1400L
        private const val STEADY_LISTEN_MS = 2200L
        private const val RESCAN_INTERVAL_MS = 6000L
        private const val STALE_DEVICE_TTL_MS = 22_000L
        private const val CACHE_RETENTION_MS = 90_000L
    }
}
