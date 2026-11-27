package com.music.bitchord.playback.separation

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.AutomixModels
import com.music.bitchord.playback.smart.LocalAudioSource
import com.music.bitchord.ui.player.VocalSeparationController
import com.music.bitchord.ui.player.VocalSeparationUiState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide coordinator for Sonora's Audio Intelligence background vocal separation.
 *
 * Responsibilities:
 * - Resolves the active track's audio source (local file, downloaded copy, or complete
 *   cached stream rendition) without introducing a second player.
 * - Checks [StemCacheStore] first for instant offline reuse; otherwise downloads the
 *   verified Open-Unmix vocal model on demand and runs [VocalSeparationEngine] in the
 *   background while normal playback continues uninterrupted.
 * - Deduplicates requests, enforces single-job concurrency, and cancels cleanly on
 *   user toggle or track change.
 * - Publishes active [VocalSeparationProcessor.ActiveStemConfig] to all attached DSP
 *   chains (both Player A and Player B in `PlaybackService`) so stems crossfade smoothly
 *   into live playback.
 */
@UnstableApi
object VocalSeparationCoordinator : VocalSeparationController {

    private const val TAG = "VocalSeparationCoord"
    private const val DEFAULT_ACTIVE_VOCAL_GAIN = 0.0f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processors = CopyOnWriteArrayList<VocalSeparationProcessor>()
    private val jobLock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cacheStore: StemCacheStore? = null

    @Volatile
    private var engine: VocalSeparationEngine? = null

    @Volatile
    private var currentTrackId: String? = null

    @Volatile
    private var pinnedActiveKey: String? = null

    private var activeJob: Job? = null
    private var activeJobTrackId: String? = null

    private val _state = MutableStateFlow<VocalSeparationUiState>(VocalSeparationUiState.Idle)
    override val state: StateFlow<VocalSeparationUiState> = _state.asStateFlow()

    fun install(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val root = File(app.noBackupFilesDir, "vocal-stems-v1")
        val store = StemCacheStore(root)
        cacheStore = store
        engine = VocalSeparationEngine(app)
        scope.launch {
            runCatching { store.pruneAndEvict() }
        }
    }

    fun attachProcessor(processor: VocalSeparationProcessor) {
        if (!processors.contains(processor)) {
            processors.add(processor)
        }
    }

    fun detachProcessor(processor: VocalSeparationProcessor) {
        processors.remove(processor)
    }

    /**
     * Called by `PlaybackService` whenever the active track changes.
     * Disarms stem mixing for the previous track and cancels stale background work.
     */
    fun onCurrentTrackChanged(trackId: String?) {
        if (trackId == currentTrackId) return
        currentTrackId = trackId
        cancelInternal(clearProcessor = true)
    }

    override fun toggle(song: Song) {
        val trackId = song.videoId
        currentTrackId = trackId

        val current = _state.value
        when {
            current is VocalSeparationUiState.Active && current.trackId == trackId -> {
                deactivateStems()
                _state.value = VocalSeparationUiState.Idle
                return
            }
            (current is VocalSeparationUiState.DownloadingModel && current.trackId == trackId) ||
                (current is VocalSeparationUiState.Processing && current.trackId == trackId) -> {
                cancelInternal(clearProcessor = true)
                return
            }
        }

        val app = appContext
        val store = cacheStore
        val sepEngine = engine
        if (app == null || store == null || sepEngine == null) {
            _state.value = VocalSeparationUiState.Error(trackId, "Audio Intelligence not initialized")
            return
        }

        synchronized(jobLock) {
            if (activeJob?.isActive == true && activeJobTrackId == trackId) {
                return
            }
            activeJob?.cancel()
            activeJobTrackId = trackId
            activeJob = scope.launch {
                runSeparationForSong(app, store, sepEngine, song)
            }
        }
    }

    override fun setVocalGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        for (p in processors) {
            p.setVocalGain(clamped)
        }
        val cur = _state.value
        if (cur is VocalSeparationUiState.Active) {
            _state.value = cur.copy(vocalGain = clamped)
        }
    }

    override fun cancel() {
        cancelInternal(clearProcessor = true)
    }

    private fun cancelInternal(clearProcessor: Boolean) {
        synchronized(jobLock) {
            activeJob?.cancel()
            activeJob = null
            activeJobTrackId = null
        }
        if (clearProcessor) {
            deactivateStems()
        }
        _state.value = VocalSeparationUiState.Idle
    }

    private fun activateStems(entry: StemCacheStore.StemEntry) {
        val store = cacheStore ?: return
        val oldKey = pinnedActiveKey
        if (oldKey != null && oldKey != entry.key) {
            store.unpin(oldKey)
        }
        store.pin(entry.key)
        pinnedActiveKey = entry.key

        val cfg = VocalSeparationProcessor.ActiveStemConfig(
            key = entry.key,
            trackId = entry.trackId,
            vocalsFile = entry.vocalsFile,
            instFile = entry.instFile,
            stemSampleRate = entry.sampleRate,
            stemChannels = entry.channels,
            totalFrames = entry.frameCount,
            vocalGain = DEFAULT_ACTIVE_VOCAL_GAIN,
        )
        for (p in processors) {
            p.setActiveConfig(cfg)
        }
        _state.value = VocalSeparationUiState.Active(
            trackId = entry.trackId,
            vocalGain = DEFAULT_ACTIVE_VOCAL_GAIN,
        )
    }

    private fun deactivateStems() {
        for (p in processors) {
            p.setActiveConfig(null)
        }
        pinnedActiveKey?.let { key ->
            cacheStore?.unpin(key)
        }
        pinnedActiveKey = null
    }

    private data class ResolvedSource(
        val identity: String,
        val open: () -> MediaDataSource?,
    )

    private suspend fun resolveSource(app: Context, song: Song): ResolvedSource? {
        val local = song.localUri ?: Downloads.savedUri(app, song.videoId)?.toString()
            ?: song.videoId.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        if (local != null) {
            val uri = Uri.parse(local)
            val stamp = if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path))
                if (!file.isFile) return null
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
            return ResolvedSource(identity = "$uri:$stamp") {
                LocalAudioSource.open(app.contentResolver, uri)
            }
        }

        val uri = Uri.parse("bitchord://watch?v=${Uri.encode(song.videoId)}")
        val key = AudioCache.cacheKeyOf(uri)
        val rendition = AudioCache.renditionsOf(uri)
            .firstOrNull { it.key == key && it.isComplete }
            ?: AudioCache.renditionsOf(uri).firstOrNull { it.isComplete }
            ?: return null
        return ResolvedSource(identity = "${rendition.key}:${rendition.contentLength}") {
            AudioCache.renditionDataSource(uri, rendition)
        }
    }

    private suspend fun runSeparationForSong(
        app: Context,
        store: StemCacheStore,
        sepEngine: VocalSeparationEngine,
        song: Song,
    ) {
        val trackId = song.videoId
        var staging: StemCacheStore.StagingFiles? = null
        try {
            val source = resolveSource(app, song)
            if (source == null) {
                _state.value = VocalSeparationUiState.Error(
                    trackId = trackId,
                    message = "Wait for the track to finish buffering or download it first.",
                )
                return
            }

            val priority = AppSettings.audioIntelligencePriority.value
            val cacheKey = StemCacheStore.computeKey(
                sourceIdentity = source.identity,
                modelVersion = VocalSeparationEngine.MODEL_VERSION,
                priority = priority,
                sampleRate = StftProcessor.MODEL_SAMPLE_RATE,
                channels = 2,
            )

            // 1. Check persistent LRU cache first (instant offline reuse).
            store.getValid(cacheKey)?.let { cached ->
                if (currentTrackId == trackId) {
                    Log.i(TAG, "Cache hit for $trackId (key=${cacheKey.take(10)})")
                    activateStems(cached)
                }
                return
            }

            // 2. Ensure verified Open-Unmix vocal model is present on disk.
            _state.value = VocalSeparationUiState.DownloadingModel(trackId, 0f)
            val modelFile = AutomixModels.ensureVocalsModel { dlProgress ->
                if (currentTrackId == trackId) {
                    _state.value = VocalSeparationUiState.DownloadingModel(trackId, dlProgress)
                }
            }

            // 3. Separate in bounded chunks into atomic staging files.
            _state.value = VocalSeparationUiState.Processing(trackId, 0f)
            val staged = store.beginStaging(cacheKey)
            staging = staged

            val metrics = sepEngine.separateToStaging(
                modelFile = modelFile,
                openSource = source.open,
                staging = staged,
                priority = priority,
                onProgress = { progress ->
                    if (currentTrackId == trackId) {
                        _state.value = VocalSeparationUiState.Processing(trackId, progress)
                    }
                },
            )

            val committed = store.commitStaging(
                staging = staged,
                trackId = trackId,
                sourceIdentity = source.identity,
                modelVersion = VocalSeparationEngine.MODEL_VERSION,
                priority = priority,
                sampleRate = metrics.sampleRate,
                channels = metrics.channels,
                frameCount = metrics.frameCount,
            )
            staging = null

            if (committed == null) {
                _state.value = VocalSeparationUiState.Error(trackId, "Failed to save separated stems")
                return
            }

            if (currentTrackId == trackId) {
                activateStems(committed)
            }
        } catch (e: CancellationException) {
            staging?.let { store.discardStaging(it) }
            if (currentTrackId == trackId) {
                _state.value = VocalSeparationUiState.Idle
            }
            throw e
        } catch (e: Exception) {
            staging?.let { store.discardStaging(it) }
            Log.w(TAG, "Vocal separation failed for $trackId", e)
            if (currentTrackId == trackId) {
                _state.value = VocalSeparationUiState.Error(
                    trackId = trackId,
                    message = e.message ?: "Vocal separation failed",
                )
            }
        } finally {
            synchronized(jobLock) {
                if (activeJobTrackId == trackId) {
                    activeJob = null
                    activeJobTrackId = null
                }
            }
        }
    }
}
