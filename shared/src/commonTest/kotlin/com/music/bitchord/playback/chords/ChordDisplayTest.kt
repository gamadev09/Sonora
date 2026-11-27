package com.music.bitchord.playback.chords

import kotlin.test.*

class ChordDisplayTest {
    private val events = listOf(
        ChordEvent(0, 2000, "Cmaj7"), ChordEvent(2000, 4000, "Am7"), ChordEvent(4000, 6000, "G7"),
    )

    @Test fun simplifyPreservesRootAndMinorQuality() {
        assertEquals("C", simplifyChord("Cmaj7"))
        assertEquals("Am", simplifyChord("Am7"))
        assertEquals("G", simplifyChord("G7"))
        assertEquals("F#m", simplifyChord("F#m7"))
        assertEquals("Bb", simplifyChord("Bbmaj7"))
        assertEquals("N", simplifyChord("N"))
        assertEquals("Cdim", simplifyChord("Cdim"))
    }

    @Test fun displaySimplificationDoesNotAlterOriginalAnalysis() {
        val original = listOf(ChordEvent(0, 1000, "C"), ChordEvent(1000, 2000, "Cmaj7"))
        assertEquals(listOf(ChordEvent(0, 2000, "C")), simplifyChordEvents(original))
        assertEquals("Cmaj7", original[1].label)
    }

    @Test fun linesIncludeCarryOverAndAllChangesWithinTheLine() {
        val rows = chordsAboveLines(events, listOf(1000, 3000))
        assertEquals(listOf("Cmaj7", "Am7"), rows[0].map { it.label })
        assertEquals(listOf("Am7", "G7"), rows[1].map { it.label })
        assertEquals("Cmaj7", rows[0][chordIndexAt(rows[0], 1500)].label)
        assertEquals("Am7", rows[0][chordIndexAt(rows[0], 2500)].label)
    }

    @Test fun duplicateDuetTimestampsAndExactBoundaryChanges() {
        val rows = chordsAboveLines(events, listOf(0, 2000, 2000, 4000))
        assertEquals(listOf(events[0]), rows[0])
        assertEquals(listOf(events[1]), rows[1])
        assertEquals(rows[1], rows[2])
        assertEquals(listOf(events[2]), rows[3])
    }

    @Test fun lyricOffsetChangesPlacementWithoutShiftingChordTimes() {
        val rows = chordsAboveLines(events, listOf(0, 2000), 1000)
        assertEquals(listOf("Cmaj7", "Am7"), rows[0].map { it.label })
        assertEquals(2000, rows[0][1].startMs)
        assertEquals(listOf(events[0]), chordsAboveLines(events, listOf(0, 2000), -1000)[0])
    }

    @Test fun emptyAndOutOfRangeWindowsAreSafe() {
        assertEquals(listOf(emptyList()), chordsAboveLines(emptyList(), listOf(0)))
        assertEquals(listOf(emptyList()), chordsAboveLines(events, listOf(6000)))
        assertEquals(emptyList(), chordsAboveLines(events, emptyList()))
    }

    @Test fun guitarAndPianoValidateRepresentativeChordsAndCapo() {
        val chords = listOf("C", "Am", "Cmaj7", "G7", "Dm7", "Dsus2", "Asus4", "F#m", "Bb", "Bmaj7")
        chords.forEach { label ->
            val shape = guitarShape(label)
            assertNotNull(shape, "Expected guitar shape for $label")
            assertEquals(6, shape.frets.size, "Expected 6 strings for $label")

            val piano = pianoChordNotes(label)
            assertNotNull(piano, "Expected piano notes for $label")
            assertTrue(piano.all { it in 0..23 }, "Piano notes for $label out of 2-octave range: $piano")
        }

        // F major is a 1st-fret barre chord across all 6 strings.
        val fShape = guitarShape("F")!!
        assertEquals(listOf(1, 3, 3, 2, 1, 1), fShape.frets)
        assertEquals(1, fShape.firstFret)

        // Capo 2 transposes D -> C for guitar display while piano uses capo 0.
        val dEvent = listOf(ChordEvent(0, 2000, "D"))
        assertEquals("C", displayChordEvents(dEvent, simplify = false, notation = ChordNotation.SHARPS, capo = 2).single().label)
        assertEquals("D", displayChordEvents(dEvent, simplify = false, notation = ChordNotation.SHARPS, capo = 0).single().label)
    }
}
