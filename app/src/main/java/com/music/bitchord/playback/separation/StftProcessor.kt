package com.music.bitchord.playback.separation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure-Kotlin radix-2 STFT / ISTFT processor tuned for Open-Unmix HQ vocals
 * (`n_fft = 4096`, `hop = 1024`, `2049` positive-frequency bins, `44.1 kHz`).
 *
 * Uses a periodic Hann window on both analysis and synthesis with exact
 * squared-window COLA normalization so round-trip reconstruction without
 * masking preserves the input signal to floating-point precision (< 1e-5).
 *
 * No Android dependencies — fully unit-testable on the JVM.
 */
object StftProcessor {
    const val N_FFT = 4096
    const val HOP = 1024
    const val BINS = N_FFT / 2 + 1 // 2049
    const val MODEL_FRAMES = 960
    const val MODEL_SAMPLE_RATE = 44_100

    /** Exact number of samples covered by [MODEL_FRAMES] centered STFT frames. */
    const val MODEL_WINDOW_SAMPLES = (MODEL_FRAMES - 1) * HOP // 982,016 (~22.27 s)

    private const val MASK_EPS = 1e-7f
    private const val NORM_EPS = 1e-8f

    private val hannWindow: FloatArray = FloatArray(N_FFT) { n ->
        (0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)).toFloat()
    }

    private val hannWindowSq: FloatArray = FloatArray(N_FFT) { n ->
        val w = hannWindow[n]
        w * w
    }

    // Precomputed radix-2 bit-reversal permutation and twiddle factors for N_FFT = 4096.
    private val bitRev: IntArray = IntArray(N_FFT).also { rev ->
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) {
            rev[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    private val cosTable: FloatArray = FloatArray(N_FFT / 2) { k ->
        cos(2.0 * PI * k / N_FFT).toFloat()
    }

    private val sinTable: FloatArray = FloatArray(N_FFT / 2) { k ->
        sin(2.0 * PI * k / N_FFT).toFloat()
    }

    /**
     * Complex STFT representation of a stereo audio chunk.
     *
     * Layout of [real], [imag], and [magnitude]:
     * `[channel * BINS * frames + bin * frames + frame]`
     * which matches the Open-Unmix ONNX input layout `[1, 2, 2049, T]` directly!
     */
    class StereoStft(
        val frames: Int,
        val sampleCount: Int,
        val real: FloatArray,
        val imag: FloatArray,
        val magnitude: FloatArray,
    )

    /**
     * Two reconstructed stereo stems with identical sample length [sampleCount].
     */
    class SeparatedChunk(
        val vocalsLeft: FloatArray,
        val vocalsRight: FloatArray,
        val instLeft: FloatArray,
        val instRight: FloatArray,
    )

    /**
     * Computes centered STFT for a stereo chunk into a tensor-ready layout `[2, BINS, frames]`.
     *
     * If [fixedFrames] > 0, the output always has exactly [fixedFrames] time frames
     * (zero-padded on the right if the input is shorter), matching Open-Unmix's static
     * `[1, 2, 2049, 960]` input shape without extra copies.
     */
    fun stftStereo(
        left: FloatArray,
        right: FloatArray,
        fixedFrames: Int = 0,
    ): StereoStft {
        val sampleCount = minOf(left.size, right.size)
        val naturalFrames = if (sampleCount <= 0) 0 else (sampleCount / HOP) + 1
        val frames = if (fixedFrames > 0) fixedFrames else naturalFrames
        val total = 2 * BINS * frames
        val real = FloatArray(total)
        val imag = FloatArray(total)
        val mag = FloatArray(total)

        val fftRe = FloatArray(N_FFT)
        val fftIm = FloatArray(N_FFT)
        val half = N_FFT / 2

        for (ch in 0..1) {
            val src = if (ch == 0) left else right
            val chBase = ch * BINS * frames
            val framesToCompute = minOf(frames, naturalFrames)
            for (t in 0 until framesToCompute) {
                val center = t * HOP
                val start = center - half
                for (n in 0 until N_FFT) {
                    val idx = start + n
                    val sample = if (idx in 0 until sampleCount) src[idx] else 0f
                    fftRe[n] = sample * hannWindow[n]
                    fftIm[n] = 0f
                }
                fft(fftRe, fftIm, inverse = false)
                for (b in 0 until BINS) {
                    val dst = chBase + b * frames + t
                    val re = fftRe[b]
                    val im = fftIm[b]
                    real[dst] = re
                    imag[dst] = im
                    mag[dst] = hypot(re.toDouble(), im.toDouble()).toFloat()
                }
            }
        }
        return StereoStft(
            frames = frames,
            sampleCount = sampleCount,
            real = real,
            imag = imag,
            magnitude = mag,
        )
    }

    /**
     * Reconstructs isolated vocals and instrumental stems from [stft] and predicted
     * vocal magnitude spectrogram [vocalMagnitude] (`[2, BINS, frames]`).
     *
     * Applies a phase-preserving soft Wiener ratio mask:
     * - `M_v = vMag^p / (vMag^p + max(0, |X| - vMag)^p + eps)`
     * - `Vocal = M_v * X`
     * - `Inst = (1 - M_v) * X`
     *
     * Because `M_v + (1 - M_v) == 1`, the sum `Vocal + Inst` reconstructs the original
     * mixture with zero phase distortion.
     */
    fun separateAndReconstruct(
        stft: StereoStft,
        vocalMagnitude: FloatArray?,
        maskPower: Float = 1.0f,
    ): SeparatedChunk {
        val sampleCount = stft.sampleCount
        val frames = stft.frames
        val activeFrames = minOf(frames, if (sampleCount <= 0) 0 else (sampleCount / HOP) + 1)
        val half = N_FFT / 2

        val vocalsLeft = FloatArray(sampleCount)
        val vocalsRight = FloatArray(sampleCount)
        val instLeft = FloatArray(sampleCount)
        val instRight = FloatArray(sampleCount)
        val norm = FloatArray(sampleCount)

        val vRe = FloatArray(N_FFT)
        val vIm = FloatArray(N_FFT)
        val iRe = FloatArray(N_FFT)
        val iIm = FloatArray(N_FFT)

        val usePower = maskPower != 1.0f

        for (ch in 0..1) {
            val vOut = if (ch == 0) vocalsLeft else vocalsRight
            val iOut = if (ch == 0) instLeft else instRight
            if (ch == 1) norm.fill(0f)
            val chBase = ch * BINS * frames

            for (t in 0 until activeFrames) {
                for (b in 0 until BINS) {
                    val idx = chBase + b * frames + t
                    val re = stft.real[idx]
                    val im = stft.imag[idx]
                    val mixMag = stft.magnitude[idx]
                    val mv = if (vocalMagnitude == null || mixMag <= MASK_EPS) {
                        0f
                    } else {
                        val vMag = vocalMagnitude[idx].coerceAtLeast(0f)
                        if (!usePower) {
                            (vMag / (mixMag + MASK_EPS)).coerceIn(0f, 1f)
                        } else {
                            val rMag = (mixMag - vMag).coerceAtLeast(0f)
                            val vp = vMag.toDouble().pow(maskPower.toDouble()).toFloat()
                            val rp = rMag.toDouble().pow(maskPower.toDouble()).toFloat()
                            (vp / (vp + rp + MASK_EPS)).coerceIn(0f, 1f)
                        }
                    }
                    val mi = 1f - mv
                    vRe[b] = re * mv
                    vIm[b] = im * mv
                    iRe[b] = re * mi
                    iIm[b] = im * mi
                }
                // Conjugate symmetry for real-valued IFFT (bins 2049..4095).
                for (b in 1 until N_FFT / 2) {
                    val mirror = N_FFT - b
                    vRe[mirror] = vRe[b]
                    vIm[mirror] = -vIm[b]
                    iRe[mirror] = iRe[b]
                    iIm[mirror] = -iIm[b]
                }

                fft(vRe, vIm, inverse = true)
                fft(iRe, iIm, inverse = true)

                val center = t * HOP
                val start = center - half
                for (n in 0 until N_FFT) {
                    val outIdx = start + n
                    if (outIdx in 0 until sampleCount) {
                        val w = hannWindow[n]
                        vOut[outIdx] += vRe[n] * w
                        iOut[outIdx] += iRe[n] * w
                        norm[outIdx] += hannWindowSq[n]
                    }
                }
            }

            for (i in 0 until sampleCount) {
                val denom = norm[i]
                if (denom > NORM_EPS) {
                    val inv = 1f / denom
                    vOut[i] *= inv
                    iOut[i] *= inv
                }
            }
        }

        return SeparatedChunk(
            vocalsLeft = vocalsLeft,
            vocalsRight = vocalsRight,
            instLeft = instLeft,
            instRight = instRight,
        )
    }

    /**
     * In-place radix-2 Cooley-Tukey FFT / IFFT of length [N_FFT].
     * When [inverse] is true, divides by [N_FFT].
     */
    internal fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until N_FFT) {
            val j = bitRev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        val sign = if (inverse) 1f else -1f
        var len = 2
        while (len <= N_FFT) {
            val halfLen = len ushr 1
            val step = N_FFT / len
            var i = 0
            while (i < N_FFT) {
                var tableIdx = 0
                for (j in 0 until halfLen) {
                    val wr = cosTable[tableIdx]
                    val wi = sign * sinTable[tableIdx]
                    val uIdx = i + j
                    val vIdx = uIdx + halfLen
                    val vr = re[vIdx] * wr - im[vIdx] * wi
                    val vi = re[vIdx] * wi + im[vIdx] * wr
                    re[vIdx] = re[uIdx] - vr
                    im[vIdx] = im[uIdx] - vi
                    re[uIdx] += vr
                    im[uIdx] += vi
                    tableIdx += step
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / N_FFT
            for (i in 0 until N_FFT) {
                re[i] *= invN
                im[i] *= invN
            }
        }
    }
}
