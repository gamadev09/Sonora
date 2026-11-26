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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections
import java.util.Locale

/**
 * SSDP discovery for UPnP / DLNA MediaRenderer devices on the local Wi-Fi LAN.
 */
class DlnaDiscovery(private val context: Context) {
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val foundDevices = LinkedHashMap<String, DlnaDevice>()
    private val fetchedLocations = Collections.synchronizedSet(HashSet<String>())

    @Synchronized
    fun start() {
        if (scanJob?.isActive == true) return
        acquireMulticastLock()
        scanJob = scope.launch {
            val ssdpAddr = runCatching { InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS) }.getOrNull()
                ?: return@launch
            Log.i(TAG, "Starting DLNA SSDP discovery")
            while (isActive) {
                val localAddrs = activeLanIpv4Addresses()
                if (localAddrs.isNotEmpty()) {
                    for (localAddr in localAddrs) {
                        if (!isActive) break
                        sendSsdpBurstAndListen(ssdpAddr, localAddr)
                    }
                } else {
                    sendSsdpBurstAndListen(ssdpAddr, null)
                }
                delay(4000L)
            }
        }
    }

    @Synchronized
    fun stop() {
        scanJob?.cancel()
        scanJob = null
        releaseMulticastLock()
        synchronized(foundDevices) {
            foundDevices.clear()
        }
        fetchedLocations.clear()
        _devices.value = emptyList()
    }

    private fun sendSsdpBurstAndListen(ssdpAddr: InetAddress, bindAddress: InetAddress?) {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                if (bindAddress != null) {
                    socket.bind(InetSocketAddress(bindAddress, 0))
                } else {
                    socket.bind(InetSocketAddress(0))
                }
                socket.soTimeout = 2500
                for (st in DlnaProtocol.SEARCH_TARGETS) {
                    val queryBytes = DlnaProtocol.buildSsdpQuery(st, mxSeconds = 2)
                        .toByteArray(Charsets.US_ASCII)
                    socket.send(
                        DatagramPacket(queryBytes, queryBytes.size, ssdpAddr, DlnaProtocol.SSDP_PORT),
                    )
                }
                val buffer = ByteArray(4096)
                val endAt = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < endAt) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        break
                    }
                    val host = packet.address?.hostAddress ?: continue
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = DlnaProtocol.extractSsdpLocation(response) ?: continue
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
            // Guard against SSRF from spoofed SSDP replies: LOCATION must point back to the sender IP.
            if (locationUri.host == null || locationUri.host != hostAddress) {
                Log.d(TAG, "Ignoring SSDP LOCATION with mismatched host: $location vs $hostAddress")
                return
            }

            val conn = (locationUri.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = false
            }
            val xml = try {
                if (conn.responseCode !in 200..299) {
                    fetchedLocations.remove(location)
                    return
                }
                conn.inputStream.use { readBoundedUtf8(it, MAX_DESCRIPTION_BYTES) }
            } finally {
                conn.disconnect()
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

    private fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (out.size() < maxBytes) {
            val read = input.read(buf, 0, minOf(buf.size, maxBytes - out.size()))
            if (read <= 0) break
            out.write(buf, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val MAX_DESCRIPTION_BYTES = 262_144
    }
}
