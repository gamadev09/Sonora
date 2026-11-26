package com.music.bitchord.data.lyrics

import java.text.Normalizer

/**
 * A YouTube title, as a lyrics database would have indexed it.
 *
 * Only credits and upload packaging are stripped — anything naming a different
 * recording ("(Remix)", "(Acoustic)", "(Live)", "(Remastered 2011)", "(En Vivo)")
 * stays intact so timed lyrics are never mismatched to the wrong edit.
 */
fun String.forLyricsSearch(): String {
    var name = this
    CREDITS.forEach { pattern -> name = pattern.replace(name, " ") }
    return name.replace(WHITESPACE, " ").trim().trimEnd(',', '-', '–', '—', '|').trim()
        .ifBlank { trim() }
}

/**
 * Trims " - Topic" and "VEVO" off auto-generated YouTube channel names.
 */
fun String.artistForLyricsSearch(): String =
    removeSuffix(" - Topic")
        .replace(VEVO_SUFFIX, "")
        .trim()
        .ifBlank { trim() }

/**
 * Extracts the primary credited artist when multiple artists are listed
 * (e.g. "Bad Bunny, Chencho Corleone" -> "Bad Bunny"), used only as a
 * progressive fallback after full-credit matching misses.
 */
fun String.primaryArtistForLyricsSearch(): String {
    val cleaned = artistForLyricsSearch()
    val first = MULTI_ARTIST_SPLIT.split(cleaned).firstOrNull()?.trim()
    return if (!first.isNullOrBlank()) first else cleaned
}

/**
 * Removes Unicode combining diacritics (e.g. "Canción" -> "Cancion") for
 * progressive international/Spanish search fallback and loose title comparison.
 */
fun String.stripAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")

private val WHITESPACE = Regex("""\s+""")
private val COMBINING_MARKS = Regex("""\p{InCombiningDiacriticalMarks}+""")
private val VEVO_SUFFIX = Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE)
private val MULTI_ARTIST_SPLIT = Regex("""\s*(?:,|&|\bx\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b|\bcon\b)\s*""", RegexOption.IGNORE_CASE)

private val CREDITS = listOf(
    // Bracketed credits: (feat. X), [ft. X], (with X), (con X).
    Regex("""\s*[(\[]\s*(feat|ft|featuring|with|con)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE),
    // The same, unbracketed and running to the end of the title.
    Regex("""\s+(feat|ft|featuring)\.?\s+.*$""", RegexOption.IGNORE_CASE),
    // English & Spanish/international upload packaging (not recording edits).
    Regex(
        """\s*[(\[]\s*(official\s*)?(music\s*)?""" +
            """(video|audio|visuali[sz]er|lyrics?\s*video|lyrics?|m/?v|hd|hq|4k|full\s*song|""" +
            """video\s*oficial|audio\s*oficial|letra\s*oficial|letra|video\s*lyric)""" +
            """\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\s*\|\s*(official|video\s*oficial|audio\s*oficial|letra).*$""", RegexOption.IGNORE_CASE),
    Regex("""\s*[(\[]\s*(official|oficial)\s*[)\]]""", RegexOption.IGNORE_CASE),
)
