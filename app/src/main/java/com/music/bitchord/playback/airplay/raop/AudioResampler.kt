/*
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 */
package com.music.bitchord.playback.airplay.raop

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polyphase FIR resampler for 16-bit little-endian interleaved PCM.
 * Default (l=147, m=160): 48000 -> 44100 Hz.
 * For 44100 -> 48000 Hz pass (l=160, m=147).
 */
class AudioResampler(
    private val l: Int = 147,
    private val m: Int = 160,
    private val channels: Int = 2,
) {
    private val tapsPerPhase = 24
    private val filter: FloatArray

    private val history = Array(channels) { FloatArray(tapsPerPhase) }
    private var phase = 0

    init {
        val totalTaps = tapsPerPhase * l
        filter = FloatArray(totalTaps)

        val cutoff = 1.0f / maxOf(l, m).toFloat()
        val center = (totalTaps - 1) / 2.0f

        for (i in 0 until totalTaps) {
            val x = i.toFloat() - center
            val sinc = if (x == 0f) 1f else sin(PI.toFloat() * cutoff * x) / (PI.toFloat() * cutoff * x)
            val window = 0.54f - 0.46f * cos(2f * PI.toFloat() * i / (totalTaps - 1))
            filter[i] = sinc * window
        }

        // Normalize each polyphase partition to unity gain
        for (p in 0 until l) {
            var sum = 0f
            for (i in 0 until tapsPerPhase) {
                sum += filter[p + i * l]
            }
            if (sum != 0f) {
                for (i in 0 until tapsPerPhase) {
                    filter[p + i * l] /= sum
                }
            }
        }
    }

    fun reset() {
        for (c in 0 until channels) history[c].fill(0f)
        phase = 0
    }

    fun resample(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        val frameBytes = 2 * channels
        val inputSamples = length / frameBytes
        if (inputSamples <= 0) return ByteArray(0)
        val outputSamples = (inputSamples.toLong() * l / m).toInt() + 2
        var output = ByteArray(outputSamples * frameBytes)

        var outIdx = 0
        var inIdx = 0

        while (inIdx < inputSamples) {
            val base = offset + inIdx * frameBytes
            for (c in 0 until channels) {
                val sampleIdx = base + c * 2
                val sample = ((input[sampleIdx + 1].toInt() shl 8) or (input[sampleIdx].toInt() and 0xFF))
                    .toShort()
                    .toFloat()
                val chHist = history[c]
                for (i in tapsPerPhase - 1 downTo 1) {
                    chHist[i] = chHist[i - 1]
                }
                chHist[0] = sample
            }

            while (phase < l) {
                if (outIdx + frameBytes > output.size) output = output.copyOf(output.size * 2)
                for (c in 0 until channels) {
                    var sum = 0f
                    val chHist = history[c]
                    for (i in 0 until tapsPerPhase) {
                        sum += chHist[i] * filter[phase + i * l]
                    }
                    val outSample = sum.toInt().coerceIn(-32768, 32767)
                    output[outIdx++] = (outSample and 0xFF).toByte()
                    output[outIdx++] = (outSample shr 8).toByte()
                }
                phase += m
            }

            phase -= l
            inIdx++
        }

        return output.copyOf(outIdx)
    }
}
