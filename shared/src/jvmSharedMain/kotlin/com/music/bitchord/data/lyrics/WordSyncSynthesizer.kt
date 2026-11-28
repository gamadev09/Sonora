package com.music.bitchord.data.lyrics

import kotlin.math.roundToLong

/**
 * Synthesizes musical word-by-word timings for line-synced lyrics when no real
 * word/syllable-synced provider matched the track.
 *
 * Designed strictly as a **post-selection display enhancement**:
 * - Never mutates lines that already carry real word/syllable timings ([LyricLine.words]).
 * - Never fabricates timings for plain unsynchronized lyrics ([LyricsTimingTier.PLAIN]).
 * - Never runs before [assessLyricsQuality] or [LyricsCache.put], so real word/syllable
 *   providers always outrank synthesized fallbacks and cached line-synced tracks still
 *   upgrade automatically when a real word-synced provider answers.
 */
object WordSyncSynthesizer {

    private const val MIN_LINE_SPAN_MS = 400L
    private const val MAX_LINE_SPAN_MS = 8_000L
    private const val BREATH_TAIL_MS = 120L

    /**
     * Returns [lines] with synthesized [LyricWord] timings populated for any timed line
     * that lacks real word timings. Already word-synced lines and plain unsynchronized
     * documents are returned unchanged.
     */
    fun ensureWordSync(
        lines: List<LyricLine>,
        trackDurationMs: Long = 0L,
    ): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val hasTimedLines = lines.any { !it.isGap && it.timeMs > 0L }
        if (!hasTimedLines) return lines

        return lines.mapIndexed { index, line ->
            val nextTimeMs = findNextLineTimeMs(lines, index)
            synthesizeLine(line, nextTimeMs, trackDurationMs)
        }
    }

    private fun findNextLineTimeMs(lines: List<LyricLine>, index: Int): Long? {
        val currentStart = lines[index].timeMs
        for (i in (index + 1) until lines.size) {
            val candidate = lines[i].timeMs
            if (candidate > currentStart) return candidate
        }
        return null
    }

    private fun synthesizeLine(
        line: LyricLine,
        nextTimeMs: Long?,
        trackDurationMs: Long,
    ): LyricLine {
        val synthesizedBackground = line.background?.let { bg ->
            if (bg.isWordSynced || bg.isGap || bg.timeMs <= 0L) {
                bg
            } else {
                synthesizeLine(bg, nextTimeMs, trackDurationMs)
            }
        }

        if (line.isGap || line.isWordSynced || line.timeMs <= 0L) {
            return if (synthesizedBackground !== line.background) {
                line.copy(background = synthesizedBackground)
            } else {
                line
            }
        }

        val tokens = tokenize(line.text)
        if (tokens.isEmpty()) {
            return if (synthesizedBackground !== line.background) {
                line.copy(background = synthesizedBackground)
            } else {
                line
            }
        }

        val startMs = line.timeMs
        val estimatedSpan = estimateNaturalSpanMs(tokens)
        val rawEndMs = when {
            line.sungUntilMs != null && line.sungUntilMs > startMs + 150L -> line.sungUntilMs
            nextTimeMs != null && nextTimeMs > startMs + 150L -> nextTimeMs
            trackDurationMs > startMs + 150L -> minOf(trackDurationMs, startMs + estimatedSpan)
            else -> startMs + estimatedSpan
        }

        val rawSpan = (rawEndMs - startMs).coerceAtLeast(MIN_LINE_SPAN_MS)
        val cappedSpan = minOf(rawSpan, maxOf(estimatedSpan, MAX_LINE_SPAN_MS.coerceAtMost(rawSpan)))
        // Leave a subtle breath gap before the next line's downbeat when abutting another line.
        val tailTrim = if (nextTimeMs != null && line.sungUntilMs == null && cappedSpan >= 900L) {
            minOf(BREATH_TAIL_MS, (cappedSpan * 0.08f).roundToLong())
        } else {
            0L
        }
        val activeSpan = (cappedSpan - tailTrim).coerceAtLeast(MIN_LINE_SPAN_MS)

        val weights = FloatArray(tokens.size) { i -> tokenWeight(tokens[i]) }
        val totalWeight = weights.sum().coerceAtLeast(1f)

        val words = ArrayList<LyricWord>(tokens.size)
        var cursorMs = startMs
        val endLimitMs = startMs + activeSpan
        var accWeight = 0f

        for (i in tokens.indices) {
            accWeight += weights[i]
            val targetEndMs = if (i == tokens.lastIndex) {
                endLimitMs
            } else {
                (startMs + (activeSpan * (accWeight / totalWeight)).roundToLong())
                    .coerceIn(cursorMs + 20L, (endLimitMs - (tokens.size - 1 - i) * 20L).coerceAtLeast(cursorMs + 20L))
            }
            val safeEndMs = maxOf(cursorMs + 1L, targetEndMs)
            words.add(
                LyricWord(
                    startMs = cursorMs,
                    endMs = safeEndMs,
                    text = tokens[i],
                ),
            )
            cursorMs = safeEndMs
        }

        return line.copy(
            words = words,
            background = synthesizedBackground,
        )
    }

    /**
     * Splits [text] into tokens whose exact substrings appear in order inside [text],
     * guaranteeing [LyricLine.wordSpans] aligns 1:1. Handles both whitespace-delimited
     * scripts and unspaced CJK/Japanese/Korean lines.
     */
    internal fun tokenize(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val hasWhitespace = trimmed.any { it.isWhitespace() }
        if (hasWhitespace) {
            return trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        }

        // Unspaced CJK / Japanese / Korean / Thai: group into natural 2-character clusters,
        // breaking immediately after punctuation so pauses feel rhythmic.
        if (trimmed.any(::isCjkOrUnspacedScript)) {
            val clusters = ArrayList<String>()
            val current = StringBuilder()
            for (ch in trimmed) {
                current.append(ch)
                if (current.length >= 2 || isPausePunctuation(ch)) {
                    clusters.add(current.toString())
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) clusters.add(current.toString())
            return clusters
        }

        return listOf(trimmed)
    }

    private fun estimateNaturalSpanMs(tokens: List<String>): Long {
        val totalWeight = tokens.sumOf { tokenWeight(it).toDouble() }
        return (totalWeight * 340.0).roundToLong().coerceIn(1_200L, MAX_LINE_SPAN_MS)
    }

    private fun tokenWeight(token: String): Float {
        var syllables = 0
        var inVowelGroup = false
        var cjkChars = 0
        var letters = 0

        for (ch in token) {
            when {
                isCjkOrUnspacedScript(ch) -> {
                    cjkChars++
                    inVowelGroup = false
                }
                ch.isLetter() -> {
                    letters++
                    if (isVowel(ch)) {
                        if (!inVowelGroup) {
                            syllables++
                            inVowelGroup = true
                        }
                    } else {
                        inVowelGroup = false
                    }
                }
                else -> inVowelGroup = false
            }
        }

        val base = when {
            cjkChars > 0 -> cjkChars * 1.15f + syllables * 0.9f
            syllables > 0 -> syllables.toFloat() + letters * 0.08f
            else -> maxOf(0.8f, token.length * 0.25f)
        }

        val last = token.lastOrNull()
        val pauseBonus = when (last) {
            ',', ';', ':', '、', '，' -> 0.45f
            '.', '!', '?', '…', '。', '！', '？' -> 0.65f
            else -> 0f
        }
        return (base + pauseBonus).coerceAtLeast(0.65f)
    }

    private fun isVowel(ch: Char): Boolean {
        val lower = ch.lowercaseChar()
        return lower in "aeiouyáéíóúàèìòùâêîôûäëïöüãõāēīōū"
    }

    private fun isPausePunctuation(ch: Char): Boolean =
        ch in ",.;:!?…、，。！？"

    private fun isCjkOrUnspacedScript(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3040..0x30FF) || // Hiragana & Katakana
            (code in 0x4E00..0x9FFF) || // CJK Unified Ideographs
            (code in 0xAC00..0xD7AF) || // Hangul Syllables
            (code in 0x0E00..0x0E7F)    // Thai
    }

    private val WHITESPACE_REGEX = Regex("\\s+")
}
