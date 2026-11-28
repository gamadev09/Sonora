package com.music.bitchord.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encrypted LAN pairing between Sonora Phone and Sonora TV.
 *
 * Security model:
 * 1. TV generates a single-use 128-bit [sid] and 256-bit AES-GCM [keyBytes]
 *    with a 2-minute TTL and renders them in a QR code (`bitchord://tv-pair?...`).
 * 2. The phone validates that `host` is a private RFC-1918 LAN IPv4 address
 *    (refusing public hosts so a malicious QR can never exfiltrate cookies).
 * 3. After explicit user confirmation, the phone encrypts the active session
 *    JSON with AES-256-GCM (random 96-bit IV, [sid] bound as AAD) and POSTs it
 *    to `http://<host>:<port>/pair`.
 * 4. TV verifies [sid], TTL, and GCM authentication tag, consumes the session
 *    once, zeroes [keyBytes], and shuts down the pairing server.
 */
object TvPairingCrypto {
    const val SCHEME = "sonora"
    const val HOST = "tv-pair"
    const val SESSION_TTL_MS = 120_000L
    const val MAX_ATTEMPTS = 3

    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val SID_BYTES = 16

    private val random = SecureRandom()

    data class TvPairSession(
        val sid: String,
        val keyBytes: ByteArray,
        val expiresAtMs: Long,
    ) {
        fun buildQrUri(host: String, port: Int): String {
            val k = Base64.encodeToString(keyBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "$SCHEME://$HOST?host=$host&port=$port&sid=$sid&k=$k"
        }

        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs > expiresAtMs

        fun wipe() {
            keyBytes.fill(0)
        }
    }

    data class TvPairRequest(
        val host: String,
        val port: Int,
        val sid: String,
        val keyBytes: ByteArray,
    )

    data class EncryptedPayload(
        val sid: String,
        val ivB64: String,
        val ctB64: String,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("sid", sid)
            put("iv", ivB64)
            put("ct", ctB64)
        }.toString()
    }

    fun generateSession(nowMs: Long = System.currentTimeMillis()): TvPairSession {
        val sidBytes = ByteArray(SID_BYTES).also(random::nextBytes)
        val keyBytes = ByteArray(KEY_BYTES).also(random::nextBytes)
        val sid = sidBytes.joinToString("") { "%02x".format(it) }
        return TvPairSession(sid = sid, keyBytes = keyBytes, expiresAtMs = nowMs + SESSION_TTL_MS)
    }

    fun parseQrUri(raw: String?): TvPairRequest? {
        if (raw.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        if (!isPrivateLanIpv4(host)) return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        if (port !in 1024..65535) return null
        val sid = uri.getQueryParameter("sid")?.trim().orEmpty()
        if (sid.length < 16 || !sid.all { it.isLetterOrDigit() }) return null
        val k = uri.getQueryParameter("k")?.trim().orEmpty()
        val keyBytes = runCatching {
            Base64.decode(k, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        if (keyBytes.size != KEY_BYTES) return null
        return TvPairRequest(host = host, port = port, sid = sid, keyBytes = keyBytes)
    }

    /** Strictly allow only RFC-1918 private LAN IPv4 addresses so cookies can never leave the local network. */
    fun isPrivateLanIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31)
    }

    fun encryptSession(session: CapturedSession, sid: String, keyBytes: ByteArray): EncryptedPayload {
        val plaintext = JSONObject().apply {
            put("cookie", session.cookie)
            session.pageId?.let { put("pageId", it) }
            session.dataSyncId?.let { put("dataSyncId", it) }
            session.authUser?.let { put("authUser", it) }
            session.visitorData?.let { put("visitorData", it) }
            session.clientVersion?.let { put("clientVersion", it) }
        }.toString().toByteArray(Charsets.UTF_8)

        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(sid.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext)
        return EncryptedPayload(
            sid = sid,
            ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP),
            ctB64 = Base64.encodeToString(ct, Base64.NO_WRAP),
        )
    }

    fun decryptSession(jsonBody: String, expectedSid: String, keyBytes: ByteArray): CapturedSession? {
        return runCatching {
            val obj = JSONObject(jsonBody)
            val sid = obj.optString("sid", "")
            if (sid != expectedSid) return null
            val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
            val ct = Base64.decode(obj.getString("ct"), Base64.NO_WRAP)
            if (iv.size != IV_BYTES || ct.size < 16) return null
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(expectedSid.toByteArray(Charsets.UTF_8))
            val plain = String(cipher.doFinal(ct), Charsets.UTF_8)
            val data = JSONObject(plain)
            val cookie = data.optString("cookie", "").trim()
            if (cookie.isBlank()) return null
            CapturedSession(
                cookie = cookie,
                pageId = data.optString("pageId").takeIf { it.isNotBlank() },
                dataSyncId = data.optString("dataSyncId").takeIf { it.isNotBlank() },
                authUser = data.optString("authUser").takeIf { it.isNotBlank() },
                visitorData = data.optString("visitorData").takeIf { it.isNotBlank() },
                clientVersion = data.optString("clientVersion").takeIf { it.isNotBlank() },
                loggedIn = true,
            )
        }.getOrNull()
    }

    /** Phone-side encrypted upload of the currently signed-in account session to the TV on local Wi-Fi. */
    suspend fun sendActiveSessionToTv(context: Context, request: TvPairRequest): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(isPrivateLanIpv4(request.host)) { "TV address must be on your local Wi-Fi network." }
                val store = AuthStore(context)
                val active = store.activeSession ?: error("Sign in on this phone first before pairing with TV.")
                val profile = active.profiles.firstOrNull { it.profileId == active.activeProfileId }
                    ?: active.profiles.firstOrNull()
                val captured = CapturedSession(
                    cookie = active.cookie,
                    pageId = profile?.pageId,
                    dataSyncId = profile?.dataSyncId,
                    authUser = profile?.authUser,
                    visitorData = null,
                    clientVersion = null,
                    loggedIn = true,
                )
                val encrypted = encryptSession(captured, request.sid, request.keyBytes)
                val url = URL("http://${request.host}:${request.port}/pair")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7_000
                    readTimeout = 7_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(encrypted.toJson()) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                        error(err?.takeIf { it.isNotBlank() } ?: "TV rejected pairing (HTTP $code)")
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
}

/** Pending `bitchord://tv-pair?...` deep link received by MainActivity when scanning a TV QR code. */
object TvPairDeepLink {
    private val _pending = MutableStateFlow<TvPairingCrypto.TvPairRequest?>(null)
    val pending: StateFlow<TvPairingCrypto.TvPairRequest?> = _pending.asStateFlow()

    fun consume(intent: Intent?) {
        val data = intent?.dataString ?: return
        val parsed = TvPairingCrypto.parseQrUri(data) ?: return
        _pending.value = parsed
    }

    fun handled() {
        _pending.value = null
    }
}
