package com.music.bitchord.playback.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DlnaProtocolTest {

    @Test
    fun parseRemoteDescription_extractsAvTransportAndRenderingControl() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>Living Room TV &amp; Soundbar</friendlyName>
    <UDN>uuid:1234-5678</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <controlURL>/upnp/control/rendercontrol1</controlURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <controlURL>/upnp/control/avtransport1</controlURL>
      </service>
    </serviceList>
  </device>
</root>"""
        val parsed = DlnaProtocol.parseRemoteDescription(xml)
        assertNotNull(parsed)
        assertEquals("uuid:1234-5678", parsed?.udn)
        assertEquals("Living Room TV & Soundbar", parsed?.friendlyName)
        assertEquals("/upnp/control/avtransport1", parsed?.avControl)
        assertEquals("/upnp/control/rendercontrol1", parsed?.rcControl)
    }

    @Test
    fun parseRemoteDescription_returnsNullWithoutAvTransport() {
        val xml = """<root><device><friendlyName>Router</friendlyName><serviceList>
<service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl</controlURL></service>
</serviceList></device></root>"""
        assertNull(DlnaProtocol.parseRemoteDescription(xml))
    }

    @Test
    fun resolveTrustedControlUrl_acceptsRelativeAndSameHostRejectsCrossHost() {
        val location = URI("http://192.168.1.40:49152/description.xml")

        assertEquals(
            "http://192.168.1.40:49152/AVTransport/control",
            DlnaProtocol.resolveTrustedControlUrl("/AVTransport/control", location, "192.168.1.40"),
        )
        assertEquals(
            "http://192.168.1.40:49152/AVTransport/control",
            DlnaProtocol.resolveTrustedControlUrl("AVTransport/control", location, "192.168.1.40"),
        )
        assertEquals(
            "http://192.168.1.40:8080/ctrl",
            DlnaProtocol.resolveTrustedControlUrl("http://192.168.1.40:8080/ctrl", location, "192.168.1.40"),
        )
        assertNull(
            DlnaProtocol.resolveTrustedControlUrl("http://192.168.1.99:8080/evil", location, "192.168.1.40"),
        )
    }

    @Test
    fun buildStreamingWavHeader_hasCorrectPcm44k16StereoFormat() {
        val hdr = DlnaProtocol.buildStreamingWavHeader()
        assertEquals(44, hdr.size)
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(hdr, 0, 4, Charsets.US_ASCII))
        assertEquals(-1, bb.getInt(4))
        assertEquals("WAVE", String(hdr, 8, 4, Charsets.US_ASCII))
        assertEquals(1, bb.getShort(20).toInt()) // PCM
        assertEquals(2, bb.getShort(22).toInt()) // stereo
        assertEquals(44100, bb.getInt(24))
        assertEquals(44100 * 4, bb.getInt(28))
        assertEquals(4, bb.getShort(32).toInt())
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals(-1, bb.getInt(40))
    }

    @Test
    fun didlAndSoapEnvelopes_escapeSpecialXmlChars() {
        val envelope = DlnaProtocol.setAvTransportUriEnvelope(
            streamUrl = "http://192.168.1.10:5555/stream.wav",
            metadata = DlnaProtocol.TrackMetadata(
                title = "Rock & Roll <Live>",
                artist = "A \"B\" C",
                album = "Hits",
                artworkUrl = "http://192.168.1.10:5555/artwork.jpg?a=1&b=2",
            ),
        )
        assertTrue(envelope.contains("urn:schemas-upnp-org:service:AVTransport:1"))
        assertTrue(envelope.contains("http-get:*:audio/wav:DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS="))
        assertTrue(envelope.contains("&amp;"))
        assertTrue(!envelope.contains("<Live>"))
    }
}
