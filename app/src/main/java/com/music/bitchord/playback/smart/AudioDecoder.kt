/*
 * Modeled on Orchard's own AudioDecoder (https://github.com/SFG5453/Orchard),
 * with platform MediaExtractor/MediaCodec primary decoding and Media3 extractor +
 * vendored FFmpeg audio decoder fallback for formats or containers the platform
 * decoder cannot handle cleanly.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Decodes a region of a cached or local track to mono or stereo float PCM.
 *
 * Uses Android's MediaExtractor + MediaCodec as the fast primary path (plus direct
 * PCM passthrough for uncompressed WAV/raw streams), and automatically falls back
 * to Media3 container demuxing and the vendored [FfmpegAudioDecoder] when the
 * platform path fails or cannot decode the format.
 */
@UnstableApi
object AudioDecoder {

    private const val TAG = "BitChordAudioDecoder"
    private const val TIMEOUT_US = 10_000L
    private const val NO_PROGRESS_TIMEOUT_MS = 8_000L
    private const val RAW_READ_BUFFER_BYTES = 64 * 1024
    private const val FFMPEG_INPUT_BUFFERS = 8
    private const val FFMPEG_OUTPUT_BUFFERS = 8
    private const val FFMPEG_INITIAL_INPUT_BYTES = 16 * 1024

    /** Decoded mono PCM at the container's own sample rate; the caller resamples. */
    data class Pcm(val samples: FloatArray, val sampleRate: Double)

    /** Decoded planar stereo PCM at the container's own sample rate. */
    data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double)

    enum class DecodeFailure {
        INCOMPLETE_SOURCE,
        CORRUPT_MEDIA,
        UNSUPPORTED_FORMAT,
        DECODER_ERROR,
        ABORTED,
    }

    sealed interface DecodeOutcome<out T> {
        data class Success<T>(val value: T) : DecodeOutcome<T>
        data class Failure(val reason: DecodeFailure, val detail: String? = null) : DecodeOutcome<Nothing>
    }

    /**
     * Reads the audio duration a container advertises, without decoding it.
     */
    fun containerDurationSeconds(source: MediaDataSource): Double? {
        val extractor = MediaExtractor()
        val platformDuration = try {
            extractor.setDataSource(source)
            (0 until extractor.trackCount)
                .mapNotNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: return@mapNotNull null
                    if (!mime.startsWith("audio/") || !format.containsKey(MediaFormat.KEY_DURATION)) {
                        return@mapNotNull null
                    }
                    format.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 }?.div(1_000_000.0)
                }
                .maxOrNull()
                ?.takeIf { it.isFinite() && it > 0 }
        } catch (error: Exception) {
            Log.w(TAG, "MediaExtractor could not read duration", error)
            null
        } finally {
            runCatching { extractor.release() }
        }
        return platformDuration
    }

    /**
     * Reads the audio duration using fresh source instances, falling back to Media3
     * container probing when Android's [MediaExtractor] cannot parse the container.
     */
    fun containerDurationSecondsWithFallback(openSource: () -> MediaDataSource?): Double? {
        val first = openSource() ?: return null
        val platform = first.use { containerDurationSeconds(it) }
        if (platform != null) return platform

        val second = openSource() ?: return null
        return second.use { probeDurationWithMedia3(it) }
    }

    /**
     * Decodes [startSeconds] to [endSeconds] of [source], downmixed to mono at
     * the container's native rate.
     */
    fun decodeRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
    ): Pair<Pcm, Double>? {
        val chunks = ArrayList<FloatArray>()
        val outcome = decodePlatform(source, startSeconds, endSeconds, abort) { buffer, info, channels, _, pcmEncoding ->
            chunks += toMono(buffer, info, channels, pcmEncoding)
        }
        if (outcome is DecodeOutcome.Success) {
            return Pcm(flatten(chunks), outcome.value.first) to outcome.value.second
        }
        if (outcome is DecodeOutcome.Failure && outcome.reason == DecodeFailure.ABORTED) return null
        chunks.clear()
        val fallback = decodeFfmpegWithMedia3(source, startSeconds, endSeconds, abort) { samples, _, _ ->
            chunks += samples
        }
        return if (fallback is DecodeOutcome.Success) {
            Pcm(flatten(chunks), fallback.value.first) to fallback.value.second
        } else {
            null
        }
    }

    /**
     * As [decodeRegion], but keeping the two channels apart.
     */
    fun decodeRegionStereo(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
    ): Pair<StereoPcm, Double>? {
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        val outcome = decodePlatform(source, startSeconds, endSeconds, abort) { buffer, info, channels, _, pcmEncoding ->
            toStereo(buffer, info, channels, pcmEncoding, left, right)
        }
        if (outcome is DecodeOutcome.Success) {
            return StereoPcm(flatten(left), flatten(right), outcome.value.first) to outcome.value.second
        }
        if (outcome is DecodeOutcome.Failure && outcome.reason == DecodeFailure.ABORTED) return null
        left.clear()
        right.clear()
        val fallback = decodeFfmpegStereoWithMedia3(source, startSeconds, endSeconds, abort, left, right)
        return if (fallback is DecodeOutcome.Success) {
            StereoPcm(flatten(left), flatten(right), fallback.value.first) to fallback.value.second
        } else {
            null
        }
    }

    /**
     * Streams decoded mono PCM chunks from [source] into [onChunk].
     */
    fun streamRegion(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
        onChunk: (samples: FloatArray, atSeconds: Double, sampleRate: Int) -> Unit,
    ): Pair<Double, Double>? {
        var shortScratch = ShortArray(0)
        val outcome = decodePlatform(source, startSeconds, endSeconds, abort) { buffer, info, channels, rate, pcmEncoding ->
            val needed = if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) (info.size / 2).coerceAtLeast(0) else 0
            if (needed > shortScratch.size) shortScratch = ShortArray(needed)
            val mono = toMono(buffer, info, channels, pcmEncoding, if (needed > 0) shortScratch else null)
            if (mono.isNotEmpty()) {
                onChunk(mono, (info.presentationTimeUs.coerceAtLeast(0L)) / 1_000_000.0, rate)
            }
        }
        return (outcome as? DecodeOutcome.Success)?.value
    }

    /**
     * Streams decoded mono PCM chunks using fresh [MediaDataSource] instances per stage.
     *
     * Calls [onReset] before any fallback attempt so callers can discard partial state
     * from a failed primary attempt and never mix partial results.
     */
    fun streamRegionWithFallback(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean = { false },
        onReset: () -> Unit = {},
        onChunk: (samples: FloatArray, atSeconds: Double, sampleRate: Int) -> Unit,
    ): DecodeOutcome<Pair<Double, Double>> {
        if (endSeconds <= startSeconds) {
            return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Invalid decode range")
        }
        val primarySource = openSource()
            ?: return DecodeOutcome.Failure(DecodeFailure.INCOMPLETE_SOURCE, "Audio source could not be opened")

        var shortScratch = ShortArray(0)
        val primary = primarySource.use { src ->
            decodePlatform(src, startSeconds, endSeconds, abort) { buffer, info, channels, rate, pcmEncoding ->
                val needed = if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) (info.size / 2).coerceAtLeast(0) else 0
                if (needed > shortScratch.size) shortScratch = ShortArray(needed)
                val mono = toMono(buffer, info, channels, pcmEncoding, if (needed > 0) shortScratch else null)
                if (mono.isNotEmpty()) {
                    onChunk(mono, (info.presentationTimeUs.coerceAtLeast(0L)) / 1_000_000.0, rate)
                }
            }
        }
        if (primary is DecodeOutcome.Success) return primary
        val primaryFailure = primary as DecodeOutcome.Failure
        if (primaryFailure.reason == DecodeFailure.ABORTED || abort()) {
            return DecodeOutcome.Failure(DecodeFailure.ABORTED, "Cancelled")
        }

        Log.w(TAG, "Primary MediaCodec decode failed (${primaryFailure.reason}: ${primaryFailure.detail}); attempting FFmpeg/Media3 fallback")
        onReset()

        val fallbackSource = openSource()
            ?: return DecodeOutcome.Failure(DecodeFailure.INCOMPLETE_SOURCE, "Audio source could not be reopened for fallback")
        val fallback = fallbackSource.use { src ->
            decodeFfmpegWithMedia3(src, startSeconds, endSeconds, abort, onChunk)
        }
        if (fallback is DecodeOutcome.Success) {
            Log.i(TAG, "FFmpeg/Media3 fallback decode succeeded")
            return fallback
        }
        val fallbackFailure = fallback as DecodeOutcome.Failure
        if (fallbackFailure.reason == DecodeFailure.ABORTED) return fallbackFailure
        Log.w(TAG, "FFmpeg/Media3 fallback also failed (${fallbackFailure.reason}: ${fallbackFailure.detail})")
        return if (primaryFailure.reason == DecodeFailure.UNSUPPORTED_FORMAT &&
            fallbackFailure.reason == DecodeFailure.UNSUPPORTED_FORMAT
        ) {
            fallbackFailure
        } else if (fallbackFailure.reason == DecodeFailure.CORRUPT_MEDIA ||
            primaryFailure.reason == DecodeFailure.CORRUPT_MEDIA
        ) {
            DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, fallbackFailure.detail ?: primaryFailure.detail)
        } else {
            fallbackFailure
        }
    }

    /** Concatenates the decoded chunks into one contiguous buffer. */
    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val samples = FloatArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return samples
    }

    private fun decodePlatform(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean,
        onBuffer: (ByteBuffer, MediaCodec.BufferInfo, Int, Int, Int) -> Unit,
    ): DecodeOutcome<Pair<Double, Double>> {
        if (endSeconds <= startSeconds) {
            return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Invalid range")
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "No audio track in container")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Missing MIME type")

            val startUs = (startSeconds * 1_000_000).toLong()
            val endUs = (endSeconds * 1_000_000).toLong()
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            if (mime == MimeTypes.AUDIO_RAW) {
                return streamRawPcmFromExtractor(extractor, format, endUs, abort, onBuffer)
            }

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: Exception) {
                Log.w(TAG, "No MediaCodec decoder for $mime", error)
                return DecodeOutcome.Failure(DecodeFailure.UNSUPPORTED_FORMAT, "No platform decoder for $mime")
            }

            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (error: Exception) {
                Log.w(TAG, "MediaCodec configure/start failed for $mime", error)
                return DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, "Codec configure failed for $mime: ${error.message}")
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var outputRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var outputPcmEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
            var actualStartSeconds = -1.0
            var sawFirstSample = false
            var inputDone = false
            var outputDone = false
            var lastProgressMs = SystemClock.elapsedRealtime()

            while (!outputDone) {
                if (abort()) return DecodeOutcome.Failure(DecodeFailure.ABORTED, "Aborted")

                var progressed = false
                while (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(0L)
                    if (inputIndex < 0) break
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: break
                    progressed = true
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val sampleTimeUs = extractor.sampleTime
                    if (sampleSize < 0 || (sampleTimeUs in 0..Long.MAX_VALUE && sampleTimeUs > endUs)) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }

                var timeout = if (progressed) 0L else TIMEOUT_US
                while (!outputDone) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)
                    if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    timeout = 0L
                    progressed = true
                    when (outputIndex) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = codec.outputFormat
                            outputRate = newFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputRate
                            outputChannels = newFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                            outputPcmEncoding = newFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: outputPcmEncoding
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (outputIndex >= 0) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                            if (bufferInfo.size > 0 && outputRate > 0) {
                                if (!sawFirstSample) {
                                    actualStartSeconds = bufferInfo.presentationTimeUs.coerceAtLeast(0L) / 1_000_000.0
                                    sawFirstSample = true
                                }
                                codec.getOutputBuffer(outputIndex)?.let { output ->
                                    onBuffer(output, bufferInfo, outputChannels, outputRate, outputPcmEncoding)
                                }
                                if (bufferInfo.presentationTimeUs > endUs) outputDone = true
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                val now = SystemClock.elapsedRealtime()
                if (progressed) {
                    lastProgressMs = now
                } else if (now - lastProgressMs > NO_PROGRESS_TIMEOUT_MS) {
                    Log.w(TAG, "MediaCodec stalled for ${NO_PROGRESS_TIMEOUT_MS}ms while decoding $mime")
                    return DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, "Decoder timed out with no progress")
                }
            }

            if (!sawFirstSample || outputRate <= 0) {
                return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "No audio samples decoded")
            }
            return DecodeOutcome.Success(outputRate.toDouble() to actualStartSeconds)
        } catch (error: IOException) {
            Log.w(TAG, "Platform decode I/O failure", error)
            return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "Platform region decode failed", error)
            return DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, error.message)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun streamRawPcmFromExtractor(
        extractor: MediaExtractor,
        format: MediaFormat,
        endUs: Long,
        abort: () -> Boolean,
        onBuffer: (ByteBuffer, MediaCodec.BufferInfo, Int, Int, Int) -> Unit,
    ): DecodeOutcome<Pair<Double, Double>> {
        val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
        val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
        val pcmEncoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING) ?: AudioFormat.ENCODING_PCM_16BIT
        if (sampleRate <= 0) {
            return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Raw PCM track missing sample rate")
        }
        val readBuf = ByteBuffer.allocate(RAW_READ_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val info = MediaCodec.BufferInfo()
        var sawFirst = false
        var actualStart = 0.0
        while (true) {
            if (abort()) return DecodeOutcome.Failure(DecodeFailure.ABORTED, "Aborted")
            readBuf.clear()
            val size = extractor.readSampleData(readBuf, 0)
            if (size < 0) break
            val ptsUs = extractor.sampleTime.coerceAtLeast(0L)
            if (ptsUs > endUs) break
            if (size > 0) {
                if (!sawFirst) {
                    actualStart = ptsUs / 1_000_000.0
                    sawFirst = true
                }
                info.set(0, size, ptsUs, 0)
                onBuffer(readBuf, info, channels, sampleRate, pcmEncoding)
            }
            if (!extractor.advance()) break
        }
        return if (sawFirst) {
            DecodeOutcome.Success(sampleRate.toDouble() to actualStart)
        } else {
            DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "No raw PCM samples read")
        }
    }

    /**
     * Media3 container demuxer + [FfmpegAudioDecoder] (or direct PCM passthrough for
     * uncompressed WAV) fallback.
     *
     * Handles containers Android's [MediaExtractor] rejects (e.g. Ogg/Opus, WebM variants,
     * fragmented MP4, FLAC, ALAC) and codecs platform MediaCodec cannot decode.
     */
    private fun decodeFfmpegWithMedia3(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean,
        onChunk: (samples: FloatArray, atSeconds: Double, sampleRate: Int) -> Unit,
    ): DecodeOutcome<Pair<Double, Double>> {
        return demuxAndDecodeWithMedia3(source, startSeconds, endSeconds, abort) { buffer, size, ptsUs, channels, sampleRate, pcmEncoding ->
            val info = MediaCodec.BufferInfo().apply { set(0, size, ptsUs, 0) }
            val mono = toMono(buffer, info, channels, pcmEncoding)
            if (mono.isNotEmpty()) {
                onChunk(mono, ptsUs.coerceAtLeast(0L) / 1_000_000.0, sampleRate)
            }
        }
    }

    private fun decodeFfmpegStereoWithMedia3(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean,
        left: MutableList<FloatArray>,
        right: MutableList<FloatArray>,
    ): DecodeOutcome<Pair<Double, Double>> {
        return demuxAndDecodeWithMedia3(source, startSeconds, endSeconds, abort) { buffer, size, ptsUs, channels, _, pcmEncoding ->
            val info = MediaCodec.BufferInfo().apply { set(0, size, ptsUs, 0) }
            toStereo(buffer, info, channels, pcmEncoding, left, right)
        }
    }

    private fun probeDurationWithMedia3(source: MediaDataSource): Double? {
        val adapter = MediaDataSourceReader(source)
        val extractors = DefaultExtractorsFactory().createExtractors()
        var chosen: Extractor? = null
        var seekMapDurationUs = C.TIME_UNSET
        try {
            val length = adapter.length
            val probeInput = DefaultExtractorInput(adapter, 0L, length)
            for (candidate in extractors) {
                try {
                    if (candidate.sniff(probeInput)) {
                        chosen = candidate
                        break
                    }
                } catch (_: Exception) {
                    // Try next extractor.
                } finally {
                    probeInput.resetPeekPosition()
                }
            }
            val extractor = chosen ?: return null
            var foundSeekMap = false
            extractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput = DiscardTrackOutput
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {
                    seekMapDurationUs = seekMap.durationUs
                    foundSeekMap = true
                }
            })
            adapter.seek(0L)
            var input = DefaultExtractorInput(adapter, 0L, length)
            val positionHolder = PositionHolder()
            var steps = 0
            while (!foundSeekMap && steps++ < 256) {
                val res = extractor.read(input, positionHolder)
                if (res == Extractor.RESULT_SEEK) {
                    adapter.seek(positionHolder.position)
                    input = DefaultExtractorInput(adapter, positionHolder.position, length)
                } else if (res == Extractor.RESULT_END_OF_INPUT) {
                    break
                }
            }
            return seekMapDurationUs.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1_000_000.0)
        } catch (error: Exception) {
            Log.w(TAG, "Media3 duration probe failed", error)
            return null
        } finally {
            runCatching { chosen?.release() }
        }
    }

    private fun demuxAndDecodeWithMedia3(
        source: MediaDataSource,
        startSeconds: Double,
        endSeconds: Double,
        abort: () -> Boolean,
        onPcm: (buffer: ByteBuffer, size: Int, ptsUs: Long, channels: Int, sampleRate: Int, pcmEncoding: Int) -> Unit,
    ): DecodeOutcome<Pair<Double, Double>> {
        val adapter = MediaDataSourceReader(source)
        val length = adapter.length
        if (length == 0L) {
            return DecodeOutcome.Failure(DecodeFailure.INCOMPLETE_SOURCE, "Empty audio source")
        }

        val extractors = DefaultExtractorsFactory().createExtractors()
        var extractor: Extractor? = null
        var decoder: FfmpegAudioDecoder? = null
        try {
            val probeInput = DefaultExtractorInput(adapter, 0L, length)
            for (candidate in extractors) {
                try {
                    if (candidate.sniff(probeInput)) {
                        extractor = candidate
                        break
                    }
                } catch (_: Exception) {
                    // Try next extractor.
                } finally {
                    probeInput.resetPeekPosition()
                }
            }
            val activeExtractor = extractor
                ?: return DecodeOutcome.Failure(DecodeFailure.UNSUPPORTED_FORMAT, "No container extractor matched")

            val startUs = (startSeconds * 1_000_000).toLong()
            val endUs = (endSeconds * 1_000_000).toLong()

            var audioFormat: Format? = null
            var audioTrackId = -1
            var seekMap: SeekMap? = null
            var sawFirstSample = false
            var actualStartSeconds = -1.0
            var outputSampleRate = 0
            var outputChannels = 1
            var stopReading = false
            var fatalFailure: DecodeOutcome.Failure? = null

            fun ensureDecoder(fmt: Format): FfmpegAudioDecoder? {
                if (decoder != null) return decoder
                val mime = fmt.sampleMimeType ?: run {
                    fatalFailure = DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Missing MIME type")
                    return null
                }
                if (mime == MimeTypes.AUDIO_RAW) return null
                if (!FfmpegLibrary.isAvailable()) {
                    fatalFailure = DecodeOutcome.Failure(DecodeFailure.UNSUPPORTED_FORMAT, "FFmpeg native library unavailable")
                    return null
                }
                if (!FfmpegLibrary.supportsFormat(mime)) {
                    fatalFailure = DecodeOutcome.Failure(DecodeFailure.UNSUPPORTED_FORMAT, "FFmpeg does not support $mime")
                    return null
                }
                if (requiresInitData(mime) && fmt.initializationData.isEmpty()) {
                    fatalFailure = DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "Missing codec init data for $mime")
                    return null
                }
                return try {
                    FfmpegAudioDecoder(
                        fmt,
                        FFMPEG_INPUT_BUFFERS,
                        FFMPEG_OUTPUT_BUFFERS,
                        FFMPEG_INITIAL_INPUT_BYTES,
                        /* outputFloat = */ true,
                    ).also { decoder = it }
                } catch (error: Exception) {
                    Log.w(TAG, "FfmpegAudioDecoder init failed for $mime", error)
                    fatalFailure = DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, error.message)
                    null
                }
            }

            fun drainDecoder(dec: FfmpegAudioDecoder, waitMs: Long) {
                val deadline = SystemClock.elapsedRealtime() + waitMs
                while (true) {
                    val out = dec.dequeueOutputBuffer()
                    if (out == null) {
                        if (waitMs <= 0L || SystemClock.elapsedRealtime() >= deadline || abort()) break
                        Thread.sleep(2L)
                        continue
                    }
                    try {
                        if (out.isEndOfStream) break
                        val data = out.data
                        if (!out.shouldBeSkipped && data != null && data.remaining() > 0) {
                            val sr = dec.sampleRate.takeIf { it > 0 } ?: (audioFormat?.sampleRate ?: 0)
                            val ch = dec.channelCount.takeIf { it > 0 } ?: (audioFormat?.channelCount ?: 1)
                            if (sr > 0) {
                                outputSampleRate = sr
                                outputChannels = ch
                                val pts = out.timeUs.coerceAtLeast(0L)
                                if (pts >= startUs && pts <= endUs) {
                                    if (!sawFirstSample) {
                                        actualStartSeconds = pts / 1_000_000.0
                                        sawFirstSample = true
                                    }
                                    val encoding = if (dec.encoding == C.ENCODING_PCM_FLOAT) {
                                        AudioFormat.ENCODING_PCM_FLOAT
                                    } else {
                                        AudioFormat.ENCODING_PCM_16BIT
                                    }
                                    onPcm(data, data.remaining(), pts, ch, sr, encoding)
                                }
                                if (pts > endUs) stopReading = true
                            }
                        }
                    } finally {
                        out.release()
                    }
                }
            }

            val sampleAccumulator = ByteArrayOutputStreamWithBuffer()

            val trackOutput = object : TrackOutput {
                override fun format(format: Format) {
                    if (audioFormat == null && format.sampleMimeType?.startsWith("audio/") == true) {
                        audioFormat = format
                        outputSampleRate = format.sampleRate.coerceAtLeast(0)
                        outputChannels = format.channelCount.coerceAtLeast(1)
                    }
                }

                override fun sampleData(
                    input: DataReader,
                    length: Int,
                    allowEndOfInput: Boolean,
                    sampleDataPart: Int,
                ): Int {
                    val buf = ByteArray(length.coerceAtMost(RAW_READ_BUFFER_BYTES))
                    val read = input.read(buf, 0, buf.size)
                    if (read == C.RESULT_END_OF_INPUT) {
                        if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                        throw EOFException()
                    }
                    sampleAccumulator.write(buf, 0, read)
                    return read
                }

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                    sampleAccumulator.write(data.data, data.position, length)
                    data.skipBytes(length)
                }

                override fun sampleMetadata(
                    timeUs: Long,
                    flags: Int,
                    size: Int,
                    offset: Int,
                    cryptoData: TrackOutput.CryptoData?,
                ) {
                    val packetBytes = sampleAccumulator.takeBytes()
                    if (stopReading || fatalFailure != null) return
                    if (abort()) {
                        fatalFailure = DecodeOutcome.Failure(DecodeFailure.ABORTED, "Aborted")
                        stopReading = true
                        return
                    }
                    val fmt = audioFormat ?: return
                    if (timeUs > endUs) {
                        stopReading = true
                        return
                    }
                    val sliceStart = (packetBytes.size - offset - size).coerceAtLeast(0)
                    val sliceLen = size.coerceAtMost(packetBytes.size - sliceStart).coerceAtLeast(0)
                    if (sliceLen <= 0) return

                    if (fmt.sampleMimeType == MimeTypes.AUDIO_RAW) {
                        val sr = fmt.sampleRate.takeIf { it > 0 } ?: return
                        val ch = fmt.channelCount.takeIf { it > 0 } ?: 1
                        outputSampleRate = sr
                        outputChannels = ch
                        val pts = timeUs.coerceAtLeast(0L)
                        if (pts >= startUs) {
                            if (!sawFirstSample) {
                                actualStartSeconds = pts / 1_000_000.0
                                sawFirstSample = true
                            }
                            val bb = ByteBuffer.wrap(packetBytes, sliceStart, sliceLen).order(ByteOrder.LITTLE_ENDIAN)
                            onPcm(bb, sliceLen, pts, ch, sr, media3PcmToAndroid(fmt.pcmEncoding))
                        }
                        return
                    }

                    val dec = ensureDecoder(fmt) ?: run {
                        stopReading = true
                        return
                    }

                    val waitStart = SystemClock.elapsedRealtime()
                    var inBuf = dec.dequeueInputBuffer()
                    while (inBuf == null) {
                        drainDecoder(dec, 0L)
                        if (abort()) {
                            fatalFailure = DecodeOutcome.Failure(DecodeFailure.ABORTED, "Aborted")
                            stopReading = true
                            return
                        }
                        if (SystemClock.elapsedRealtime() - waitStart > NO_PROGRESS_TIMEOUT_MS) {
                            fatalFailure = DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, "FFmpeg decoder stalled")
                            stopReading = true
                            return
                        }
                        Thread.sleep(1L)
                        inBuf = dec.dequeueInputBuffer()
                    }
                    inBuf.ensureSpaceForWrite(sliceLen)
                    inBuf.data?.let { dst ->
                        dst.clear()
                        dst.put(packetBytes, sliceStart, sliceLen)
                        dst.flip()
                    }
                    inBuf.timeUs = timeUs.coerceAtLeast(0L)
                    dec.queueInputBuffer(inBuf)
                    drainDecoder(dec, 0L)
                }
            }

            activeExtractor.init(object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    if (type == C.TRACK_TYPE_AUDIO && audioTrackId == -1) {
                        audioTrackId = id
                        return trackOutput
                    }
                    return DiscardTrackOutput
                }

                override fun endTracks() {}

                override fun seekMap(map: SeekMap) {
                    seekMap = map
                }
            })

            adapter.seek(0L)
            var input = DefaultExtractorInput(adapter, 0L, length)
            val positionHolder = PositionHolder()
            var didSeekToStart = startUs <= 0L

            while (!stopReading && fatalFailure == null) {
                if (abort()) {
                    return DecodeOutcome.Failure(DecodeFailure.ABORTED, "Aborted")
                }
                if (!didSeekToStart && seekMap != null && audioFormat != null) {
                    didSeekToStart = true
                    val map = seekMap!!
                    if (map.isSeekable) {
                        val seekPoints = map.getSeekPoints(startUs)
                        val pos = seekPoints.first.position
                        activeExtractor.seek(pos, startUs)
                        adapter.seek(pos)
                        input = DefaultExtractorInput(adapter, pos, length)
                        continue
                    }
                }
                val result = activeExtractor.read(input, positionHolder)
                if (result == Extractor.RESULT_SEEK) {
                    adapter.seek(positionHolder.position)
                    input = DefaultExtractorInput(adapter, positionHolder.position, length)
                } else if (result == Extractor.RESULT_END_OF_INPUT) {
                    break
                }
            }

            if (fatalFailure != null) return fatalFailure!!

            decoder?.let { dec ->
                val eosBuf = dec.dequeueInputBuffer()
                if (eosBuf != null) {
                    eosBuf.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    dec.queueInputBuffer(eosBuf)
                }
                drainDecoder(dec, 500L)
            }

            if (!sawFirstSample || outputSampleRate <= 0) {
                return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, "No audio samples decoded by FFmpeg fallback")
            }
            return DecodeOutcome.Success(outputSampleRate.toDouble() to actualStartSeconds)
        } catch (error: IOException) {
            Log.w(TAG, "Media3/FFmpeg decode I/O error", error)
            return DecodeOutcome.Failure(DecodeFailure.CORRUPT_MEDIA, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "Media3/FFmpeg decode error", error)
            return DecodeOutcome.Failure(DecodeFailure.DECODER_ERROR, error.message)
        } finally {
            runCatching { decoder?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun requiresInitData(mime: String): Boolean = when (mime) {
        MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_VORBIS -> true
        else -> false
    }

    private fun media3PcmToAndroid(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> AudioFormat.ENCODING_PCM_8BIT
        C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_24BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_24BIT_PACKED else AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_32BIT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) AudioFormat.ENCODING_PCM_32BIT else AudioFormat.ENCODING_PCM_16BIT
        C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
        else -> AudioFormat.ENCODING_PCM_16BIT
    }

    /** Downmixes one PCM output buffer (16-bit, float, 8-bit, 24-bit, or 32-bit) to mono float in [-1, 1]. */
    private fun toMono(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        scratch: ShortArray? = null,
    ): FloatArray {
        val safeChannels = max(1, channels)
        val slice = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit((info.offset + info.size).coerceAtMost(capacity()))
        }
        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = slice.asFloatBuffer()
                val frames = floats.remaining() / safeChannels
                if (frames <= 0) return FloatArray(0)
                val mono = FloatArray(frames)
                val invChannels = 1f / safeChannels
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until safeChannels) sum += floats.get()
                    mono[i] = (sum * invChannels).coerceIn(-1f, 1f)
                }
                mono
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = slice.remaining() / safeChannels
                if (frames <= 0) return FloatArray(0)
                val mono = FloatArray(frames)
                val scale = 1f / (128f * safeChannels)
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until safeChannels) sum += (slice.get().toInt() and 0xFF) - 128
                    mono[i] = sum * scale
                }
                mono
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val bytesPerFrame = 3 * safeChannels
                val frames = slice.remaining() / bytesPerFrame
                if (frames <= 0) return FloatArray(0)
                val mono = FloatArray(frames)
                val scale = 1f / (8388608f * safeChannels)
                for (i in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until safeChannels) {
                        val b0 = slice.get().toInt() and 0xFF
                        val b1 = slice.get().toInt() and 0xFF
                        val b2 = slice.get().toInt()
                        val sample = (b2 shl 16) or (b1 shl 8) or b0
                        sum += sample
                    }
                    mono[i] = (sum * scale).coerceIn(-1f, 1f)
                }
                mono
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ints = slice.asIntBuffer()
                val frames = ints.remaining() / safeChannels
                if (frames <= 0) return FloatArray(0)
                val mono = FloatArray(frames)
                val scale = 1.0 / (2147483648.0 * safeChannels)
                for (i in 0 until frames) {
                    var sum = 0.0
                    for (c in 0 until safeChannels) sum += ints.get().toDouble()
                    mono[i] = (sum * scale).toFloat().coerceIn(-1f, 1f)
                }
                mono
            }
            else -> {
                val shorts = slice.asShortBuffer()
                val totalShorts = shorts.remaining()
                val frames = totalShorts / safeChannels
                if (frames <= 0) return FloatArray(0)
                val needed = frames * safeChannels
                val raw = if (scratch != null && scratch.size >= needed) scratch else ShortArray(needed)
                shorts.get(raw, 0, needed)
                val mono = FloatArray(frames)
                val scale = 1f / 32768f
                when (safeChannels) {
                    1 -> for (i in 0 until frames) mono[i] = raw[i] * scale
                    2 -> {
                        val stereoScale = 0.5f * scale
                        var src = 0
                        for (i in 0 until frames) {
                            mono[i] = (raw[src] + raw[src + 1]) * stereoScale
                            src += 2
                        }
                    }
                    else -> {
                        val invChannelsScale = scale / safeChannels
                        var src = 0
                        for (i in 0 until frames) {
                            var sum = 0
                            for (c in 0 until safeChannels) sum += raw[src + c]
                            mono[i] = sum * invChannelsScale
                            src += safeChannels
                        }
                    }
                }
                mono
            }
        }
    }

    /** Splits one PCM output buffer into planar left/right float in [-1, 1]. */
    private fun toStereo(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int,
        left: MutableList<FloatArray>,
        right: MutableList<FloatArray>,
    ) {
        val safeChannels = max(1, channels)
        val slice = buffer.duplicate().apply {
            order(ByteOrder.LITTLE_ENDIAN)
            position(info.offset)
            limit((info.offset + info.size).coerceAtMost(capacity()))
        }
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = slice.asFloatBuffer()
                val frames = floats.remaining() / safeChannels
                if (frames <= 0) return
                val leftChunk = FloatArray(frames)
                val rightChunk = FloatArray(frames)
                val frame = FloatArray(safeChannels)
                for (index in 0 until frames) {
                    floats.get(frame, 0, safeChannels)
                    leftChunk[index] = frame[0].coerceIn(-1f, 1f)
                    rightChunk[index] = (if (safeChannels > 1) frame[1] else frame[0]).coerceIn(-1f, 1f)
                }
                left += leftChunk
                right += rightChunk
            }
            else -> {
                val shorts = slice.asShortBuffer()
                val frames = shorts.remaining() / safeChannels
                if (frames <= 0) return
                val leftChunk = FloatArray(frames)
                val rightChunk = FloatArray(frames)
                val frame = ShortArray(safeChannels)
                for (index in 0 until frames) {
                    shorts.get(frame, 0, safeChannels)
                    leftChunk[index] = frame[0] / 32768f
                    rightChunk[index] = (if (safeChannels > 1) frame[1] else frame[0]) / 32768f
                }
                left += leftChunk
                right += rightChunk
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private class MediaDataSourceReader(private val source: MediaDataSource) : DataReader {
        val length: Long = runCatching { source.size }.getOrDefault(C.LENGTH_UNSET.toLong())
            .let { if (it < 0L) C.LENGTH_UNSET.toLong() else it }
        private var position: Long = 0L

        fun seek(newPosition: Long) {
            position = newPosition.coerceAtLeast(0L)
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            if (readLength == 0) return 0
            val count = source.readAt(position, buffer, offset, readLength)
            if (count <= 0) return C.RESULT_END_OF_INPUT
            position += count
            return count
        }
    }

    private object DiscardTrackOutput : TrackOutput {
        override fun format(format: Format) {}
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val scratch = ByteArray(length.coerceAtMost(4096))
            val read = input.read(scratch, 0, scratch.size)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            return read
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }
        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {}
    }

    private class ByteArrayOutputStreamWithBuffer : java.io.ByteArrayOutputStream(4096) {
        fun takeBytes(): ByteArray {
            val out = toByteArray()
            reset()
            return out
        }
    }
}
