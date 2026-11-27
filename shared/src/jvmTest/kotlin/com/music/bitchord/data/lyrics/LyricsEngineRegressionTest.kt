package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsEngineRegressionTest {

    @Test
    fun lrcLibParsePlain_createsUnsyncedLinesIgnoringBlankRows() {
        val plain = "\nFirst line\n\n  Second line  \nThird line\n"
        val lines = LrcLib.parsePlain(plain)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.timeMs == 0L })
        assertEquals("First line", lines[0].text)
        assertEquals("Second line", lines[1].text)
        assertEquals("Third line", lines[2].text)
    }

    @Test
    fun queryNormalization_stripsSpanishAndEnglishUploadLabelsPreservingRecordingEdits() {
        assertEquals("Me Porto Bonito", "Me Porto Bonito (Video Oficial)".forLyricsSearch())
        assertEquals("Tití Me Preguntó", "Tití Me Preguntó (Letra Oficial)".forLyricsSearch())
        assertEquals("Dakiti", "Dakiti | Audio Oficial".forLyricsSearch())
        assertEquals("La Canción", "La Canción (feat. Bad Bunny) [Official Video]".forLyricsSearch())
        assertEquals("Creep (Acoustic)", "Creep (Acoustic) (Official Audio)".forLyricsSearch())
        assertEquals("Hotel California (Live)", "Hotel California (Live) [HD]".forLyricsSearch())
        assertEquals("Música Ligera (En Vivo)", "Música Ligera (En Vivo) (Video Oficial)".forLyricsSearch())
    }

    @Test
    fun artistNormalization_handlesTopicVevoAndMultipleCredits() {
        assertEquals("Bad Bunny", "Bad Bunny - Topic".artistForLyricsSearch())
        assertEquals("Shakira", "ShakiraVEVO".artistForLyricsSearch())
        assertEquals("Bad Bunny", "Bad Bunny, Chencho Corleone".primaryArtistForLyricsSearch())
        assertEquals("Daft Punk", "Daft Punk & Pharrell Williams".primaryArtistForLyricsSearch())
        assertEquals("Rosalia", "Rosalía".stripAccents())
        assertEquals("Cancion", "Canción".stripAccents())
    }

    @Test
    fun repositoryPick_prefersTimedOverPlainEvenWhenPlainArrivesFirst() = runBlocking {
        val plain = LyricsRepository.Result(
            LyricsSource.LRCLIB,
            LrcLib.parsePlain("Line one\nLine two"),
        )
        val synced = LyricsRepository.Result(
            LyricsSource.MUSIXMATCH,
            listOf(LyricLine(1000L, "Line one"), LyricLine(4000L, "Line two")),
        )
        val picked = LyricsRepository.pick(
            racing = listOf(
                LyricsSource.LRCLIB to suspend { plain },
                LyricsSource.MUSIXMATCH to suspend { synced },
            ),
            prioritizeSyllableSync = false,
        )
        assertNotNull(picked)
        assertEquals(LyricsSource.MUSIXMATCH, picked!!.source)
        assertTrue(picked.lines.any { it.timeMs > 0L })
    }

    @Test
    fun repositoryPick_fallsBackToPlainWhenNoTimedLyricsExist() = runBlocking {
        val plain = LyricsRepository.Result(
            LyricsSource.LRCLIB,
            LrcLib.parsePlain("Plain lyric line"),
        )
        val picked = LyricsRepository.pick(
            racing = listOf(
                LyricsSource.BETTER_LYRICS to suspend { null },
                LyricsSource.LRCLIB to suspend { plain },
            ),
            prioritizeSyllableSync = true,
        )
        assertNotNull(picked)
        assertEquals(LyricsSource.LRCLIB, picked!!.source)
        assertFalse(picked.lines.any { it.timeMs > 0L })
    }

    @Test
    fun qualityHierarchy_completeSyllableBeatsCompleteWordBeatsPartialWordBeatsLine() = runBlocking {
        val lineSynced = LyricsRepository.Result(
            LyricsSource.LRCLIB,
            listOf(
                LyricLine(1000L, "Hello world"),
                LyricLine(3000L, "Second line"),
                LyricLine(5000L, "Third line"),
                LyricLine(7000L, "Fourth line"),
            ),
        )
        val partialWord = LyricsRepository.Result(
            LyricsSource.UNISON,
            listOf(
                LyricLine(1000L, "Hello world", words = listOf(LyricWord(1000L, 1500L, "Hello"), LyricWord(1500L, 2000L, "world"))),
                LyricLine(3000L, "Second line", words = listOf(LyricWord(3000L, 3500L, "Second"), LyricWord(3500L, 4000L, "line"))),
                LyricLine(5000L, "Third line"),
                LyricLine(7000L, "Fourth line"),
            ),
        )
        val completeWord = LyricsRepository.Result(
            LyricsSource.BETTER_LYRICS,
            listOf(
                LyricLine(1000L, "Hello world", words = listOf(LyricWord(1000L, 1500L, "Hello"), LyricWord(1500L, 2000L, "world"))),
                LyricLine(3000L, "Second line", words = listOf(LyricWord(3000L, 3500L, "Second"), LyricWord(3500L, 4000L, "line"))),
                LyricLine(5000L, "Third line", words = listOf(LyricWord(5000L, 5500L, "Third"), LyricWord(5500L, 6000L, "line"))),
                LyricLine(7000L, "Fourth line", words = listOf(LyricWord(7000L, 7500L, "Fourth"), LyricWord(7500L, 8000L, "line"))),
            ),
        )
        val completeSyllable = LyricsRepository.Result(
            LyricsSource.LRC_RED,
            listOf(
                LyricLine(
                    1000L,
                    "Hello world",
                    words = listOf(
                        LyricWord(1000L, 1500L, "Hello", listOf(LyricSyllable(1000L, 1250L, 0, 2), LyricSyllable(1250L, 1500L, 2, 5))),
                        LyricWord(1500L, 2000L, "world"),
                    ),
                ),
                LyricLine(
                    3000L,
                    "Second line",
                    words = listOf(
                        LyricWord(3000L, 3500L, "Second", listOf(LyricSyllable(3000L, 3250L, 0, 3), LyricSyllable(3250L, 3500L, 3, 6))),
                        LyricWord(3500L, 4000L, "line"),
                    ),
                ),
                LyricLine(5000L, "Third line", words = listOf(LyricWord(5000L, 5500L, "Third"), LyricWord(5500L, 6000L, "line"))),
                LyricLine(7000L, "Fourth line", words = listOf(LyricWord(7000L, 7500L, "Fourth"), LyricWord(7500L, 8000L, "line"))),
            ),
        )

        assertEquals(LyricsTimingTier.LINE_SYNCED, assessLyricsQuality(lineSynced.lines).tier)
        assertEquals(LyricsTimingTier.PARTIAL_WORD, assessLyricsQuality(partialWord.lines).tier)
        assertEquals(LyricsTimingTier.COMPLETE_WORD, assessLyricsQuality(completeWord.lines).tier)
        assertEquals(LyricsTimingTier.COMPLETE_SYLLABLE, assessLyricsQuality(completeSyllable.lines).tier)

        val preliminary = mutableListOf<LyricsSource>()
        val winner = LyricsRepository.pick(
            racing = listOf(
                LyricsSource.LRCLIB to suspend { lineSynced },
                LyricsSource.UNISON to suspend { partialWord },
                LyricsSource.BETTER_LYRICS to suspend { completeWord },
                LyricsSource.LRC_RED to suspend { completeSyllable },
            ),
            prioritizeSyllableSync = true,
            onPreliminaryResult = { preliminary += it.source },
        )
        assertEquals(LyricsSource.LRC_RED, winner?.source)
        assertEquals(listOf(LyricsSource.LRCLIB, LyricsSource.UNISON, LyricsSource.BETTER_LYRICS), preliminary)
    }

    @Test
    fun invalidZeroDurationWords_doNotOutrankLineSyncedDocument() {
        val corruptWords = listOf(
            LyricLine(1000L, "Hello world", words = listOf(LyricWord(1000L, 1000L, "Hello"), LyricWord(1000L, 1000L, "world"))),
            LyricLine(3000L, "Second line", words = listOf(LyricWord(3000L, 3000L, "Second"), LyricWord(3000L, 3000L, "line"))),
        )
        val assessment = assessLyricsQuality(corruptWords)
        assertEquals(LyricsTimingTier.LINE_SYNCED, assessment.tier)
        assertTrue(LyricsSource.MUSIXMATCH.wordSynced)
    }

    @Test
    fun lyricsCache_persistsAndNeverDowngradesSuperiorTier() {
        val tempDir = java.nio.file.Files.createTempDirectory("sonora_lyrics_cache_test").toFile()
        try {
            LyricsCache.cacheDir = tempDir
            LyricsCache.clear()

            val wordSynced = LyricsRepository.Result(
                LyricsSource.BETTER_LYRICS,
                listOf(
                    LyricLine(1000L, "Hello world", words = listOf(LyricWord(1000L, 1500L, "Hello"), LyricWord(1500L, 2000L, "world"))),
                    LyricLine(3000L, "Second line", words = listOf(LyricWord(3000L, 3500L, "Second"), LyricWord(3500L, 4000L, "line"))),
                ),
            )
            val lineSynced = LyricsRepository.Result(
                LyricsSource.LRCLIB,
                listOf(LyricLine(1000L, "Hello world"), LyricLine(3000L, "Second line")),
            )

            LyricsCache.put("vid123", "Song", "Artist", 180_000L, wordSynced)
            // Attempt to overwrite with inferior line-synced lyrics -> must be ignored.
            LyricsCache.put("vid123", "Song", "Artist", 180_000L, lineSynced)

            val loaded = LyricsCache.get(
                videoId = "vid123",
                title = "Song",
                artist = "Artist",
                durationMs = 181_000L,
                allowedSources = setOf(LyricsSource.BETTER_LYRICS, LyricsSource.LRCLIB),
            )
            assertNotNull(loaded)
            assertEquals(LyricsSource.BETTER_LYRICS, loaded!!.source)
            assertEquals(LyricsTimingTier.COMPLETE_WORD, assessLyricsQuality(loaded.lines).tier)

            // Large duration drift must not return mismatched recording.
            val drifted = LyricsCache.get(
                videoId = "vid123",
                title = "Song",
                artist = "Artist",
                durationMs = 210_000L,
                allowedSources = setOf(LyricsSource.BETTER_LYRICS),
            )
            assertEquals(null, drifted)
        } finally {
            LyricsCache.clear()
            LyricsCache.cacheDir = null
            tempDir.deleteRecursively()
        }
    }
}
