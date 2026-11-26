package com.music.bitchord.playback.chords

import kotlin.math.sqrt

internal val chordRoots = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
internal val chordSuffixes = listOf("", "m", "7", "maj7", "m7")
internal val chordIntervals = listOf(listOf(0, 4, 7), listOf(0, 3, 7), listOf(0, 4, 7, 10), listOf(0, 4, 7, 11), listOf(0, 3, 7, 10))

data class ChordKey(val tonic: Int, val minor: Boolean, val confidence: Double) {
    val preferFlats: Boolean get() = (if (minor) (tonic + 3) % 12 else tonic) in listOf(1, 3, 5, 8, 10)
    val scale: Set<Int> get() = (if (minor) listOf(0, 2, 3, 5, 7, 8, 10) else listOf(0, 2, 4, 5, 7, 9, 11)).map { (tonic + it) % 12 }.toSet()
}

/** Whole-recording tonal estimate. Confidence scales a soft prior; borrowed chords remain possible. */
internal fun estimateChordKey(frames: List<DoubleArray>): ChordKey? {
    val total = DoubleArray(12)
    frames.forEach { frame -> for (i in 0..11) total[i] += frame[i] }
    if (total.sum() < 1e-9) return null
    val profiles = listOf(
        listOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88),
        listOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17),
    )
    val mean = total.average()
    val scores = (0..23).map { k ->
        val profile = profiles[k / 12]
        val pm = profile.average()
        var dot = 0.0; var a = 0.0; var b = 0.0
        for (i in 0..11) {
            val x = total[(i + k % 12) % 12] - mean
            val y = profile[i] - pm
            dot += x * y; a += x * x; b += y * y
        }
        k to if (a * b > 1e-12) dot / sqrt(a * b) else 0.0
    }.sortedByDescending { it.second }
    val best = scores.first()
    return ChordKey(best.first % 12, best.first >= 12, ((best.second - scores[1].second) * 5).coerceIn(0.0, 1.0))
}

/** Sequence decoding makes a switch earn its cost across frames, while strong changes survive. */
internal fun decodeHarmony(frames: List<DoubleArray>, key: ChordKey?, triadsOnly: Boolean): List<String> {
    if (frames.isEmpty()) return emptyList()
    val qualities = if (triadsOnly) 2 else chordIntervals.size
    val states = 12 * qualities + 1
    val none = states - 1
    val back = Array(frames.size) { IntArray(states) }
    val scale = key?.scale.orEmpty()
    var previous = DoubleArray(states)
    frames.forEachIndexed { index, chroma ->
        val silent = chroma.sum() < 1e-9
        val bestChord = (0 until none).maxBy { previous[it] }
        val next = DoubleArray(states)
        for (state in 0 until states) {
            val root = state / qualities
            val quality = state % qualities
            val emission = if (state == none) {
                if (silent) 0.0 else 0.60
            } else if (silent) -4.0 else {
                val tones = chordIntervals[quality]
                val fit = tones.sumOf { chroma[(root + it) % 12] } / sqrt(tones.size.toDouble())
                val outside = if (key == null) 0.0 else tones.count { (root + it) % 12 !in scale }.toDouble() / tones.size
                fit - outside * 0.08 * (key?.confidence ?: 0.0)
            }
            fun transition(from: Int): Double = previous[from] - when {
                from == state -> 0.0
                silent -> 0.12
                state == none || from == none -> 0.40
                else -> 0.45
            }
            val from = listOf(state, bestChord, none).maxBy { transition(it) }
            back[index][state] = from
            next[state] = emission + transition(from)
        }
        val max = next.max()
        previous = DoubleArray(states) { next[it] - max }
    }
    var state = previous.indices.maxBy { previous[it] }
    val labels = MutableList(frames.size) { "N" }
    for (i in frames.indices.reversed()) {
        labels[i] = if (state == none) "N" else chordRoots[state / qualities] + chordSuffixes[state % qualities]
        state = back[i][state]
    }
    return labels
}
