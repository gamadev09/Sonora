package com.music.bitchord.playback.separation

import com.music.bitchord.playback.smart.MelSpectrogram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited Blackman-windowed sinc resampler for vocal separation.
 *
 * Uses Sonora's native C++ polyphase Blackman-windowed sinc resampler
 * (`MelSpectrogram.resample` -> `native/analyzer/resampler.cpp`) on Android
 * for NEON-vectorized anti-aliased conversion (48, 88.2, 96 kHz -> 44.1 kHz),
 * with an equivalent pure-Kotlin Blackman-windowed sinc fallback for JVM unit
 * tests or environments where the native library is not loaded.
 */
object BandLimitedResampler {
    private const val ZERO_CROSSINGS = 16

    fun resampleChannel(
        input: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray {
        if (input.isEmpty() || inputRate <= 0.0 || outputRate <= 0.0) return FloatArray(0)
        if (abs(inputRate - outputRate) < 1.0) return input

        // Fast native C++ Blackman-windowed polyphase sinc resampler on Android.
        if (MelSpectrogram.available) {
            MelSpectrogram.resample(input, inputRate, outputRate)?.let { nativeOut ->
                if (nativeOut.isNotEmpty()) return nativeOut
            }
        }
        return resampleBlackmanSincKotlin(input, inputRate, outputRate)
    }

    /**
     * Resamples planar stereo `[left, right]` from [inputRate] to [outputRate],
     * guaranteeing identical output lengths and consistent L/R channel timing.
     */
    fun resampleStereo(
        left: FloatArray,
        right: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): Pair<FloatArray, FloatArray> {
        val len = minOf(left.size, right.size)
        if (len <= 0) return FloatArray(0) to FloatArray(0)
        val lIn = if (left.size == len) left else left.copyOf(len)
        val rIn = if (right.size == len) right else right.copyOf(len)
        val lOut = resampleChannel(lIn, inputRate, outputRate)
        val rOut = resampleChannel(rIn, inputRate, outputRate)
        val outLen = minOf(lOut.size, rOut.size)
        return (if (lOut.size == outLen) lOut else lOut.copyOf(outLen)) to
            (if (rOut.size == outLen) rOut else rOut.copyOf(outLen))
    }

    internal fun resampleBlackmanSincKotlin(
        input: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray {
        if (input.isEmpty() || inputRate <= 0.0 || outputRate <= 0.0) return FloatArray(0)
        if (abs(inputRate - outputRate) < 1e-6) return input.copyOf()

        val ratio = outputRate / inputRate
        val cutoff = 0.475 * min(1.0, ratio)
        val halfWidth = ZERO_CROSSINGS.toDouble() / (2.0 * cutoff)
        val outputCount = floor(input.size * ratio).toInt()
        if (outputCount <= 0) return FloatArray(0)

        val kernelRes = 512.0
        val tableSize = ceil(halfWidth * kernelRes).toInt() + 2
        val kernel = DoubleArray(tableSize)
        val twoCutoff = 2.0 * cutoff
        val twoHalfWidth = 2.0 * halfWidth
        for (i in 0 until tableSize) {
            val offset = i / kernelRes
            if (offset >= halfWidth) {
                kernel[i] = 0.0
            } else {
                val pos = (offset + halfWidth) / twoHalfWidth
                val window = 0.42 - 0.5 * cos(2.0 * PI * pos) + 0.08 * cos(4.0 * PI * pos)
                val x = twoCutoff * offset
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                kernel[i] = twoCutoff * sinc * window
            }
        }

        val out = FloatArray(outputCount)
        val last = input.size - 1
        for (i in 0 until outputCount) {
            val center = i / ratio
            val firstTap = ceil(center - halfWidth).toInt()
            val lastTap = floor(center + halfWidth).toInt()
            var sum = 0.0
            var weightSum = 0.0
            for (tap in firstTap..lastTap) {
                val offset = abs(center - tap)
                val scaled = offset * kernelRes
                val slot = scaled.toInt()
                if (slot + 1 >= tableSize) continue
                val frac = scaled - slot
                val w = kernel[slot] + frac * (kernel[slot + 1] - kernel[slot])
                val clamped = tap.coerceIn(0, last)
                sum += w * input[clamped].toDouble()
                weightSum += w
            }
            val v = if (weightSum != 0.0) (sum / weightSum).toFloat() else 0f
            out[i] = if (v.isFinite()) v else 0f
        }
        return out
    }
}
