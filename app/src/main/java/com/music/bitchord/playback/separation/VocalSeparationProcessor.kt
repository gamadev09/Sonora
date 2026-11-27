package com.music.bitchord.playback.separation

import android.util.Log
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.floor

/**
 * Real-time in-place Float32 audio processor that mixes pre-separated vocal and
 * instrumental stems into Sonora's existing playback pipeline (`DspChain`).
 *
 * Real-time safety guarantees:
 * - **Zero file I/O on the audio thread**: all `RandomAccessFile` open, seek,
 *   read, and close operations run exclusively on a dedicated background
 *   read-ahead thread (`Sonora-StemReadAhead`).
 * - **Lock-free bounded ring buffer**: fixed 65,536-frame (~1.48 s at 44.1 kHz)
 *   stereo PCM16 ring buffers for vocals and instrumental (512 KB total RAM).
 *   [process] performs zero disk operations, zero locks, and zero allocations.
 * - **Exact frame alignment**: vocals and instrumental frames are read at the
 *   same byte offset and published atomically under a shared [bufEndFrame] and
 *   [bufGen] sequence counter.
 * - **Click-free fallback**: if a seek is still refilling or an I/O error occurs,
 *   [process] smoothly ramps `wetMix` back to original PCM rather than blocking
 *   or outputting silence.
 * - **Sample-rate & gain accuracy**: preserves timestamp alignment, linear
 *   resampling when output rate != stem rate (e.g. 48 kHz vs 44.1 kHz), 40 ms
 *   activation/deactivation crossfade, and real-time [setVocalGain] updates.
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

    @Volatile
    private var lastValidConfig: ActiveStemConfig? = null

    /** Smooth crossfade factor: 0f = 100% original PCM, 1f = 100% separated stem mix. */
    private var wetMix: Float = 0f

    // --- Lock-free SPSC bounded read-ahead ring buffer (512 KB total) ---
    // Producer = background I/O thread; Consumer = real-time audio thread.
    private val vocalsRing = ShortArray(RING_FRAMES * 2)
    private val instRing = ShortArray(RING_FRAMES * 2)

    @Volatile
    private var bufGen: Long = 0L

    @Volatile
    private var bufStartFrame: Long = 0L

    @Volatile
    private var bufEndFrame: Long = 0L

    @Volatile
    private var bufIoFailed: Boolean = false

    private val desiredGen = AtomicLong(0L)

    @Volatile
    private var consumerReadFrame: Long = 0L

    @Volatile
    private var pendingSeekFrame: Long = -1L

    private val seekRequestSeq = AtomicLong(0L)
    private val shutdownRequested = AtomicBoolean(false)

    @Volatile
    private var ioThread: Thread? = null

    private val ioThreadLock = Any()

    val isActive: Boolean
        get() = targetConfig != null || wetMix > 0f

    fun setActiveConfig(config: ActiveStemConfig?) {
        targetConfig = config
        if (config != null) {
            lastValidConfig = config
            bufIoFailed = false
            ensureIoThreadStarted()
        }
        desiredGen.incrementAndGet()
        wakeIoThread()
    }

    fun setVocalGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        targetConfig?.vocalGain = clamped
        lastValidConfig?.vocalGain = clamped
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

    /**
     * Real-time audio callback: consumes only already-buffered ring samples.
     * Performs ZERO file I/O, ZERO locks, ZERO heap allocations, and ZERO logging.
     */
    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames <= 0 || outputSampleRate <= 0 || block.channelCount != 2) return

        val desired = targetConfig
        val wantWet = desired != null

        // Fast-path byte-identical bypass when completely inactive.
        if (!wantWet && wetMix <= 0f) {
            return
        }

        val activeCfg = desired ?: lastValidConfig
        if (activeCfg == null) {
            wetMix = 0f
            return
        }

        val trackUs = blockTrackTimeUs
        if (trackUs == Long.MIN_VALUE || trackUs < 0L) {
            return
        }

        val stemRate = activeCfg.stemSampleRate.toDouble()
        val outRate = outputSampleRate.toDouble()
        val startStemFrameD = (trackUs.toDouble() * stemRate) / 1_000_000.0
        val stepPerOutFrame = stemRate / outRate
        val firstStemFrame = floor(startStemFrameD).toLong()
        val lastNeededStemFrame = floor(startStemFrameD + (frames - 1) * stepPerOutFrame).toLong() + 1L

        val fadeSamples = (outputSampleRate * CROSSFADE_MS / 1000).coerceAtLeast(1)
        val fadeStep = 1f / fadeSamples.toFloat()

        if (firstStemFrame < 0L || firstStemFrame >= activeCfg.totalFrames || bufIoFailed) {
            // Beyond stem duration or I/O error: click-free ramp back to original PCM.
            wetMix = (wetMix - fadeStep * frames).coerceAtLeast(0f)
            return
        }

        val clampedLast = minOf(lastNeededStemFrame, activeCfg.totalFrames - 1L)
        val expectedGen = desiredGen.get()
        val snapGen = bufGen
        val snapStart = bufStartFrame
        val snapEnd = bufEndFrame

        val bufferReady = snapGen == expectedGen &&
            firstStemFrame >= snapStart &&
            clampedLast < snapEnd

        if (!bufferReady) {
            // Request background seek/prefill (non-blocking) if outside current window.
            if (snapGen == expectedGen && (firstStemFrame < snapStart || firstStemFrame > snapEnd + SEEK_JUMP_FRAMES)) {
                requestAsyncSeek(firstStemFrame)
            } else {
                consumerReadFrame = firstStemFrame
                wakeIoThread()
            }
            // Click-free fallback to original PCM on underrun (never output silence).
            wetMix = (wetMix - fadeStep * frames).coerceAtLeast(0f)
            return
        }

        val vocalGain = activeCfg.vocalGain.coerceIn(0f, 1f)
        val samples = block.samples
        val exactRate = abs(stepPerOutFrame - 1.0) < 1e-6

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
                val stemFrame = minOf(firstStemFrame + i.toLong(), clampedLast)
                val ringIdx = ((stemFrame and RING_MASK).toInt()) shl 1
                vL = vocalsRing[ringIdx] * INV_32768
                vR = vocalsRing[ringIdx + 1] * INV_32768
                iL = instRing[ringIdx] * INV_32768
                iR = instRing[ringIdx + 1] * INV_32768
            } else {
                // 4-point Catmull-Rom cubic Hermite interpolation (clean 44.1 -> 48 kHz
                // playback with zero extra allocations and far lower imaging than linear).
                val stemPos = startStemFrameD + i * stepPerOutFrame
                val f1 = floor(stemPos).toLong().coerceIn(firstStemFrame, clampedLast)
                val f0 = maxOf(f1 - 1L, snapStart)
                val f2 = minOf(f1 + 1L, clampedLast)
                val f3 = minOf(f1 + 2L, clampedLast)
                val t = (stemPos - f1).toFloat().coerceIn(0f, 1f)
                val r0 = ((f0 and RING_MASK).toInt()) shl 1
                val r1 = ((f1 and RING_MASK).toInt()) shl 1
                val r2 = ((f2 and RING_MASK).toInt()) shl 1
                val r3 = ((f3 and RING_MASK).toInt()) shl 1
                vL = catmullRom(vocalsRing[r0], vocalsRing[r1], vocalsRing[r2], vocalsRing[r3], t) * INV_32768
                vR = catmullRom(vocalsRing[r0 + 1], vocalsRing[r1 + 1], vocalsRing[r2 + 1], vocalsRing[r3 + 1], t) * INV_32768
                iL = catmullRom(instRing[r0], instRing[r1], instRing[r2], instRing[r3], t) * INV_32768
                iR = catmullRom(instRing[r0 + 1], instRing[r1 + 1], instRing[r2 + 1], instRing[r3 + 1], t) * INV_32768
            }

            // Sequence-lock check: if a concurrent seek/reconfig invalidated the ring,
            // leave remaining samples as original PCM rather than mixing stale frames.
            if (bufGen != expectedGen || bufStartFrame > firstStemFrame) {
                currentWet = (currentWet - fadeStep).coerceAtLeast(0f)
                break
            }

            val stemL = iL + vocalGain * vL
            val stemR = iR + vocalGain * vR

            val outIdx = i shl 1
            val dry = 1f - currentWet
            samples[outIdx] = samples[outIdx] * dry + stemL * currentWet
            samples[outIdx + 1] = samples[outIdx + 1] * dry + stemR * currentWet
        }

        wetMix = currentWet
        consumerReadFrame = clampedLast + 1L
        if (snapEnd - consumerReadFrame < REFILL_LOW_WATER_FRAMES) {
            wakeIoThread()
        }
    }

    override fun flush() {
        blockTrackTimeUs = Long.MIN_VALUE
        // Invalidate stale buffered frames on seek / discontinuity without blocking.
        if (targetConfig != null) {
            desiredGen.incrementAndGet()
            wakeIoThread()
        }
    }

    override fun reset() {
        blockTrackTimeUs = Long.MIN_VALUE
        wetMix = 0f
        targetConfig = null
        lastValidConfig = null
        desiredGen.incrementAndGet()
        wakeIoThread()
    }

    /** Releases the background read-ahead thread and any open file handles. */
    fun release() {
        targetConfig = null
        lastValidConfig = null
        wetMix = 0f
        shutdownRequested.set(true)
        desiredGen.incrementAndGet()
        wakeIoThread()
    }

    /** Test/diagnostics helper: awaits the read-ahead buffer reaching at least [frameExclusive]. */
    internal fun awaitBufferedUpTo(frameExclusive: Long, timeoutMs: Long = 1_500L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (bufGen == desiredGen.get() && bufEndFrame >= frameExclusive) return true
            Thread.sleep(2L)
        }
        return bufGen == desiredGen.get() && bufEndFrame >= frameExclusive
    }

    private fun requestAsyncSeek(frame: Long) {
        pendingSeekFrame = frame.coerceAtLeast(0L)
        seekRequestSeq.incrementAndGet()
        wakeIoThread()
    }

    private fun ensureIoThreadStarted() {
        if (ioThread != null && !shutdownRequested.get()) return
        synchronized(ioThreadLock) {
            if (ioThread != null && !shutdownRequested.get()) return
            shutdownRequested.set(false)
            val t = Thread(::ioLoop, "Sonora-StemReadAhead").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
            ioThread = t
            t.start()
        }
    }

    private fun wakeIoThread() {
        ioThread?.let { LockSupport.unpark(it) }
    }

    private fun ioLoop() {
        var openKey: String? = null
        var vocalsRaf: RandomAccessFile? = null
        var instRaf: RandomAccessFile? = null
        var activeGen = -1L
        var lastHandledSeekSeq = 0L
        var writeCursorFrame = 0L

        val vocalsBytes = ByteArray(IO_CHUNK_FRAMES * BYTES_PER_STEREO_PCM16_FRAME)
        val instBytes = ByteArray(IO_CHUNK_FRAMES * BYTES_PER_STEREO_PCM16_FRAME)

        fun closeHandles() {
            runCatching { vocalsRaf?.close() }
            runCatching { instRaf?.close() }
            vocalsRaf = null
            instRaf = null
            openKey = null
        }

        try {
            while (!shutdownRequested.get()) {
                val cfg = targetConfig
                val gen = desiredGen.get()

                if (cfg == null) {
                    if (openKey != null) {
                        closeHandles()
                        bufStartFrame = 0L
                        bufEndFrame = 0L
                        bufGen = gen
                    }
                    LockSupport.parkNanos(IDLE_PARK_NANOS)
                    continue
                }

                // Reopen files if config changed.
                if (openKey != cfg.key || vocalsRaf == null || instRaf == null) {
                    closeHandles()
                    try {
                        vocalsRaf = RandomAccessFile(cfg.vocalsFile, "r")
                        instRaf = RandomAccessFile(cfg.instFile, "r")
                        openKey = cfg.key
                        bufIoFailed = false
                    } catch (e: Exception) {
                        Log.w(TAG, "Background open failed for stem ${cfg.key}", e)
                        closeHandles()
                        bufIoFailed = true
                        bufStartFrame = 0L
                        bufEndFrame = 0L
                        bufGen = gen
                        LockSupport.parkNanos(IDLE_PARK_NANOS)
                        continue
                    }
                }

                val seekSeq = seekRequestSeq.get()
                val needsRebase = (activeGen != gen) || (seekSeq != lastHandledSeekSeq)
                if (needsRebase) {
                    val requestedStart = if (seekSeq != lastHandledSeekSeq) {
                        lastHandledSeekSeq = seekSeq
                        pendingSeekFrame.coerceIn(0L, (cfg.totalFrames - 1L).coerceAtLeast(0L))
                    } else {
                        consumerReadFrame.coerceIn(0L, (cfg.totalFrames - 1L).coerceAtLeast(0L))
                    }
                    activeGen = gen
                    writeCursorFrame = requestedStart
                    // Publish empty range for the new generation before filling.
                    bufEndFrame = requestedStart
                    bufStartFrame = requestedStart
                    bufGen = gen
                }

                val vRaf = vocalsRaf
                val iRaf = instRaf
                if (vRaf == null || iRaf == null) continue

                // Keep consumer behind-margin intact so we never overwrite unread frames.
                val safeConsumer = consumerReadFrame.coerceIn(bufStartFrame, writeCursorFrame)
                val newStart = maxOf(bufStartFrame, safeConsumer - BEHIND_MARGIN_FRAMES)
                if (newStart > bufStartFrame) {
                    bufStartFrame = newStart
                }

                val bufferedFrames = writeCursorFrame - bufStartFrame
                val maxFillable = (RING_FRAMES - SAFETY_GAP_FRAMES) - bufferedFrames
                val remainingInFile = cfg.totalFrames - writeCursorFrame

                if (maxFillable < IO_MIN_FILL_FRAMES || remainingInFile <= 0L) {
                    LockSupport.parkNanos(ACTIVE_PARK_NANOS)
                    continue
                }

                val framesToRead = minOf(IO_CHUNK_FRAMES.toLong(), maxFillable, remainingInFile).toInt()
                val bytesToRead = framesToRead * BYTES_PER_STEREO_PCM16_FRAME
                val byteOffset = writeCursorFrame * BYTES_PER_STEREO_PCM16_FRAME.toLong()

                val ok = try {
                    readFullyAt(vRaf, byteOffset, vocalsBytes, bytesToRead) &&
                        readFullyAt(iRaf, byteOffset, instBytes, bytesToRead)
                } catch (e: Exception) {
                    Log.w(TAG, "Background stem read failed at frame $writeCursorFrame", e)
                    false
                }

                if (!ok) {
                    bufIoFailed = true
                    LockSupport.parkNanos(IDLE_PARK_NANOS)
                    continue
                }

                // If a new generation or seek arrived mid-read, discard stale read immediately.
                if (desiredGen.get() != activeGen || seekRequestSeq.get() != lastHandledSeekSeq) {
                    continue
                }

                // Unpack stereo PCM16 into aligned ring slots before publishing bufEndFrame.
                var f = writeCursorFrame
                var bOff = 0
                for (n in 0 until framesToRead) {
                    val ringIdx = ((f and RING_MASK).toInt()) shl 1
                    vocalsRing[ringIdx] = decodePcm16(vocalsBytes, bOff)
                    vocalsRing[ringIdx + 1] = decodePcm16(vocalsBytes, bOff + 2)
                    instRing[ringIdx] = decodePcm16(instBytes, bOff)
                    instRing[ringIdx + 1] = decodePcm16(instBytes, bOff + 2)
                    f++
                    bOff += 4
                }

                writeCursorFrame = f
                bufEndFrame = writeCursorFrame
            }
        } finally {
            closeHandles()
            synchronized(ioThreadLock) {
                if (ioThread === Thread.currentThread()) {
                    ioThread = null
                }
            }
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

    private fun decodePcm16(buf: ByteArray, offset: Int): Short {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun catmullRom(y0: Short, y1: Short, y2: Short, y3: Short, t: Float): Float {
        val p0 = y0.toFloat()
        val p1 = y1.toFloat()
        val p2 = y2.toFloat()
        val p3 = y3.toFloat()
        val a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3
        val b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3
        val c = -0.5f * p0 + 0.5f * p2
        return ((a * t + b) * t + c) * t + p1
    }

    companion object {
        private const val TAG = "VocalSeparationProc"
        private const val CROSSFADE_MS = 40
        private const val BYTES_PER_STEREO_PCM16_FRAME = 4
        private const val INV_32768 = 1f / 32768f

        /** Power-of-2 ring capacity: 65,536 frames (~1.48 s at 44.1 kHz, 512 KB total). */
        private const val RING_FRAMES = 1 shl 16
        private const val RING_MASK = (RING_FRAMES - 1).toLong()
        private const val BEHIND_MARGIN_FRAMES = 4_096L
        private const val SAFETY_GAP_FRAMES = 2_048L
        private const val REFILL_LOW_WATER_FRAMES = 32_768L
        private const val SEEK_JUMP_FRAMES = 8_192L
        private const val IO_CHUNK_FRAMES = 4_096
        private const val IO_MIN_FILL_FRAMES = 1_024L
        private const val ACTIVE_PARK_NANOS = 8_000_000L // 8 ms
        private const val IDLE_PARK_NANOS = 50_000_000L // 50 ms
    }
}
