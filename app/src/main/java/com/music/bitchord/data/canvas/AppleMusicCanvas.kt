package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Apple Music's motion artwork — the animated sleeves the web player shows on
 * an album page, exposed on the catalog API as `editorialVideo`.
 *
 * Discovery order:
 * 1. Verified catalog album ID when available.
 * 2. Exact artist + track + album song lookup.
 * 3. Independent artist + album lookup (fetching full album details when search omits `editorialVideo`).
 * 4. Independent artist + track lookup.
 * 5. Bounded alternative storefront fallback when the primary storefront has no match.
 */
object AppleMusicCanvas {

    private const val TAG = "AppleMusicCanvas"
    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"
    private const val WEB_PLAYER = "https://music.apple.com/us/browse"
    private const val SEARCH_LIMIT = 15
    private const val MAX_ALBUM_FETCHES_PER_STAGE = 3
    private const val ALBUM_CACHE_MAX = 128

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Motion artwork is per storefront; use the device's if it looks sane. */
    val storefront: String by lazy {
        Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"
    }

    /** Bounded list of storefronts: device storefront first, then US/GB fallbacks. */
    private val fallbackStorefronts: List<String> by lazy {
        listOf(storefront, "us", "gb").distinct().take(2)
    }

    /**
     * Outcome of an Apple Music lookup so callers can distinguish confirmed absence
     * from transient network/auth/rate-limit failures.
     */
    sealed interface LookupResult {
        data class Found(val artwork: CanvasArtwork) : LookupResult
        data object NotFound : LookupResult
        data class TransientError(val reason: String) : LookupResult
    }

    /** Verified album artwork cache keyed by `storefront:albumId` for cross-track reuse. */
    private val albumArtworkCache = ConcurrentHashMap<String, CanvasArtwork>()

    /** Normalized `(artist|album)` -> verified `(storefront, albumId)` mapping. */
    private val releaseAlbumIdIndex = ConcurrentHashMap<String, Pair<String, String>>()

    private sealed interface ApiResponse {
        data class Ok(val body: String) : ApiResponse
        data class ClientError(val code: Int) : ApiResponse
        data class Transient(val reason: String) : ApiResponse
    }

    fun search(title: String, artist: String, album: String?, albumId: String? = null): CanvasArtwork? =
        when (val result = searchWithResult(title, artist, album, albumId)) {
            is LookupResult.Found -> result.artwork
            else -> null
        }

    fun searchWithResult(
        title: String,
        artist: String,
        album: String?,
        knownAlbumId: String? = null,
    ): LookupResult {
        if (token() == null) {
            return LookupResult.TransientError("No Apple Music web player token available")
        }

        // Stage 1: Verified catalog album ID (caller-provided or previously resolved for this release).
        val releaseKey = releaseKey(artist, album)
        val cachedRef = knownAlbumId?.let { storefront to it } ?: releaseKey?.let { releaseAlbumIdIndex[it] }
        if (cachedRef != null) {
            val (sf, id) = cachedRef
            albumArtworkCache["$sf:$id"]?.let { cached ->
                Log.d(TAG, "stage=cache_album_id hit for '$title' albumId=$id storefront=$sf")
                return LookupResult.Found(cached.copy(title = title, artist = artist))
            }
            when (val fetched = fetchAlbum(id, sf, title, artist)) {
                is LookupResult.Found -> {
                    cacheVerifiedAlbum(sf, id, releaseKey, fetched.artwork)
                    return fetched
                }
                is LookupResult.TransientError -> return fetched
                is LookupResult.NotFound -> Unit
            }
        }

        var hadTransientError = false
        var lastTransientReason = ""

        for ((sfIndex, sf) in fallbackStorefronts.withIndex()) {
            val sfLabel = if (sfIndex == 0) "primary($sf)" else "fallback($sf)"

            // Stage 2: Exact artist + track + album lookup (when album is known).
            if (!album.isNullOrBlank()) {
                val fullTerm = buildSearchTerm(artist, title, album)
                when (val res = searchSongsStage(fullTerm, title, artist, album, sf, "exact_$sfLabel")) {
                    is LookupResult.Found -> {
                        res.artwork.albumId?.let { cacheVerifiedAlbum(sf, it, releaseKey, res.artwork) }
                        return res
                    }
                    is LookupResult.TransientError -> {
                        hadTransientError = true
                        lastTransientReason = res.reason
                        continue
                    }
                    is LookupResult.NotFound -> Unit
                }

                // Stage 3: Independent artist + album lookup.
                when (val res = searchAlbumInternal(album, artist, sf, title, "album_$sfLabel")) {
                    is LookupResult.Found -> {
                        res.artwork.albumId?.let { cacheVerifiedAlbum(sf, it, releaseKey, res.artwork) }
                        return res
                    }
                    is LookupResult.TransientError -> {
                        hadTransientError = true
                        lastTransientReason = res.reason
                        continue
                    }
                    is LookupResult.NotFound -> Unit
                }
            }

            // Stage 4: Independent artist + track lookup (avoids over-specific album terms).
            val trackTerm = buildSearchTerm(artist, title, null)
            when (val res = searchSongsStage(trackTerm, title, artist, album, sf, "track_$sfLabel")) {
                is LookupResult.Found -> {
                    res.artwork.albumId?.let { cacheVerifiedAlbum(sf, it, releaseKey, res.artwork) }
                    return res
                }
                is LookupResult.TransientError -> {
                    hadTransientError = true
                    lastTransientReason = res.reason
                }
                is LookupResult.NotFound -> Unit
            }
        }

        return if (hadTransientError) {
            LookupResult.TransientError(lastTransientReason)
        } else {
            LookupResult.NotFound
        }
    }

    fun searchAlbum(album: String, artist: String): CanvasArtwork? =
        when (val result = searchAlbumWithResult(album, artist)) {
            is LookupResult.Found -> result.artwork
            else -> null
        }

    fun searchAlbumWithResult(album: String, artist: String): LookupResult {
        if (token() == null) {
            return LookupResult.TransientError("No Apple Music web player token available")
        }
        val releaseKey = releaseKey(artist, album)
        releaseKey?.let { releaseAlbumIdIndex[it] }?.let { (sf, id) ->
            albumArtworkCache["$sf:$id"]?.let { return LookupResult.Found(it) }
        }

        var hadTransient = false
        var transientReason = ""
        for ((index, sf) in fallbackStorefronts.withIndex()) {
            val stage = if (index == 0) "album_page_primary($sf)" else "album_page_fallback($sf)"
            when (val res = searchAlbumInternal(album, artist, sf, songTitle = null, stage = stage)) {
                is LookupResult.Found -> {
                    res.artwork.albumId?.let { cacheVerifiedAlbum(sf, it, releaseKey, res.artwork) }
                    return res
                }
                is LookupResult.TransientError -> {
                    hadTransient = true
                    transientReason = res.reason
                }
                is LookupResult.NotFound -> Unit
            }
        }
        return if (hadTransient) LookupResult.TransientError(transientReason) else LookupResult.NotFound
    }

    private fun searchSongsStage(
        term: String,
        title: String,
        artist: String,
        album: String?,
        sf: String,
        stage: String,
    ): LookupResult {
        val url = "$AMP/$sf/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .build()
            .toString()

        val body = when (val resp = getWithRetry(url)) {
            is ApiResponse.Ok -> resp.body
            is ApiResponse.Transient -> return LookupResult.TransientError(resp.reason)
            is ApiResponse.ClientError -> return LookupResult.NotFound
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return LookupResult.TransientError("Malformed search JSON")
        val hits = root["results"]?.jsonObject
            ?.get("songs")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return LookupResult.NotFound

        val ranked = hits.mapNotNull { hit ->
            val song = hit as? JsonObject ?: return@mapNotNull null
            val score = score(song, title, artist, album) ?: return@mapNotNull null
            score to song
        }.sortedByDescending { it.first }

        var albumFetches = 0
        for ((score, song) in ranked) {
            if (score < MIN_SCORE) {
                Log.d(TAG, "stage=$stage rejected remaining candidates for '$title' (best=$score < $MIN_SCORE)")
                break
            }
            val attributes = song["attributes"]?.jsonObject ?: continue
            val songName = attributes["name"]?.jsonPrimitive?.contentOrNull
            val songArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull
            val albumName = attributes["albumName"]?.jsonPrimitive?.contentOrNull
            val resolvedAlbumId = albumId(song)

            attributes["editorialVideo"]?.jsonObject?.let { video ->
                motionUrls(video)?.let { (primary, alternate) ->
                    Log.d(TAG, "stage=$stage inline hit '$songName' (score=$score, sf=$sf)")
                    return LookupResult.Found(
                        CanvasArtwork(
                            url = primary,
                            fallbackUrl = alternate,
                            title = songName,
                            artist = songArtist,
                            album = albumName,
                            source = CanvasSource.APPLE,
                            portraitUrl = tallUrl(video),
                            albumId = resolvedAlbumId,
                            storefront = sf,
                        ),
                    )
                }
            }

            if (resolvedAlbumId != null && albumFetches < MAX_ALBUM_FETCHES_PER_STAGE) {
                albumFetches++
                albumArtworkCache["$sf:$resolvedAlbumId"]?.let { cached ->
                    Log.d(TAG, "stage=$stage album cache hit id=$resolvedAlbumId")
                    return LookupResult.Found(cached.copy(title = songName ?: title, artist = songArtist ?: artist))
                }
                when (val fetched = fetchAlbum(resolvedAlbumId, sf, songName, songArtist)) {
                    is LookupResult.Found -> return fetched
                    is LookupResult.TransientError -> return fetched
                    is LookupResult.NotFound -> Unit
                }
            }
        }
        return LookupResult.NotFound
    }

    private fun searchAlbumInternal(
        album: String,
        artist: String,
        sf: String,
        songTitle: String?,
        stage: String,
    ): LookupResult {
        val term = buildSearchTerm(artist, album, null)
        val url = "$AMP/$sf/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "albums")
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val body = when (val resp = getWithRetry(url)) {
            is ApiResponse.Ok -> resp.body
            is ApiResponse.Transient -> return LookupResult.TransientError(resp.reason)
            is ApiResponse.ClientError -> return LookupResult.NotFound
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return LookupResult.TransientError("Malformed album search JSON")
        val hits = root["results"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return LookupResult.NotFound

        val ranked = hits.mapNotNull { hit ->
            val record = hit as? JsonObject ?: return@mapNotNull null
            val score = score(record, album, artist, album, albumIsSelf = true)
                ?: return@mapNotNull null
            score to record
        }.sortedByDescending { it.first }

        var directFetches = 0
        for ((score, record) in ranked) {
            if (score < MIN_SCORE) {
                Log.d(TAG, "stage=$stage rejected remaining albums for '$album' (best=$score < $MIN_SCORE)")
                break
            }
            val id = record["id"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.startsWith("pl.") }
            val attributes = record["attributes"]?.jsonObject ?: continue
            val name = attributes["name"]?.jsonPrimitive?.contentOrNull
            if (name != null && isCompilation(name)) continue
            val recordArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull

            // Inline editorialVideo when search returns it.
            attributes["editorialVideo"]?.jsonObject?.let { video ->
                motionUrls(video)?.let { (primary, alternate) ->
                    Log.d(TAG, "stage=$stage inline album hit '$name' (score=$score, sf=$sf)")
                    return LookupResult.Found(
                        CanvasArtwork(
                            url = primary,
                            fallbackUrl = alternate,
                            title = songTitle ?: name,
                            artist = recordArtist ?: artist,
                            album = name,
                            source = CanvasSource.APPLE,
                            portraitUrl = tallUrl(video),
                            albumId = id,
                            storefront = sf,
                        ),
                    )
                }
            }

            // Search results frequently omit editorialVideo; fetch verified album details directly.
            if (id != null && directFetches < MAX_ALBUM_FETCHES_PER_STAGE) {
                directFetches++
                albumArtworkCache["$sf:$id"]?.let { cached ->
                    return LookupResult.Found(cached.copy(title = songTitle ?: cached.title, artist = recordArtist ?: artist))
                }
                when (val fetched = fetchAlbum(id, sf, songTitle ?: name, recordArtist ?: artist)) {
                    is LookupResult.Found -> return fetched
                    is LookupResult.TransientError -> return fetched
                    is LookupResult.NotFound -> Unit
                }
            }
        }
        return LookupResult.NotFound
    }

    private fun buildSearchTerm(artist: String, primary: String, secondary: String?): String = buildString {
        val cleanPrimary = primary.canonicalReleaseBase().ifBlank { primary }
        val primaryArtist = splitArtists(artist).firstOrNull().orEmpty().ifBlank { artist }
        if (!cleanPrimary.contains(primaryArtist, ignoreCase = true)) {
            append(primaryArtist).append(' ')
        }
        append(cleanPrimary)
        if (!secondary.isNullOrBlank()) {
            val cleanSecondary = secondary.canonicalReleaseBase().ifBlank { secondary }
            if (cleanSecondary.isNotBlank() && !cleanPrimary.contains(cleanSecondary, ignoreCase = true)) {
                append(' ').append(cleanSecondary)
            }
        }
    }.trim()

    private fun releaseKey(artist: String, album: String?): String? {
        if (album.isNullOrBlank() || artist.isBlank()) return null
        val a = splitArtists(artist).firstOrNull().orEmpty()
        val alb = album.canonicalReleaseBase()
        if (a.isBlank() || alb.isBlank()) return null
        return "$a|$alb"
    }

    private fun cacheVerifiedAlbum(sf: String, albumId: String, releaseKey: String?, artwork: CanvasArtwork) {
        if (albumArtworkCache.size >= ALBUM_CACHE_MAX) {
            albumArtworkCache.clear()
        }
        albumArtworkCache["$sf:$albumId"] = artwork
        if (releaseKey != null) {
            if (releaseAlbumIdIndex.size >= ALBUM_CACHE_MAX) releaseAlbumIdIndex.clear()
            releaseAlbumIdIndex[releaseKey] = sf to albumId
        }
    }

    // ---- Search scoring ------------------------------------------------

    private const val MIN_SCORE = 14

    private fun score(
        record: JsonObject,
        title: String,
        artist: String,
        album: String?,
        albumIsSelf: Boolean = false,
    ): Int? {
        val attributes = record["attributes"]?.jsonObject ?: return null
        val hitName = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hitArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hitAlbum = if (albumIsSelf) {
            hitName
        } else {
            attributes["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        if (isCompilation(hitName) || isCompilation(hitAlbum)) return null
        if (!artistsCompatible(artist, hitArtist)) return null

        // Never match distinct recording versions (Live vs Studio, Remix vs Original, etc.).
        if (hitName.recordingQualifiers() != title.recordingQualifiers()) return null

        var score = 10

        val wantTitle = title.normalizeForMatch()
        val hitTitle = hitName.normalizeForMatch()
        val wantBase = title.canonicalReleaseBase()
        val hitBase = hitName.canonicalReleaseBase()

        score += when {
            hitTitle == wantTitle -> 18
            hitBase.isNotBlank() && hitBase == wantBase -> 15
            hitBase.isNotBlank() && wantBase.isNotBlank() &&
                (hitBase.contains(wantBase) || wantBase.contains(hitBase)) -> 8
            else -> return null
        }

        if (!album.isNullOrBlank() && hitAlbum.isNotBlank()) {
            val wantAlbum = album.normalizeForMatch()
            val gotAlbum = hitAlbum.normalizeForMatch()
            val wantAlbBase = album.canonicalReleaseBase()
            val gotAlbBase = hitAlbum.canonicalReleaseBase()
            score += when {
                gotAlbum == wantAlbum -> 20
                gotAlbBase.isNotBlank() && gotAlbBase == wantAlbBase -> 14
                albumsEquivalent(hitAlbum, album) -> 10
                else -> -4
            }
        }

        // Prefer exact edition match when both sides specify or omit edition markers.
        for (word in EDITION_WORDS) {
            val inWanted = title.contains(word, ignoreCase = true) || (album?.contains(word, ignoreCase = true) == true)
            val inHit = hitName.contains(word, ignoreCase = true) || hitAlbum.contains(word, ignoreCase = true)
            if (inWanted && inHit) score += 3 else if (inHit && !inWanted) score -= 2
        }

        return score
    }

    private val EDITION_WORDS =
        listOf("deluxe", "expanded", "remastered", "anniversary", "bonus")

    private fun isCompilation(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return COMPILATION_MARKERS.any { lower.contains(it) }
    }

    private val COMPILATION_MARKERS = listOf(
        "playlist", "set list", "essentials", "dj mix", "mixed",
        "apple music", "today's hits", "session",
    )

    // ---- Album lookup --------------------------------------------------

    private fun albumId(song: JsonObject): String? {
        val fromRelationship = song["relationships"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        if (fromRelationship != null) return fromRelationship.takeUnless { it.startsWith("pl.") }

        val url = song["attributes"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: return null
        return url.substringAfter("/album/", "")
            .substringBefore("?")
            .substringAfterLast("/")
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
    }

    private fun fetchAlbum(
        albumId: String,
        sf: String,
        songTitle: String?,
        songArtist: String?,
    ): LookupResult {
        val url = "$AMP/$sf/albums/$albumId".toHttpUrl().newBuilder()
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val body = when (val resp = getWithRetry(url)) {
            is ApiResponse.Ok -> resp.body
            is ApiResponse.Transient -> return LookupResult.TransientError(resp.reason)
            is ApiResponse.ClientError -> return LookupResult.NotFound
        }

        val album = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return LookupResult.NotFound

        val attributes = album["attributes"]?.jsonObject ?: return LookupResult.NotFound
        val albumName = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (isCompilation(albumName)) return LookupResult.NotFound

        val video = attributes["editorialVideo"]?.jsonObject ?: return LookupResult.NotFound
        val (primary, alternate) = motionUrls(video) ?: return LookupResult.NotFound

        Log.d(TAG, "stage=fetch_album hit '$albumName' (id=$albumId, sf=$sf)")
        return LookupResult.Found(
            CanvasArtwork(
                url = primary,
                fallbackUrl = alternate,
                title = songTitle,
                artist = songArtist ?: attributes["artistName"]?.jsonPrimitive?.contentOrNull,
                album = albumName,
                source = CanvasSource.APPLE,
                portraitUrl = tallUrl(video),
                albumId = albumId,
                storefront = sf,
            ),
        )
    }

    private fun link(video: JsonObject, key: String): String? = video[key]?.jsonObject?.let { asset ->
        asset["video"]?.jsonPrimitive?.contentOrNull
            ?: asset["videoUrl"]?.jsonPrimitive?.contentOrNull
            ?: asset["hlsUrl"]?.jsonPrimitive?.contentOrNull
            ?: asset["url"]?.jsonPrimitive?.contentOrNull
    }?.takeIf { it.isNotBlank() }

    /** The 3:4 rendition Apple's own player runs under its whole phone screen. */
    private fun tallUrl(video: JsonObject): String? =
        link(video, "motionDetailTall")
            ?: link(video, "motionTallVideo3x4")
            ?: link(video, "motionTallVideo9x16")

    private fun motionUrls(video: JsonObject): Pair<String, String?>? {
        val square = link(video, "motionDetailSquare") ?: link(video, "motionSquareVideo1x1")
        val raw = link(video, "motionDetailRaw")
        val tall = tallUrl(video)
        val primary = square ?: raw ?: tall ?: return null
        val alternate = listOfNotNull(square, raw, tall).firstOrNull { it != primary }
        return primary to alternate
    }

    // ---- Token ---------------------------------------------------------

    private var cachedToken: String? = null
    private var tokenExpiresAtMs = 0L
    private var retryTokenAfterMs = 0L

    /** Tokens the catalog API has already turned down. */
    private val rejected = mutableSetOf<String>()

    @Synchronized
    private fun token(forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedToken?.let { if (now < tokenExpiresAtMs - 60_000) return it }
            if (now < retryTokenAfterMs) return null
        }

        val html = canvasGet(WEB_PLAYER, mapOf("User-Agent" to CANVAS_UA))
        val scripts = html?.let {
            Regex("""/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\.js""")
                .findAll(it).map(MatchResult::value).distinct().toList()
        }.orEmpty()

        for (path in scripts) {
            val script = canvasGet("https://music.apple.com$path", mapOf("User-Agent" to CANVAS_UA))
                ?: continue
            val candidates = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
                .findAll(script)
                .map(MatchResult::value)
                .distinct()
                .filter { it !in rejected }
                .mapNotNull { jwt -> expiry(jwt)?.let { jwt to it } }
                .filter { it.second > now }
                .toList()
            if (candidates.isEmpty()) continue

            val (jwt, expiresAt) = candidates.firstOrNull { isWebPlayerToken(it.first) }
                ?: candidates.first()
            cachedToken = jwt
            tokenExpiresAtMs = expiresAt
            retryTokenAfterMs = 0L
            Log.d(TAG, "web player token refreshed (expires ${java.util.Date(expiresAt)})")
            return jwt
        }

        Log.w(TAG, "no usable web player token in ${scripts.size} bundle(s); backing off")
        retryTokenAfterMs = now + TOKEN_RETRY_MS
        return null
    }

    private const val TOKEN_RETRY_MS = 10L * 60 * 1000

    /**
     * Performs an authenticated catalog GET and immediately refreshes/retries once
     * if the catalog API rejects the current bearer token with 401.
     */
    private fun getWithRetry(url: String): ApiResponse {
        val firstBearer = token() ?: return ApiResponse.Transient("No bearer token")
        val first = executeGet(url, firstBearer)
        if (first !is ApiResponse.ClientError || first.code != 401) return first

        Log.w(TAG, "catalog token rejected (401); refreshing token and retrying immediately")
        synchronized(this) {
            rejected += firstBearer
            if (cachedToken == firstBearer) {
                cachedToken = null
                tokenExpiresAtMs = 0L
            }
            retryTokenAfterMs = 0L
        }
        val refreshedBearer = token(forceRefresh = true)
            ?: return ApiResponse.Transient("Token refresh failed after 401")
        return executeGet(url, refreshedBearer)
    }

    private fun executeGet(url: String, bearer: String): ApiResponse {
        val request = Request.Builder().url(url).apply {
            authHeaders(bearer).forEach { (name, value) -> header(name, value) }
        }.build()
        return try {
            Http.client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string()
                        if (body != null) ApiResponse.Ok(body) else ApiResponse.Transient("Empty response body")
                    }
                    response.code == 401 -> ApiResponse.ClientError(401)
                    response.code == 429 || response.code >= 500 ->
                        ApiResponse.Transient("HTTP ${response.code}")
                    else -> ApiResponse.ClientError(response.code)
                }
            }
        } catch (error: Exception) {
            ApiResponse.Transient(error.javaClass.simpleName)
        }
    }

    private fun isWebPlayerToken(jwt: String): Boolean = runCatching {
        val parts = jwt.split(".")
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        header.contains("WebPlayKid") || payload.contains("AMPWebPlay")
    }.getOrDefault(false)

    private fun expiry(jwt: String): Long? = runCatching {
        val payload = String(
            Base64.getUrlDecoder().decode(jwt.split(".")[1]),
            Charsets.UTF_8,
        )
        val seconds = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)
        seconds?.toLong()?.times(1000)
    }.getOrDefault(null)

    private fun authHeaders(bearer: String) = mapOf(
        "Authorization" to "Bearer $bearer",
        "Origin" to "https://music.apple.com",
        "Referer" to "https://music.apple.com/",
        "User-Agent" to CANVAS_UA,
    )
}
