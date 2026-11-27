package com.music.bitchord.data.recommendations

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.RankedEntry
import com.music.bitchord.data.stats.RankedSong
import com.music.bitchord.data.stats.ReplayPeriod
import java.util.Locale

/**
 * Local-first listener taste profile derived from on-device listening history,
 * completed listens, favourite artists/albums, genres, initial taste selections,
 * and like/dislike/skip signals.
 *
 * Works without Google sign-in and requires zero external AI or dedicated server.
 */
data class ListenerProfile(
    val recentSongs: List<RankedSong> = emptyList(),
    val allTimeSongs: List<RankedSong> = emptyList(),
    val topArtists: List<RankedEntry> = emptyList(),
    val topAlbums: List<RankedEntry> = emptyList(),
    val topGenres: List<RankedEntry> = emptyList(),
    val likedIds: Set<String> = emptySet(),
    val dislikedIds: Set<String> = emptySet(),
    /** Video IDs with very short listen duration and zero completed plays (frequent skips). */
    val skippedIds: Set<String> = emptySet(),
    /** Optional locally-selected initial genres/moods for new users without history. */
    val seedGenres: Set<String> = emptySet(),
) {
    val hasHistory: Boolean
        get() = recentSongs.isNotEmpty() || allTimeSongs.isNotEmpty() || topArtists.isNotEmpty()

    val hasPreferences: Boolean
        get() = hasHistory || seedGenres.isNotEmpty() || likedIds.isNotEmpty()

    val preferredArtistNames: Set<String> by lazy {
        topArtists.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT).trim() }
    }

    val preferredGenreNames: Set<String> by lazy {
        buildSet {
            topGenres.forEach { add(it.title.lowercase(Locale.ROOT).trim()) }
            seedGenres.forEach { add(it.lowercase(Locale.ROOT).trim()) }
        }
    }
}

/** Partitioned New/Explore feed: personalised new releases on top, global & charts clearly separated at bottom. */
data class NewFeedPartition(
    val personalized: List<HomeShelf> = emptyList(),
    val global: List<HomeShelf> = emptyList(),
)

/**
 * Separates and personalises Sonora's **Home** and **New** feeds locally:
 * - **Home**: Exclusively prioritises listener-relevant content; excludes generic worldwide
 *   charts and random trending shelves when taste signals exist.
 * - **New**: Tiered 5-level priority — (1) favourite artists' latest releases, (2) taste-matching
 *   new albums/singles, (3) similar artists, (4) relevant playlists & emerging artists,
 *   (5) global new releases & worldwide charts clearly separated at the bottom.
 */
object HomePersonalizer {

    private val GENERIC_WORLDWIDE_KEYWORDS = listOf(
        "trending", "charts", "top 100", "viral", "popular right now",
        "global top", "worldwide", "top songs", "top music videos", "top episodes",
    )

    private val HOME_PRIORITY_TIERS: List<Pair<Int, List<String>>> = listOf(
        10 to listOf("recently played", "listen again", "quick picks"),
        20 to listOf("your favourite artists", "your favorite artists", "favourite albums", "favorite albums", "from your library"),
        30 to listOf("made for you", "mixed for you", "your mixes", "supermix"),
        35 to listOf("frequently played", "most played", "on repeat"),
        40 to listOf("based on your listening", "because you listened", "similar to"),
        50 to listOf("rediscover", "forgotten favourites", "forgotten favorites"),
        60 to listOf("genres", "moods", "vibes"),
        70 to listOf("community playlists", "recommended playlists", "playlists for you"),
        80 to listOf("recommended albums", "albums for you"),
    )

    private val NEW_PRIORITY_TIERS: List<Pair<Int, List<String>>> = listOf(
        10 to listOf("latest from your artists"),
        20 to listOf("new for your taste", "new releases for you"),
        30 to listOf("similar", "because you listened", "more from artists"),
        40 to listOf("playlist", "emerging", "fresh", "radar", "discover"),
    )

    /** Builds a [ListenerProfile] purely from local on-device stats, taste seeds, and like statuses. */
    suspend fun buildLocalProfile(
        likeStatuses: Map<String, LikeStatus> = emptyMap(),
        seedGenres: Set<String> = TastePreferences.selectedGenres.value,
    ): ListenerProfile {
        val month = runCatching { ListeningStats.summary(ReplayPeriod.THIS_MONTH) }.getOrNull()
        val allTime = runCatching { ListeningStats.summary(ReplayPeriod.ALL_TIME) }.getOrNull()
        val recentSongs = month?.songs.orEmpty()
        val allTimeSongs = allTime?.songs.orEmpty()
        val topArtists = (month?.artists.orEmpty() + allTime?.artists.orEmpty())
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .take(15)
        val topAlbums = (month?.albums.orEmpty() + allTime?.albums.orEmpty())
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .take(12)
        val topGenres = (month?.genres.orEmpty() + allTime?.genres.orEmpty())
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .take(8)
        val liked = likeStatuses.filterValues { it == LikeStatus.LIKE }.keys
        val disliked = likeStatuses.filterValues { it == LikeStatus.DISLIKE }.keys
        // Tracks sampled briefly (< 20s) with 0 completed plays indicate repeated skips.
        val skipped = allTimeSongs
            .filter { it.plays == 0 && it.ms in 1L..20_000L }
            .mapTo(HashSet()) { it.song.videoId }

        return ListenerProfile(
            recentSongs = recentSongs,
            allTimeSongs = allTimeSongs,
            topArtists = topArtists,
            topAlbums = topAlbums,
            topGenres = topGenres,
            likedIds = liked,
            dislikedIds = disliked,
            skippedIds = skipped,
            seedGenres = seedGenres,
        )
    }

    /**
     * Personalises **Home** shelves strictly for the listener's taste.
     * Generic worldwide charts and random trending shelves are excluded once
     * the listener has history or taste preferences.
     */
    fun personalizeShelves(
        rawShelves: List<HomeShelf>,
        profile: ListenerProfile,
        refreshSeed: Long = 0L,
    ): List<HomeShelf> {
        val cleaned = rawShelves.mapNotNull { shelf ->
            val filtered = shelf.items
                .filterNot { item -> item.videoId != null && item.videoId in profile.dislikedIds }
                .sortedByDescending { item -> itemAffinityScore(item, profile) }
            if (filtered.isEmpty()) null else shelf.copy(items = filtered)
        }

        if (!profile.hasPreferences) {
            // New user with no history or taste seeds yet: keep non-chart shelves first.
            return cleaned.sortedBy { if (isGenericWorldwideShelf(it.title)) 500 else 100 }
        }

        // Home must NOT display generic worldwide charts or random trending content.
        val nonGeneric = cleaned.filterNot { isGenericWorldwideShelf(it.title) }
        val existingTitles = nonGeneric.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
        val localShelves = mutableListOf<HomeShelf>()
        val usedVideoIds = HashSet<String>()

        // 1. Recently played (local fallback when signed out or before YT history loads).
        if (existingTitles.none { it.contains("recently played") || it.contains("listen again") } &&
            profile.recentSongs.isNotEmpty()
        ) {
            val recentItems = profile.recentSongs
                .filter { it.song.videoId !in profile.dislikedIds && it.song.videoId !in profile.skippedIds }
                .take(12)
                .map { ranked ->
                    usedVideoIds.add(ranked.song.videoId)
                    ranked.song.toShelfItem()
                }
            if (recentItems.isNotEmpty()) {
                localShelves += HomeShelf(
                    title = "Recently played",
                    subtitle = "Jump back in",
                    items = recentItems,
                    layout = ShelfLayout.CARDS,
                )
            }
        }

        // 2. Your favourite artists (from completed listens).
        val artistItems = profile.topArtists
            .filter { !it.browseId.isNullOrBlank() }
            .take(10)
            .map { artist ->
                ShelfItem(
                    title = artist.title,
                    subtitle = "${artist.plays} plays",
                    thumbnailUrl = artist.artworkUrl,
                    videoId = null,
                    browseId = artist.browseId,
                )
            }
        if (artistItems.isNotEmpty() && existingTitles.none { it.contains("favourite artists") || it.contains("favorite artists") }) {
            localShelves += HomeShelf(
                title = "Your favourite artists",
                subtitle = "Based on your listening",
                items = artistItems,
                layout = ShelfLayout.CARDS,
            )
        }

        // 3. Favourite albums (from completed listens).
        val albumItems = profile.topAlbums
            .filter { !it.browseId.isNullOrBlank() }
            .take(10)
            .map { album ->
                ShelfItem(
                    title = album.title,
                    subtitle = album.subtitle ?: "${album.plays} plays",
                    thumbnailUrl = album.artworkUrl,
                    videoId = null,
                    browseId = album.browseId,
                )
            }
        if (albumItems.isNotEmpty() && existingTitles.none { it.contains("favourite albums") || it.contains("favorite albums") }) {
            localShelves += HomeShelf(
                title = "Favourite albums",
                subtitle = "Albums you return to",
                items = albumItems,
                layout = ShelfLayout.CARDS,
            )
        }

        // 4. Rediscover your favourites (all-time favourites excluding immediate top 5, rotated on refresh).
        val recentTopIds = profile.recentSongs.take(5).mapTo(HashSet()) { it.song.videoId }
        val rediscoverPool = profile.allTimeSongs
            .filter {
                it.plays >= 2 &&
                    it.song.videoId !in recentTopIds &&
                    it.song.videoId !in profile.dislikedIds &&
                    it.song.videoId !in profile.skippedIds
            }
        if (rediscoverPool.isNotEmpty() && existingTitles.none { it.contains("rediscover") || it.contains("forgotten") }) {
            val rotated = rotateList(rediscoverPool, refreshSeed).take(10)
            val rediscoverItems = rotated.map {
                usedVideoIds.add(it.song.videoId)
                it.song.toShelfItem()
            }
            if (rediscoverItems.isNotEmpty()) {
                localShelves += HomeShelf(
                    title = "Rediscover your favourites",
                    subtitle = "Forgotten favourites",
                    items = rediscoverItems,
                    layout = ShelfLayout.CARDS,
                )
            }
        }

        // 5. Based on your listening history — high-affinity items from provider shelves.
        if (existingTitles.none { it.contains("based on your listening") }) {
            val matchingItems = nonGeneric
                .asSequence()
                .flatMap { it.items.asSequence() }
                .filter { item ->
                    val id = item.videoId
                    (id == null || (id !in usedVideoIds && id !in profile.dislikedIds && id !in profile.skippedIds)) &&
                        itemAffinityScore(item, profile) > 0
                }
                .distinctBy { it.videoId ?: it.browseId ?: it.title }
                .take(12)
                .toList()
            if (matchingItems.size >= 3) {
                localShelves += HomeShelf(
                    title = "Based on your listening history",
                    subtitle = "Tailored to your taste",
                    items = matchingItems,
                    layout = ShelfLayout.CARDS,
                )
            }
        }

        val combined = (localShelves + nonGeneric)
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .sortedBy { homeShelfPriorityRank(it, profile) }

        return if (combined.isNotEmpty()) combined else cleaned
    }

    /**
     * Partitions and ranks **New** (Explore) shelves in strict 5-tier order:
     * 1. Latest releases from favourite/frequently played artists
     * 2. New albums and singles matching the user's musical taste
     * 3. Recently released music from similar artists
     * 4. New relevant playlists and emerging artists
     * 5. Global new releases & worldwide charts (clearly separated in [NewFeedPartition.global])
     */
    fun personalizeNewShelves(
        rawNewShelves: List<HomeShelf>,
        profile: ListenerProfile,
        refreshSeed: Long = 0L,
    ): NewFeedPartition {
        val cleaned = rawNewShelves.mapNotNull { shelf ->
            val filtered = shelf.items
                .filterNot { item -> item.videoId != null && item.videoId in profile.dislikedIds }
                .sortedByDescending { item -> itemAffinityScore(item, profile) }
            if (filtered.isEmpty()) null else shelf.copy(items = filtered)
        }

        if (!profile.hasPreferences) {
            return NewFeedPartition(personalized = emptyList(), global = cleaned)
        }

        val allItems = cleaned.flatMap { it.items }
            .filter { it.videoId == null || it.videoId !in profile.skippedIds }
            .distinctBy { it.videoId ?: it.browseId ?: it.title }

        val personalizedShelves = mutableListOf<HomeShelf>()
        val usedKeys = HashSet<String>()

        // Tier 1: Latest releases from favourite & frequently played artists.
        if (profile.preferredArtistNames.isNotEmpty()) {
            val fromFavArtists = allItems.filter { item ->
                val text = "${item.title} ${item.subtitle}".lowercase(Locale.ROOT)
                profile.preferredArtistNames.any { artist -> artist.length >= 2 && text.contains(artist) }
            }
            if (fromFavArtists.isNotEmpty()) {
                val items = rotateList(fromFavArtists, refreshSeed).take(12)
                items.forEach { usedKeys.add(it.videoId ?: it.browseId ?: it.title) }
                personalizedShelves += HomeShelf(
                    title = "Latest from your artists",
                    subtitle = "New releases from artists you play",
                    items = items,
                    layout = ShelfLayout.CARDS,
                )
            }
        }

        // Tier 2: New albums and singles matching the user's musical taste (genres / affinity).
        val tasteMatched = allItems.filter { item ->
            val key = item.videoId ?: item.browseId ?: item.title
            key !in usedKeys && itemAffinityScore(item, profile) > 0
        }
        if (tasteMatched.size >= 2) {
            val items = rotateList(tasteMatched, refreshSeed).take(12)
            items.forEach { usedKeys.add(it.videoId ?: it.browseId ?: it.title) }
            personalizedShelves += HomeShelf(
                title = "New for your taste",
                subtitle = "Albums and singles matching your preferences",
                items = items,
                layout = ShelfLayout.CARDS,
            )
        }

        // Partition remaining provider shelves: Tiers 3–4 (similar/playlists/emerging) vs Tier 5 (global/charts).
        val globalShelves = mutableListOf<HomeShelf>()
        for (shelf in cleaned) {
            val lower = shelf.title.lowercase(Locale.ROOT)
            val isGlobal = isGenericWorldwideShelf(lower) || lower.contains("new release")
            val hasAffinity = shelf.items.any { itemAffinityScore(it, profile) > 0 }
            if (!isGlobal && (hasAffinity || lower.contains("playlist") || lower.contains("emerging") || lower.contains("similar"))) {
                personalizedShelves += shelf
            } else {
                globalShelves += shelf
            }
        }

        val orderedPersonalized = personalizedShelves
            .distinctBy { it.title.lowercase(Locale.ROOT) }
            .sortedBy { newShelfPriorityRank(it) }

        return NewFeedPartition(
            personalized = orderedPersonalized,
            global = globalShelves.distinctBy { it.title.lowercase(Locale.ROOT) },
        )
    }

    internal fun isGenericWorldwideShelf(title: String): Boolean {
        val lower = title.lowercase(Locale.ROOT)
        return GENERIC_WORLDWIDE_KEYWORDS.any { lower.contains(it) }
    }

    internal fun itemAffinityScore(item: ShelfItem, profile: ListenerProfile): Int {
        var score = 0
        val vid = item.videoId
        if (vid != null) {
            if (vid in profile.likedIds) score += 40
            if (vid in profile.skippedIds) score -= 35
            if (vid in profile.dislikedIds) score -= 100
        }
        val text = "${item.title} ${item.subtitle}".lowercase(Locale.ROOT)
        if (profile.preferredArtistNames.any { artist -> artist.length >= 2 && text.contains(artist) }) {
            score += 30
        }
        if (profile.preferredGenreNames.any { genre -> genre.length >= 3 && text.contains(genre) }) {
            score += 20
        }
        return score
    }

    private fun homeShelfPriorityRank(shelf: HomeShelf, profile: ListenerProfile): Int {
        val lower = shelf.title.lowercase(Locale.ROOT)
        if (isGenericWorldwideShelf(lower)) return 500
        for ((rank, keywords) in HOME_PRIORITY_TIERS) {
            if (keywords.any { lower.contains(it) }) return rank
        }
        val avgAffinity = shelf.items.take(8).map { itemAffinityScore(it, profile) }.average()
        return if (avgAffinity >= 15.0) 65 else 200
    }

    private fun newShelfPriorityRank(shelf: HomeShelf): Int {
        val lower = shelf.title.lowercase(Locale.ROOT)
        for ((rank, keywords) in NEW_PRIORITY_TIERS) {
            if (keywords.any { lower.contains(it) }) return rank
        }
        return 100
    }

    private fun <T> rotateList(items: List<T>, seed: Long): List<T> {
        if (items.size <= 1 || seed == 0L) return items
        val shift = ((seed % items.size) + items.size).toInt() % items.size
        return items.drop(shift) + items.take(shift)
    }

    private fun com.music.bitchord.data.model.Song.toShelfItem() = ShelfItem(
        title = title,
        subtitle = artist,
        thumbnailUrl = thumbnailUrl,
        videoId = videoId,
        browseId = albumId,
        isVideo = isVideo,
    )
}
