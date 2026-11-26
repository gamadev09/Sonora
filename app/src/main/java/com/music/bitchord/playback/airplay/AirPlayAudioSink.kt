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

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.music.bitchord.BuildConfig
import com.music.bitchord.playback.dlna.DlnaController
import java.nio.ByteBuffer

/**
 * Intercepts decoded/DSP-processed linear PCM buffers downstream of [com.music.bitchord.playback.audio.PrecisionAudioSink]
 * and streams them to an active AirPlay 1 / AirPlay 2 or DLNA / UPnP speaker while muting the local [delegate] AudioTrack.
 */
@UnstableApi
class AirPlayAudioSink(
    private val delegate: AudioSink,
    private val isAudible: () -> Boolean = { true },
) : ForwardingAudioSink(delegate) {

    private val converter = AirPlayPcmConverter()
    private var configuredEncoding: Int = C.ENCODING_INVALID
    private var configuredSampleRate: Int = 0
    private var configuredChannels: Int = 0
    private var requestedVolume: Float = 1f
    private var mutedByRemote: Boolean = false
    private var captureCount: Long = 0L

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        configuredEncoding = format.pcmEncoding
        configuredSampleRate = format.sampleRate
        configuredChannels = format.channelCount
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "configure enc=$configuredEncoding rate=$configuredSampleRate ch=$configuredChannels audible=${isAudible()} remote=${isRemoteStreaming()}",
            )
        }
        super.configure(audioSinkConfig)
        syncDelegateMute(isRemoteStreaming())
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val audible = isAudible()
        val airPlay = AirPlayController.isStreaming && audible
        val dlna = DlnaController.isStreaming && audible
        val streaming = airPlay || dlna
        syncDelegateMute(streaming)
        if (!streaming) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        val startPos = buffer.position()
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val endPos = buffer.position()
        val byteLen = endPos - startPos
        if (byteLen > 0) {
            val pcm = converter.convert(
                buffer = buffer,
                startOffset = startPos,
                byteLength = byteLen,
                pcmEncoding = configuredEncoding,
                sampleRate = configuredSampleRate,
                channelCount = configuredChannels,
            )
            if (pcm.isNotEmpty()) {
                captureCount++
                if (BuildConfig.DEBUG && (captureCount == 1L || captureCount % 250L == 0L)) {
                    var nonZero = false
                    for (i in 0 until minOf(pcm.size, 64)) {
                        if (pcm[i].toInt() != 0) {
                            nonZero = true
                            break
                        }
                    }
                    Log.i(
                        TAG,
                        "capture #$captureCount inBytes=$byteLen outBytes=${pcm.size} nonZero=$nonZero airPlay=$airPlay dlna=$dlna enc=$configuredEncoding rate=$configuredSampleRate",
                    )
                }
                if (airPlay) AirPlayController.writePcm(pcm)
                if (dlna) DlnaController.writePcm(pcm)
            }
        }
        return consumed
    }

    override fun setVolume(volume: Float) {
        requestedVolume = volume
        if (isRemoteStreaming()) {
            mutedByRemote = true
            super.setVolume(0f)
        } else {
            mutedByRemote = false
            super.setVolume(volume)
        }
    }

    override fun pause() {
        super.pause()
        val audible = isAudible()
        val airPlay = AirPlayController.isStreaming && audible
        val dlna = DlnaController.isStreaming && audible
        if (airPlay || dlna) {
            converter.reset()
            if (airPlay) AirPlayController.flush()
            if (dlna) DlnaController.flush()
        }
    }

    override fun flush() {
        super.flush()
        val audible = isAudible()
        val airPlay = AirPlayController.isStreaming && audible
        val dlna = DlnaController.isStreaming && audible
        if (airPlay || dlna) {
            converter.reset()
            if (airPlay) AirPlayController.flush()
            if (dlna) DlnaController.flush()
        }
    }

    override fun reset() {
        converter.reset()
        mutedByRemote = false
        super.reset()
    }

    private fun isRemoteStreaming(): Boolean =
        (AirPlayController.isStreaming || DlnaController.isStreaming) && isAudible()

    private fun syncDelegateMute(streaming: Boolean) {
        if (streaming && !mutedByRemote) {
            mutedByRemote = true
            super.setVolume(0f)
        } else if (!streaming && mutedByRemote) {
            mutedByRemote = false
            super.setVolume(requestedVolume)
        }
    }

    companion object {
        private const val TAG = "AirPlayAudioSink"
    }
}
