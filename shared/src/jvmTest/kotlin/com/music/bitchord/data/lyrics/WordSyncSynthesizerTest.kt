package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordSyncSynthesizerTest {

    @Test
    fun synthesizesMonotonicWordTimingsForLineSyncedLyrics() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "I remember every beautiful melody,"),
            LyricLine(timeMs = 5_000L, text = "echoing through the midnight sky."),
        )

        val result = WordSyncSynthesizer.ensureWordSync(lines, trackDurationMs = 9_500L)
        assertEquals(2, result.size)

        result.forEach { line ->
            assertTrue(line.isWordSynced, "Expected synthesized words for: ${line.text}")
            assertTrue(line.words.size >= 4)
            var prevEnd = line.timeMs
            line.words.forEachIndexed { idx, w ->
                assertTrue(w.startMs >= prevEnd, "Non-monotonic start at word $idx")
                assertTrue(w.endMs > w.startMs, "Zero/negative duration at word $idx")
                prevEnd = w.endMs
            }
            // Verify exact substring alignment for LyricLine.wordSpans
            assertEquals(line.words.size, line.wordSpans.size)
            line.words.zip(line.wordSpans).forEach { (word, span) ->
                assertEquals(word.text, line.text.substring(span))
            }
        }

        // Longer multi-syllable word ("beautiful") should receive more time than short word ("I")
        val firstLineWords = result[0].words
        val shortDuration = firstLineWords[0].endMs - firstLineWords[0].startMs
        val longDuration = firstLineWords[3].endMs - firstLineWords[3].startMs
        assertTrue(longDuration > shortDuration, "Expected 'beautiful' ($longDuration ms) > 'I' ($shortDuration ms)")
    }

    @Test
    fun preservesRealWordSyncedLinesUntouched() {
        val realWords = listOf(
            LyricWord(startMs = 2_000L, endMs = 2_350L, text = "Hello"),
            LyricWord(startMs = 2_400L, endMs = 3_100L, text = "world"),
        )
        val lines = listOf(
            LyricLine(timeMs = 2_000L, text = "Hello world", words = realWords),
        )

        val result = WordSyncSynthesizer.ensureWordSync(lines, trackDurationMs = 10_000L)
        assertEquals(realWords, result[0].words)
    }

    @Test
    fun leavesPlainUnsyncedLyricsUntouched() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "Plain line one"),
            LyricLine(timeMs = 0L, text = "Plain line two"),
        )

        val result = WordSyncSynthesizer.ensureWordSync(lines, trackDurationMs = 120_000L)
        assertTrue(result.none { it.isWordSynced })
    }

    @Test
    fun clustersUnspacedCjkLinesForSmoothSweep() {
        val lines = listOf(
            LyricLine(timeMs = 1_000L, text = "君の名は永遠に、忘れない"),
            LyricLine(timeMs = 4_500L, text = ""),
        )

        val result = WordSyncSynthesizer.ensureWordSync(lines, trackDurationMs = 10_000L)
        assertTrue(result[0].isWordSynced)
        assertTrue(result[0].words.size >= 4, "Expected CJK clustering, got ${result[0].words}")
        assertFalse(result[1].isWordSynced, "Instrumental gap should remain unsynthesized")
    }
}
