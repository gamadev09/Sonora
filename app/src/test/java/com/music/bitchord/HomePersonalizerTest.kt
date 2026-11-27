package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.recommendations.HomePersonalizer
import com.music.bitchord.data.recommendations.ListenerProfile
import com.music.bitchord.data.stats.RankedEntry
import com.music.bitchord.data.stats.RankedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePersonalizerTest {

    private fun song(id: String, title: String, artist: String) =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, fromAutoplay = false)

    private fun shelfItem(id: String, title: String, subtitle: String) =
        ShelfItem(title = title, subtitle = subtitle, thumbnailUrl = null, videoId = id, browseId = null)

    @Test
    fun `home excludes generic worldwide charts and prioritises personal taste`() {
        val profile = ListenerProfile(
            recentSongs = listOf(
                RankedSong(song("v1", "Track 1", "Daft Punk"), ms = 200_000L, plays = 4),
            ),
            allTimeSongs = listOf(
                RankedSong(song("v1", "Track 1", "Daft Punk"), ms = 200_000L, plays = 4),
                RankedSong(song("v2", "Classic A", "Daft Punk"), ms = 400_000L, plays = 8),
                RankedSong(song("v3", "Classic B", "Radiohead"), ms = 350_000L, plays = 6),
                RankedSong(song("v4", "Classic C", "Tame Impala"), ms = 300_000L, plays = 5),
                RankedSong(song("v5", "Classic D", "Justice"), ms = 280_000L, plays = 4),
                RankedSong(song("v6", "Old Favourite", "Radiohead"), ms = 500_000L, plays = 9),
            ),
            topArtists = listOf(
                RankedEntry(title = "Daft Punk", subtitle = null, artworkUrl = null, browseId = "UC_DAFT", ms = 600_000L, plays = 12),
                RankedEntry(title = "Radiohead", subtitle = null, artworkUrl = null, browseId = "UC_RADIO", ms = 500_000L, plays = 9),
            ),
            likedIds = setOf("v_liked"),
            dislikedIds = setOf("v_disliked"),
            skippedIds = setOf("v_skipped"),
        )

        val raw = listOf(
            HomeShelf(
                title = "Trending songs",
                items = listOf(shelfItem("t1", "Random Viral Hit", "Unknown")),
            ),
            HomeShelf(
                title = "Worldwide Charts",
                items = listOf(shelfItem("c1", "Chart #1", "Unknown")),
            ),
            HomeShelf(
                title = "Made for you",
                items = listOf(
                    shelfItem("v_disliked", "Annoying Hit", "Generic"),
                    shelfItem("v_skipped", "Skipped Song", "Generic"),
                    shelfItem("v_liked", "Loved Song", "Daft Punk"),
                ),
            ),
        )

        val result = HomePersonalizer.personalizeShelves(raw, profile)
        val titles = result.map { it.title }

        assertTrue("Recently played" in titles)
        assertTrue("Your favourite artists" in titles)
        assertTrue("Rediscover your favourites" in titles)
        assertFalse("Trending songs" in titles)
        assertFalse("Worldwide Charts" in titles)

        val madeForYou = result.first { it.title == "Made for you" }
        assertFalse(madeForYou.items.any { it.videoId == "v_disliked" })
        assertEquals("v_liked", madeForYou.items.first().videoId)
    }

    @Test
    fun `new feed separates favourite artist releases and taste matches from global charts`() {
        val profile = ListenerProfile(
            topArtists = listOf(
                RankedEntry(title = "Daft Punk", subtitle = null, artworkUrl = null, browseId = "UC_DAFT", ms = 600_000L, plays = 12),
            ),
            seedGenres = setOf("Electronic"),
            dislikedIds = setOf("bad1"),
        )

        val rawNew = listOf(
            HomeShelf(
                title = "New albums & singles",
                items = listOf(
                    shelfItem("dp_new", "New Single", "Daft Punk"),
                    shelfItem("el1", "Electronic Anthem", "Electronic · Synth"),
                    shelfItem("el2", "Night Drive", "Electronic · House"),
                    shelfItem("bad1", "Disliked Release", "Other"),
                ),
            ),
            HomeShelf(
                title = "Top 100 Charts",
                items = listOf(shelfItem("ch1", "Global Hit", "Pop Star")),
            ),
        )

        val partitioned = HomePersonalizer.personalizeNewShelves(rawNew, profile)
        val persTitles = partitioned.personalized.map { it.title }
        val globalTitles = partitioned.global.map { it.title }

        assertEquals("Latest from your artists", persTitles.first())
        assertTrue("New for your taste" in persTitles)
        assertTrue("Top 100 Charts" in globalTitles)
        assertFalse("Top 100 Charts" in persTitles)
        assertFalse(partitioned.personalized.flatMap { it.items }.any { it.videoId == "bad1" })
    }

    @Test
    fun `new users without listening history do not receive unrelated provider feed`() {
        val raw = listOf(
            HomeShelf("Discover something new", listOf(shelfItem("x1", "Fresh", "Artist"))),
        )
        val result = HomePersonalizer.personalizeShelves(raw, ListenerProfile())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `relevant shelf title does not admit unrelated tracks or duplicate recordings`() {
        val profile = ListenerProfile(seedGenres = setOf("Electronic"))
        val raw = listOf(
            HomeShelf("Made for you", listOf(
                shelfItem("related", "Night Drive", "Electronic"),
                shelfItem("unrelated", "Country Road", "Country"),
            )),
            HomeShelf("Recommended albums", listOf(
                shelfItem("related", "Night Drive", "Electronic"),
                shelfItem("other", "House Music", "Electronic"),
            )),
        )
        val ids = HomePersonalizer.personalizeShelves(raw, profile)
            .flatMap { it.items }.mapNotNull { it.videoId }
        assertEquals(listOf("related", "other"), ids)
    }

    @Test
    fun `artist names require a whole phrase match`() {
        val profile = ListenerProfile(topArtists = listOf(
            RankedEntry("Muse", null, null, "UC_MUSE", 200_000L, 3),
        ))
        val raw = listOf(HomeShelf("Recommended", listOf(
            shelfItem("wrong", "A Song", "Museum Ensemble"),
            shelfItem("right", "Another Song", "Muse"),
        )))
        val ids = HomePersonalizer.personalizeShelves(raw, profile)
            .flatMap { it.items }.mapNotNull { it.videoId }
        assertTrue("right" in ids)
        assertFalse("wrong" in ids)
    }

    @Test
    fun `global chart title cannot turn into a personalised release`() {
        val profile = ListenerProfile(topArtists = listOf(
            RankedEntry("Muse", null, null, "UC_MUSE", 200_000L, 3),
        ))
        val chart = HomeShelf("Worldwide Charts", listOf(shelfItem("chart", "Hit", "Muse")))
        val partition = HomePersonalizer.personalizeNewShelves(listOf(chart), profile)
        assertTrue(partition.personalized.isEmpty())
        assertEquals("Worldwide Charts", partition.global.single().title)
    }
}
