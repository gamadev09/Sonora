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
        // Null vocal mask -> 100% instrumental, 0% vocals.
        val out = StftProcessor.separateAndReconstruct(stft, vocalMagnitude = null, maskPower = 1.0f)

        var maxErr = 0f
        // Check interior samples (away from exact zero-padded start/end edge).
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
        // Synthetic predicted vocal magnitude = 40% of mixture magnitude.
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
        // Cap at 10 KB so 3 entries of 4 KB each trigger LRU eviction.
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

        // Each entry: 500 frames * 4 bytes * 2 files = 4,000 bytes.
        writeEntry(k1, "t1", 500L, 1_000L)
        writeEntry(k2, "t2", 500L, 2_000L)
        // Pin k1 (oldest) so it cannot be evicted when k3 pushes total to 12,000 > 10,000.
        store.pin(k1)
        writeEntry(k3, "t3", 500L, 3_000L)

        assertNotNull("Pinned oldest entry must survive LRU eviction", store.getValid(k1, 4_000L))
        assertNull("Unpinned second-oldest entry should be evicted", store.getValid(k2, 4_000L))
        assertNotNull("Newest entry must remain", store.getValid(k3, 4_000L))

        // Corrupt k3's vocals file and verify getValid rejects & cleans it up.
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
        processor.configure(44_100, 2)

        // 1. Inactive -> byte-identical passthrough.
        val block = AudioBlock(capacityFrames = 512, channelCount = 2)
        block.frameCount = 256
        for (i in 0 until 512) block.samples[i] = 0.75f
        processor.setBlockTimestampUs(0L)
        processor.process(block)
        assertEquals(0.75f, block.samples[0], 0f)
        assertEquals(0.75f, block.samples[511], 0f)

        // 2. Active with vocalGain = 0f -> after 40 ms (> 1764 frames) crossfade, output matches instrumental stem.
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
        var frameCursor = 0L
        while (frameCursor < 2560L) {
            block.frameCount = 256
            for (i in 0 until 512) block.samples[i] = 0.75f
            processor.setBlockTimestampUs(frameCursor * 1_000_000L / 44_100L)
            processor.process(block)
            frameCursor += 256L
        }
        // After 58 ms, wetMix == 1.0 -> should equal inst (0.25, -0.25) within PCM16 quantization.
        assertEquals(0.25f, block.samples[510], 1e-3f)
        assertEquals(-0.25f, block.samples[511], 1e-3f)
        processor.reset()
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
}
