package com.music.bitchord.playback.chords

private val CHORD_SIMPLIFY_REGEX = Regex("^([A-G](?:#|b)?)(maj7|m7|7|m)?$")

/** Display-only reduction. Never changes the analysed harmony or its cache. */
fun simplifyChord(label: String): String {
    val match = CHORD_SIMPLIFY_REGEX.matchEntire(label) ?: return label
    val root = match.groupValues[1]
    val quality = match.groupValues[2]
    return root + if (quality == "m" || quality == "m7") "m" else ""
}

fun simplifyChordEvents(events: List<ChordEvent>): List<ChordEvent> {
    if (events.isEmpty()) return emptyList()
    val result = ArrayList<ChordEvent>(events.size)
    for (i in events.indices) {
        val event = events[i]
        val label = event.simpleLabel ?: simplifyChord(event.label)
        val previous = result.lastOrNull()
        if (previous != null && previous.label == label && previous.endMs == event.startMs) {
            result[result.lastIndex] = previous.copy(endMs = event.endMs)
        } else {
            result += event.copy(label = label)
        }
    }
    return result
}

/**
 * Chords that sound during each lyric's window, including a chord carried over
 * from the previous line. Duplicate timestamps (duet lines) share a window.
 * Lyric offsets move the windows, never the audio timestamps or chord changes.
 */
fun chordsAboveLines(
    events: List<ChordEvent>,
    lyricStartsMs: List<Long>,
    lyricOffsetMs: Int = 0,
): List<List<ChordEvent>> {
    if (events.isEmpty() || lyricStartsMs.isEmpty()) {
        return List(lyricStartsMs.size) { emptyList() }
    }
    val boundaries = lyricStartsMs.distinct().sorted()
    val duration = events.last().endMs
    return lyricStartsMs.map { lyricStart ->
        val index = boundaries.binarySearch(lyricStart)
        val start = (lyricStart + lyricOffsetMs).coerceAtLeast(0L)
        val end = boundaries.getOrNull(index + 1)?.let { (it + lyricOffsetMs).coerceAtLeast(0L) } ?: duration
        if (end <= start) {
            emptyList()
        } else {
            val first = chordIndexAt(events, start).takeIf { it >= 0 }
                ?: events.indexOfFirst { it.startMs >= start }
            if (first < 0) {
                emptyList()
            } else {
                val matching = ArrayList<ChordEvent>(4)
                for (i in first until events.size) {
                    val event = events[i]
                    if (event.startMs >= end) break
                    if (event.endMs > start) matching += event
                }
                matching
            }
        }
    }
}
