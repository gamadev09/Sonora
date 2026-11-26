package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Shared plumbing for the lyric providers.
 *
 * Distinguishes transient network/rate-limit failures from genuine 404 misses,
 * supports HTTP-level cancellation when switching tracks, and applies bounded
 * retries with exponential backoff / Retry-After on transient errors.
 */
private const val LYRICS_TIMEOUT_SECONDS = 6L
private const val MAX_TRANSIENT_RETRIES = 1
private const val MAX_RETRY_AFTER_MS = 2_500L

internal const val LYRICS_AGENT = "Sonora (https://github.com/gamadev09/Sonora)"

val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Distinguishable provider HTTP outcome instead of collapsing every error to null. */
sealed interface LyricsHttpResult {
    data class Success(val body: String) : LyricsHttpResult
    data object NotFound : LyricsHttpResult
    data object Timeout : LyricsHttpResult
    data object NetworkUnavailable : LyricsHttpResult
    data class RateLimited(val retryAfterMs: Long? = null) : LyricsHttpResult
    data object AuthRequired : LyricsHttpResult
    data object Unavailable : LyricsHttpResult
    data object InvalidResponse : LyricsHttpResult
    data object Cancelled : LyricsHttpResult

    val bodyOrNull: String? get() = (this as? Success)?.body
}

private val client by lazy {
    Http.client.newBuilder()
        .callTimeout(LYRICS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()
}

private val authenticatedClient by lazy {
    client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
}

private suspend fun OkHttpClient.executeCancellable(request: Request): LyricsHttpResult {
    var attempt = 0
    while (true) {
        val outcome = executeOnceCancellable(request)
        val retryDelayMs = when (outcome) {
            is LyricsHttpResult.RateLimited -> (outcome.retryAfterMs ?: 600L).coerceIn(200L, MAX_RETRY_AFTER_MS)
            LyricsHttpResult.Timeout, LyricsHttpResult.Unavailable -> 400L * (1 shl attempt)
            else -> null
        }
        if (retryDelayMs == null || attempt >= MAX_TRANSIENT_RETRIES) {
            return outcome
        }
        attempt++
        delay(retryDelayMs)
    }
}

private suspend fun OkHttpClient.executeOnceCancellable(request: Request): LyricsHttpResult =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled() || cont.isCancelled) {
                    if (cont.isActive) cont.resume(LyricsHttpResult.Cancelled)
                    return
                }
                val result = when (e) {
                    is SocketTimeoutException -> LyricsHttpResult.Timeout
                    is UnknownHostException -> LyricsHttpResult.NetworkUnavailable
                    else -> LyricsHttpResult.Unavailable
                }
                if (cont.isActive) cont.resume(result)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val outcome = when {
                        res.isSuccessful -> {
                            val body = runCatching { res.body?.string() }.getOrNull()
                            if (body != null) LyricsHttpResult.Success(body) else LyricsHttpResult.InvalidResponse
                        }
                        res.code == 404 -> LyricsHttpResult.NotFound
                        res.code == 401 || res.code == 403 -> LyricsHttpResult.AuthRequired
                        res.code == 429 -> {
                            val retryAfterSec = res.header("Retry-After")?.trim()?.toLongOrNull()
                            LyricsHttpResult.RateLimited(retryAfterSec?.times(1_000L))
                        }
                        res.code in 500..599 -> LyricsHttpResult.Unavailable
                        else -> LyricsHttpResult.InvalidResponse
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
            }
        })
    }

/** Cancellable GET with structured error classification and bounded transient retry. */
internal suspend fun lyricsGetResult(url: String): LyricsHttpResult {
    return try {
        val request = Request.Builder().url(url)
            .header("User-Agent", LYRICS_AGENT)
            .header("Accept", "application/json")
            .build()
        client.executeCancellable(request)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        LyricsHttpResult.InvalidResponse
    }
}

/** Body of a successful GET, or null for any failure. */
internal fun lyricsGet(url: String): String? = runCatching {
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .build()
    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()

/** Body of an authenticated provider GET without service-specific browser headers. */
internal fun lyricsGetBearer(url: String, bearer: String): String? = runCatching {
    if (bearer.isBlank()) return null
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json, text/plain, */*")
        .header("Authorization", "Bearer $bearer")
        .build()
    authenticatedClient.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()

/**
 * [lyricsGet], with a bearer token and the headers Apple's own web player
 * sends alongside one — `amp-api.music.apple.com` answers a token with no
 * `Origin` at all the same way it answers a wrong one, with a 403.
 */
internal fun lyricsGetAuthorized(url: String, bearer: String): String? = runCatching {
    val request = Request.Builder().url(url)
        .header("User-Agent", LYRICS_AGENT)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $bearer")
        .header("Origin", "https://music.apple.com")
        .header("Referer", "https://music.apple.com/")
        .build()
    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) response.body?.string() else null
    }
}.getOrNull()
