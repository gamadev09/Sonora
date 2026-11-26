package com.music.bitchord.playback.chords

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.Process
import android.util.AtomicFile
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.AudioDecoder
import com.music.bitchord.playback.smart.LocalAudioSource
import com.music.bitchord.ui.player.ChordAnalysisSource
import com.music.bitchord.ui.player.ChordJobSnapshot
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Explicitly requested, offline-only MVP. Never fetches a different recording. */
@androidx.annotation.OptIn(UnstableApi::class)
class AndroidChordAnalysis(context: Context) : ChordAnalysisSource {
    private val app = context.applicationContext
    private val folder = File(app.cacheDir, "chords-v${ChordAnalyzer.VERSION}")
    private val analyzeMutex = Mutex()
    private val cacheWriteMutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobFlows = ConcurrentHashMap<String, MutableStateFlow<ChordJobSnapshot>>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val memoryCache = android.util.LruCache<String, List<ChordEvent>>(32)

    private data class Input(val uri: Uri, val identity: String, val open: () -> MediaDataSource?)

    private suspend fun input(song: Song): Input? {
        val local = song.localUri ?: Downloads.savedUri(app, song.videoId)?.toString()
            ?: song.videoId.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        if (local != null) {
            val uri = Uri.parse(local)
            // Size and modification time invalidate results when a local file is replaced.
            val stamp = if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path))
                "${file.length()}:${file.lastModified()}"
            } else {
                val size = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
                val modified = runCatching {
                    app.contentResolver.query(uri, arrayOf("date_modified"), null, null, null)?.use {
                        if (it.moveToFirst()) it.getLong(0) else 0L
                    } ?: 0L
                }.getOrDefault(0L)
                "$size:$modified"
            }
            return Input(uri, "$uri:$stamp") { LocalAudioSource.open(app.contentResolver, uri) }
        }
        val uri = Uri.parse("bitchord://watch?v=${Uri.encode(song.videoId)}")
        // Do not pick the smallest sibling: it may be a different cut of this song.
        val key = AudioCache.cacheKeyOf(uri)
        val rendition = AudioCache.renditionsOf(uri).firstOrNull { it.key == key && it.isComplete } ?: return null
        return Input(uri, "${rendition.key}:${rendition.contentLength}") {
            AudioCache.renditionDataSource(uri, rendition)
        }
    }

    private fun cacheFile(input: Input): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(input.identity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(folder, "$hash.json")
    }

    override suspend fun cached(song: Song): List<ChordEvent>? = withContext(Dispatchers.IO) {
        val input = input(song) ?: return@withContext null
        memoryCache.get(input.identity)?.let { return@withContext it }
        read(cacheFile(input))?.also { memoryCache.put(input.identity, it) }
    }

    override fun jobState(song: Song): StateFlow<ChordJobSnapshot> =
        jobFlows.getOrPut(song.videoId) { MutableStateFlow(ChordJobSnapshot()) }.asStateFlow()

    override fun startBackground(song: Song): Boolean {
        val key = song.videoId
        val flow = jobFlows.getOrPut(key) { MutableStateFlow(ChordJobSnapshot()) }
        if (activeJobs[key]?.isActive == true) return true
        val prevEvents = flow.value.events
        val nextGen = flow.value.generation + 1L
        flow.value = ChordJobSnapshot(
            loading = true,
            progress = 0f,
            events = prevEvents,
            error = null,
            generation = nextGen,
        )
        lateinit var job: Job
        job = appScope.launch {
            try {
                val events = analyze(song) { progress ->
                    flow.update { snap ->
                        if (snap.loading && snap.generation == nextGen) snap.copy(progress = progress) else snap
                    }
                }
                flow.value = ChordJobSnapshot(
                    loading = false,
                    progress = 1f,
                    events = events,
                    error = null,
                    generation = nextGen,
                )
            } catch (e: CancellationException) {
                flow.value = ChordJobSnapshot(
                    loading = false,
                    progress = 0f,
                    events = prevEvents,
                    error = null,
                    generation = nextGen,
                )
                throw e
            } catch (e: Exception) {
                flow.value = ChordJobSnapshot(
                    loading = false,
                    progress = 0f,
                    events = prevEvents,
                    error = e.message ?: "Unable to analyse this recording.",
                    generation = nextGen,
                )
            } finally {
                activeJobs.remove(key, job)
            }
        }
        activeJobs[key] = job
        return true
    }

    override fun cancelBackground(song: Song) {
        activeJobs.remove(song.videoId)?.cancel()
    }

    override suspend fun analyze(song: Song, onProgress: (Float) -> Unit): List<ChordEvent> =
        withContext(Dispatchers.IO) {
            analyzeMutex.withLock {
                val input = input(song) ?: error("Download this song, or let it finish caching, then try again.")
                val events = analyzeInput(input, onProgress)
                currentCoroutineContext().ensureActive()
                if (events.isNotEmpty()) {
                    cacheWriteMutex.withLock { write(cacheFile(input), events) }
                }
                withContext(Dispatchers.Main) { onProgress(1f) }
                events
            }
        }

    private suspend fun analyzeInput(input: Input, onProgress: (Float) -> Unit): List<ChordEvent> = coroutineScope {
        val updates = Channel<Float>(Channel.CONFLATED)
        val notifier = launch(Dispatchers.Main) { for (value in updates) onProgress(value) }
        val prevPriority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val source = input.open() ?: error("This audio file cannot be opened.")
            val coroutine = currentCoroutineContext()
            source.use {
                val duration = AudioDecoder.containerDurationSeconds(it)
                    ?.takeIf { seconds -> seconds.isFinite() && seconds > 0 && seconds <= 1800 }
                    ?: error("Use a supported audio file no longer than 30 minutes.")
                val analyzer = ChordAnalyzer()
                var nextProgress = 0.0
                val decoded = AudioDecoder.streamRegion(it, 0.0, duration, abort = { !coroutine.isActive }) {
                        samples, at, rate ->
                    coroutine.ensureActive()
                    analyzer.accept(samples, at, rate)
                    if (at >= nextProgress) {
                        updates.trySend((at / duration).toFloat().coerceIn(0f, 0.99f))
                        nextProgress = at + 0.5
                    }
                }
                coroutine.ensureActive()
                check(decoded != null) { "Android could not decode this audio format." }
                analyzer.finish((duration * 1000).toLong())
            }
        } finally {
            Process.setThreadPriority(prevPriority)
            updates.close()
            notifier.cancel()
        }
    }

    private fun read(file: File): List<ChordEvent>? = runCatching {
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        check(json.getInt("version") == ChordAnalyzer.VERSION)
        val rows = json.getJSONArray("events")
        check(rows.length() in 1..10000)
        val events = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            ChordEvent(row.getLong("start"), row.getLong("end"), row.getString("chord"), row.optString("simple").takeIf { it.isNotEmpty() && it != "null" })
        }
        check(events.all { it.startMs >= 0 && it.endMs > it.startMs && it.label.matches(Regex("N|[A-G]#?(?:maj7|m7|7|m)?")) })
        check(events.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        file.setLastModified(System.currentTimeMillis())
        events
    }.getOrNull()

    private fun write(file: File, events: List<ChordEvent>) {
        folder.mkdirs()
        val rows = JSONArray()
        events.forEach { rows.put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("chord", it.label).put("simple", it.simpleLabel)) }
        val json = JSONObject().put("version", ChordAnalyzer.VERSION).put("events", rows)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        folder.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(64)?.forEach { AtomicFile(it).delete() }
    }
}
