package com.music.bitchord

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.ui.lyricsLookupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The player's lyrics are looked up again when a setting that picks the answer changes. */
class LyricsLookupKeyTest {
    private val sources = setOf(LyricsSource.LRCLIB, LyricsSource.GENIUS)
    private val order = listOf(LyricsSource.LRCLIB, LyricsSource.GENIUS)

    private fun key(order: List<LyricsSource> = this.order, prioritize: Boolean = false) =
        lyricsLookupKey("abcdefghijk", sources, order, prioritize)

    @Test
    fun `the same track under the same settings is not looked up twice`() {
        assertEquals(key(), key())
    }

    @Test
    fun `reordering sources or switching syllable sync is a new lookup`() {
        assertNotEquals(key(), key(order = order.reversed()))
        assertNotEquals(key(), key(prioritize = true))
    }

    @Test
    fun `metadata corrections and meaningful duration changes trigger a new lookup`() {
        val base = lyricsLookupKey("abcdefghijk", sources, order, true, "Title", "Artist", "Album", 180_000L)
        // Minor player duration jitter within the 2s bucket should not re-trigger.
        val jittered = lyricsLookupKey("abcdefghijk", sources, order, true, "Title", "Artist", "Album", 180_900L)
        assertEquals(base, jittered)

        val correctedTitle = lyricsLookupKey("abcdefghijk", sources, order, true, "Corrected Title", "Artist", "Album", 180_000L)
        val correctedDuration = lyricsLookupKey("abcdefghijk", sources, order, true, "Title", "Artist", "Album", 215_000L)
        assertNotEquals(base, correctedTitle)
        assertNotEquals(base, correctedDuration)
    }
}
