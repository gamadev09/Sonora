package com.music.bitchord.data.canvas

import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a clip came from — read in one place, by
 * [CanvasArtworkPlayer][com.music.bitchord.ui.player.CanvasArtworkPlayer]'s
 * caller, to decide whether the backdrop should keep re-tinting itself off
 * the clip as it loops rather than settling on its first frame.
 */
enum class CanvasSource { SPOTIFY, APPLE, OTHER }

/**
 * A looping video that stands in for a track's cover art — what Spotify calls
 * a Canvas and Apple calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors,
 * which is how the Apple provider hands over a second rendition of the same
 * clip when the preferred one won't decode. The metadata fields let [matches]
 * re-check free-text provider answers against what is actually playing.
 */
data class CanvasArtwork(
    val url: String,
    val fallbackUrl: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val source: CanvasSource = CanvasSource.OTHER,
    /**
     * The same clip framed 3:4, when the provider has one. Only the phone
     * player takes it (it runs the clip under the whole screen); everywhere
     * else keeps the square [url].
     */
    val portraitUrl: String? = null,
    /** Verified provider album identifier when known (e.g. Apple Music catalog album id). */
    val albumId: String? = null,
    /** Storefront code where the clip was resolved, when applicable. */
    val storefront: String? = null,
) {
    /**
     * Whether this clip really belongs to the track or release we asked about.
     *
     * Uses Unicode-aware normalization so multilingual titles, accents, punctuation,
     * featured-artist formatting, and harmless edition markers (Deluxe/Remastered/Expanded)
     * do not falsely reject valid matches, while still rejecting distinct recording
     * versions (Live, Acoustic, Remix, Sped Up, Slowed, Instrumental, etc.).
     */
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?): Boolean {
        val titleOk = title == null || wantTitle.isBlank() ||
            titlesEquivalent(title, wantTitle)

        val artistOk = artist == null || wantArtist.isBlank() ||
            artistsCompatible(wantArtist, artist)

        val albumOk = album.isNullOrBlank() || wantAlbum.isNullOrBlank() ||
            albumsEquivalent(album, wantAlbum)

        return titleOk && artistOk && albumOk
    }
}

/**
 * Unicode-aware normalization that preserves non-Latin scripts (CJK, Cyrillic,
 * Arabic, Greek, Devanagari, etc.) while folding case, accents/diacritics, and
 * punctuation.
 */
fun String.normalizeForMatch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_LETTER_OR_DIGIT, " ")
        .replace(WHITESPACE, " ")
        .trim()

/**
 * Strips featured-artist credits and harmless release-packaging suffixes
 * (Deluxe, Remastered, Expanded, Explicit, Single, EP) while keeping the core
 * title or album name intact.
 */
fun String.canonicalReleaseBase(): String {
    var s = this
        .replace(PARENS_FEAT, " ")
        .replace(INLINE_FEAT, " ")
        .replace(EDITION_SUFFIX_PARENS, " ")
        .replace(EDITION_SUFFIX_DASH, " ")
        .replace(TRAILING_EP_SINGLE, " ")
    return s.normalizeForMatch()
}

/**
 * Recording-version qualifiers that denote a meaningfully different recording
 * rather than a repackaged edition of the same master.
 */
fun String.recordingQualifiers(): Set<String> {
    val norm = normalizeForMatch()
    if (norm.isBlank()) return emptySet()
    return RECORDING_QUALIFIERS.filterTo(HashSet()) { qualifier ->
        Regex("(?:^|\\s)${Regex.escape(qualifier)}(?:$|\\s)").containsMatchIn(norm)
    }
}

fun titlesEquivalent(candidate: String, wanted: String): Boolean {
    val normCand = candidate.normalizeForMatch()
    val normWant = wanted.normalizeForMatch()
    if (normCand.isBlank() || normWant.isBlank()) return false
    if (normCand == normWant) return true

    // Preserve meaningful recording/version differences.
    if (candidate.recordingQualifiers() != wanted.recordingQualifiers()) return false

    val baseCand = candidate.canonicalReleaseBase()
    val baseWant = wanted.canonicalReleaseBase()
    if (baseCand.isBlank() || baseWant.isBlank()) return false
    return baseCand == baseWant
}

fun albumsEquivalent(candidate: String, wanted: String): Boolean {
    val normCand = candidate.normalizeForMatch()
    val normWant = wanted.normalizeForMatch()
    if (normCand.isBlank() || normWant.isBlank()) return false
    if (normCand == normWant) return true

    val baseCand = candidate.canonicalReleaseBase()
    val baseWant = wanted.canonicalReleaseBase()
    if (baseCand.isBlank() || baseWant.isBlank()) return false
    if (baseCand == baseWant) return true

    // Reject if recording qualifiers conflict (e.g. Live album vs Studio album).
    if (candidate.recordingQualifiers() != wanted.recordingQualifiers()) return false

    // Allow a slightly longer edition name if one cleanly prefixes the other on a word boundary.
    return (baseCand.startsWith("$baseWant ") || baseWant.startsWith("$baseCand ")) &&
        minOf(baseCand.length, baseWant.length) >= 4
}

fun artistsCompatible(wantedRaw: String, candidateRaw: String): Boolean {
    val wanted = splitArtists(wantedRaw)
    val credited = splitArtists(candidateRaw)
    if (wanted.isEmpty() || credited.isEmpty()) return false

    // Exact match on all wanted artists (best case).
    if (wanted.all { want -> credited.any { it == want } }) return true

    // Primary artist matches (handles cases where YouTube Music appends or omits a featured artist).
    val wantedPrimary = wanted.first()
    val creditedPrimary = credited.first()
    if (wantedPrimary == creditedPrimary) return true
    if (credited.any { it == wantedPrimary } && wanted.any { it == creditedPrimary }) return true

    // Handle "The X" vs "X" on primary artist.
    val stripThe = { s: String -> s.removePrefix("the ").trim() }
    if (stripThe(wantedPrimary).isNotBlank() && stripThe(wantedPrimary) == stripThe(creditedPrimary)) {
        return true
    }

    return false
}

/**
 * One credit string into its individual artists. Every service picks its own
 * separator — commas, ampersands, "feat.", a bare "x" between collaborators —
 * so comparing the joined strings would fail on formatting alone.
 */
fun splitArtists(raw: String): List<String> =
    raw.split(ARTIST_SEPARATORS)
        .map { it.normalizeForMatch() }
        .filter { it.isNotBlank() }

private val COMBINING_MARKS = Regex("\\p{M}+")
private val NON_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}\\s]")
private val WHITESPACE = Regex("\\s+")

private val ARTIST_SEPARATORS = Regex(
    "(?:\\s*,\\s*|\\s*&\\s*|\\s+×\\s+|\\s+x\\s+|\\s+•\\s+|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bfeaturing\\b|\\bwith\\b)",
    RegexOption.IGNORE_CASE,
)

private val PARENS_FEAT = Regex(
    """[\(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+[^\)\]]+[\)\]]""",
    RegexOption.IGNORE_CASE,
)

private val INLINE_FEAT = Regex(
    """\s+(?:feat\.?|ft\.?|featuring)\s+.+$""",
    RegexOption.IGNORE_CASE,
)

private val EDITION_SUFFIX_PARENS = Regex(
    """[\(\[]\s*(?:deluxe|super deluxe|expanded|remaster(?:ed)?|anniversary|collector'?s?|special|bonus|explicit|clean|standard|complete|platinum|gold|legacy|digital|apple music|international|japan(?:ese)?|uk|us|limited|\d{4}\s*remaster(?:ed)?|\d{2}(?:th|st|nd|rd)\s*anniversary)(?:\s+[^\)\]]*)?[\)\]]""",
    RegexOption.IGNORE_CASE,
)

private val EDITION_SUFFIX_DASH = Regex(
    """\s+[-–—]\s+(?:deluxe|super deluxe|expanded|remaster(?:ed)?|anniversary|special|bonus|explicit|clean|single|ep|\d{4}\s*remaster(?:ed)?)(?:\s+.*)?$""",
    RegexOption.IGNORE_CASE,
)

private val TRAILING_EP_SINGLE = Regex(
    """\s*[\(\[]\s*(?:single|ep)\s*[\)\]]\s*$""",
    RegexOption.IGNORE_CASE,
)

private val RECORDING_QUALIFIERS = listOf(
    "live",
    "acoustic",
    "remix",
    "instrumental",
    "karaoke",
    "sped up",
    "slowed",
    "demo",
    "a cappella",
    "acapella",
    "unplugged",
    "orchestral",
    "chopped",
    "nightcore",
    "8d",
    "cover",
    "radio edit",
)
