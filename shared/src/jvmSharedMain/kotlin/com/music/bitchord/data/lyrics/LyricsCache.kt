package com.music.bitchord.data.lyrics

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.abs

/**
 * Bounded persistent cache for successful lyrics lookups.
 *
 * - Stores recording identity, provider, timing tier, duration, and retrieval time.
 * - Never persists transient network failures or invalid/empty lyrics.
 * - Never overwrites a higher-tier cached result with a lower-tier one.
 * - Enforces a bounded LRU capacity on both memory and disk.
 */
object LyricsCache {
    private const val MAX_MEMORY_ENTRIES = 64
    private const val MAX_DISK_ENTRIES = 150
    private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
    private const val MAX_DURATION_DRIFT_MS = 4_000L

    /** Set by the host application (e.g. Android `cacheDir`). Null = memory-only. */
    @Volatile
    var cacheDir: File? = null

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
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val quality = assessLyricsQuality(result.lines)
        if (quality.tier == LyricsTimingTier.INVALID) return

        val key = buildCacheKey(videoId, title, artist)
        val existing = memory[key] ?: readFromDisk(key)
        if (existing != null && (nowMs - existing.retrievedAtMs <= CACHE_TTL_MS)) {
            val existingTier = runCatching { LyricsTimingTier.valueOf(existing.tier) }
                .getOrDefault(LyricsTimingTier.INVALID)
            // Never downgrade superior cached lyrics with an inferior timing tier.
            if (existingTier.rank > quality.tier.rank) return
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
        writeToDisk(key, record)
    }

    fun clear() {
        memory.clear()
        runCatching {
            storeDir()?.listFiles()?.forEach { it.delete() }
        }
    }

    internal fun buildCacheKey(videoId: String, title: String, artist: String): String {
        val raw = if (videoId.isNotBlank()) {
            "vid:${videoId.trim()}"
        } else {
            "meta:${title.forLyricsSearch().lowercase()}|${artist.artistForLyricsSearch().lowercase()}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun storeDir(): File? = cacheDir?.let { File(it, "sonora_lyrics_cache") }

    private fun readFromDisk(key: String): CachedLyricsRecord? {
        val file = storeDir()?.let { File(it, "$key.json") } ?: return null
        if (!file.isFile) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            lyricsJson.decodeFromString<CachedLyricsRecord>(file.readText())
        }.getOrNull()
    }

    private fun writeToDisk(key: String, record: CachedLyricsRecord) {
        val dir = storeDir() ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$key.json")
            file.writeText(lyricsJson.encodeToString(record))
            trimDiskCache(dir)
        }
    }

    private fun deleteFromDisk(key: String) {
        runCatching { storeDir()?.let { File(it, "$key.json").delete() } }
    }

    private fun trimDiskCache(dir: File) {
        val files = dir.listFiles() ?: return
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
