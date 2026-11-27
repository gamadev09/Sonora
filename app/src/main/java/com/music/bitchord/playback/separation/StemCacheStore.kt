package com.music.bitchord.playback.separation

import com.music.bitchord.data.settings.AudioIntelligencePriority
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Persistent automatic LRU cache for separated vocal and instrumental stems.
 *
 * Design invariants:
 * - Metadata (`<key>.meta.json`) is stored separately from raw PCM stem data
 *   (`<key>.vocals.pcm` and `<key>.inst.pcm`).
 * - Cache keys incorporate source identity, model version/hash, processing priority,
 *   sample rate, and channel count.
 * - Writes use `.part` staging files and commit `meta.json` last so partial or
 *   crashed runs are never reused.
 * - Entries are validated (existence, metadata integrity, exact byte lengths) before reuse.
 * - Actively playing or in-progress keys are pinned via [pin]/[unpin] and are never
 *   evicted by LRU cleanup.
 */
class StemCacheStore(
    private val rootDir: File,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val DEFAULT_MAX_CACHE_BYTES = 512L * 1024L * 1024L // 512 MB automatic cap
        private const val META_SUFFIX = ".meta.json"
        private const val VOCALS_SUFFIX = ".vocals.pcm"
        private const val INST_SUFFIX = ".inst.pcm"

        fun computeKey(
            sourceIdentity: String,
            modelVersion: String,
            priority: AudioIntelligencePriority,
            sampleRate: Int,
            channels: Int,
        ): String {
            val raw = "v$FORMAT_VERSION|$sourceIdentity|$modelVersion|${priority.cacheProfileTag}|$sampleRate|$channels"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }

    data class StemEntry(
        val key: String,
        val trackId: String,
        val sourceIdentity: String,
        val modelVersion: String,
        val priority: String,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
        val createdAtMs: Long,
        val lastAccessedMs: Long,
        val vocalsFile: File,
        val instFile: File,
    ) {
        val expectedPcmBytes: Long get() = frameCount * channels.toLong() * 2L
        val totalBytes: Long get() = vocalsFile.length() + instFile.length()
    }

    data class StagingFiles(
        val key: String,
        val vocalsPart: File,
        val instPart: File,
        val metaPart: File,
    )

    private val lock = Any()
    private val pinnedKeys = ConcurrentHashMap.newKeySet<String>()

    init {
        rootDir.mkdirs()
    }

    fun pin(key: String) {
        pinnedKeys.add(key)
    }

    fun unpin(key: String) {
        pinnedKeys.remove(key)
    }

    fun isPinned(key: String): Boolean = pinnedKeys.contains(key)

    /**
     * Looks up and validates a cached stem entry. Returns null if missing or corrupt
     * (cleaning up corrupt artifacts automatically unless pinned).
     */
    fun getValid(key: String, nowMs: Long = System.currentTimeMillis()): StemEntry? = synchronized(lock) {
        val metaFile = File(rootDir, "$key$META_SUFFIX")
        val vocalsFile = File(rootDir, "$key$VOCALS_SUFFIX")
        val instFile = File(rootDir, "$key$INST_SUFFIX")
        if (!metaFile.isFile || !vocalsFile.isFile || !instFile.isFile) {
            return null
        }
        val parsed = readMeta(metaFile, key, vocalsFile, instFile)
        if (parsed == null || !isEntryValid(parsed)) {
            if (!isPinned(key)) {
                deleteKeyFiles(key)
            }
            return null
        }
        // Update LRU access timestamp.
        val touched = parsed.copy(lastAccessedMs = nowMs)
        writeMetaAtomic(metaFile, touched)
        touched
    }

    /**
     * Allocates staging `.part` files for an atomic write. The key is automatically
     * pinned until [commitStaging] or [discardStaging] is called.
     */
    fun beginStaging(key: String): StagingFiles = synchronized(lock) {
        rootDir.mkdirs()
        pin(key)
        val vocalsPart = File(rootDir, "$key$VOCALS_SUFFIX.part")
        val instPart = File(rootDir, "$key$INST_SUFFIX.part")
        val metaPart = File(rootDir, "$key$META_SUFFIX.part")
        vocalsPart.delete()
        instPart.delete()
        metaPart.delete()
        StagingFiles(key, vocalsPart, instPart, metaPart)
    }

    /**
     * Validates staged PCM files, atomically renames them into place, writes metadata
     * last, unpins staging, and runs LRU eviction if over budget.
     */
    fun commitStaging(
        staging: StagingFiles,
        trackId: String,
        sourceIdentity: String,
        modelVersion: String,
        priority: AudioIntelligencePriority,
        sampleRate: Int,
        channels: Int,
        frameCount: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): StemEntry? = synchronized(lock) {
        val expectedBytes = frameCount * channels.toLong() * 2L
        if (frameCount <= 0L || sampleRate <= 0 || channels <= 0 ||
            !staging.vocalsPart.isFile || !staging.instPart.isFile ||
            staging.vocalsPart.length() != expectedBytes ||
            staging.instPart.length() != expectedBytes
        ) {
            discardStaging(staging)
            return null
        }

        val vocalsTarget = File(rootDir, "${staging.key}$VOCALS_SUFFIX")
        val instTarget = File(rootDir, "${staging.key}$INST_SUFFIX")
        val metaTarget = File(rootDir, "${staging.key}$META_SUFFIX")

        vocalsTarget.delete()
        instTarget.delete()
        metaTarget.delete()

        if (!staging.vocalsPart.renameTo(vocalsTarget) || !staging.instPart.renameTo(instTarget)) {
            vocalsTarget.delete()
            instTarget.delete()
            discardStaging(staging)
            return null
        }

        val entry = StemEntry(
            key = staging.key,
            trackId = trackId,
            sourceIdentity = sourceIdentity,
            modelVersion = modelVersion,
            priority = priority.cacheProfileTag,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = frameCount,
            createdAtMs = nowMs,
            lastAccessedMs = nowMs,
            vocalsFile = vocalsTarget,
            instFile = instTarget,
        )
        if (!writeMetaAtomic(metaTarget, entry)) {
            deleteKeyFiles(staging.key)
            unpin(staging.key)
            return null
        }

        evictIfNeededLocked()
        unpin(staging.key)
        entry
    }

    fun discardStaging(staging: StagingFiles) = synchronized(lock) {
        staging.vocalsPart.delete()
        staging.instPart.delete()
        staging.metaPart.delete()
        unpin(staging.key)
    }

    /**
     * Removes orphaned `.part` files left by a prior crash and enforces the LRU size cap.
     */
    fun pruneAndEvict() = synchronized(lock) {
        val files = rootDir.listFiles() ?: return
        for (f in files) {
            if (f.name.endsWith(".part")) {
                val key = f.name.substringBefore('.')
                if (!isPinned(key)) f.delete()
            }
        }
        evictIfNeededLocked()
    }

    fun totalCacheBytes(): Long = synchronized(lock) {
        rootDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(VOCALS_SUFFIX) || it.name.endsWith(INST_SUFFIX)) }
            ?.sumOf { it.length() }
            ?: 0L
    }

    private fun evictIfNeededLocked() {
        val entries = listValidEntriesLocked()
        var total = entries.sumOf { it.totalBytes }
        if (total <= maxCacheBytes) return

        // Oldest accessed first; never evict pinned entries.
        val sorted = entries.sortedBy { it.lastAccessedMs }
        for (entry in sorted) {
            if (total <= maxCacheBytes) break
            if (isPinned(entry.key)) continue
            val freed = entry.totalBytes
            deleteKeyFiles(entry.key)
            total = (total - freed).coerceAtLeast(0L)
        }
    }

    private fun listValidEntriesLocked(): List<StemEntry> {
        val metaFiles = rootDir.listFiles { _, name -> name.endsWith(META_SUFFIX) } ?: return emptyList()
        val result = ArrayList<StemEntry>(metaFiles.size)
        for (metaFile in metaFiles) {
            val key = metaFile.name.removeSuffix(META_SUFFIX)
            val vocalsFile = File(rootDir, "$key$VOCALS_SUFFIX")
            val instFile = File(rootDir, "$key$INST_SUFFIX")
            val entry = readMeta(metaFile, key, vocalsFile, instFile)
            if (entry != null && isEntryValid(entry)) {
                result += entry
            } else if (!isPinned(key)) {
                deleteKeyFiles(key)
            }
        }
        return result
    }

    private fun isEntryValid(entry: StemEntry): Boolean {
        if (entry.frameCount <= 0L || entry.sampleRate <= 0 || entry.channels != 2) return false
        val expected = entry.expectedPcmBytes
        return entry.vocalsFile.isFile &&
            entry.instFile.isFile &&
            entry.vocalsFile.length() == expected &&
            entry.instFile.length() == expected
    }

    private fun readMeta(
        metaFile: File,
        key: String,
        vocalsFile: File,
        instFile: File,
    ): StemEntry? = runCatching {
        val json = JSONObject(metaFile.readText(Charsets.UTF_8))
        if (json.optInt("formatVersion", -1) != FORMAT_VERSION) return null
        StemEntry(
            key = key,
            trackId = json.getString("trackId"),
            sourceIdentity = json.getString("sourceIdentity"),
            modelVersion = json.getString("modelVersion"),
            priority = json.getString("priority"),
            sampleRate = json.getInt("sampleRate"),
            channels = json.getInt("channels"),
            frameCount = json.getLong("frameCount"),
            createdAtMs = json.getLong("createdAtMs"),
            lastAccessedMs = json.getLong("lastAccessedMs"),
            vocalsFile = vocalsFile,
            instFile = instFile,
        )
    }.getOrNull()

    private fun writeMetaAtomic(metaTarget: File, entry: StemEntry): Boolean = runCatching {
        val part = File(metaTarget.parentFile, "${metaTarget.name}.part")
        val json = JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("trackId", entry.trackId)
            .put("sourceIdentity", entry.sourceIdentity)
            .put("modelVersion", entry.modelVersion)
            .put("priority", entry.priority)
            .put("sampleRate", entry.sampleRate)
            .put("channels", entry.channels)
            .put("frameCount", entry.frameCount)
            .put("createdAtMs", entry.createdAtMs)
            .put("lastAccessedMs", entry.lastAccessedMs)
        part.writeText(json.toString(), Charsets.UTF_8)
        if (metaTarget.exists()) metaTarget.delete()
        if (!part.renameTo(metaTarget)) {
            part.delete()
            return false
        }
        true
    }.getOrDefault(false)

    private fun deleteKeyFiles(key: String) {
        File(rootDir, "$key$META_SUFFIX").delete()
        File(rootDir, "$key$VOCALS_SUFFIX").delete()
        File(rootDir, "$key$INST_SUFFIX").delete()
        File(rootDir, "$key$META_SUFFIX.part").delete()
        File(rootDir, "$key$VOCALS_SUFFIX.part").delete()
        File(rootDir, "$key$INST_SUFFIX.part").delete()
    }
}
