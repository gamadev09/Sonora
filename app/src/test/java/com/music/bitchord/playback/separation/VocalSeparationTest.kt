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
