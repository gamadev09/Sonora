/*
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 */
package com.music.bitchord.playback.airplay.raop

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object RaopUtils {
    fun getLocalIpAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val name = iface.name.lowercase()
                if (name.contains("tun") || name.contains("ppp") || name.contains("tap")) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
