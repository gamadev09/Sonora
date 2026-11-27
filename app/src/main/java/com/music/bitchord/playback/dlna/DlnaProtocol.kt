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

import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure UPnP AV / DLNA MediaRenderer parsing, SSRF URL validation, WAV header
 * generation, DIDL-Lite metadata, and SOAP envelope builders.
 */
object DlnaProtocol {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2
    const val BITS_PER_SAMPLE = 16
    const val BYTE_RATE = SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)
    const val BLOCK_ALIGN = CHANNELS * (BITS_PER_SAMPLE / 8)

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900

    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"

    val SEARCH_TARGETS = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "ssdp:all",
        "upnp:rootdevice",
    )

    data class RemoteRenderer(
        val udn: String?,
        val friendlyName: String,
        val avControl: String,
        val rcControl: String?,
    )

    data class TrackMetadata(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artworkUrl: String? = null,
    )

    fun buildSsdpQuery(searchTarget: String, mxSeconds: Int = 3): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $searchTarget\r\n\r\n"

    fun extractSsdpLocation(response: String): String? =
        response.split("\r\n", "\n")
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Parses a UPnP device description fetched from an SSDP LOCATION. Returns null unless the
     * device (or one embedded in it, e.g. Sonos) offers AVTransport.
     */
    fun parseRemoteDescription(xml: String): RemoteRenderer? {
        fun tag(t: String, src: String): String? =
            Regex("<(?:\\w+:)?$t(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?$t>")
                .find(src)
                ?.groupValues
                ?.get(1)
                ?.let(::xmlUnescape)
                ?.trim()

        val name = tag("friendlyName", xml)?.takeIf { it.isNotEmpty() } ?: return null
        val services = Regex("<(?:\\w+:)?service(?:\\s[^>]*)?>([\\s\\S]*?)</(?:\\w+:)?service>")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toList()

        fun control(kind: String): String? =
            services.firstOrNull { tag("serviceType", it)?.contains(":service:$kind:") == true }
                ?.let { tag("controlURL", it) }
                ?.takeIf { it.isNotEmpty() }

        val av = control("AVTransport") ?: return null
        return RemoteRenderer(
            udn = tag("UDN", xml)?.takeIf { it.isNotEmpty() },
            friendlyName = name,
            avControl = av,
            rcControl = control("RenderingControl"),
        )
    }

    /**
     * Resolves a UPnP `<controlURL>` against the already host-validated device description URI.
     * Drops any absolute URL pointing at a host other than [expectedHost] to prevent confused-deputy SSRF.
     */
    fun resolveTrustedControlUrl(raw: String, locationUri: URI, expectedHost: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            val portPart = if (locationUri.port == -1) "" else ":${locationUri.port}"
            val path = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "${locationUri.scheme}://${locationUri.host}$portPart$path"
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        return if (uri.host == expectedHost) trimmed else null
    }

    /**
     * 44-byte RIFF/WAVE header with `-1` chunk sizes for continuous live PCM streaming.
     */
    fun buildStreamingWavHeader(
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()
        val byteRate = sampleRate * blockAlign.toInt()
        return ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(-1)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort()) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(-1)
        }.array()
    }

    const val DLNA_CONTENT_FEATURES =
        "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    fun createDidlMetadata(streamUrl: String, metadata: TrackMetadata?): String {
        val title = metadata?.title?.takeIf { it.isNotBlank() } ?: "Sonora Live Stream"
        val artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: "Sonora"
        val album = metadata?.album.orEmpty()
        val artwork = metadata?.artworkUrl.orEmpty()
        val artworkTag = if (artwork.isNotBlank()) {
            "<upnp:albumArtURI>${xmlEscape(artwork)}</upnp:albumArtURI>"
        } else {
            ""
        }
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:artist>${xmlEscape(artist)}</upnp:artist>" +
            "<upnp:album>${xmlEscape(album)}</upnp:album>" +
            "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            artworkTag +
            "<res protocolInfo=\"http-get:*:audio/wav:$DLNA_CONTENT_FEATURES\">${xmlEscape(streamUrl)}</res>" +
            "</item></DIDL-Lite>"
    }

    fun stopEnvelope(): String = soapEnvelope(
        service = SERVICE_AV_TRANSPORT,
        action = "Stop",
        argsXml = "<InstanceID>0</InstanceID>",
    )

    fun setAvTransportUriEnvelope(streamUrl: String, metadata: TrackMetadata?): String {
        val didl = createDidlMetadata(streamUrl, metadata)
        return soapEnvelope(
            service = SERVICE_AV_TRANSPORT,
            action = "SetAVTransportURI",
            argsXml = "<InstanceID>0</InstanceID>" +
                "<CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>" +
                "<CurrentURIMetaData>${xmlEscape(didl)}</CurrentURIMetaData>",
        )
    }

    fun playEnvelope(): String = soapEnvelope(
        service = SERVICE_AV_TRANSPORT,
        action = "Play",
        argsXml = "<InstanceID>0</InstanceID><Speed>1</Speed>",
    )

    fun setVolumeEnvelope(percent: Int): String {
        val clamped = percent.coerceIn(0, 100)
        return soapEnvelope(
            service = SERVICE_RENDERING_CONTROL,
            action = "SetVolume",
            argsXml = "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$clamped</DesiredVolume>",
        )
    }

    private fun soapEnvelope(service: String, action: String, argsXml: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$service\">$argsXml</u:$action></s:Body>" +
            "</s:Envelope>"

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
