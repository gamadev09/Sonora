/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.airplay

import androidx.media3.common.C
import com.music.bitchord.playback.airplay.raop.AudioResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Converts arbitrary linear PCM buffers (16/24/32-bit int or 32-bit float,
 * any channel count, any sample rate) into 44.1 kHz 16-bit little-endian stereo
 * PCM for [AirPlayStreamer].
 */
internal class AirPlayPcmConverter {

    private var cachedRate = 0
    private var resampler: AudioResampler? = null

    fun reset() {
        resampler?.reset()
    }

    fun convert(
        buffer: ByteBuffer,
        startOffset: Int,
        byteLength: Int,
        pcmEncoding: Int,
        sampleRate: Int,
        channelCount: Int,
    ): ByteArray {
        if (byteLength <= 0 || sampleRate <= 0 || channelCount <= 0) return EMPTY

        val bytesPerSample = bytesPerSample(pcmEncoding) ?: return EMPTY
        val bytesPerFrame = bytesPerSample * channelCount
        val frameCount = byteLength / bytesPerFrame
        if (frameCount <= 0) return EMPTY

        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(startOffset)
        view.limit(startOffset + frameCount * bytesPerFrame)

        val stereo44kOrSource = toStereoS16(view, frameCount, pcmEncoding, channelCount)
        if (sampleRate == AirPlayAudio.SAMPLE_RATE) return stereo44kOrSource

        return resampleTo44100(stereo44kOrSource, sampleRate)
    }

    private fun toStereoS16(
        view: ByteBuffer,
        frameCount: Int,
        pcmEncoding: Int,
        channelCount: Int,
    ): ByteArray {
        if (pcmEncoding == C.ENCODING_PCM_16BIT && channelCount == 2) {
            val out = ByteArray(frameCount * 4)
            view.get(out)
            return out
        }

        val out = ByteArray(frameCount * 4)
        var outIdx = 0
        for (frame in 0 until frameCount) {
            var left = 0f
            var right = 0f
            for (ch in 0 until channelCount) {
                val sample = readNormalizedSample(view, pcmEncoding)
                when {
                    channelCount == 1 -> {
                        left = sample
                        right = sample
                    }
                    ch == 0 -> left = sample
                    ch == 1 -> right = sample
                    ch == 2 && channelCount >= 3 -> {
                        // Center channel mixed equally into L/R
                        val c = sample * 0.7071f
                        left += c
                        right += c
                    }
                    ch % 2 == 0 -> left += sample * 0.5f
                    else -> right += sample * 0.5f
                }
            }
            val l16 = floatToS16(left)
            val r16 = floatToS16(right)
            out[outIdx++] = (l16 and 0xFF).toByte()
            out[outIdx++] = (l16 shr 8).toByte()
            out[outIdx++] = (r16 and 0xFF).toByte()
            out[outIdx++] = (r16 shr 8).toByte()
        }
        return out
    }

    private fun readNormalizedSample(view: ByteBuffer, pcmEncoding: Int): Float = when (pcmEncoding) {
        C.ENCODING_PCM_16BIT -> view.getShort().toFloat() / 32768.0f
        C.ENCODING_PCM_24BIT -> {
            val b0 = view.get().toInt() and 0xFF
            val b1 = view.get().toInt() and 0xFF
            val b2 = view.get().toInt() // sign-extended
            ((b2 shl 16) or (b1 shl 8) or b0).toFloat() / 8388608.0f
        }
        C.ENCODING_PCM_32BIT -> (view.getInt().toDouble() / 2147483648.0).toFloat()
        C.ENCODING_PCM_FLOAT -> {
            val f = view.getFloat()
            when {
                f.isNaN() -> 0f
                f > 1.0f -> 1.0f
                f < -1.0f -> -1.0f
                else -> f
            }
        }
        C.ENCODING_PCM_8BIT -> (((view.get().toInt() and 0xFF) - 128) shl 8).toFloat() / 32768.0f
        else -> 0f
    }

    private fun resampleTo44100(stereoS16: ByteArray, sampleRate: Int): ByteArray {
        if (sampleRate != cachedRate) {
            cachedRate = sampleRate
            val g = gcd(AirPlayAudio.SAMPLE_RATE, sampleRate)
            val l = AirPlayAudio.SAMPLE_RATE / g
            val m = sampleRate / g
            resampler = if (l <= MAX_POLYPHASE_L) AudioResampler(l = l, m = m, channels = 2) else null
        }
        val poly = resampler
        if (poly != null) return poly.resample(stereoS16)
        return linearResample(stereoS16, sampleRate, AirPlayAudio.SAMPLE_RATE)
    }

    private fun linearResample(stereoS16: ByteArray, inRate: Int, outRate: Int): ByteArray {
        val inFrames = stereoS16.size / 4
        if (inFrames <= 0) return EMPTY
        val outFrames = ((inFrames.toLong() * outRate) / inRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outFrames * 4)
        val step = inRate.toDouble() / outRate.toDouble()
        var outIdx = 0
        for (i in 0 until outFrames) {
            val pos = i * step
            val idx0 = pos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inFrames - 1)
            val frac = (pos - idx0).toFloat()
            val off0 = idx0 * 4
            val off1 = idx1 * 4
            val l0 = ((stereoS16[off0 + 1].toInt() shl 8) or (stereoS16[off0].toInt() and 0xFF)).toShort()
            val r0 = ((stereoS16[off0 + 3].toInt() shl 8) or (stereoS16[off0 + 2].toInt() and 0xFF)).toShort()
            val l1 = ((stereoS16[off1 + 1].toInt() shl 8) or (stereoS16[off1].toInt() and 0xFF)).toShort()
            val r1 = ((stereoS16[off1 + 3].toInt() shl 8) or (stereoS16[off1 + 2].toInt() and 0xFF)).toShort()
            val l = (l0 + (l1 - l0) * frac).roundToInt().coerceIn(-32768, 32767)
            val r = (r0 + (r1 - r0) * frac).roundToInt().coerceIn(-32768, 32767)
            out[outIdx++] = (l and 0xFF).toByte()
            out[outIdx++] = (l shr 8).toByte()
            out[outIdx++] = (r and 0xFF).toByte()
            out[outIdx++] = (r shr 8).toByte()
        }
        return out
    }

    private fun bytesPerSample(pcmEncoding: Int): Int? = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    private fun floatToS16(f: Float): Int {
        if (f.isNaN()) return 0
        val scaled = f * 32768.0f
        if (scaled <= -32768.0f) return -32768
        if (scaled >= 32767.0f) return 32767
        return scaled.roundToInt()
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x.coerceAtLeast(1)
    }

    companion object {
        private const val MAX_POLYPHASE_L = 512
        private val EMPTY = ByteArray(0)
    }
}
