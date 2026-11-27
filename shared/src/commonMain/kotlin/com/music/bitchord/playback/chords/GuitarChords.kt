package com.music.bitchord.playback.chords

enum class ChordNotation { AUTO, FLATS, SHARPS }

private val flats = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
private val chordPattern = Regex("^([A-G])([#b♯♭]?)(.*)$")
private fun chordParts(label: String): Pair<Int, String>? {
    val m = chordPattern.matchEntire(label) ?: return null
    val natural = mapOf("C" to 0, "D" to 2, "E" to 4, "F" to 5, "G" to 7, "A" to 9, "B" to 11)[m.groupValues[1]] ?: return null
    val accidental = when (m.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
    return (natural + accidental + 12) % 12 to m.groupValues[3]
}

/** Root-position voicing within a two-octave C keyboard; null means unknown harmony. */
fun pianoChordNotes(label: String): List<Int>? {
    val (root, suffix) = chordParts(label) ?: return null
    val intervals = when (suffix) {
        "sus2" -> listOf(0, 2, 7)
        "sus4" -> listOf(0, 5, 7)
        else -> {
            val quality = chordSuffixes.indexOf(suffix).takeIf { it >= 0 } ?: return null
            chordIntervals[quality]
        }
    }
    return intervals.map { (root + it).coerceIn(0, 23) }
}

fun keyForChordEvents(events: List<ChordEvent>): ChordKey? {
    val total = DoubleArray(12)
    events.forEach { event ->
        val (root, suffix) = chordParts(event.label) ?: return@forEach
        val quality = chordSuffixes.indexOf(suffix).takeIf { it >= 0 } ?: return@forEach
        chordIntervals[quality].forEach { total[(root + it) % 12] += (event.endMs - event.startMs).coerceAtLeast(0).toDouble() }
    }
    return estimateChordKey(listOf(total))
}

fun spellChord(label: String, preferFlats: Boolean, transpose: Int = 0): String {
    val (root, suffix) = chordParts(label) ?: return label
    val pitch = ((root + transpose) % 12 + 12) % 12
    return (if (preferFlats) flats[pitch] else chordRoots[pitch].replace("#", "♯")) + suffix
}

/** Frets are relative to the capo, ordered low E to high E; -1 means muted. */
data class GuitarShape(val frets: List<Int>) {
    val firstFret: Int get() = if (frets.max() <= 4) 1 else frets.filter { it > 0 }.min()
}

private val openShapes = mapOf(
    "C" to listOf(-1, 3, 2, 0, 1, 0), "D" to listOf(-1, -1, 0, 2, 3, 2),
    "E" to listOf(0, 2, 2, 1, 0, 0), "G" to listOf(3, 2, 0, 0, 0, 3), "A" to listOf(-1, 0, 2, 2, 2, 0),
    "Am" to listOf(-1, 0, 2, 2, 1, 0), "Dm" to listOf(-1, -1, 0, 2, 3, 1), "Em" to listOf(0, 2, 2, 0, 0, 0),
    "C7" to listOf(-1, 3, 2, 3, 1, 3), "D7" to listOf(-1, -1, 0, 2, 1, 2),
    "E7" to listOf(0, 2, 0, 1, 0, 0), "G7" to listOf(3, 2, 0, 0, 0, 1), "A7" to listOf(-1, 0, 2, 0, 2, 0),
    "Cmaj7" to listOf(-1, 3, 2, 0, 0, 0), "Dmaj7" to listOf(-1, -1, 0, 2, 2, 2),
    "Emaj7" to listOf(0, 2, 1, 1, 0, 0), "Gmaj7" to listOf(3, 2, 0, 0, 0, 2), "Amaj7" to listOf(-1, 0, 2, 1, 2, 0),
    "Am7" to listOf(-1, 0, 2, 0, 1, 0), "Dm7" to listOf(-1, -1, 0, 2, 1, 1), "Em7" to listOf(0, 2, 0, 0, 0, 0),
    "Asus2" to listOf(-1, 0, 2, 2, 0, 0), "Dsus2" to listOf(-1, -1, 0, 2, 3, 0), "Esus2" to listOf(0, 2, 4, 4, 0, 0),
    "Asus4" to listOf(-1, 0, 2, 2, 3, 0), "Dsus4" to listOf(-1, -1, 0, 2, 3, 3), "Esus4" to listOf(0, 2, 2, 2, 0, 0),
)

fun guitarShape(label: String): GuitarShape? {
    val (root, suffix) = chordParts(label) ?: return null
    openShapes[chordRoots[root] + suffix]?.let { return GuitarShape(it) }
    if (suffix !in chordSuffixes && suffix != "sus2" && suffix != "sus4") return null
    // Movable E and A families preserve the detected quality, including sevenths and suspended chords.
    val candidates = listOf(4, 9).map { base ->
        val shift = (root - base + 12) % 12
        val template = when (base to suffix) {
            4 to "" -> listOf(0, 2, 2, 1, 0, 0)
            4 to "m" -> listOf(0, 2, 2, 0, 0, 0)
            4 to "7" -> listOf(0, 2, 0, 1, 0, 0)
            4 to "maj7" -> listOf(0, 2, 1, 1, 0, 0)
            4 to "m7" -> listOf(0, 2, 0, 0, 0, 0)
            4 to "sus2" -> listOf(0, 2, 4, 4, 0, 0)
            4 to "sus4" -> listOf(0, 2, 2, 2, 0, 0)
            9 to "" -> listOf(-1, 0, 2, 2, 2, 0)
            9 to "m" -> listOf(-1, 0, 2, 2, 1, 0)
            9 to "7" -> listOf(-1, 0, 2, 0, 2, 0)
            9 to "maj7" -> listOf(-1, 0, 2, 1, 2, 0)
            9 to "sus2" -> listOf(-1, 0, 2, 2, 0, 0)
            9 to "sus4" -> listOf(-1, 0, 2, 2, 3, 0)
            else -> listOf(-1, 0, 2, 0, 1, 0)
        }
        GuitarShape(template.map { if (it < 0) -1 else it + shift })
    }
    return candidates.minBy { it.frets.max() }
}

fun suggestedCapo(events: List<ChordEvent>): Int = (0..7).minBy { capo ->
    events.sumOf { event ->
        if (chordParts(event.label) == null) 0.0 else {
            val label = spellChord(event.label, false, -capo).replace("♯", "#")
            val difficulty = if (label in openShapes) 0.0 else 3.0
            difficulty * (event.endMs - event.startMs).coerceAtLeast(0)
        }
    } + capo * 0.001
}

fun displayChordEvents(events: List<ChordEvent>, simplify: Boolean, notation: ChordNotation, capo: Int = 0): List<ChordEvent> {
    val source = if (simplify) simplifyChordEvents(events) else events
    val useFlats = when (notation) {
        ChordNotation.FLATS -> true
        ChordNotation.SHARPS -> false
        ChordNotation.AUTO -> keyForChordEvents(events)?.preferFlats == true
    }
    val result = mutableListOf<ChordEvent>()
    source.forEach { event ->
        val label = spellChord(event.label, useFlats, -capo)
        val previous = result.lastOrNull()
        if (previous != null && previous.label == label && previous.endMs == event.startMs) {
            result[result.lastIndex] = previous.copy(endMs = event.endMs)
        } else result += event.copy(label = label)
    }
    return result
}

/** A moving, seek-safe window: the sounding chord followed by two upcoming changes. */
fun chordFollowWindow(events: List<ChordEvent>, positionMs: Long): List<ChordEvent> {
    if (events.isEmpty() || positionMs >= events.last().endMs) return emptyList()
    val current = chordIndexAt(events, positionMs)
    val index = if (current >= 0) current else {
        var low = 0
        var high = events.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (events[mid].startMs < positionMs) low = mid + 1 else high = mid
        }
        low
    }
    return events.subList(index, minOf(index + 3, events.size))
}

/**
 * Computes the continuous 0..1 progress fraction through [event] at [audioPositionMs].
 * Safe against zero/negative durations and out-of-bounds seeks.
 */
fun chordProgressFraction(event: ChordEvent, audioPositionMs: Long): Float {
    val duration = (event.endMs - event.startMs).coerceAtLeast(1L)
    return ((audioPositionMs - event.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

/**
 * Estimates the current audio position between authoritative [sampledPositionMs] ticks
 * using monotonic frame time [nowNanos], bounded to [maxExtrapolationMs] so progress
 * stays smooth while playing, freezes immediately on pause, and never drifts on stalls.
 */
fun interpolatedChordPositionMs(
    sampledPositionMs: Long,
    sampledAtNanos: Long,
    nowNanos: Long,
    advancing: Boolean,
    maxExtrapolationMs: Long = 500L,
): Long {
    if (!advancing || sampledAtNanos <= 0L) return sampledPositionMs
    val elapsedMs = ((nowNanos - sampledAtNanos).coerceAtLeast(0L) / 1_000_000L)
        .coerceAtMost(maxExtrapolationMs)
    return sampledPositionMs + elapsedMs
}
