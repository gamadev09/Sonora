package com.music.bitchord.playback.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import android.media.MediaDataSource
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AudioIntelligencePriority
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.AutomixModels
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Bounded-memory background vocal-separation engine powered by the verified
 * Open-Unmix HQ INT8 ONNX model (`vocals_umxhq_int8.onnx`, MIT license,
 * SHA-256 verified via [AutomixModels.VOCALS]).
 *
 * Execution properties:
 * - Processes audio in bounded overlapping ~22.27 s chunks (matching the model's
 *   native `[1, 2, 2049, 960]` STFT window at 44.1 kHz) so full tracks are never
 *   held in RAM.
 * - Reconstructs two phase-coherent stereo stems (isolated vocals + instrumental
 *   accompaniment) via soft Wiener ratio masking and streams 16-bit PCM directly
 *   to staging files on disk.
 * - Adapts CPU thread count and inter-chunk pacing to [AudioIntelligencePriority],
 *   device thermal status, battery saver, and available memory.
 *
 * Acceleration note:
 * Open-Unmix HQ uses dynamic-quantized bidirectional LSTM and MatMul ops. On
 * Snapdragon 6 Gen 1 (Android 14) with the bundled minimal ONNX Runtime 1.28.0
 * AAR, ARM64 NEON/dotprod CPU execution is the reliable path; NNAPI does not
 * accelerate BiLSTM layers cleanly and is intentionally not forced.
 */
@UnstableApi
class VocalSeparationEngine(
    private val context: Context,
) {
    companion object {
        private const val TAG = "AudioIntelligenceEngine"
        private const val MIN_FREE_STORAGE_BYTES = 128L * 1024L * 1024L // 128 MB safety margin
        private const val OUTPUT_SAMPLE_RATE = StftProcessor.MODEL_SAMPLE_RATE // 44,100 Hz
        private const val OUTPUT_CHANNELS = 2
        private const val PCM_WRITE_BUFFER_BYTES = 64 * 1024

        val MODEL_VERSION: String = "umxhq_int8_${AutomixModels.VOCALS.sha256.take(12)}"
    }

    data class SeparationMetrics(
        val frameCount: Long,
        val sampleRate: Int,
        val channels: Int,
        val durationSeconds: Double,
        val elapsedMs: Long,
        val chunksProcessed: Int,
        val effectiveThreads: Int,
        val backend: String,
    )

    private data class ExecutionBudget(
        val threads: Int,
        val yieldMs: Long,
        val throttledReason: String?,
    )

    private fun computeExecutionBudget(priority: AudioIntelligencePriority): ExecutionBudget {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        var threads = priority.inferenceThreads.coerceIn(1, (cores / 2).coerceAtLeast(1))
        var yieldMs = priority.interChunkYieldMs
        var reason: String? = null

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            if (pm.isPowerSaveMode) {
                threads = 1
                yieldMs = maxOf(yieldMs, 35L)
                reason = "battery_saver"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val thermal = pm.currentThermalStatus
                if (thermal >= priority.maxThermalStatus) {
                    threads = 1
                    yieldMs = maxOf(yieldMs, 60L)
                    reason = "thermal_$thermal"
                }
            }
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            if (memInfo.lowMemory) {
                threads = 1
                yieldMs = maxOf(yieldMs, 50L)
                reason = "low_memory"
            }
        }

        return ExecutionBudget(threads = threads, yieldMs = yieldMs, throttledReason = reason)
    }

    private fun checkStorageAvailable(targetDir: File) {
        val stat = StatFs(targetDir.absolutePath)
        val freeBytes = stat.availableBytes
        if (freeBytes < MIN_FREE_STORAGE_BYTES) {
            throw IOException("Not enough free storage (${freeBytes / (1024 * 1024)} MB free)")
        }
    }

    /**
     * Separates audio from [openSource] into [staging.vocalsPart] and [staging.instPart].
     * Streams output directly to disk in bounded chunks and reports progress in `[0f, 1f]`.
     */
    suspend fun separateToStaging(
        modelFile: File,
        openSource: () -> MediaDataSource?,
        staging: StemCacheStore.StagingFiles,
        priority: AudioIntelligencePriority,
        onProgress: (Float) -> Unit,
    ): SeparationMetrics {
        checkStorageAvailable(staging.vocalsPart.parentFile ?: context.noBackupFilesDir)

        val totalDurationSec = AudioDecoder.containerDurationSecondsWithFallback(openSource)
            ?.takeIf { it.isFinite() && it > 0.5 }
            ?: throw IOException("Could not determine track duration")

        val startedMs = SystemClock.elapsedRealtime()
        val initialBudget = computeExecutionBudget(priority)

        val env = OrtEnvironment.getEnvironment()
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(initialBudget.threads)
            setInterOpNumThreads(1)
            setCpuMemArena(false)
            setMemoryPattern(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }

        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        val windowSamples = StftProcessor.MODEL_WINDOW_SAMPLES
        val windowSec = windowSamples.toDouble() / OUTPUT_SAMPLE_RATE
        val overlapSamples = ((windowSamples * priority.overlapRatio).roundToInt() / StftProcessor.HOP) * StftProcessor.HOP
        val stepSamples = (windowSamples - overlapSamples).coerceAtLeast(StftProcessor.HOP * 64)
        val stepSec = stepSamples.toDouble() / OUTPUT_SAMPLE_RATE
        val totalExpectedSamples = ceil(totalDurationSec * OUTPUT_SAMPLE_RATE).toLong().coerceAtLeast(1L)
        val totalChunks = maxOf(1, ceil((totalDurationSec - (windowSec - stepSec)).coerceAtLeast(0.0) / stepSec).toInt() + 1)

        Log.i(
            TAG,
            "Starting vocal separation: duration=%.1fs priority=%s threads=%d overlap=%.1f%% chunks=%d throttle=%s".format(
                totalDurationSec,
                priority.name,
                initialBudget.threads,
                priority.overlapRatio * 100f,
                totalChunks,
                initialBudget.throttledReason ?: "none",
            ),
        )

        var writtenFrames = 0L
        var chunksProcessed = 0

        // Tail buffers from previous chunk for seamless linear crossfade across overlap.
        var prevVocalsTailL: FloatArray? = null
        var prevVocalsTailR: FloatArray? = null
        var prevInstTailL: FloatArray? = null
        var prevInstTailR: FloatArray? = null

        val pcmBuffer = ByteArray(PCM_WRITE_BUFFER_BYTES)

        sessionOptions.use { opts ->
            env.createSession(modelFile.absolutePath, opts).use { session ->
                val inputName = session.inputNames.first()
                val inputShape = longArrayOf(
                    1L,
                    2L,
                    StftProcessor.BINS.toLong(),
                    StftProcessor.MODEL_FRAMES.toLong(),
                )

                BufferedOutputStream(FileOutputStream(staging.vocalsPart), PCM_WRITE_BUFFER_BYTES).use { vocalsOut ->
                    BufferedOutputStream(FileOutputStream(staging.instPart), PCM_WRITE_BUFFER_BYTES).use { instOut ->
                        var chunkStartSample = 0L
                        var chunkIndex = 0

                        while (chunkStartSample < totalExpectedSamples) {
                            coroutineContext.ensureActive()

                            val chunkEndSample = minOf(totalExpectedSamples, chunkStartSample + windowSamples)
                            val targetChunkSamples = (chunkEndSample - chunkStartSample).toInt()
                            if (targetChunkSamples <= 0) break

                            val startSec = chunkStartSample.toDouble() / OUTPUT_SAMPLE_RATE
                            val endSec = (chunkEndSample.toDouble() / OUTPUT_SAMPLE_RATE).coerceAtMost(totalDurationSec + 0.05)

                            val src = openSource()
                                ?: throw IOException("Audio source became unavailable during separation")
                            val decoded = src.use { dataSource ->
                                AudioDecoder.decodeRegionStereo(
                                    source = dataSource,
                                    startSeconds = startSec,
                                    endSeconds = endSec,
                                    abort = { false },
                                )
                            }?.first ?: throw IOException("Failed to decode audio chunk at ${startSec.roundToInt()}s")

                            coroutineContext.ensureActive()

                            val (left44, right44) = resampleStereoTo44100(
                                decoded.left,
                                decoded.right,
                                decoded.sampleRate,
                                targetChunkSamples,
                            )

                            val stft = StftProcessor.stftStereo(
                                left = left44,
                                right = right44,
                                fixedFrames = StftProcessor.MODEL_FRAMES,
                            )

                            coroutineContext.ensureActive()

                            val vocalMag: FloatArray = OnnxTensor.createTensor(
                                env,
                                FloatBuffer.wrap(stft.magnitude),
                                inputShape,
                            ).use { tensor ->
                                session.run(mapOf(inputName to tensor)).use { results ->
                                    val outTensor = results[0] as OnnxTensor
                                    val fb = outTensor.floatBuffer
                                    val arr = FloatArray(fb.remaining())
                                    fb.get(arr)
                                    arr
                                }
                            }

                            coroutineContext.ensureActive()

                            val separated = StftProcessor.separateAndReconstruct(
                                stft = stft,
                                vocalMagnitude = vocalMag,
                                maskPower = priority.maskPower,
                            )

                            // Crossfade the leading overlap with the previous chunk's saved tail.
                            val prevVL = prevVocalsTailL
                            val prevVR = prevVocalsTailR
                            val prevIL = prevInstTailL
                            val prevIR = prevInstTailR
                            if (prevVL != null && prevVR != null && prevIL != null && prevIR != null) {
                                val blendLen = minOf(prevVL.size, targetChunkSamples)
                                val denom = (blendLen - 1).coerceAtLeast(1).toFloat()
                                for (i in 0 until blendLen) {
                                    val alpha = i / denom
                                    val inv = 1f - alpha
                                    separated.vocalsLeft[i] = prevVL[i] * inv + separated.vocalsLeft[i] * alpha
                                    separated.vocalsRight[i] = prevVR[i] * inv + separated.vocalsRight[i] * alpha
                                    separated.instLeft[i] = prevIL[i] * inv + separated.instLeft[i] * alpha
                                    separated.instRight[i] = prevIR[i] * inv + separated.instRight[i] * alpha
                                }
                            }

                            val isLastChunk = chunkEndSample >= totalExpectedSamples
                            val emitCount = if (isLastChunk) {
                                targetChunkSamples
                            } else {
                                minOf(stepSamples, targetChunkSamples)
                            }

                            writeStereoPcm16(
                                vocalsOut,
                                separated.vocalsLeft,
                                separated.vocalsRight,
                                emitCount,
                                pcmBuffer,
                            )
                            writeStereoPcm16(
                                instOut,
                                separated.instLeft,
                                separated.instRight,
                                emitCount,
                                pcmBuffer,
                            )
                            writtenFrames += emitCount.toLong()

                            if (!isLastChunk && targetChunkSamples > emitCount) {
                                val tailLen = targetChunkSamples - emitCount
                                prevVocalsTailL = separated.vocalsLeft.copyOfRange(emitCount, emitCount + tailLen)
                                prevVocalsTailR = separated.vocalsRight.copyOfRange(emitCount, emitCount + tailLen)
                                prevInstTailL = separated.instLeft.copyOfRange(emitCount, emitCount + tailLen)
                                prevInstTailR = separated.instRight.copyOfRange(emitCount, emitCount + tailLen)
                            } else {
                                prevVocalsTailL = null
                                prevVocalsTailR = null
                                prevInstTailL = null
                                prevInstTailR = null
                            }

                            chunksProcessed++
                            chunkIndex++
                            val progress = (writtenFrames.toFloat() / totalExpectedSamples.toFloat()).coerceIn(0f, 0.99f)
                            onProgress(progress)

                            if (isLastChunk) break
                            chunkStartSample += stepSamples

                            // Re-check thermals between chunks and yield cooperatively.
                            val currentBudget = computeExecutionBudget(priority)
                            if (currentBudget.yieldMs > 0L) {
                                delay(currentBudget.yieldMs)
                            }
                        }

                        vocalsOut.flush()
                        instOut.flush()
                    }
                }
            }
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedMs
        val rtf = if (totalDurationSec > 0.0) (elapsedMs / 1000.0) / totalDurationSec else 0.0
        Log.i(
            TAG,
            "Completed vocal separation: frames=%d elapsed=%dms RTF=%.2fx chunks=%d".format(
                writtenFrames,
                elapsedMs,
                rtf,
                chunksProcessed,
            ),
        )
        onProgress(1f)

        return SeparationMetrics(
            frameCount = writtenFrames,
            sampleRate = OUTPUT_SAMPLE_RATE,
            channels = OUTPUT_CHANNELS,
            durationSeconds = totalDurationSec,
            elapsedMs = elapsedMs,
            chunksProcessed = chunksProcessed,
            effectiveThreads = initialBudget.threads,
            backend = "ONNX Runtime CPU (NEON/INT8)",
        )
    }

    private fun resampleStereoTo44100(
        left: FloatArray,
        right: FloatArray,
        sourceRate: Double,
        targetSamples: Int,
    ): Pair<FloatArray, FloatArray> {
        val srcLen = minOf(left.size, right.size)
        if (srcLen == 0) return FloatArray(targetSamples) to FloatArray(targetSamples)

        val outL = FloatArray(targetSamples)
        val outR = FloatArray(targetSamples)

        if (kotlin.math.abs(sourceRate - OUTPUT_SAMPLE_RATE.toDouble()) < 1.0) {
            val copyLen = minOf(srcLen, targetSamples)
            System.arraycopy(left, 0, outL, 0, copyLen)
            System.arraycopy(right, 0, outR, 0, copyLen)
            return outL to outR
        }

        val ratio = sourceRate / OUTPUT_SAMPLE_RATE.toDouble()
        val lastIdx = srcLen - 1
        for (i in 0 until targetSamples) {
            val pos = i * ratio
            val idx = floor(pos).toInt()
            if (idx >= lastIdx) {
                if (idx == lastIdx) {
                    outL[i] = left[lastIdx]
                    outR[i] = right[lastIdx]
                }
                break
            }
            val frac = (pos - idx).toFloat()
            val inv = 1f - frac
            outL[i] = left[idx] * inv + left[idx + 1] * frac
            outR[i] = right[idx] * inv + right[idx + 1] * frac
        }
        return outL to outR
    }

    private fun writeStereoPcm16(
        out: BufferedOutputStream,
        left: FloatArray,
        right: FloatArray,
        count: Int,
        scratch: ByteArray,
    ) {
        var offset = 0
        for (i in 0 until count) {
            if (offset + 4 > scratch.size) {
                out.write(scratch, 0, offset)
                offset = 0
            }
            val l = (left[i].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            val r = (right[i].coerceIn(-1f, 1f) * 32767f).roundToInt().coerceIn(-32768, 32767)
            scratch[offset++] = (l and 0xFF).toByte()
            scratch[offset++] = ((l ushr 8) and 0xFF).toByte()
            scratch[offset++] = (r and 0xFF).toByte()
            scratch[offset++] = ((r ushr 8) and 0xFF).toByte()
        }
        if (offset > 0) {
            out.write(scratch, 0, offset)
        }
    }
}
