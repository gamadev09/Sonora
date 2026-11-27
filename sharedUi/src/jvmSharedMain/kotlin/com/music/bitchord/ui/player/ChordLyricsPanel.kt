package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import dev.chrisbanes.haze.HazeState

/** Additive wrapper: the original lyrics implementation remains unchanged. */
@Composable
internal fun LyricsPanel(
    lines: List<LyricLine>,
    /**
     * Romanized or translated copies of [lines], index for index, drawn small
     * under each original line. Null shows the originals alone.
     */
    subLines: List<LyricLine>? = null,
    trackKey: String,
    chordSong: Song? = null,
    playhead: LyricPlayhead,
    /** Whether a lookup for this track is still in flight. */
    looking: Boolean,
    isPlaying: Boolean,
    /** False while the panel is retained offscreen solely as a warm layout. */
    active: Boolean = true,
    onSeekToLine: (Long) -> Unit,
    controlsOpen: Boolean,
    onRevealControls: () -> Unit,
    onHideControls: () -> Unit,
    translationProgress: State<Float>? = null,
    /** Reports whether the lyric list is mid-scroll, so the player above it
     * can stand down its own swipe gestures for as long as it is. */
    onScrollingChange: (Boolean) -> Unit = {},
    /** Whether this platform can turn picked lines into a card at all. */
    canPick: Boolean = false,
    /** Whether the reader is choosing lines to put on a share card. */
    picking: Boolean = false,
    /** Which lines are chosen, as indices into [lines]. */
    picked: Set<Int> = emptySet(),
    /** Long-press on a line: start picking, or fold that line into the pick. */
    onPickLine: (Int) -> Unit = {},
    /** Tap while picking: add or drop that line. */
    onTogglePick: (Int) -> Unit = {},
    /**
     * Holds the sung line in the middle of the panel rather than at its top.
     * For the wide layouts — TV, landscape, tablet — where a column of lyrics
     * read from its top edge leaves most of the screen below the words.
     */
    centerFocus: Boolean = false,
    chordState: ChordUiState? = null,
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
) {
    val source = PlayerPlatform.host.chordAnalysis
    val internalChordState = if (chordState != null) {
        chordState
    } else if (source != null && chordSong != null) {
        rememberChordState(chordSong, source, playhead, active)
    } else null

    val content: @Composable (Modifier) -> Unit = { bodyModifier ->
        LyricsContent(
            lines = lines,
            chordState = internalChordState,
            subLines = subLines,
            trackKey = trackKey,
            playhead = playhead,
            looking = looking,
            isPlaying = isPlaying,
            active = active,
            onSeekToLine = onSeekToLine,
            controlsOpen = controlsOpen,
            onRevealControls = onRevealControls,
            onHideControls = onHideControls,
            translationProgress = translationProgress,
            onScrollingChange = onScrollingChange,
            canPick = canPick,
            picking = picking,
            picked = picked,
            onPickLine = onPickLine,
            onTogglePick = onTogglePick,
            centerFocus = centerFocus,
            modifier = bodyModifier,
        )
    }
    if (source == null || chordSong == null || internalChordState == null) {
        content(modifier)
    } else {
        val showControls = active && !picking &&
            ((internalChordState.events != null && internalChordState.show) ||
                internalChordState.loading ||
                internalChordState.error != null)
        val synced = remember(lines) { lines.any { it.timeMs > 0 } }
        Column(modifier) {
            AnimatedVisibility(
                visible = showControls,
                enter = expandVertically(tween(260, easing = FastOutSlowInEasing)) +
                    fadeIn(tween(220)),
                exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) +
                    fadeOut(tween(180)),
            ) {
                ChordControls(chordSong, source, internalChordState, true, synced, hazeState = hazeState)
            }
            content(Modifier.fillMaxWidth().weight(1f))
        }
    }
}
