package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the looping video that belongs behind a track's or a release's cover
 * art — Spotify's Canvas, Apple's motion artwork, Tidal, or community index.
 *
 * Improvements:
 * - Deduplicates concurrent lookups per key instead of blocking unrelated keys.
 * - Distinguishes confirmed misses (cached with bounded TTL) from transient
 *   network/auth/rate-limit failures (not negative-cached).
 * - Reuses verified Apple album artwork across tracks belonging to the same release.
 * - Revalidates provisional misses when album metadata arrives later.
 */
object CanvasRepository {

    private const val TAG = "CanvasRepository"
    private const val CACHE_SIZE = 96
    private const val NEGATIVE_CACHE_TTL_MS = 15L * 60 * 1000L

    private class Entry(
        val artwork: CanvasArtwork?,
        val withAlbum: Boolean,
        val expiresAtMs: Long,
    ) {
        fun isExpired(nowMs: Long): Boolean = expiresAtMs in 1..nowMs
    }

    private data class LookupOutcome(
        val artwork: CanvasArtwork?,
        val cacheNegative: Boolean,
    )

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    /** Verified release-level Apple artwork reused across sibling tracks on the same album. */
    private val verifiedAlbumCache = ConcurrentHashMap<String, CanvasArtwork>()

    /** Deduplicates concurrent identical lookups without serializing unrelated requests. */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<CanvasArtwork?>>()

    suspend fun canvasFor(song: Song): CanvasArtwork? {
        if (song.videoId.isBlank() && (song.localUri != null || song.localPath != null)) return null

        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) return null

        val album = song.albumName?.cleaned()?.takeIf { it.isNotBlank() }
        val spotifyFirst = AppSettings.effectivePrioritizeSpotifyCanvas.value
        val spotifyDisabled = AppSettings.disableSpotifyCanvas.value
        val key = cacheKey("song|${song.videoId}", spotifyFirst, spotifyDisabled)

        // Reuse verified Apple album artwork immediately across tracks on the same release
        // when Apple is the highest-priority source (i.e. Spotify is not prioritized first).
        if (!spotifyFirst && album != null) {
            releaseCacheKey(artist, album)?.let { relKey ->
                verifiedAlbumCache[relKey]?.let { albumArt ->
                    val reused = albumArt.copy(title = title, artist = artist)
                    synchronized(cache) {
                        cache[key] = Entry(reused, withAlbum = true, expiresAtMs = 0L)
                    }
                    return reused
                }
            }
        }

        return resolve(key, withAlbum = album != null) {
            val appleStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                when (val res = AppleMusicCanvas.searchWithResult(title, artist, album)) {
                    is AppleMusicCanvas.LookupResult.Found -> res.artwork to false
                    is AppleMusicCanvas.LookupResult.NotFound -> null to false
                    is AppleMusicCanvas.LookupResult.TransientError -> {
                        Log.d(TAG, "Apple lookup transient failure for '$title': ${res.reason}")
                        null to true
                    }
                }
            }
            val spotifyStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                if (AppSettings.disableSpotifyCanvas.value) null to false
                else runCatching { SpotifyCanvas.search(title, artist, album) to false }
                    .getOrElse { null to true }
            }
            val tidalStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                runCatching { TidalCanvas.search(title, artist, album) to false }
                    .getOrElse { null to true }
            }
            val communityStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                runCatching { CommunityCanvas.search(title, artist, album) to false }
                    .getOrElse { null to true }
            }

            val ordered = if (spotifyFirst) {
                listOf(spotifyStep, appleStep, tidalStep, communityStep)
            } else {
                listOf(appleStep, tidalStep, communityStep, spotifyStep)
            }
            val outcome = firstHitWithStatus(ordered) { it.matches(title, artist, album) }
            if (outcome.artwork?.source == CanvasSource.APPLE && album != null) {
                releaseCacheKey(artist, album)?.let { relKey ->
                    if (verifiedAlbumCache.size >= CACHE_SIZE) verifiedAlbumCache.clear()
                    verifiedAlbumCache[relKey] = outcome.artwork
                }
            }
            outcome
        }
    }

    suspend fun shareClipsFor(song: Song): Map<CanvasSource, CanvasArtwork> = coroutineScope {
        val playing = cached(song)
        val title = song.title.cleaned()
        val artist = song.artist.cleaned()
        if (title.isBlank() || artist.isBlank()) {
            return@coroutineScope playing?.let { mapOf(it.source to it) }.orEmpty()
        }
        val album = song.albumName?.cleaned() ?: playing?.album
        fun CanvasArtwork?.ok() = this?.takeIf { it.matches(title, artist, album) }
        val apple = async(Dispatchers.IO) {
            if (playing?.source == CanvasSource.APPLE) playing
            else runCatching { AppleMusicCanvas.search(title, artist, album) }.getOrNull().ok()
        }
        val spotify = async(Dispatchers.IO) {
            when {
                AppSettings.disableSpotifyCanvas.value -> null
                playing?.source == CanvasSource.SPOTIFY -> playing
                else -> runCatching { SpotifyCanvas.search(title, artist, album) }.getOrNull().ok()
            }
        }
        buildMap {
            if (playing?.source == CanvasSource.OTHER) put(CanvasSource.OTHER, playing)
            apple.await()?.let { put(CanvasSource.APPLE, it) }
            spotify.await()?.let { put(CanvasSource.SPOTIFY, it) }
        }
    }

    fun cached(song: Song): CanvasArtwork? {
        val key = cacheKey(
            base = "song|${song.videoId}",
            spotifyFirst = AppSettings.effectivePrioritizeSpotifyCanvas.value,
            spotifyDisabled = AppSettings.disableSpotifyCanvas.value,
        )
        val now = System.currentTimeMillis()
        return synchronized(cache) {
            cache[key]?.takeUnless { it.isExpired(now) }?.artwork
        }?.takeUnless { AppSettings.disableSpotifyCanvas.value && it.source == CanvasSource.SPOTIFY }
    }

    suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? {
        val name = album.cleaned()
        val credit = artist.cleaned()
        if (name.isBlank() || credit.isBlank()) return null

        val spotifyFirst = AppSettings.effectivePrioritizeSpotifyCanvas.value
        val spotifyDisabled = AppSettings.disableSpotifyCanvas.value
        val key = cacheKey("album|$name|$credit", spotifyFirst, spotifyDisabled)

        return resolve(key, withAlbum = true) {
            val appleStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                when (val res = AppleMusicCanvas.searchAlbumWithResult(name, credit)) {
                    is AppleMusicCanvas.LookupResult.Found -> res.artwork to false
                    is AppleMusicCanvas.LookupResult.NotFound -> null to false
                    is AppleMusicCanvas.LookupResult.TransientError -> null to true
                }
            }
            val spotifyStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                if (AppSettings.disableSpotifyCanvas.value) null to false
                else runCatching { SpotifyCanvas.searchAlbum(name, credit) to false }
                    .getOrElse { null to true }
            }
            val tidalStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                runCatching { TidalCanvas.searchAlbum(name, credit) to false }
                    .getOrElse { null to true }
            }
            val communityStep: suspend () -> Pair<CanvasArtwork?, Boolean> = {
                runCatching { CommunityCanvas.searchAlbum(name, credit) to false }
                    .getOrElse { null to true }
            }
            val ordered = if (spotifyFirst) {
                listOf(spotifyStep, appleStep, tidalStep, communityStep)
            } else {
                listOf(appleStep, tidalStep, communityStep, spotifyStep)
            }
            val outcome = firstHitWithStatus(ordered) { it.matches(name, credit, name) }
            if (outcome.artwork?.source == CanvasSource.APPLE) {
                releaseCacheKey(credit, name)?.let { relKey ->
                    if (verifiedAlbumCache.size >= CACHE_SIZE) verifiedAlbumCache.clear()
                    verifiedAlbumCache[relKey] = outcome.artwork
                }
            }
            outcome
        }
    }

    private fun releaseCacheKey(artist: String, album: String): String? {
        val a = splitArtists(artist).firstOrNull().orEmpty()
        val alb = album.canonicalReleaseBase()
        if (a.isBlank() || alb.isBlank()) return null
        return "$a|$alb"
    }

    private fun cacheKey(base: String, spotifyFirst: Boolean, spotifyDisabled: Boolean): String =
        "$base|spotifyFirst=$spotifyFirst|spotifyDisabled=$spotifyDisabled"

    private suspend fun resolve(
        key: String,
        withAlbum: Boolean,
        lookUp: suspend () -> LookupOutcome,
    ): CanvasArtwork? {
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { entry ->
                if (!entry.isExpired(now) &&
                    entry.reusable(withAlbum) &&
                    !(AppSettings.disableSpotifyCanvas.value && entry.artwork?.source == CanvasSource.SPOTIFY)
                ) {
                    return entry.artwork
                }
            }
        }

        val flightKey = "$key|alb=$withAlbum"
        val mine = CompletableDeferred<CanvasArtwork?>()
        val existing = inFlight.putIfAbsent(flightKey, mine)
        if (existing != null) {
            return existing.await()
        }

        return try {
            val outcome = withContext(Dispatchers.IO) { lookUp() }
            val filtered = outcome.artwork?.takeUnless {
                AppSettings.disableSpotifyCanvas.value && it.source == CanvasSource.SPOTIFY
            }
            synchronized(cache) {
                when {
                    filtered != null -> cache[key] = Entry(filtered, withAlbum, expiresAtMs = 0L)
                    outcome.cacheNegative -> cache[key] = Entry(
                        artwork = null,
                        withAlbum = withAlbum,
                        expiresAtMs = System.currentTimeMillis() + NEGATIVE_CACHE_TTL_MS,
                    )
                }
            }
            mine.complete(filtered)
            filtered
        } catch (t: Throwable) {
            mine.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(flightKey, mine)
        }
    }

    private fun Entry.reusable(withAlbum: Boolean): Boolean =
        artwork != null || this.withAlbum || !withAlbum

    private suspend fun firstHitWithStatus(
        sources: List<suspend () -> Pair<CanvasArtwork?, Boolean>>,
        accept: (CanvasArtwork) -> Boolean,
    ): LookupOutcome {
        var anyTransientFailure = false
        for (source in sources) {
            val (found, transient) = runCatching { source() }
                .onFailure { Log.d(TAG, "source threw: ${it.message}") }
                .getOrElse { null to true }
            if (transient) anyTransientFailure = true
            if (found == null) continue
            if (!accept(found)) {
                Log.d(TAG, "rejected candidate '${found.title}' by '${found.artist}' (${found.source})")
                continue
            }
            return LookupOutcome(found, cacheNegative = true)
        }
        return LookupOutcome(artwork = null, cacheNegative = !anyTransientFailure)
    }

    private fun String.cleaned(): String = replace(NOISE, " ")
        .substringBefore(" | ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { this }

    private val NOISE = Regex(
        """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
            """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
}
