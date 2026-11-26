package com.music.bitchord.playback.chords

import kotlin.math.sqrt
import kotlin.test.*

class ChordHarmonyTest {
    @Test fun pianoVoicingsPreserveQualityEnharmonicsAndFitTheKeyboard() {
        assertEquals(listOf(0, 4, 7), pianoChordNotes("C"))
        assertEquals(listOf(9, 12, 16, 19), pianoChordNotes("Am7"))
        assertEquals(pianoChordNotes("G#maj7"), pianoChordNotes("A♭maj7"))
        assertNull(pianoChordNotes("N"))
        assertNull(pianoChordNotes("Csus4"))
        for (root in chordRoots) for (suffix in chordSuffixes) {
            val notes = assertNotNull(pianoChordNotes(root + suffix))
            assertTrue(notes.all { it in 0..23 })
            assertEquals(notes.size, notes.distinct().size)
        }
    }

    @Test fun followWindowAdvancesAtChangesAndRestoresOnBackwardSeek() {
        val events = listOf("C", "Am", "N", "F", "G").mapIndexed { i, label -> ChordEvent(i * 1000L, (i + 1) * 1000L, label) }
        assertEquals(listOf("C", "Am", "N"), chordFollowWindow(events, 999).map { it.label })
        assertEquals(listOf("Am", "N", "F"), chordFollowWindow(events, 1000).map { it.label })
        assertEquals(listOf("G"), chordFollowWindow(events, 4500).map { it.label })
        assertEquals(listOf("C", "Am", "N"), chordFollowWindow(events, 0).map { it.label })
        assertTrue(chordFollowWindow(events, 5000).isEmpty())
        assertTrue(chordFollowWindow(emptyList(), 0).isEmpty())
        assertEquals(events.take(3), chordFollowWindow(events, -100))
    }

    @Test fun displayMergesRepeatedLabelsSoFollowerShowsActualChanges() {
        val events = listOf(ChordEvent(0, 1000, "C", "C"), ChordEvent(1000, 2000, "C", "Am"), ChordEvent(2000, 3000, "G"))
        val display = displayChordEvents(events, false, ChordNotation.AUTO)
        assertEquals(listOf("C", "G"), display.map { it.label })
        assertEquals(2000, display.first().endMs)
        assertEquals(listOf("C", "Am", "G"), displayChordEvents(events, true, ChordNotation.AUTO).map { it.label })
    }

    private fun chroma(vararg pitches: Int): DoubleArray = DoubleArray(12) { if (it in pitches) 1.0 / sqrt(pitches.size.toDouble()) else 0.0 }

    @Test fun isolatedWeakChangesAreSuppressedButSustainedChangesSurvive() {
        val c = chroma(0, 4, 7)
        val g = chroma(7, 11, 2)
        val weak = DoubleArray(12) { c[it] * 0.45 + g[it] * 0.55 }
        val frames = List(8) { c } + listOf(weak) + List(8) { c } + List(8) { g }
        val labels = decodeHarmony(frames, null, true)
        assertEquals("C", labels[8])
        assertEquals("G", labels.last())
        assertEquals("C", decodeHarmony(List(8) { c } + listOf(g) + List(8) { c }, null, true)[8])
    }

    @Test fun estimatesFlatMajorKeyFromTonalEvidence() {
        val profile = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val ab = DoubleArray(12) { profile[(it - 8 + 12) % 12] }
        val key = assertNotNull(estimateChordKey(listOf(ab)))
        assertEquals(8, key.tonic)
        assertFalse(key.minor)
        assertTrue(key.preferFlats)
    }

    @Test fun silenceRemainsUnknown() {
        assertTrue(decodeHarmony(List(10) { DoubleArray(12) }, null, false).all { it == "N" })
        assertNull(estimateChordKey(listOf(DoubleArray(12))))
    }

    @Test fun keyPriorBreaksAmbiguityWithoutForbiddingBorrowedChords() {
        val ambiguous = DoubleArray(12) { (chroma(0, 4, 7)[it] + chroma(0, 3, 7)[it]) / 2 }
        assertEquals("C", decodeHarmony(List(8) { ambiguous }, ChordKey(0, false, 1.0), true).last())
        assertEquals("Cm", decodeHarmony(List(8) { ambiguous }, ChordKey(0, true, 1.0), true).last())
        assertEquals("F#", decodeHarmony(List(8) { chroma(6, 10, 1) }, ChordKey(0, false, 1.0), true).last())
    }

    @Test fun simplificationUsesIndependentAudioEstimate() {
        val event = ChordEvent(0, 1000, "Am7", "C")
        assertEquals("C", simplifyChordEvents(listOf(event)).single().label)
        assertEquals("Am7", event.label)
        // An Am7 pitch collection with a stronger C triad need not reduce to Am.
        val frame = chroma(0, 4, 7).also { it[9] = 0.2 }
        assertEquals("C", decodeHarmony(List(8) { frame }, null, true).last())
    }

    @Test fun enharmonicsPreserveQualityAndSupportFlatOverride() {
        assertEquals("A♭maj7", spellChord("G#maj7", true))
        assertEquals("G♯m", spellChord("Abm", false))
        assertEquals("N", spellChord("N", true))
        assertTrue(ChordKey(8, false, 1.0).preferFlats)
        assertEquals("A♭", displayChordEvents(listOf(ChordEvent(0, 1000, "G#")), false, ChordNotation.FLATS).single().label)
    }

    @Test fun capoPreservesSoundingPitchAndDiagramsPreserveHarmony() {
        val events = listOf(ChordEvent(0, 1000, "F#"), ChordEvent(1000, 2000, "B"), ChordEvent(2000, 3000, "C#"))
        assertEquals(4, suggestedCapo(events))
        assertEquals(listOf("E", "A", "B"), displayChordEvents(events, false, ChordNotation.SHARPS, 2).map { it.label })
        val tuning = listOf(4, 9, 2, 7, 11, 4)
        for (root in 0..11) for (quality in chordSuffixes.indices) {
            val shape = assertNotNull(guitarShape(chordRoots[root] + chordSuffixes[quality]))
            val notes = shape.frets.mapIndexedNotNull { i, fret -> if (fret < 0) null else (tuning[i] + fret) % 12 }.toSet()
            assertEquals(chordIntervals[quality].map { (root + it) % 12 }.toSet(), notes)
            assertTrue(shape.frets.filter { it > 0 }.all { it in shape.firstFret..shape.firstFret + 3 })
        }
    }
}
