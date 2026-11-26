package com.music.bitchord.playback.chords

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.*

class ChordAnalysisTest {
    private fun tone(notes: List<Int>, seconds: Int, rate: Int): FloatArray =
        FloatArray(seconds * rate) { i ->
            (notes.sumOf { note ->
                sin(2 * PI * 440 * 2.0.pow((note - 69) / 12.0) * i / rate)
            } * 0.15).toFloat()
        }

    private fun analyze(audio: FloatArray, rate: Int, chunk: Int = 1024): List<ChordEvent> {
        val analyzer = ChordAnalyzer()
        var start = 0
        while (start < audio.size) {
            val end = minOf(start + chunk, audio.size)
            analyzer.accept(audio.copyOfRange(start, end), start.toDouble() / rate, rate)
            start = end
        }
        return analyzer.finish(audio.size * 1000L / rate)
    }

    @Test fun identifiesEveryMajorAndMinorTriadAtCommonRates() {
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        for (rate in listOf(44100, 48000)) for (root in 0..11) for (minor in listOf(false, true)) {
            val notes = listOf(48 + root, 48 + root + if (minor) 3 else 4, 55 + root)
            val expected = names[root] + if (minor) "m" else ""
            val events = analyze(tone(notes, 3, rate), rate)
            assertEquals(expected, events.maxBy { it.endMs - it.startMs }.label, "$rate Hz $expected")
        }
    }

    @Test fun silenceIsNotReportedAsAChord() {
        assertTrue(analyze(FloatArray(44100 * 2), 44100).all { it.label == "N" })
    }

    @Test fun progressionHasOrderedContiguousEventsAndTracksSeeks() {
        val rate = 44100
        val audio = tone(listOf(48, 52, 55), 3, rate) + tone(listOf(45, 48, 52), 3, rate)
        val events = analyze(audio, rate)
        assertEquals("C", events[chordIndexAt(events, 1500)].label)
        assertEquals("Am", events[chordIndexAt(events, 4500)].label)
        assertEquals(0, events.first().startMs)
        assertEquals(6000, events.last().endMs)
        assertTrue(events.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertEquals(-1, chordIndexAt(events, -1))
        assertEquals(-1, chordIndexAt(events, 6000))
        // Lookup is stateless: seeking backwards must immediately restore the chord.
        assertEquals("C", events[chordIndexAt(events, 1500)].label)
    }

    @Test fun decoderBufferSizeDoesNotChangeAnalysis() {
        val audio = tone(listOf(50, 53, 57), 3, 48000)
        assertEquals(analyze(audio, 48000, 127), analyze(audio, 48000, 8192))
    }

    @Test fun emptyOrVeryShortAudioReturnsNoInventedChords() {
        assertEquals(emptyList(), analyze(FloatArray(100), 44100))
        assertEquals(-1, chordIndexAt(emptyList(), 0))
    }

    @Test fun identifiesSeventhsForMeaningfulSimplification() {
        val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val templates = listOf("7" to listOf(0, 4, 7, 10), "maj7" to listOf(0, 4, 7, 11), "m7" to listOf(0, 3, 7, 10))
        for (rate in listOf(44100, 48000)) for (root in 0..11) for ((suffix, intervals) in templates) {
            val events = analyze(tone(intervals.map { 48 + root + it }, 3, rate), rate)
            assertEquals(names[root] + suffix, events.maxBy { it.endMs - it.startMs }.label, "$rate Hz ${names[root]}$suffix")
        }
    }
}
