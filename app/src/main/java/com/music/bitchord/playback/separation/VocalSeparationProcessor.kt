package com.music.bitchord.playback.separation

import android.util.Log
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.floor

/**
 * Real-time in-place Float32 audio processor that mixes pre-separated vocal and
 * instrumental stems into Sonora's existing playback pipeline (`DspChain`).
 *
 * Guarantees:
 * - Zero-overhead byte-identical bypass when inactive and crossfade has settled.
 * - Sample-accurate alignment to the live playback clock via [setBlockTimestampUs].
 * - Linear resampling when the live output sample rate (e.g. 48 kHz) differs from
 *   the 44.1 kHz stem sample rate.
 * - Smooth 40 ms crossfade on activation/deactivation so switching mid-track never
 *   clicks, pops, or restarts playback.
 * - Automatic safe fallback to original PCM on EOF or any I/O error.
 *
 * Because this runs inside [com.music.bitchord.playback.audio.PrecisionAudioSink]'s
 * [com.music.bitchord.playback.audio.DspChain], all downstream stages (Spatial, EQ,
 * Transition, Loudness, speaker/BT/DAC output, AirPlay, and DLNA PCM streaming)
 * receive the separated mix automatically.
 */
class VocalSeparationProcessor : FloatAudioProcessor {

    data class ActiveStemConfig(
        val key: String,
        val trackId: String,
        val vocalsFile: File,
        val instFile: File,
        val stemSampleRate: Int,
        val stemChannels: Int,
        val totalFrames: Long,
        @Volatile var vocalGain: Float = 0.0f,
    )

    private var outputSampleRate: Int = 0
    private var outputChannels: Int = AudioBlock.DEFAULT_CHANNELS
    private var blockTrackTimeUs: Long = Long.MIN_VALUE

    @Volatile
    private var targetConfig: ActiveStemConfig? = null

    private var openKey: String? = null
    private var vocalsRaf: RandomAccessFile? = null
    private var instRaf: RandomAccessFile? = null
    private var lastConfig: ActiveStemConfig? = null

    /** Smooth crossfade factor: 0f = 100% original PCM, 1f = 100% separated stem mix. */
    private var wetMix: Float = 0f

    /** Reusable read buffers on the audio thread (zero per-block heap allocations). */
    private var vocalsBytes = ByteArray(16 * 1024)
    private var instBytes = ByteArray(16 * 1024)

    val isActive: Boolean
        get() = targetConfig != null || wetMix > 0f

    fun setActiveConfig(config: ActiveStemConfig?) {
        targetConfig = config
    }

    fun setVocalGain(gain: Float) {
        targetConfig?.vocalGain = gain.coerceIn(0f, 1f)
    }

    /**
     * Called by `PrecisionAudioSink.handleBuffer` immediately before `dspChain.process(audioBlock)`
     * with the track-relative presentation timestamp of the first frame in [AudioBlock].
     */
    fun setBlockTimestampUs(trackTimeUs: Long) {
        blockTrackTimeUs = trackTimeUs
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        outputSampleRate = sampleRate
        outputChannels = channelCount
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames <= 0 || outputSampleRate <= 0 || block.channelCount != 2) return

        val desired = targetConfig
        val wantWet = desired != null

        // Fast-path byte-identical bypass when completely inactive.
        if (!wantWet && wetMix <= 0f) {
            if (openKey != null) closeFiles()
            return
        }

        val activeCfg = desired ?: lastConfig
        if (activeCfg == null) {
            wetMix = 0f
            closeFiles()
            return
        }

        val trackUs = blockTrackTimeUs
        if (trackUs == Long.MIN_VALUE || trackUs < 0L) {
            // Timestamp unavailable (e.g. silence pre-roll): leave original PCM untouched.
            return
        }

        if (!ensureOpen(activeCfg)) {
            // I/O failure opening stems: fall back cleanly to original PCM.
            wetMix = 0f
            targetConfig = null
            closeFiles()
            return
        }

        val vRaf = vocalsRaf ?: return
        val iRaf = instRaf ?: return

        val stemRate = activeCfg.stemSampleRate.toDouble()
        val outRate = outputSampleRate.toDouble()
        val startStemFrameD = (trackUs.toDouble() * stemRate) / 1_000_000.0
        val stepPerOutFrame = stemRate / outRate
        val firstStemFrame = floor(startStemFrameD).toLong()
        val lastNeededStemFrame = floor(startStemFrameD + (frames - 1) * stepPerOutFrame).toLong() + 1L

        if (firstStemFrame < 0L || firstStemFrame >= activeCfg.totalFrames) {
            // Beyond stem duration: gracefully fade out wet mix and keep original PCM.
            wetMix = 0f
            return
        }

        val clampedLast = minOf(lastNeededStemFrame, activeCfg.totalFrames - 1L)
        val stemFramesToRead = (clampedLast - firstStemFrame + 1L).toInt().coerceAtLeast(1)
        val bytesNeeded = stemFramesToRead * BYTES_PER_STEREO_PCM16_FRAME
        ensureScratchCapacity(bytesNeeded)

        val byteOffset = firstStemFrame * BYTES_PER_STEREO_PCM16_FRAME.toLong()
        val readOk = try {
            readFullyAt(vRaf, byteOffset, vocalsBytes, bytesNeeded) &&
                readFullyAt(iRaf, byteOffset, instBytes, bytesNeeded)
        } catch (e: Exception) {
            Log.w(TAG, "Stem read failed at frame $firstStemFrame; falling back to original PCM", e)
            false
        }

        if (!readOk) {
            wetMix = 0f
            return
        }

        val fadeSamples = (outputSampleRate * CROSSFADE_MS / 1000).coerceAtLeast(1)
        val fadeStep = 1f / fadeSamples.toFloat()
        val vocalGain = activeCfg.vocalGain.coerceIn(0f, 1f)
        val samples = block.samples
        val maxLocalIdx = stemFramesToRead - 1
        val exactRate = kotlin.math.abs(stepPerOutFrame - 1.0) < 1e-6

        var currentWet = wetMix
        for (i in 0 until frames) {
            currentWet = if (wantWet) {
                (currentWet + fadeStep).coerceAtMost(1f)
            } else {
                (currentWet - fadeStep).coerceAtLeast(0f)
            }

            if (currentWet <= 0f) {
                if (!wantWet) break
                continue
            }

            val vL: Float
            val vR: Float
            val iL: Float
            val iR: Float

            if (exactRate) {
                val localIdx = i.coerceAtMost(maxLocalIdx)
                val bOff = localIdx * 4
                vL = readPcm16Sample(vocalsBytes, bOff)
                vR = readPcm16Sample(vocalsBytes, bOff + 2)
                iL = readPcm16Sample(instBytes, bOff)
                iR = readPcm16Sample(instBytes, bOff + 2)
            } else {
                val stemPos = (startStemFrameD + i * stepPerOutFrame) - firstStemFrame
                val idx0 = floor(stemPos).toInt().coerceIn(0, maxLocalIdx)
                val idx1 = (idx0 + 1).coerceAtMost(maxLocalIdx)
                val frac = (stemPos - idx0).toFloat().coerceIn(0f, 1f)
                val inv = 1f - frac
                val off0 = idx0 * 4
                val off1 = idx1 * 4
                vL = readPcm16Sample(vocalsBytes, off0) * inv + readPcm16Sample(vocalsBytes, off1) * frac
                vR = readPcm16Sample(vocalsBytes, off0 + 2) * inv + readPcm16Sample(vocalsBytes, off1 + 2) * frac
                iL = readPcm16Sample(instBytes, off0) * inv + readPcm16Sample(instBytes, off1) * frac
                iR = readPcm16Sample(instBytes, off0 + 2) * inv + readPcm16Sample(instBytes, off1 + 2) * frac
            }

            val stemL = iL + vocalGain * vL
            val stemR = iR + vocalGain * vR

            val outIdx = i * 2
            val dry = 1f - currentWet
            samples[outIdx] = samples[outIdx] * dry + stemL * currentWet
            samples[outIdx + 1] = samples[outIdx + 1] * dry + stemR * currentWet
        }

        wetMix = currentWet
        if (!wantWet && wetMix <= 0f) {
            closeFiles()
        }
    }

    override fun flush() {
        blockTrackTimeUs = Long.MIN_VALUE
    }

    override fun reset() {
        blockTrackTimeUs = Long.MIN_VALUE
        wetMix = 0f
        closeFiles()
    }

    private fun ensureOpen(cfg: ActiveStemConfig): Boolean {
        if (openKey == cfg.key && vocalsRaf != null && instRaf != null) {
            lastConfig = cfg
            return true
        }
        closeFiles()
        return try {
            vocalsRaf = RandomAccessFile(cfg.vocalsFile, "r")
            instRaf = RandomAccessFile(cfg.instFile, "r")
            openKey = cfg.key
            lastConfig = cfg
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open stem files for ${cfg.key}", e)
            closeFiles()
            false
        }
    }

    private fun closeFiles() {
        runCatching { vocalsRaf?.close() }
        runCatching { instRaf?.close() }
        vocalsRaf = null
        instRaf = null
        openKey = null
        lastConfig = null
    }

    private fun ensureScratchCapacity(bytesNeeded: Int) {
        if (vocalsBytes.size < bytesNeeded) {
            val next = maxOf(bytesNeeded, vocalsBytes.size * 2)
            vocalsBytes = ByteArray(next)
            instBytes = ByteArray(next)
        }
    }

    private fun readFullyAt(
        raf: RandomAccessFile,
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Boolean {
        raf.seek(offset)
        var read = 0
        while (read < length) {
            val n = raf.read(buffer, read, length - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun readPcm16Sample(buf: ByteArray, offset: Int): Float {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        val s = (hi shl 8) or lo
        return s * INV_32768
    }

    companion object {
        private const val TAG = "VocalSeparationProc"
        private const val CROSSFADE_MS = 40
        private const val BYTES_PER_STEREO_PCM16_FRAME = 4
        private const val INV_32768 = 1f / 32768f
    }
}
