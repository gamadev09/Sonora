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
        // Recording-distinguishing edits MUST stay intact
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
}
