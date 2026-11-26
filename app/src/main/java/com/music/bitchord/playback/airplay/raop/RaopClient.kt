/*
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 */
package com.music.bitchord.playback.airplay.raop

import com.music.bitchord.playback.airplay.AirPlayAudio
import com.music.bitchord.playback.airplay.AirPlayAuthRequiredException
import com.music.bitchord.playback.airplay.AirPlayBusyException
import com.music.bitchord.playback.airplay.AirPlayLog
import com.music.bitchord.playback.airplay.AirPlaySenderIdentity
import com.music.bitchord.playback.airplay.AirPlaySessionListener
import com.music.bitchord.playback.airplay.AirPlayStreamer
import com.music.bitchord.playback.airplay.AirPlayTxt
import com.music.bitchord.playback.airplay.AirPlayVolume
import com.music.bitchord.playback.airplay.Bytes
import com.music.bitchord.playback.airplay.DigestAuth
import com.music.bitchord.playback.airplay.Dmap
import com.music.bitchord.playback.airplay.NtpTimingResponder
import com.music.bitchord.playback.airplay.RtpAudioSender
import com.music.bitchord.playback.airplay.RtpPacketizer
import com.music.bitchord.playback.airplay.RtpSyncMode
import com.music.bitchord.playback.airplay.RtspConnection
import com.music.bitchord.playback.airplay.RtspException
import com.music.bitchord.playback.airplay.RtspMessage
import com.music.bitchord.playback.airplay.airplay2.AirPlay2Crypto
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * AirPlay 1 (RAOP) sender.
 */
class RaopClient(
    override val host: String,
    private val port: Int,
    private val identity: AirPlaySenderIdentity,
    private val txt: Map<String, String> = emptyMap(),
    /** The receiver's MAC (its RAOP service-name prefix), for Apple-Response verification. */
    private val receiverMac: String? = null,
    private val password: String? = null,
    /** Set once legacy pairing with this receiver has succeeded. */
    private val legacyPaired: Boolean = false,
    private val initialVolumeDb: Double = -15.0,
    private val preferEncryption: Boolean = false,
    var listener: AirPlaySessionListener? = null,
) : AirPlayStreamer {

    companion object {
        private const val TAG = "RaopClient"
        private const val KEEPALIVE_INTERVAL_MS = 15_000L
    }

    enum class Codec { ALAC, PCM }

    private val random = SecureRandom()
    private lateinit var rtsp: RtspConnection
    private var sessionUri = ""
    private var sessionHeader: String? = null
    private val rtpSessionId = (random.nextInt() and 0x7FFFFFFF).toLong()
    private val ssrc = random.nextInt()

    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var timing: NtpTimingResponder? = null
    private var sender: RtpAudioSender? = null
    private var keepAlive: Thread? = null
    @Volatile private var closed = false
    @Volatile private var tornDown = false
    @Volatile private var pendingFlushSeq = -1
    @Volatile private var pendingFlushRtp = 0

    var codec: Codec = Codec.ALAC; private set
    var encrypted: Boolean = false; private set
    /** Receiver-reported extra latency (Audio-Latency on RECORD), frames. */
    var audioLatency: Int = 0; private set
    var audioJackStatus: String? = null; private set
    /** null = receiver didn't answer the challenge; true/false = verified or not. */
    var appleResponseValid: Boolean? = null; private set

    private val metadataCaps: Set<Int> = AirPlayTxt.intList(txt["md"])

    override var extraDelayMs: Int = 0
        set(value) { field = value; sender?.extraDelayMs = value }

    /** Runs the full handshake and starts streaming. Throws on failure. */
    fun connect() {
        try {
            val ports = if (port == 7000) listOf(7000, 5000) else listOf(if (port > 0) port else 5000)
            var last: Exception? = null
            for (p in ports) {
                try {
                    val c = RtspConnection(host, p)
                    c.connect()
                    rtsp = c
                    last = null
                    break
                } catch (e: Exception) {
                    last = e
                    AirPlayLog.d(TAG, "connect $host:$p failed: ${e.message}")
                }
            }
            if (last != null) throw last
            handshake()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun handshake() {
        val local = rtsp.localAddress
        sessionUri = "rtsp://${rtsp.localUrlHost}/$rtpSessionId"
        rtsp.defaultHeaders.apply {
            put("User-Agent", "AirPlay/381.13")
            put("Client-Instance", identity.dacpId)
            put("DACP-ID", identity.dacpId)
            put("Active-Remote", identity.activeRemote)
            put("X-Apple-Device-ID", "0x${identity.deviceIdHex}")
            put("X-Apple-Client-Name", identity.name)
        }
        if (password != null) rtsp.digest = DigestAuth("iTunes", password)

        // 1. OPTIONS with an Apple-Challenge
        val challenge = ByteArray(16).also(random::nextBytes)
        val challengeB64 = Base64.getEncoder().withoutPadding().encodeToString(challenge)
        val options = rtsp.request("OPTIONS", "*", headers = mapOf("Apple-Challenge" to challengeB64))
        checkAuth(options, "OPTIONS")
        options.header("Apple-Response")?.let { response ->
            val mac = receiverMac?.let { runCatching { Bytes.unhex(it.replace(":", "")) }.getOrNull() }
            appleResponseValid = if (mac != null && mac.size == 6) {
                RaopCrypto.verifyAppleResponse(response, challenge, rtsp.remoteAddress.address, mac)
            } else null
            AirPlayLog.d(TAG, "$host Apple-Response verified=$appleResponseValid")
        }

        // 2. legacy pair-verify for receivers that require an onscreen code
        if (legacyPaired) {
            try {
                RaopLegacyPairing.pairVerify(rtsp, identity.pairingSeed)
            } catch (e: RtspException) {
                throw AirPlayAuthRequiredException(AirPlayAuthRequiredException.Kind.PIN, host, "pairing no longer accepted: ${e.message}")
            }
        }

        // 3. MFi auth-setup (et contains 4): some receivers refuse ANNOUNCE without it
        val et = AirPlayTxt.intList(txt["et"])
        if (4 in et) authSetup()

        // 4. ANNOUNCE
        val cn = AirPlayTxt.intList(txt["cn"])
        codec = if (cn.isEmpty() || 1 in cn) Codec.ALAC else if (0 in cn) Codec.PCM else Codec.ALAC
        encrypted = when {
            et.isEmpty() -> false
            1 in et && (preferEncryption || 0 !in et) -> true
            else -> false
        }
        val crypto = if (encrypted) RaopCrypto() else null
        val sdp = buildSdp(local, crypto)
        val announce = rtsp.request("ANNOUNCE", sessionUri, sdp.toByteArray(Charsets.UTF_8), "application/sdp")
        checkAuth(announce, "ANNOUNCE")
        if (!announce.isSuccess) throw RtspException("ANNOUNCE rejected: ${announce.code} ${announce.reason}", announce.code)

        // 5. SETUP - the receiver starts timing requests straight away, so answer them first
        val timingSock = DatagramSocket(0).also { timingSocket = it }
        val controlSock = DatagramSocket(0).also { controlSocket = it }
        dataSocket = DatagramSocket(0)
        timing = NtpTimingResponder(timingSock, host).also { it.start() }
        val setup = rtsp.request(
            "SETUP",
            sessionUri,
            headers = mapOf(
                "Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlSock.localPort};timing_port=${timingSock.localPort}",
            ),
        )
        checkAuth(setup, "SETUP")
        if (!setup.isSuccess) throw RtspException("SETUP rejected: ${setup.code}", setup.code)
        sessionHeader = setup.header("Session")?.substringBefore(';')?.trim()
        sessionHeader?.let { rtsp.defaultHeaders["Session"] = it }
        audioJackStatus = setup.header("Audio-Jack-Status")
        val transport = parseTransport(setup.header("Transport") ?: "")
        val serverPort = transport["server_port"]?.toIntOrNull() ?: throw RtspException("SETUP: no server_port")
        val serverControl = transport["control_port"]?.toIntOrNull() ?: (serverPort + 1)
        AirPlayLog.d(TAG, "$host SETUP server=$serverPort control=$serverControl timing=${transport["timing_port"]} jack=$audioJackStatus")

        // 6. RECORD
        val firstSeq = random.nextInt(0x10000)
        val firstRtp = random.nextInt()
        val record = rtsp.request(
            "RECORD",
            sessionUri,
            headers = mapOf(
                "Range" to "npt=0-",
                "RTP-Info" to "seq=$firstSeq;rtptime=${firstRtp.toUInt()}",
            ),
        )
        checkAuth(record, "RECORD")
        if (!record.isSuccess) throw RtspException("RECORD rejected: ${record.code}", record.code)
        audioLatency = record.header("Audio-Latency")?.trim()?.toIntOrNull() ?: 0

        // 7. initial volume
        setVolume(initialVolumeDb)

        // 8. audio
        val packetizer = RtpPacketizer { seq, rtp, marker, pcm ->
            var payload = if (codec == Codec.ALAC) AirPlayAudio.alacUncompressed(pcm) else AirPlayAudio.l16(pcm)
            if (crypto != null) payload = crypto.encryptAudio(payload)
            val packet = ByteArray(12 + payload.size)
            packet[0] = 0x80.toByte()
            packet[1] = (if (marker) 0xE0 else 0x60).toByte()
            Bytes.putU16(packet, 2, seq)
            Bytes.putU32(packet, 4, rtp)
            Bytes.putU32(packet, 8, ssrc)
            System.arraycopy(payload, 0, packet, 12, payload.size)
            packet
        }
        sender = RtpAudioSender(
            name = host,
            receiver = rtsp.remoteAddress,
            dataPort = serverPort,
            controlPort = serverControl,
            dataSocket = dataSocket!!,
            controlSocket = controlSock,
            packetizer = packetizer,
            syncMode = RtpSyncMode.Ntp,
            initialSequence = firstSeq,
            initialRtpTime = firstRtp,
        ).also { it.extraDelayMs = extraDelayMs; it.start() }

        startKeepAlive()
        AirPlayLog.i(TAG, "$host streaming (${codec.name}${if (encrypted) ", AES" else ""}, latency=$audioLatency)")
    }

    private fun checkAuth(resp: RtspMessage, step: String) {
        when (resp.code) {
            401 -> throw AirPlayAuthRequiredException(
                AirPlayAuthRequiredException.Kind.PASSWORD,
                host,
                if (password == null) "$step: password required" else "$step: password rejected",
            )
            403, 470 -> throw AirPlayAuthRequiredException(
                AirPlayAuthRequiredException.Kind.PIN,
                host,
                "$step: pairing required (${resp.code})",
            )
            453 -> throw AirPlayBusyException(host)
        }
    }

    private fun authSetup() {
        try {
            val keyPair = AirPlay2Crypto.generateCurve25519KeyPair()
            val body = byteArrayOf(0x01) + AirPlay2Crypto.getPublicKeyBytes(keyPair)
            val resp = rtsp.request("POST", "/auth-setup", body, "application/octet-stream")
            AirPlayLog.d(TAG, "$host auth-setup -> ${resp.code}")
        } catch (e: RtspException) {
            throw e
        } catch (e: Exception) {
            AirPlayLog.w(TAG, "$host auth-setup failed: ${e.message}")
        }
    }

    private fun buildSdp(local: InetAddress, crypto: RaopCrypto?): String {
        val family = if (local is Inet6Address) "IP6" else "IP4"
        val localIp = local.hostAddress?.substringBefore('%') ?: "0.0.0.0"
        val remoteIp = rtsp.remoteAddress.hostAddress?.substringBefore('%') ?: host
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=iTunes $rtpSessionId 0 IN $family $localIp\r\n")
        sb.append("s=iTunes\r\n")
        sb.append("c=IN $family $remoteIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio 0 RTP/AVP 96\r\n")
        if (codec == Codec.ALAC) {
            sb.append("a=rtpmap:96 AppleLossless\r\n")
            sb.append("a=fmtp:96 ${AirPlayAudio.ALAC_FMTP}\r\n")
        } else {
            sb.append("a=rtpmap:96 L16/44100/2\r\n")
        }
        if (crypto != null) {
            val key = ByteArray(16).also(random::nextBytes)
            val iv = ByteArray(16).also(random::nextBytes)
            crypto.initAes(key, iv)
            val enc = Base64.getEncoder().withoutPadding()
            sb.append("a=rsaaeskey:${enc.encodeToString(RaopCrypto.encryptAesKey(key))}\r\n")
            sb.append("a=aesiv:${enc.encodeToString(iv)}\r\n")
        }
        return sb.toString()
    }

    private fun parseTransport(header: String): Map<String, String> =
        header.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.toMap()

    private fun startKeepAlive() {
        keepAlive = thread(name = "raop-keepalive-$host", isDaemon = true) {
            while (!closed) {
                try { Thread.sleep(KEEPALIVE_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (closed) break
                if (System.nanoTime() - rtsp.lastActivityNanos < KEEPALIVE_INTERVAL_MS * 1_000_000L) continue
                try {
                    val r = rtsp.request("OPTIONS", "*")
                    if (r.code == 453 || r.code == 0) throw RtspException("keep-alive refused: ${r.code}")
                } catch (e: Exception) {
                    if (!closed) {
                        AirPlayLog.w(TAG, "$host connection lost: ${e.message}")
                        listener?.onDisconnected(e.message ?: "connection lost")
                    }
                    break
                }
            }
        }
    }

    override fun writePcm(pcm: ByteArray) {
        sender?.write(pcm)
    }

    private fun setParameter(contentType: String, body: ByteArray, rtpInfo: Boolean = false) {
        if (closed || !::rtsp.isInitialized) return
        val headers = if (rtpInfo) mapOf("RTP-Info" to "rtptime=${(sender?.currentRtpTime() ?: 0).toUInt()}") else emptyMap()
        try {
            val r = rtsp.request("SET_PARAMETER", sessionUri, body, contentType, headers)
            if (!r.isSuccess) AirPlayLog.d(TAG, "$host SET_PARAMETER $contentType -> ${r.code}")
        } catch (e: Exception) {
            AirPlayLog.w(TAG, "$host SET_PARAMETER $contentType failed: ${e.message}")
        }
    }

    override fun setVolume(db: Double) {
        val clamped = if (db <= AirPlayVolume.MIN_DB - 0.5) AirPlayVolume.MUTE_DB else db.coerceIn(AirPlayVolume.MIN_DB, AirPlayVolume.MAX_DB)
        setParameter("text/parameters", "volume: ${AirPlayVolume.format(clamped)}\r\n".toByteArray())
    }

    private var itemId = 1L

    override fun setMetadata(title: String?, artist: String?, album: String?, durationMs: Long?) {
        if (0 !in metadataCaps) return
        setParameter("application/x-dmap-tagged", Dmap.trackInfo(title, artist, album, durationMs = durationMs, itemId = itemId++), rtpInfo = true)
    }

    override fun setArtwork(image: ByteArray, mimeType: String) {
        if (1 !in metadataCaps || image.isEmpty()) return
        setParameter(mimeType, image, rtpInfo = true)
    }

    override fun setProgress(positionMs: Long, durationMs: Long) {
        if (2 !in metadataCaps || durationMs <= 0) return
        val now = (sender?.currentRtpTime() ?: return).toLong() and 0xFFFFFFFFL
        val start = (now - positionMs * AirPlayAudio.SAMPLE_RATE / 1000) and 0xFFFFFFFFL
        val end = (start + durationMs * AirPlayAudio.SAMPLE_RATE / 1000) and 0xFFFFFFFFL
        setParameter("text/parameters", "progress: $start/$now/$end\r\n".toByteArray())
    }

    override fun flushFifoOnly() {
        val s = sender ?: return
        pendingFlushSeq = s.nextSequence()
        pendingFlushRtp = s.nextRtpTime()
        s.flush()
    }

    override fun sendRtspFlush() {
        if (closed || !::rtsp.isInitialized) return
        val s = sender ?: return
        val seq = pendingFlushSeq.takeIf { it >= 0 } ?: s.nextSequence()
        val rtp = if (pendingFlushSeq >= 0) pendingFlushRtp else s.nextRtpTime()
        pendingFlushSeq = -1
        try {
            rtsp.request("FLUSH", sessionUri, headers = mapOf("RTP-Info" to "seq=$seq;rtptime=${rtp.toUInt()}"))
        } catch (e: Exception) {
            AirPlayLog.w(TAG, "$host FLUSH failed: ${e.message}")
        }
    }

    override fun teardown() {
        if (tornDown || !::rtsp.isInitialized || !rtsp.isConnected) return
        tornDown = true
        sender?.stop()
        try {
            rtsp.setReadTimeout(2000)
            rtsp.request("TEARDOWN", sessionUri)
        } catch (e: Exception) {
            AirPlayLog.d(TAG, "$host TEARDOWN: ${e.message}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        keepAlive?.interrupt()
        sender?.stop()
        timing?.stop()
        dataSocket?.close()
        controlSocket?.close()
        timingSocket?.close()
        if (::rtsp.isInitialized) rtsp.close()
    }

    internal val rtpSender: RtpAudioSender? get() = sender

    override fun statsSummary(): String {
        val s = sender ?: return "not streaming"
        return "sent=${s.packetsSent} resent=${s.packetsResent} underruns=${s.underruns} " +
            "drift(-${s.framesDropped}/+${s.framesInserted}) timing=${timing?.requestsAnswered ?: 0}"
    }

    /** Opens a bare connection and asks the receiver to display its pairing code. */
    fun requestPinDisplay() {
        val c = RtspConnection(host, if (port > 0) port else 5000)
        try {
            c.connect()
            RaopLegacyPairing.startPinDisplay(c)
        } finally {
            c.close()
        }
    }

    /** Pairs with the code the user read off the receiver. */
    fun pairWithPin(pin: String) {
        val c = RtspConnection(host, if (port > 0) port else 5000)
        try {
            c.connect()
            RaopLegacyPairing.pairSetup(c, identity.pairingId, pin, identity.pairingSeed)
        } finally {
            c.close()
        }
    }

    internal fun currentRtpTime(): Int? = sender?.currentRtpTime()
}
