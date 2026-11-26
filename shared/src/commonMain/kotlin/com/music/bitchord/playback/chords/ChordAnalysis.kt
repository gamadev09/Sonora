package com.music.bitchord.playback.chords

import kotlin.math.*

/** Estimated harmony, not a transcription. N means silence or uncertain harmony. */
data class ChordEvent(val startMs: Long, val endMs: Long, val label: String, val simpleLabel: String? = null)

fun chordIndexAt(events: List<ChordEvent>, positionMs: Long): Int {
    var low = 0
    var high = events.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val event = events[mid]
        when {
            positionMs < event.startMs -> high = mid - 1
            positionMs >= event.endMs -> low = mid + 1
            else -> return mid
        }
    }
    return -1
}

/**
 * Offline baseline: low-pass/decimate mono PCM, FFT chroma, then key-weighted major/minor and seventh-chord
 * sequence decoding. Triads are decoded independently for simplified display. No network, weights or native dependencies. Dense mixes, inversions,
 * detuning and richer extensions need a better detector; the UI contract stays the same.
 * One instance per recording, fed in presentation-time order from a worker thread.
 */
class ChordAnalyzer {
    private val ring = DoubleArray(SIZE)
    private val real = DoubleArray(SIZE)
    private val imaginary = DoubleArray(SIZE)
    private val chroma = DoubleArray(12)
    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1)) }
    private var written = 0L
    private var nextFrame = SIZE.toLong()
    private var phase = 0.0
    private var sum = 0.0
    private var count = 0
    private var originMs: Double? = null
    private var rate: Int? = null
    private val frames = mutableListOf<Pair<Long, DoubleArray>>()

    fun accept(samples: FloatArray, atSeconds: Double, sampleRate: Int) {
        require(sampleRate >= TARGET_RATE && atSeconds.isFinite())
        require(rate == null || rate == sampleRate) { "Sample rate changed during analysis" }
        rate = sampleRate
        if (originMs == null) originMs = atSeconds * 1000
        for (i in samples.indices) {
            val sample = samples[i]
            sum += if (sample.isFinite()) sample.toDouble() else 0.0
            count++
            phase += TARGET_RATE
            if (phase >= sampleRate) {
                phase -= sampleRate
                ring[(written.toInt() and MASK)] = sum / count
                sum = 0.0
                count = 0
                written++
                if (written >= nextFrame) {
                    val centreMs = originMs!! + (written - SIZE / 2) * 1000.0 / TARGET_RATE
                    frames += centreMs.toLong().coerceAtLeast(0) to classify()
                    nextFrame += HOP
                }
            }
        }
    }

    fun finish(durationMs: Long): List<ChordEvent> {
        if (frames.isEmpty() || durationMs <= 0) return emptyList()
        val observations = frames.map { it.second }
        val key = estimateChordKey(observations)
        val labels = decodeHarmony(observations, key, false)
        val simple = decodeHarmony(observations, key, true)
        val events = mutableListOf<ChordEvent>()
        var start = 0L
        var label = labels[0]
        var triad = simple[0]
        for (i in 1 until frames.size) {
            if (labels[i] == label && simple[i] == triad) continue
            val boundary = ((frames[i - 1].first + frames[i].first) / 2).coerceIn(start, durationMs)
            if (boundary > start) events += ChordEvent(start, boundary, label, triad)
            start = boundary
            label = labels[i]
            triad = simple[i]
        }
        if (start < durationMs) events += ChordEvent(start, durationMs, label, triad)
        return events
    }

    private fun classify(): DoubleArray {
        val base = written.toInt()
        val nativeOut = DoubleArray(12)
        if (nativeComputeChroma(ring, base, nativeOut)) return nativeOut

        var energy = 0.0
        for (i in 0 until SIZE) {
            val sample = ring[(base + i) and MASK]
            energy += sample * sample
            real[i] = sample * window[i]
            imaginary[i] = 0.0
        }
        if (energy / SIZE < 1e-7) return DoubleArray(12)
        fft()
        chroma.fill(0.0)
        for (bin in BIN_MIN..BIN_MAX) {
            val c = BIN_CHROMA[bin]
            if (c < 0) continue
            chroma[c] += hypot(real[bin], imaginary[bin]) * BIN_WEIGHT[bin]
        }
        val norm = sqrt(chroma.sumOf { it * it })
        return DoubleArray(12) { if (norm > 1e-9) chroma[it] / norm else 0.0 }
    }

    private fun fft() {
        val re = real
        val im = imaginary
        val bitRev = BIT_REV
        for (i in 1 until SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val t = re[i]
                re[i] = re[j]
                re[j] = t
            }
        }
        val cosTbl = HALF_COS
        val sinTbl = HALF_SIN
        var length = 2
        while (length <= SIZE) {
            val half = length ushr 1
            val stride = SIZE / length
            var start = 0
            while (start < SIZE) {
                for (k in 0 until half) {
                    val twiddleIdx = k * stride
                    val wr = cosTbl[twiddleIdx]
                    val wi = sinTbl[twiddleIdx]
                    val a = start + k
                    val b = a + half
                    val br = re[b] * wr - im[b] * wi
                    val bi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - br
                    im[b] = im[a] - bi
                    re[a] += br
                    im[a] += bi
                }
                start += length
            }
            length = length shl 1
        }
    }

    companion object {
        const val VERSION = 3
        private const val TARGET_RATE = 11025
        private const val SIZE = 8192
        private const val MASK = SIZE - 1
        private const val HOP = 2756
        private val BIT_REV = IntArray(SIZE).also { table ->
            for (i in 1 until SIZE) {
                var j = 0
                var x = i
                var bit = SIZE ushr 1
                while (x != 0) {
                    if ((x and 1) != 0) j = j or bit
                    x = x ushr 1
                    bit = bit ushr 1
                }
                table[i] = j
            }
        }
        private val HALF_COS = DoubleArray(SIZE / 2) { k -> cos(-2 * PI * k / SIZE) }
        private val HALF_SIN = DoubleArray(SIZE / 2) { k -> sin(-2 * PI * k / SIZE) }
        private val BIN_CHROMA = IntArray(SIZE / 2) { -1 }
        private val BIN_WEIGHT = DoubleArray(SIZE / 2)
        private val BIN_MIN: Int
        private val BIN_MAX: Int

        init {
            var minBin = SIZE / 2
            var maxBin = 0
            val ln2 = ln(2.0)
            for (bin in 1 until SIZE / 2) {
                val frequency = bin.toDouble() * TARGET_RATE / SIZE
                if (frequency < 65 || frequency > 1600) continue
                val midi = 69 + 12 * ln(frequency / 440) / ln2
                val note = midi.roundToInt()
                val distance = abs(midi - note)
                if (distance > 0.4) continue
                BIN_CHROMA[bin] = ((note % 12) + 12) % 12
                BIN_WEIGHT[bin] = 1 - distance / 0.5
                if (bin < minBin) minBin = bin
                if (bin > maxBin) maxBin = bin
            }
            BIN_MIN = minBin
            BIN_MAX = maxBin
        }
    }
}
