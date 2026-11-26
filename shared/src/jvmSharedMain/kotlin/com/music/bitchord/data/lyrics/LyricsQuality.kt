package com.music.bitchord.data.lyrics

/**
 * Document-level lyrics synchronisation quality hierarchy (highest to lowest):
 * 1. Complete syllable-synchronised
 * 2. Complete word-synchronised
 * 3. Partially word-synchronised (sufficiently accurate)
 * 4. Line-synchronised
 * 5. Plain unsynchronised text
 */
enum class LyricsTimingTier(val rank: Int) {
    INVALID(0),
    PLAIN(1),
    LINE_SYNCED(2),
    PARTIAL_WORD(3),
    COMPLETE_WORD(4),
    COMPLETE_SYLLABLE(5),
}

data class LyricsQualityAssessment(
    val tier: LyricsTimingTier,
    val vocalLineCount: Int,
    val wordTimedLineRatio: Float,
    val textCoverageRatio: Float,
    val syllableWordRatio: Float,
    val invalidWordRatio: Float,
) {
    val isTimed: Boolean get() = tier.rank >= LyricsTimingTier.LINE_SYNCED.rank
    val isWordLevel: Boolean get() = tier.rank >= LyricsTimingTier.PARTIAL_WORD.rank
}

/**
 * Evaluates an entire lyrics document rather than accepting word-sync on a single
 * timed line. Rejects corrupt/zero-duration word timing from outranking a complete
 * timed document.
 */
fun assessLyricsQuality(lines: List<LyricLine>): LyricsQualityAssessment {
    val vocal = lines.filter { !it.isGap && it.text.isNotBlank() }
    if (vocal.isEmpty()) {
        return LyricsQualityAssessment(
            tier = LyricsTimingTier.INVALID,
            vocalLineCount = 0,
            wordTimedLineRatio = 0f,
            textCoverageRatio = 0f,
            syllableWordRatio = 0f,
            invalidWordRatio = 0f,
        )
    }

    var timedLines = 0
    var wordTimedLines = 0
    var totalVocalChars = 0
    var coveredWordChars = 0
    var totalWords = 0
    var validWords = 0
    var syllableWords = 0
    var invalidWords = 0

    for (line in vocal) {
        if (line.timeMs > 0L) timedLines++
        val lineNonWsChars = line.text.count { !it.isWhitespace() }
        totalVocalChars += lineNonWsChars

        if (line.words.isNotEmpty()) {
            var lineValidWords = 0
            var lineCoveredChars = 0
            var prevStart = -1L
            for (word in line.words) {
                totalWords++
                val valid = word.text.isNotBlank() &&
                    word.startMs >= 0L &&
                    word.endMs > word.startMs &&
                    (prevStart < 0L || word.startMs >= prevStart - 50L)
                if (valid) {
                    validWords++
                    lineValidWords++
                    lineCoveredChars += word.text.count { !it.isWhitespace() }
                    prevStart = word.startMs
                    if (word.syllables.size >= 2 && word.syllables.all { it.endMs >= it.startMs }) {
                        syllableWords++
                    }
                } else {
                    invalidWords++
                }
            }
            if (lineValidWords > 0) {
                wordTimedLines++
                coveredWordChars += lineCoveredChars.coerceAtMost(lineNonWsChars)
            }
        }
    }

    val vocalCount = vocal.size
    val lineTimedRatio = timedLines.toFloat() / vocalCount
    val wordLineRatio = wordTimedLines.toFloat() / vocalCount
    val textCoverage = if (totalVocalChars > 0) coveredWordChars.toFloat() / totalVocalChars else 0f
    val syllableRatio = if (validWords > 0) syllableWords.toFloat() / validWords else 0f
    val invalidRatio = if (totalWords > 0) invalidWords.toFloat() / totalWords else 0f

    val hasValidTiming = invalidRatio < 0.25f
    val tier = when {
        hasValidTiming && wordLineRatio >= 0.75f && textCoverage >= 0.70f && syllableRatio >= 0.20f ->
            LyricsTimingTier.COMPLETE_SYLLABLE
        hasValidTiming && wordLineRatio >= 0.75f && textCoverage >= 0.70f ->
            LyricsTimingTier.COMPLETE_WORD
        hasValidTiming && wordLineRatio >= 0.35f && textCoverage >= 0.35f ->
            LyricsTimingTier.PARTIAL_WORD
        lineTimedRatio >= 0.50f || (vocalCount == 1 && timedLines == 1) ->
            LyricsTimingTier.LINE_SYNCED
        else ->
            LyricsTimingTier.PLAIN
    }

    return LyricsQualityAssessment(
        tier = tier,
        vocalLineCount = vocalCount,
        wordTimedLineRatio = wordLineRatio,
        textCoverageRatio = textCoverage,
        syllableWordRatio = syllableRatio,
        invalidWordRatio = invalidRatio,
    )
}
