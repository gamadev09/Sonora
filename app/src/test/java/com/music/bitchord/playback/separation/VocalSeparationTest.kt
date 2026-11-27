package com.music.bitchord.playback.separation

import com.music.bitchord.data.settings.AudioIntelligencePriority
import com.music.bitchord.playback.audio.AudioBlock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VocalSeparationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun stftRoundTripPreservesStereoSignal() {
        val sampleCount = 16_384
        val left = FloatArray(sampleCount) { i ->
            (0.45 * sin(2.0 * PI * 440.0 * i / 44100.0) +
                0.25 * sin(2.0 * PI * 1320.0 * i / 44100.0)).toFloat()
        }
        val right = FloatArray(sampleCount) { i ->
            (0.40 * sin(2.0 * PI * 554.37 * i / 44100.0) +
                0.20 * sin(2.0 * PI * 2200.0 * i / 44100.0)).toFloat()
        }

        val stft = StftProcessor.stftStereo(left, right)
        val out = StftProcessor.separateAndReconstruct(stft, vocalMagnitude = null, maskPower = 1.0f)

        var maxErr = 0f
        for (i in 2048 until sampleCount - 2048) {
            maxErr = maxOf(maxErr, abs(out.instLeft[i] - left[i]))
            maxErr = maxOf(maxErr, abs(out.instRight[i] - right[i]))
            assertEquals(0f, out.vocalsLeft[i], 1e-6f)
            assertEquals(0f, out.vocalsRight[i], 1e-6f)
        }
        assertTrue("Expected STFT round-trip error < 1e-4, got $maxErr", maxErr < 1e-4f)
    }

    @Test
    fun wienerMaskStemsSumBackToOriginalMixture() {
        val sampleCount = 12_288
        val left = FloatArray(sampleCount) { i -> (0.6 * sin(2.0 * PI * 330.0 * i / 44100.0)).toFloat() }
        val right = FloatArray(sampleCount) { i -> (0.5 * sin(2.0 * PI * 660.0 * i / 44100.0)).toFloat() }

        val stft = StftProcessor.stftStereo(left, right)
        val syntheticVocalMag = FloatArray(stft.magnitude.size) { idx -> stft.magnitude[idx] * 0.4f }

        for (power in listOf(1.0f, 1.15f)) {
            val out = StftProcessor.separateAndReconstruct(stft, syntheticVocalMag, maskPower = power)
            var maxSumErr = 0f
            for (i in 2048 until sampleCount - 2048) {
                maxSumErr = maxOf(maxSumErr, abs((out.vocalsLeft[i] + out.instLeft[i]) - left[i]))
                maxSumErr = maxOf(maxSumErr, abs((out.vocalsRight[i] + out.instRight[i]) - right[i]))
            }
            assertTrue("Sum error for maskPower=$power was $maxSumErr", maxSumErr < 1e-4f)
        }
    }

    @Test
    fun cacheStoreValidatesAndEvictsLruWithoutDeletingPinned() {
        val cacheDir = tempFolder.newFolder("stems")
        val store = StemCacheStore(cacheDir, maxCacheBytes = 10_000L)

        val k1 = StemCacheStore.computeKey("src1", "v1", AudioIntelligencePriority.BALANCED, 44100, 2)
        val k2 = StemCacheStore.computeKey("src2", "v1", AudioIntelligencePriority.BALANCED, 44100, 2)
        val k3 = StemCacheStore.computeKey("src3", "v1", AudioIntelligencePriority.BALANCED, 44100, 2)
        val k1Hq = StemCacheStore.computeKey("src1", "v1", AudioIntelligencePriority.HIGH_QUALITY, 44100, 2)
        assertNotEquals("Different priority must yield different cache key", k1, k1Hq)

        fun writeEntry(key: String, trackId: String, frames: Long, ts: Long): StemCacheStore.StemEntry {
            val staging = store.beginStaging(key)
            val bytes = ByteArray((frames * 4L).toInt()) { 7 }
            staging.vocalsPart.writeBytes(bytes)
            staging.instPart.writeBytes(bytes)
            return requireNotNull(
                store.commitStaging(
                    staging = staging,
                    trackId = trackId,
                    sourceIdentity = trackId,
                    modelVersion = "v1",
                    priority = AudioIntelligencePriority.BALANCED,
                    sampleRate = 44100,
                    channels = 2,
                    frameCount = frames,
                    nowMs = ts,
                ),
            )
        }

        writeEntry(k1, "t1", 500L, 1_000L)
        writeEntry(k2, "t2", 500L, 2_000L)
        store.pin(k1)
        writeEntry(k3, "t3", 500L, 3_000L)

        assertNotNull("Pinned oldest entry must survive LRU eviction", store.getValid(k1, 4_000L))
        assertNull("Unpinned second-oldest entry should be evicted", store.getValid(k2, 4_000L))
        assertNotNull("Newest entry must remain", store.getValid(k3, 4_000L))

        store.unpin(k1)
        File(cacheDir, "$k3.vocals.pcm").writeBytes(ByteArray(10))
        assertNull("Truncated stem file must fail validation", store.getValid(k3, 5_000L))
    }

    @Test
    fun processorBypassesWhenInactiveAndCrossfadesStemsWhenActive() {
        val dir = tempFolder.newFolder("proc")
        val vocalsFile = File(dir, "v.pcm")
        val instFile = File(dir, "i.pcm")
        val totalFrames = 4410 // 100 ms at 44.1 kHz
        writeConstantPcm16(vocalsFile, totalFrames, left = 0.5f, right = 0.5f)
        writeConstantPcm16(instFile, totalFrames, left = 0.25f, right = -0.25f)

        val processor = VocalSeparationProcessor()
        try {
            processor.configure(44_100, 2)

            // 1. Inactive -> byte-identical passthrough.
            val block = AudioBlock(capacityFrames = 512, channelCount = 2)
            block.setFrameCount(256)
            for (i in 0 until 512) block.samples[i] = 0.75f
            processor.setBlockTimestampUs(0L)
            processor.process(block)
            assertEquals(0.75f, block.samples[0], 0f)
            assertEquals(0.75f, block.samples[511], 0f)

            // 2. Active with vocalGain = 0f -> wait for async read-ahead, then process past 40 ms crossfade.
            processor.setActiveConfig(
                VocalSeparationProcessor.ActiveStemConfig(
                    key = "k",
                    trackId = "t",
                    vocalsFile = vocalsFile,
                    instFile = instFile,
                    stemSampleRate = 44_100,
                    stemChannels = 2,
                    totalFrames = totalFrames.toLong(),
                    vocalGain = 0.0f,
                ),
            )
            assertTrue("Async read-ahead must buffer stem frames", processor.awaitBufferedUpTo(totalFrames.toLong()))

            var frameCursor = 0L
            while (frameCursor < 2560L) {
                block.setFrameCount(256)
                for (i in 0 until 512) block.samples[i] = 0.75f
                processor.setBlockTimestampUs(frameCursor * 1_000_000L / 44_100L)
                processor.process(block)
                frameCursor += 256L
            }
            assertEquals(0.25f, block.samples[510], 1e-3f)
            assertEquals(-0.25f, block.samples[511], 1e-3f)
        } finally {
            processor.release()
        }
    }

    @Test
    fun processorVocalGainLevels0_50_100ReachProcessorWithoutDeactivating() {
        val dir = tempFolder.newFolder("gain")
        val vocalsFile = File(dir, "v.pcm")
        val instFile = File(dir, "i.pcm")
        val totalFrames = 8820 // 200 ms
        writeConstantPcm16(vocalsFile, totalFrames, left = 0.40f, right = -0.20f)
        writeConstantPcm16(instFile, totalFrames, left = 0.30f, right = 0.10f)

        val processor = VocalSeparationProcessor()
        try {
            processor.configure(44_100, 2)
            processor.setActiveConfig(
                VocalSeparationProcessor.ActiveStemConfig(
                    key = "gain-key",
                    trackId = "gain-track",
                    vocalsFile = vocalsFile,
                    instFile = instFile,
                    stemSampleRate = 44_100,
                    stemChannels = 2,
                    totalFrames = totalFrames.toLong(),
                    vocalGain = 0.0f,
                ),
            )
            assertTrue(processor.awaitBufferedUpTo(totalFrames.toLong()))

            val block = AudioBlock(capacityFrames = 512, channelCount = 2)
            // Ramp wetMix to 1.0 over 58 ms.
            var cursor = 0L
            while (cursor < 2560L) {
                block.setFrameCount(256)
                block.samples.fill(0.0f, 0, 512)
                processor.setBlockTimestampUs(cursor * 1_000_000L / 44_100L)
                processor.process(block)
                cursor += 256L
            }

            // 0% vocals -> inst only (0.30, 0.10)
            processor.setVocalGain(0.0f)
            assertTrue(processor.isActive)
            block.setFrameCount(256)
            block.samples.fill(0.0f, 0, 512)
            processor.setBlockTimestampUs(cursor * 1_000_000L / 44_100L)
            processor.process(block)
            assertEquals(0.30f, block.samples[0], 1e-3f)
            assertEquals(0.10f, block.samples[1], 1e-3f)
            cursor += 256L

            // 50% vocals -> inst + 0.5 * vocal = (0.30 + 0.20, 0.10 - 0.10) = (0.50, 0.00)
            processor.setVocalGain(0.5f)
            assertTrue(processor.isActive)
            block.setFrameCount(256)
            block.samples.fill(0.0f, 0, 512)
            processor.setBlockTimestampUs(cursor * 1_000_000L / 44_100L)
            processor.process(block)
            assertEquals(0.50f, block.samples[0], 1e-3f)
            assertEquals(0.00f, block.samples[1], 1e-3f)
            cursor += 256L

            // 100% vocals -> inst + 1.0 * vocal = (0.70, -0.10)
            processor.setVocalGain(1.0f)
            assertTrue(processor.isActive)
            block.setFrameCount(256)
            block.samples.fill(0.0f, 0, 512)
            processor.setBlockTimestampUs(cursor * 1_000_000L / 44_100L)
            processor.process(block)
            assertEquals(0.70f, block.samples[0], 1e-3f)
            assertEquals(-0.10f, block.samples[1], 1e-3f)
        } finally {
            processor.release()
        }
    }

    @Test
    fun processorSeekAndUnderrunFallBackSafelyWithoutStaleSamples() {
        val dir = tempFolder.newFolder("seek")
        val vocalsFile = File(dir, "v.pcm")
        val instFile = File(dir, "i.pcm")
        // 200,000 frames (~4.5 s): first half inst = 0.20f, second half inst = 0.60f.
        val totalFrames = 200_000
        writeTwoRegionPcm16(vocalsFile, totalFrames, splitFrame = 100_000, v1 = 0.0f, v2 = 0.0f)
        writeTwoRegionPcm16(instFile, totalFrames, splitFrame = 100_000, v1 = 0.20f, v2 = 0.60f)

        val processor = VocalSeparationProcessor()
        try {
            processor.configure(44_100, 2)
            processor.setActiveConfig(
                VocalSeparationProcessor.ActiveStemConfig(
                    key = "seek-key",
                    trackId = "seek-track",
                    vocalsFile = vocalsFile,
                    instFile = instFile,
                    stemSampleRate = 44_100,
                    stemChannels = 2,
                    totalFrames = totalFrames.toLong(),
                    vocalGain = 0.0f,
                ),
            )
            assertTrue(processor.awaitBufferedUpTo(4096L))

            val block = AudioBlock(capacityFrames = 512, channelCount = 2)
            // Jump far ahead (frame 120,000) beyond the 65,536-frame ring window -> triggers underrun & async seek.
            val jumpFrame = 120_000L
            block.setFrameCount(256)
            for (i in 0 until 512) block.samples[i] = 0.85f
            processor.setBlockTimestampUs(jumpFrame * 1_000_000L / 44_100L)
            processor.process(block)
            // On immediate underrun, original PCM (0.85f) is preserved instead of outputting silence.
            assertEquals(0.85f, block.samples[0], 1e-4f)

            // Wait for background read-ahead to seek and buffer the new region.
            assertTrue(processor.awaitBufferedUpTo(jumpFrame + 4096L))
            var cursor = jumpFrame
            while (cursor < jumpFrame + 2560L) {
                block.setFrameCount(256)
                for (i in 0 until 512) block.samples[i] = 0.85f
                processor.setBlockTimestampUs(cursor * 1_000_000L / 44_100L)
                processor.process(block)
                cursor += 256L
            }
            // Must play second-region value (0.60f), NEVER stale first-region value (0.20f).
            assertEquals(0.60f, block.samples[510], 1e-3f)

            // Reset must cleanly deactivate.
            processor.reset()
            assertFalse(processor.isActive)
        } finally {
            processor.release()
        }
    }

    @Test
    fun audioDecoderTimestampTrimExcludesPrerollAndPostrollExactly() {
        for (rate in listOf(44_100, 48_000, 88_200, 96_000)) {
            val startUs = 10_000_000L // 10.0 s
            val endUs = 10_100_000L // 10.1 s

            // Buffer entirely in preroll (9.80 s .. 9.85 s) -> must be dropped.
            val prerollFrames = rate / 20 // 50 ms
            assertNull(
                com.music.bitchord.playback.smart.AudioDecoder.computeTimestampTrim(
                    bufferPtsUs = 9_800_000L,
                    bufferFrames = prerollFrames,
                    sampleRate = rate,
                    startUs = startUs,
                    endUs = endUs,
                ),
            )

            // Buffer straddling startUs (9.975 s .. 10.025 s) -> must skip first half.
            val straddle = com.music.bitchord.playback.smart.AudioDecoder.computeTimestampTrim(
                bufferPtsUs = 9_975_000L,
                bufferFrames = prerollFrames,
                sampleRate = rate,
                startUs = startUs,
                endUs = endUs,
            )
            assertNotNull("Straddling start buffer must produce trimmed slice at $rate Hz", straddle)
            val expectedSkip = kotlin.math.round(25_000.0 * rate / 1_000_000.0).toInt()
            assertEquals(expectedSkip, straddle!!.skipFrames)
            assertEquals(prerollFrames - expectedSkip, straddle.takeFrames)
            val oneSampleUs = (1_000_000L / rate) + 1L
            assertTrue(
                "trimmedStartUs=${straddle.trimmedStartUs} not within 1 sample of $startUs at $rate Hz",
                abs(straddle.trimmedStartUs - startUs) <= oneSampleUs,
            )

            // Buffer straddling endUs (10.075 s .. 10.125 s) -> must clip tail to 25 ms.
            val tail = com.music.bitchord.playback.smart.AudioDecoder.computeTimestampTrim(
                bufferPtsUs = 10_075_000L,
                bufferFrames = prerollFrames,
                sampleRate = rate,
                startUs = startUs,
                endUs = endUs,
            )
            assertNotNull("Straddling end buffer must produce trimmed slice at $rate Hz", tail)
            assertEquals(0, tail!!.skipFrames)
            assertEquals(expectedSkip, tail.takeFrames)
        }
    }

    @Test
    fun bandLimitedResamplerHandles44100_48000_88200_96000WithAntiAliasing() {
        val targetRate = 44_100.0
        val durationSec = 0.25

        for (inRate in listOf(44_100.0, 48_000.0, 88_200.0, 96_000.0)) {
            val nIn = (inRate * durationSec).toInt()
            // 1 kHz passband tone on left, 1 kHz passband + out-of-band alias tone on right (when > 44.1 kHz).
            val left = FloatArray(nIn) { i -> (0.5 * sin(2.0 * PI * 1000.0 * i / inRate)).toFloat() }
            val aliasFreq = if (inRate >= 88_200.0) 35_000.0 else if (inRate > 44_100.0) 23_200.0 else 0.0
            val right = FloatArray(nIn) { i ->
                val base = 0.5 * sin(2.0 * PI * 1000.0 * i / inRate)
                val hf = if (aliasFreq > 0.0) 0.4 * sin(2.0 * PI * aliasFreq * i / inRate) else 0.0
                (base + hf).toFloat()
            }

            val (outL, outR) = BandLimitedResampler.resampleStereo(left, right, inRate, targetRate)
            val expectedLen = kotlin.math.floor(nIn * (targetRate / inRate)).toInt()
            assertEquals("Output length mismatch for $inRate Hz", expectedLen, outL.size)
            assertEquals("Stereo L/R length mismatch for $inRate Hz", outL.size, outR.size)

            // Verify passband accuracy, finite samples, zero clipping, low DC offset, and anti-aliasing on right.
            var dcSum = 0.0
            var maxErrL = 0f
            var maxDiffLR = 0f
            for (i in 200 until outL.size - 200) {
                assertTrue(outL[i].isFinite() && outR[i].isFinite())
                assertTrue(abs(outL[i]) <= 1.0f && abs(outR[i]) <= 1.0f)
                dcSum += outL[i]
                val ref = (0.5 * sin(2.0 * PI * 1000.0 * i / targetRate)).toFloat()
                maxErrL = maxOf(maxErrL, abs(outL[i] - ref))
                // After anti-aliasing, right (which had an out-of-band tone) must closely match left!
                maxDiffLR = maxOf(maxDiffLR, abs(outR[i] - outL[i]))
            }
            val dc = abs(dcSum / (outL.size - 400))
            assertTrue("DC offset too high ($dc) for $inRate Hz", dc < 1e-3)
            assertTrue("Passband error too high ($maxErrL) for $inRate Hz", maxErrL < 2e-2f)
            assertTrue("Anti-alias rejection too weak (maxDiffLR=$maxDiffLR) for $inRate Hz", maxDiffLR < 2e-2f)
        }
    }

    @Test
    fun chunkedOverlapReconstructionMaintainsPhaseAndBoundaryContinuity() {
        val sampleRate = 44_100
        val totalSamples = 24_576
        val windowSamples = 12_288
        val overlapSamples = 3_072
        val stepSamples = windowSamples - overlapSamples

        val refL = FloatArray(totalSamples) { i ->
            (0.45 * sin(2.0 * PI * 440.0 * i / sampleRate) +
                0.25 * sin(2.0 * PI * 880.0 * i / sampleRate)).toFloat()
        }
        val refR = FloatArray(totalSamples) { i ->
            (0.40 * sin(2.0 * PI * 554.37 * i / sampleRate) +
                0.20 * sin(2.0 * PI * 1108.73 * i / sampleRate)).toFloat()
        }

        val stitchedL = FloatArray(totalSamples)
        val stitchedR = FloatArray(totalSamples)
        var prevTailL: FloatArray? = null
        var prevTailR: FloatArray? = null
        var written = 0
        var start = 0

        while (start < totalSamples) {
            val end = minOf(totalSamples, start + windowSamples)
            val len = end - start
            val chunkL = refL.copyOfRange(start, end)
            val chunkR = refR.copyOfRange(start, end)
            val stft = StftProcessor.stftStereo(chunkL, chunkR)
            val sep = StftProcessor.separateAndReconstruct(stft, vocalMagnitude = null, maskPower = 1.0f)

            val pL = prevTailL
            val pR = prevTailR
            if (pL != null && pR != null) {
                val blend = minOf(pL.size, len)
                val denom = (blend - 1).coerceAtLeast(1).toFloat()
                for (i in 0 until blend) {
                    val a = i / denom
                    val inv = 1f - a
                    sep.instLeft[i] = pL[i] * inv + sep.instLeft[i] * a
                    sep.instRight[i] = pR[i] * inv + sep.instRight[i] * a
                }
            }

            val isLast = end >= totalSamples
            val emit = if (isLast) len else minOf(stepSamples, len)
            System.arraycopy(sep.instLeft, 0, stitchedL, written, emit)
            System.arraycopy(sep.instRight, 0, stitchedR, written, emit)
            written += emit

            if (!isLast && len > emit) {
                prevTailL = sep.instLeft.copyOfRange(emit, len)
                prevTailR = sep.instRight.copyOfRange(emit, len)
            } else {
                prevTailL = null
                prevTailR = null
            }
            if (isLast) break
            start += stepSamples
        }

        assertEquals(totalSamples, written)
        var dot = 0.0
        var normRef = 0.0
        var normOut = 0.0
        var maxBoundaryErr = 0f
        for (i in 0 until totalSamples) {
            assertTrue(stitchedL[i].isFinite() && stitchedR[i].isFinite())
            dot += refL[i] * stitchedL[i]
            normRef += refL[i] * refL[i]
            normOut += stitchedL[i] * stitchedL[i]
            if (i in (stepSamples - 512)..(stepSamples + 512)) {
                maxBoundaryErr = maxOf(maxBoundaryErr, abs(stitchedL[i] - refL[i]))
            }
        }
        val corr = dot / kotlin.math.sqrt(normRef * normOut)
        assertTrue("Expected correlation > 0.9999 across chunk boundaries, got $corr", corr > 0.9999)
        assertTrue("Expected chunk boundary error < 1e-3, got $maxBoundaryErr", maxBoundaryErr < 1e-3f)
    }

    @Test
    fun stftSanitizesNonFiniteModelPredictions() {
        val sampleCount = 8192
        val left = FloatArray(sampleCount) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 44100.0)).toFloat() }
        val right = left.copyOf()
        val stft = StftProcessor.stftStereo(left, right)
        val corruptedMag = FloatArray(stft.magnitude.size) { idx ->
            when (idx % 3) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                else -> -5.0f
            }
        }
        val out = StftProcessor.separateAndReconstruct(stft, corruptedMag, maskPower = 1.15f)
        for (i in 0 until sampleCount) {
            assertTrue(out.vocalsLeft[i].isFinite() && out.instLeft[i].isFinite())
            assertTrue(out.vocalsRight[i].isFinite() && out.instRight[i].isFinite())
        }
    }

    private fun writeConstantPcm16(file: File, frames: Int, left: Float, right: Float) {
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val l = (left * 32767f).toInt().toShort()
        val r = (right * 32767f).toInt().toShort()
        for (i in 0 until frames) {
            buf.putShort(l)
            buf.putShort(r)
        }
        file.writeBytes(buf.array())
    }

    private fun writeTwoRegionPcm16(file: File, frames: Int, splitFrame: Int, v1: Float, v2: Float) {
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val s1 = (v1 * 32767f).toInt().toShort()
        val s2 = (v2 * 32767f).toInt().toShort()
        for (i in 0 until frames) {
            val s = if (i < splitFrame) s1 else s2
            buf.putShort(s)
            buf.putShort(s)
        }
        file.writeBytes(buf.array())
    }
}
