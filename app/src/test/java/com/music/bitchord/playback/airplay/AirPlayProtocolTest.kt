package com.music.bitchord.playback.airplay

import androidx.media3.common.C
import com.music.bitchord.playback.airplay.airplay2.AirPlay2Crypto
import com.music.bitchord.playback.airplay.airplay2.BinaryPlist
import com.music.bitchord.playback.airplay.airplay2.SRP6aClient
import com.music.bitchord.playback.airplay.airplay2.TlvUtil
import com.music.bitchord.playback.airplay.raop.AudioResampler
import com.music.bitchord.playback.airplay.raop.RaopCrypto
import org.bouncycastle.crypto.digests.SHA512Digest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AirPlayProtocolTest {

    @Test
    fun binaryPlistRoundTrip() {
        val input = linkedMapOf<String, Any?>(
            "name" to "BitChord",
            "unicode" to "Música ♫",
            "port" to 7000L,
            "negative" to -42L,
            "flag" to true,
            "disabled" to false,
            "ratio" to 0.75,
            "bytes" to byteArrayOf(1, 2, 3, 4),
            "list" to listOf("a", 2L, false),
        )
        val encoded = BinaryPlist.encode(input)
        assertTrue(String(encoded, 0, 8, Charsets.US_ASCII) == "bplist00")
        val decoded = BinaryPlist.decode(encoded)
        assertEquals("BitChord", decoded["name"])
        assertEquals("Música ♫", decoded["unicode"])
        assertEquals(7000L, decoded["port"])
        assertEquals(-42L, decoded["negative"])
        assertEquals(true, decoded["flag"])
        assertEquals(false, decoded["disabled"])
        assertEquals(0.75, decoded["ratio"] as Double, 1e-6)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded["bytes"] as ByteArray)
        assertEquals(listOf("a", 2L, false), decoded["list"])
    }

    @Test
    fun tlvBuildAndParseFragments() {
        val large = ByteArray(600) { (it and 0xFF).toByte() }
        val encoded = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(3),
            TlvUtil.TLV_PUBLIC_KEY to large,
            TlvUtil.TLV_ERROR to byteArrayOf(0),
        )
        val parsed = TlvUtil.parse(encoded)
        assertArrayEquals(byteArrayOf(3), parsed[TlvUtil.TLV_STATE]?.firstOrNull())
        assertArrayEquals(large, parsed[TlvUtil.TLV_PUBLIC_KEY]?.firstOrNull())
        assertArrayEquals(byteArrayOf(0), parsed[TlvUtil.TLV_ERROR]?.firstOrNull())
    }

    @Test
    fun srp6aClientMatchesServer() {
        val pin = "3939"
        val user = "Pair-Setup"
        val client = SRP6aClient(pin = pin, username = user, random = SecureRandom())
        val m1 = client.buildM1(transient = true)
        assertEquals(1.toByte(), TlvUtil.getFirst(m1, TlvUtil.TLV_STATE)?.get(0))

        // Synthetic SRP-6a server matching RFC 5054 3072-bit + SHA-512
        val n = SRP6aClient.N
        val g = SRP6aClient.g
        val salt = ByteArray(16) { (it + 10).toByte() }
        fun sha512(b: ByteArray): ByteArray {
            val d = SHA512Digest()
            val out = ByteArray(d.digestSize)
            d.update(b, 0, b.size)
            d.doFinal(out, 0)
            return out
        }
        fun fixed384(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val min = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
            return if (min.size >= 384) min.copyOfRange(min.size - 384, min.size) else ByteArray(384 - min.size) + min
        }
        fun minimal(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }

        val x = BigInteger(1, sha512(salt + sha512("$user:$pin".toByteArray(Charsets.UTF_8))))
        val v = g.modPow(x, n)
        val b = BigInteger(1, ByteArray(32) { 0x5A })
        val k = BigInteger(1, sha512(fixed384(n) + fixed384(g)))
        val bigB = k.multiply(v).add(g.modPow(b, n)).mod(n)

        val m2 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(2),
            TlvUtil.TLV_SALT to salt,
            TlvUtil.TLV_PUBLIC_KEY to minimal(bigB),
        )
        val m3 = client.processM2(m2)
        assertNotNull(m3)

        val bigA = BigInteger(1, TlvUtil.getFirst(m3!!, TlvUtil.TLV_PUBLIC_KEY)!!)
        val u = BigInteger(1, sha512(fixed384(bigA) + fixed384(bigB)))
        val s = bigA.multiply(v.modPow(u, n)).modPow(b, n)
        val serverK = sha512(minimal(s))
        assertArrayEquals(serverK, client.sharedKeyBytes)

        val clientProof = TlvUtil.getFirst(m3, TlvUtil.TLV_PROOF)!!
        val m4Proof = sha512(minimal(bigA) + clientProof + serverK)
        val m4 = TlvUtil.build(
            TlvUtil.TLV_STATE to byteArrayOf(4),
            TlvUtil.TLV_PROOF to m4Proof,
        )
        assertTrue(client.verifyM4(m4))
    }

    @Test
    fun hapCipherControlRoundTrip() {
        val secret = ByteArray(32) { (it * 7).toByte() }
        val client = HapCipher.forControl(secret)
        val server = HapCipher.forControlServer(secret)

        val payload = ByteArray(2500) { (it % 251).toByte() } // spans >2 blocks of MAX_BLOCK (1024)
        val sealed = client.encrypt(payload)
        val decrypted = server.decryptingStream(ByteArrayInputStream(sealed)).readBytes()
        assertArrayEquals(payload, decrypted)

        val reply = "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val sealedReply = server.encrypt(reply)
        val decryptedReply = client.decryptingStream(ByteArrayInputStream(sealedReply)).readBytes()
        assertArrayEquals(reply, decryptedReply)
    }

    @Test
    fun raopCryptoAndAudioEncoding() {
        val pcm = ByteArray(AirPlayAudio.PACKET_PCM_BYTES) { it.toByte() }
        val alac = AirPlayAudio.alacUncompressed(pcm)
        assertEquals(AirPlayAudio.PACKET_PCM_BYTES + 4, alac.size)

        val l16 = AirPlayAudio.l16(pcm, 0, 8)
        assertEquals(pcm[1], l16[0])
        assertEquals(pcm[0], l16[1])

        val aesKey = ByteArray(16) { (it + 1).toByte() }
        val rsaKey = RaopCrypto.encryptAesKey(aesKey)
        assertEquals(256, rsaKey.size)

        val raopCrypto = RaopCrypto()
        raopCrypto.initAes(aesKey, ByteArray(16) { 0x33 })
        val data = ByteArray(37) { (it + 5).toByte() }
        val enc = raopCrypto.encryptAudio(data)
        assertEquals(data.size, enc.size)
        // Trailing 5 bytes remain plaintext in AirPlay 1 partial block convention
        for (i in 32 until 37) {
            assertEquals(data[i], enc[i])
        }
    }

    @Test
    fun volumeAndTxtParsing() {
        assertEquals(AirPlayVolume.MUTE_DB, AirPlayVolume.percentToDb(0), 1e-6)
        assertEquals(AirPlayVolume.MAX_DB, AirPlayVolume.percentToDb(100), 1e-6)
        assertEquals(-15.0, AirPlayVolume.percentToDb(50), 1e-6)
        assertEquals(50, AirPlayVolume.dbToPercent(-15.0))

        val feat = AirPlayTxt.features("0x5A7FFFF7,0x21E")
        assertTrue(AirPlayTxt.hasFeature(feat, AirPlayTxt.FEATURE_AUDIO))
        assertTrue(AirPlayTxt.hasFeature(feat, AirPlayTxt.FEATURE_PTP))
        assertEquals(setOf(0, 1), AirPlayTxt.intList("0, 1"))
    }

    @Test
    fun dmapAndDacpServerHandleCommands() {
        val track = Dmap.trackInfo("Song Title", "Artist", "Album", durationMs = 123_000L, itemId = 9L)
        val outer = Dmap.parse(track)
        assertTrue(outer.containsKey("mlit"))
        val inner = Dmap.parse(outer["mlit"]!!)
        assertEquals("Song Title", String(inner["minm"]!!, Charsets.UTF_8))
        assertEquals("Artist", String(inner["asar"]!!, Charsets.UTF_8))

        val received = mutableListOf<DacpServer.Command>()
        val dacp = DacpServer(activeRemote = "12345678") { received.add(it) }

        val bad = dacp.handle(RtspMessage("GET /ctrl-int/1/play HTTP/1.1", listOf("Active-Remote" to "99999999"), ByteArray(0)))
        assertEquals("403 Forbidden", bad.first)

        val good = dacp.handle(RtspMessage("GET /ctrl-int/1/playpause HTTP/1.1", listOf("Active-Remote" to "12345678"), ByteArray(0)))
        assertEquals("204 No Content", good.first)
        assertEquals(listOf(DacpServer.Command.PlayPause), received)

        val info = dacp.handle(RtspMessage("GET /server-info HTTP/1.1", listOf("Active-Remote" to "12345678"), ByteArray(0)))
        assertEquals("200 OK", info.first)
        assertTrue(Dmap.parse(info.second).containsKey("msrv"))
    }

    @Test
    fun airPlay2CryptoKeysAndCurveAgreement() {
        val kp1 = AirPlay2Crypto.generateCurve25519KeyPair()
        val kp2 = AirPlay2Crypto.generateCurve25519KeyPair()
        val s1 = AirPlay2Crypto.curve25519Agree(
            kp1.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters,
            AirPlay2Crypto.getPublicKeyBytes(kp2),
        )
        val s2 = AirPlay2Crypto.curve25519Agree(
            kp2.private as org.bouncycastle.crypto.params.X25519PrivateKeyParameters,
            AirPlay2Crypto.getPublicKeyBytes(kp1),
        )
        assertArrayEquals(s1, s2)

        val ed = AirPlay2Crypto.ed25519KeyPairFromSeed(ByteArray(32) { 42 })
        val sig = AirPlay2Crypto.ed25519Sign(ed, "hello".toByteArray())
        assertTrue(AirPlay2Crypto.ed25519Verify(AirPlay2Crypto.getEd25519PublicKey(ed), "hello".toByteArray(), sig))
        assertFalse(AirPlay2Crypto.ed25519Verify(AirPlay2Crypto.getEd25519PublicKey(ed), "other".toByteArray(), sig))
    }

    @Test
    fun resamplerAndPcmConverter() {
        val converter = AirPlayPcmConverter()
        // 48 kHz mono Float32 -> 44.1 kHz stereo S16
        val frames48k = 480
        val buf = ByteBuffer.allocate(frames48k * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames48k) {
            val v = (0.5 * sin(2.0 * PI * 440.0 * i / 48000.0)).toFloat()
            buf.putFloat(v)
        }
        val out = converter.convert(
            buffer = buf,
            startOffset = 0,
            byteLength = frames48k * 4,
            pcmEncoding = C.ENCODING_PCM_FLOAT,
            sampleRate = 48000,
            channelCount = 1,
        )
        val outFrames = out.size / 4
        assertTrue("Expected ~441 frames, got $outFrames", abs(outFrames - 441) <= 3)

        // Direct 44.1 kHz stereo S16 passthrough
        val raw = ByteArray(352 * 4) { (it and 0x7F).toByte() }
        val passthrough = converter.convert(
            buffer = ByteBuffer.wrap(raw),
            startOffset = 0,
            byteLength = raw.size,
            pcmEncoding = C.ENCODING_PCM_16BIT,
            sampleRate = 44100,
            channelCount = 2,
        )
        assertArrayEquals(raw, passthrough)

        val resampler = AudioResampler(l = 147, m = 160, channels = 2)
        val resampled = resampler.resample(ByteArray(480 * 4))
        assertEquals(441 * 4, resampled.size)
    }
}
