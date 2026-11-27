/*
 * Copyright (C) 2026 BitChord Project
 * Ported from AriaCast (GPL-3.0, https://github.com/AriaCast/AriaCast-app)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.music.bitchord.playback.dlna

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight HTTP server bound to an ephemeral port that streams continuous
 * 44.1 kHz / 16-bit / stereo PCM as `/stream.wav` and serves current track
 * artwork at `/artwork.jpg` for UPnP / DLNA renderers.
 */
class DlnaHttpServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var artworkBytes: ByteArray? = null

    private val streamClients = CopyOnWriteArrayList<Channel<ByteArray>>()

    @Synchronized
    fun start(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed && port > 0) return port

        val ss = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = ss
        port = ss.localPort
        acceptJob = scope.launch {
            Log.i(TAG, "DLNA HTTP server listening on port $port")
            while (isActive && !ss.isClosed) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    if (ss.isClosed) break else continue
                }
                launch { handleClient(client) }
            }
        }
        return port
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
            port = 0
        }
        for (channel in streamClients) {
            channel.close()
        }
        streamClients.clear()
    }

    fun pushPcm(pcm44k16Stereo: ByteArray) {
        if (pcm44k16Stereo.isEmpty() || streamClients.isEmpty()) return
        for (channel in streamClients) {
            channel.trySend(pcm44k16Stereo)
        }
    }

    fun flush() {
        for (channel in streamClients) {
            while (channel.tryReceive().isSuccess) {
                // Drain queued audio immediately on pause / seek / track skip.
            }
        }
    }

    fun artworkUrl(hostIp: String, art: ByteArray): String =
        "http://$hostIp:$port/artwork.jpg?v=${art.contentHashCode().toUInt()}"

    fun streamUrl(hostIp: String): String =
        "http://$hostIp:$port/stream.wav"

    private suspend fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.sendBufferSize = SOCKET_SEND_BUFFER_BYTES
            socket.soTimeout = 30_000

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0)?.uppercase() ?: return
            val rawTarget = parts.getOrNull(1) ?: "/"
            val path = rawTarget.substringBefore('?')
            val output = socket.getOutputStream()

            if (path.startsWith("/artwork")) {
                val bytes = artworkBytes
                if (bytes != null && bytes.isNotEmpty()) {
                    val mime = if (bytes.size > 3 &&
                        bytes[0] == 0x89.toByte() &&
                        bytes[1] == 'P'.code.toByte()
                    ) {
                        "image/png"
                    } else {
                        "image/jpeg"
                    }
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: $mime\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(resp.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") {
                        output.write(bytes)
                    }
                } else {
                    output.write(
                        "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                }
                output.flush()
                return
            }

            val ua = headers["user-agent"].orEmpty()
            val isApple = ua.contains("Apple", ignoreCase = true) ||
                ua.contains("Darwin", ignoreCase = true) ||
                ua.contains("libmpv", ignoreCase = true)
            val contentType = if (isApple) "audio/x-wav" else "audio/wav"
            Log.i(TAG, "DLNA $method $rawTarget from ${socket.inetAddress?.hostAddress} ua=${ua.take(60)}")

            val respHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Server: BitChord-DLNA/1.0 UPnP/1.0 DLNADOC/1.50\r\n" +
                "Accept-Ranges: none\r\n" +
                "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: ${DlnaProtocol.DLNA_CONTENT_FEATURES}\r\n" +
                "realTimeInfo.dlna.org: DLNA.ORG_TLAG=*\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "ICY-NAME: BitChord Stream\r\n" +
                "ICY-METADATA: 0\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
            val headerBytes = respHeaders.toByteArray(Charsets.US_ASCII)
            if (method == "HEAD") {
                output.write(headerBytes)
                output.flush()
                return
            }

            // Send HTTP 200 headers + 44-byte RIFF/WAVE header in a single write so the
            // renderer parses the format and starts buffering in one TCP round-trip.
            val wavHeader = DlnaProtocol.buildStreamingWavHeader()
            val combined = ByteArray(headerBytes.size + wavHeader.size)
            headerBytes.copyInto(combined, 0)
            wavHeader.copyInto(combined, headerBytes.size)
            output.write(combined)
            output.flush()

            val channel = Channel<ByteArray>(
                capacity = CLIENT_QUEUE_CHUNKS,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
            )
            streamClients.add(channel)
            var bytesWritten = 0L
            try {
                for (chunk in channel) {
                    output.write(chunk)
                    output.flush()
                    bytesWritten += chunk.size
                }
            } finally {
                Log.i(TAG, "DLNA stream client disconnected after $bytesWritten PCM bytes")
                streamClients.remove(channel)
                channel.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "DLNA client stream ended: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "DlnaHttpServer"
        private const val CLIENT_QUEUE_CHUNKS = 16
        private const val SOCKET_SEND_BUFFER_BYTES = 32 * 1024
    }
}
