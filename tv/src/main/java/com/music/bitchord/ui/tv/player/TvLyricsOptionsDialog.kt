package com.music.bitchord.ui.tv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TvSettings
import com.music.bitchord.ui.tv.components.TvDialog
import com.music.bitchord.ui.tv.components.TvListRow
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlin.math.abs

enum class TvLyricsSubMode { None, Translate, Romanize }

internal val TvLyricsFontScaleSteps = listOf(
    0.90f to "Compact (90%)",
    1.05f to "Normal (105%)",
    1.20f to "Large (120%)",
    1.35f to "Extra Large (135%)",
    1.50f to "Cinema (150%)",
)

internal fun tvLyricsFontScaleLabel(scale: Float): String =
    TvLyricsFontScaleSteps.minByOrNull { abs(it.first - scale) }?.second ?: "${(scale * 100).toInt()}%"

internal fun nextTvLyricsFontScale(current: Float): Float =
    TvLyricsFontScaleSteps.firstOrNull { it.first > current + 0.02f }?.first ?: TvLyricsFontScaleSteps.first().first

private val TranslationLanguageChoices = listOf(
    "" to "App Language",
    "en" to "English",
    "es" to "Español",
    "pt" to "Português",
    "fr" to "Français",
    "de" to "Deutsch",
    "ja" to "日本語",
    "ko" to "한국어",
    "zh-CN" to "中文 (简体)",
)

private fun translationLanguageLabel(tag: String): String =
    TranslationLanguageChoices.firstOrNull { it.first.equals(tag, ignoreCase = true) }?.second
        ?: tag.ifBlank { "App Language" }

private fun nextTranslationLanguage(current: String): String {
    val idx = TranslationLanguageChoices.indexOfFirst { it.first.equals(current, ignoreCase = true) }
    return TranslationLanguageChoices[(idx + 1) % TranslationLanguageChoices.size].first
}

/**
 * D-pad-friendly Lyrics Options dialog for TV Now Playing:
 * Translation, Romanization, Live Chords, Font Size, Layout, Focus Blur, Sync Offset, and Language.
 */
@Composable
internal fun TvLyricsOptionsDialog(
    subMode: TvLyricsSubMode,
    subLoading: Boolean,
    subStatus: String?,
    chordsOpen: Boolean,
    onSelectSubMode: (TvLyricsSubMode) -> Unit,
    onToggleChords: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fontScale by TvSettings.tvLyricsFontScale.collectAsStateWithLifecycle()
    val centerFocus by TvSettings.tvLyricsCenterFocus.collectAsStateWithLifecycle()
    val lyricsBlur by AppSettings.lyricsBlur.collectAsStateWithLifecycle()
    val offsetMs by AppSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val targetLang by AppSettings.translationLanguage.collectAsStateWithLifecycle()

    TvDialog(
        onDismissRequest = onDismiss,
        title = "Lyrics & Display",
        message = subStatus ?: "Adjust translation, romanization, chords, and lyric typography.",
        width = 520.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            TvListRow(
                title = "Translate Lyrics",
                value = when {
                    subLoading && subMode == TvLyricsSubMode.Translate -> "Translating…"
                    subMode == TvLyricsSubMode.Translate -> "On"
                    else -> "Off"
                },
                onClick = {
                    onSelectSubMode(
                        if (subMode == TvLyricsSubMode.Translate) TvLyricsSubMode.None else TvLyricsSubMode.Translate,
                    )
                },
            )
            TvListRow(
                title = "Romanize Lyrics",
                value = when {
                    subLoading && subMode == TvLyricsSubMode.Romanize -> "Romanizing…"
                    subMode == TvLyricsSubMode.Romanize -> "On"
                    else -> "Off"
                },
                onClick = {
                    onSelectSubMode(
                        if (subMode == TvLyricsSubMode.Romanize) TvLyricsSubMode.None else TvLyricsSubMode.Romanize,
                    )
                },
            )
            TvListRow(
                title = "Live Chords",
                value = if (chordsOpen) "On" else "Off",
                onClick = onToggleChords,
            )
            TvListRow(
                title = "Lyrics Font Size",
                value = tvLyricsFontScaleLabel(fontScale),
                onClick = { TvSettings.setTvLyricsFontScale(nextTvLyricsFontScale(fontScale)) },
            )
            TvListRow(
                title = "Lyrics Focus Layout",
                value = if (centerFocus) "Centered" else "Top",
                onClick = { TvSettings.setTvLyricsCenterFocus(!centerFocus) },
            )
            TvListRow(
                title = "Lyrics Focus Blur",
                value = if (lyricsBlur) "On" else "Off",
                onClick = { AppSettings.setLyricsBlur(!lyricsBlur) },
            )
            TvListRow(
                title = "Lyrics Sync Offset",
                value = when {
                    offsetMs == 0 -> "0 ms"
                    offsetMs > 0 -> "+$offsetMs ms"
                    else -> "$offsetMs ms"
                },
                onClick = {
                    val steps = listOf(-1000, -500, -250, 0, 250, 500, 1000)
                    val next = steps.firstOrNull { it > offsetMs } ?: steps.first()
                    AppSettings.setLyricsOffsetMs(next)
                },
            )
            TvListRow(
                title = "Translation Language",
                value = translationLanguageLabel(targetLang),
                onClick = { AppSettings.setTranslationLanguage(nextTranslationLanguage(targetLang)) },
            )
        }
    }
}
