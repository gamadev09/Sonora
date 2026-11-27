package com.music.bitchord.data.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs

/**
 * Bounded persistent cache for successful lyrics lookups.
 *
 * - Stores recording identity (videoId + normalized title & artist), provider,
 *   timing tier, duration, and retrieval time.
 * - Uses atomic, thread-safe disk writes (temp file + atomic move).
 * - Never persists transient network failures or invalid/empty lyrics.
 * - Never overwrites a higher-tier cached result from an enabled provider with
 *   a lower-tier one; allows overwriting if the cached provider is disabled or removed.
 * - Respects provider caching restrictions (e.g., web-scraped Genius stays memory-only).
 * - Preserves word timing, syllables, background vocals, and duet alignment.
 */
object LyricsCache {
    private const val MAX_MEMORY_ENTRIES = 64
    private const val MAX_DISK_ENTRIES = 150
    private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
    private const val MAX_DURATION_DRIFT_MS = 4_000L

    /** Web-scraped providers are kept in memory only, never written to disk. */
    private val NON_PERSISTABLE_SOURCES: Set<LyricsSource> = setOf(LyricsSource.GENIUS)

    /** Set by the host application (e.g. Android `cacheDir`). Null = memory-only. */
    @Volatile
    var cacheDir: File? = null

    private val diskLock = Any()

    private val memory: MutableMap<String, CachedLyricsRecord> = Collections.synchronizedMap(
        object : LinkedHashMap<String, CachedLyricsRecord>(MAX_MEMORY_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, CachedLyricsRecord>): Boolean =
                size > MAX_MEMORY_ENTRIES
        },
    )

    fun get(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        allowedSources: Set<LyricsSource>,
        nowMs: Long = System.currentTimeMillis(),
    ): LyricsRepository.Result? {
        if (allowedSources.isEmpty()) return null
        val key = buildCacheKey(videoId, title, artist)
        val record = memory[key] ?: readFromDisk(key)?.also { memory[key] = it } ?: return null

        if (nowMs - record.retrievedAtMs > CACHE_TTL_MS) {
            memory.remove(key)
            deleteFromDisk(key)
            return null
        }

        val source = runCatching { LyricsSource.valueOf(record.source) }.getOrNull() ?: return null
        if (source !in allowedSources || source.hidden) return null

        if (durationMs > 0L && record.durationMs > 0L &&
            abs(record.durationMs - durationMs) > MAX_DURATION_DRIFT_MS
        ) {
            return null
        }

        val lines = record.lines.map { it.toLyricLine() }
        if (assessLyricsQuality(lines).tier == LyricsTimingTier.INVALID) return null
        return LyricsRepository.Result(source = source, lines = lines)
    }

    fun put(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        result: LyricsRepository.Result,
        allowedSources: Set<LyricsSource> = LyricsSource.offered.toSet(),
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val quality = assessLyricsQuality(result.lines)
        if (quality.tier == LyricsTimingTier.INVALID) return

        val key = buildCacheKey(videoId, title, artist)
        val existing = memory[key] ?: readFromDisk(key)
        if (existing != null && (nowMs - existing.retrievedAtMs <= CACHE_TTL_MS)) {
            val existingSource = runCatching { LyricsSource.valueOf(existing.source) }.getOrNull()
            val existingStillUsable = existingSource != null &&
                existingSource in allowedSources &&
                !existingSource.hidden
            if (existingStillUsable) {
                val existingTier = runCatching { LyricsTimingTier.valueOf(existing.tier) }
                    .getOrDefault(LyricsTimingTier.INVALID)
                // Never downgrade superior cached lyrics from an active provider.
                if (existingTier.rank > quality.tier.rank) return
            }
        }

        val record = CachedLyricsRecord(
            recordingKey = key,
            source = result.source.name,
            tier = quality.tier.name,
            durationMs = durationMs,
            retrievedAtMs = nowMs,
            lines = result.lines.map { it.toCached() },
        )
        memory[key] = record
        if (result.source !in NON_PERSISTABLE_SOURCES) {
            writeToDiskAtomically(key, record)
        }
    }

    /** Clears only the in-memory LRU (useful for testing real disk reloads). */
    internal fun clearMemoryOnly() {
        memory.clear()
    }

    fun clear() {
        memory.clear()
        synchronized(diskLock) {
            runCatching {
                storeDir()?.listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * Includes normalized title & artist alongside videoId so corrected track
     * metadata never collides with stale videoId-only cached lyrics.
     */
    internal fun buildCacheKey(videoId: String, title: String, artist: String): String {
        val normTitle = title.forLyricsSearch().lowercase().trim()
        val normArtist = artist.artistForLyricsSearch().lowercase().trim()
        val raw = if (videoId.isNotBlank()) {
            "vid:${videoId.trim()}|t:$normTitle|a:$normArtist"
        } else {
            "meta:t:$normTitle|a:$normArtist"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun storeDir(): File? = cacheDir?.let { File(it, "sonora_lyrics_cache") }

    private fun readFromDisk(key: String): CachedLyricsRecord? = synchronized(diskLock) {
        val file = storeDir()?.let { File(it, "$key.json") } ?: return null
        if (!file.isFile) return null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            lyricsJson.decodeFromString<CachedLyricsRecord>(file.readText())
        }.getOrNull()
    }

    private fun writeToDiskAtomically(key: String, record: CachedLyricsRecord) {
        synchronized(diskLock) {
            val dir = storeDir() ?: return
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, "$key.json")
                val tmp = File(dir, "$key.${Thread.currentThread().id}.tmp")
                tmp.writeText(lyricsJson.encodeToString(record))
                try {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                trimDiskCache(dir)
            }
        }
    }

    private fun deleteFromDisk(key: String) {
        synchronized(diskLock) {
            runCatching { storeDir()?.let { File(it, "$key.json").delete() } }
        }
    }

    private fun trimDiskCache(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= MAX_DISK_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_ENTRIES)
            .forEach { it.delete() }
    }
}

@Serializable
private data class CachedLyricsRecord(
    val recordingKey: String,
    val source: String,
    val tier: String,
    val durationMs: Long,
    val retrievedAtMs: Long,
    val lines: List<CachedLyricLine>,
)

@Serializable
private data class CachedLyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<CachedLyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: CachedLyricLine? = null,
    val alignment: String = LyricAlignment.Start.name,
) {
    fun toLyricLine(): LyricLine = LyricLine(
        timeMs = timeMs,
        text = text,
        words = words.map { it.toLyricWord() },
        sungUntilMs = sungUntilMs,
        background = background?.toLyricLine(),
        alignment = runCatching { LyricAlignment.valueOf(alignment) }.getOrDefault(LyricAlignment.Start),
    )
}

@Serializable
private data class CachedLyricWord(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val syllables: List<CachedLyricSyllable> = emptyList(),
) {
    fun toLyricWord(): LyricWord = LyricWord(
        startMs = startMs,
        endMs = endMs,
        text = text,
        syllables = syllables.map { LyricSyllable(it.startMs, it.endMs, it.startChar, it.endChar) },
    )
}

@Serializable
private data class CachedLyricSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int,
)

private fun LyricLine.toCached(): CachedLyricLine = CachedLyricLine(
    timeMs = timeMs,
    text = text,
    words = words.map { w ->
        CachedLyricWord(
            startMs = w.startMs,
            endMs = w.endMs,
            text = w.text,
            syllables = w.syllables.map { s ->
                CachedLyricSyllable(s.startMs, s.endMs, s.startChar, s.endChar)
            },
        )
    },
    sungUntilMs = sungUntilMs,
    background = background?.toCached(),
    alignment = alignment.name,
)
