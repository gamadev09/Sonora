package com.music.bitchord.ui.tv.player

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.playback.chords.AndroidChordAnalysis
import com.music.bitchord.playback.chords.ChordEvent
import com.music.bitchord.playback.chords.ChordNotation
import com.music.bitchord.playback.chords.GuitarShape
import com.music.bitchord.playback.chords.chordFollowWindow
import com.music.bitchord.playback.chords.chordIndexAt
import com.music.bitchord.playback.chords.displayChordEvents
import com.music.bitchord.playback.chords.guitarShape
import com.music.bitchord.playback.chords.pianoChordNotes
import com.music.bitchord.playback.chords.spellChord
import com.music.bitchord.playback.chords.suggestedCapo
import com.music.bitchord.playback.separation.VocalSeparationCoordinator
import com.music.bitchord.ui.player.VocalSeparationUiState
import com.music.bitchord.ui.tv.components.tvClick
import com.music.bitchord.ui.tv.components.tvLift
import com.music.bitchord.ui.tv.components.tvPlatter
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class TvChordMode(val label: String) {
    INLINE("Inline"),
    GUITAR("Guitar"),
    PIANO("Piano"),
}

private val TvChordAccent = Color(0xFFF472B6)

/**
 * 10-foot Live Chords panel for Android TV Now Playing:
 * - Runs offline chroma/FFT chord detection via [AndroidChordAnalysis].
 * - Displays D-pad focusable Inline / Guitar / Piano selector + Capo control.
 * - Updates only on discrete chord transitions (never per-frame recomposition).
 */
@Composable
internal fun TvLiveChordsPanel(
    song: Song,
    position: PlaybackPosition,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val analyzer = remember(context) { AndroidChordAnalysis(context.applicationContext) }

    var rawEvents by remember(song.videoId) { mutableStateOf<List<ChordEvent>?>(null) }
    var loading by remember(song.videoId) { mutableStateOf(false) }
    var progress by remember(song.videoId) { mutableFloatStateOf(0f) }
    var error by remember(song.videoId) { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(TvChordMode.GUITAR) }
    var capo by remember(song.videoId) { mutableIntStateOf(0) }
    var capoManual by remember(song.videoId) { mutableStateOf(false) }

    // Load cached chords or kick off offline analysis when the panel is opened.
    LaunchedEffect(song.videoId) {
        error = null
        val cached = analyzer.cached(song)
        if (cached != null) {
            rawEvents = cached
            if (!capoManual) capo = suggestedCapo(cached)
            return@LaunchedEffect
        }
        loading = true
        progress = 0f
        try {
            val result = analyzer.analyze(song) { progress = it }
            rawEvents = result
            if (!capoManual) capo = suggestedCapo(result)
        } catch (e: Exception) {
            error = e.message ?: "Unable to analyse chords for this track."
        } finally {
            loading = false
        }
    }

    val displayEvents by remember(rawEvents, mode, capo) {
        derivedStateOf {
            displayChordEvents(
                events = rawEvents.orEmpty(),
                simplify = false,
                notation = ChordNotation.AUTO,
                capo = if (mode == TvChordMode.GUITAR) capo else 0,
            )
        }
    }

    // Discrete playhead sampled at 4 Hz (250ms) and only written when chord index changes.
    var activeIndex by remember(song.videoId) { mutableIntStateOf(-1) }
    var activePosMs by remember(song.videoId) { mutableLongStateOf(position.positionMs) }
    LaunchedEffect(song.videoId, displayEvents, isPlaying) {
        if (displayEvents.isEmpty()) return@LaunchedEffect
        while (true) {
            val pos = position.positionMs
            val idx = chordIndexAt(displayEvents, pos)
            if (idx != activeIndex) {
                activeIndex = idx
                activePosMs = pos
            }
            delay(if (isPlaying) 200L else 500L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header row: Instrument selector + Capo pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TvChordMode.entries.forEach { option ->
                    TvChordModeChip(
                        label = option.label,
                        selected = mode == option,
                        onClick = { mode = option },
                    )
                }
            }
            if (mode == TvChordMode.GUITAR && rawEvents != null) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TvMiniIconChip(
                        icon = Icons.Rounded.Remove,
                        contentDescription = "Decrease Capo",
                        enabled = capo > 0,
                        onClick = { capo = (capo - 1).coerceAtLeast(0); capoManual = true },
                    )
                    Text(
                        text = if (capo == 0) "Capo 0" else "Capo $capo",
                        style = TvType.Caption.copy(fontWeight = FontWeight.W600),
                        color = TvGlass.TextPrimary,
                    )
                    TvMiniIconChip(
                        icon = Icons.Rounded.Add,
                        contentDescription = "Increase Capo",
                        enabled = capo < 11,
                        onClick = { capo = (capo + 1).coerceAtMost(11); capoManual = true },
                    )
                }
            }
        }

        when {
            loading -> {
                Text(
                    text = "Analysing chords… ${(progress * 100f).roundToInt()}%",
                    style = TvType.Caption,
                    color = TvGlass.TextSecondary,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.6f).height(4.dp).clip(CircleShape),
                    color = TvChordAccent,
                    trackColor = Color.White.copy(alpha = 0.14f),
                )
            }
            error != null -> {
                Text(
                    text = error ?: "",
                    style = TvType.Caption,
                    color = TvGlass.TextSecondary,
                )
            }
            displayEvents.isNotEmpty() -> {
                val window = remember(displayEvents, activePosMs) {
                    chordFollowWindow(displayEvents, activePosMs).take(3)
                }
                val currentEvent = displayEvents.getOrNull(activeIndex)
                if (mode == TvChordMode.INLINE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        window.forEachIndexed { idx, ev ->
                            val isNow = ev == currentEvent
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isNow) TvChordAccent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = if (ev.label == "N") "—" else ev.label,
                                    style = TvType.Title.copy(fontSize = 18.sp),
                                    color = if (isNow) TvChordAccent else TvGlass.TextSecondary,
                                )
                            }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        window.forEachIndexed { idx, ev ->
                            val isNow = ev == currentEvent
                            val tint = if (isNow) TvChordAccent else TvGlass.TextPrimary.copy(alpha = 0.85f)
                            Column(
                                modifier = Modifier
                                    .width(if (mode == TvChordMode.PIANO) 168.dp else 108.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isNow) TvChordAccent.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.07f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = when {
                                        isNow -> "NOW"
                                        idx == 0 -> "NEXT"
                                        else -> "UP NEXT"
                                    },
                                    style = TvType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.W700),
                                    color = if (isNow) TvChordAccent else TvGlass.TextTertiary,
                                )
                                val shape = guitarShape(ev.label)
                                if (mode == TvChordMode.PIANO && pianoChordNotes(ev.label) != null) {
                                    TvPianoDiagram(label = ev.label, tint = tint)
                                } else if (mode == TvChordMode.GUITAR && shape != null) {
                                    TvGuitarDiagram(label = ev.label, shape = shape, tint = tint)
                                } else {
                                    Text(
                                        text = if (ev.label == "N") "—" else ev.label,
                                        style = TvType.Title.copy(fontSize = 16.sp),
                                        color = tint,
                                        modifier = Modifier.padding(vertical = 18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Compact D-pad controllable Sing (Vocal Level) bar displayed beneath album art when active. */
@Composable
internal fun TvSingVocalBar(
    song: Song,
    modifier: Modifier = Modifier,
) {
    val state by VocalSeparationCoordinator.state.collectAsStateWithLifecycle()
    val forTrack = when (val s = state) {
        is VocalSeparationUiState.DownloadingModel -> s.trackId == song.videoId
        is VocalSeparationUiState.Processing -> s.trackId == song.videoId
        is VocalSeparationUiState.Active -> s.trackId == song.videoId
        is VocalSeparationUiState.Error -> s.trackId == song.videoId
        VocalSeparationUiState.Idle -> false
    }
    if (!forTrack) return

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.10f))
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val active = state as? VocalSeparationUiState.Active ?: return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        VocalSeparationCoordinator.setVocalGain((active.vocalGain - 0.1f).coerceIn(0f, 1f))
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        VocalSeparationCoordinator.setVocalGain((active.vocalGain + 0.1f).coerceIn(0f, 1f))
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (val s = state) {
            is VocalSeparationUiState.DownloadingModel -> {
                Text(
                    text = "Downloading vocal model… ${(s.progress * 100f).roundToInt()}%",
                    style = TvType.Caption,
                    color = TvGlass.TextPrimary,
                )
                LinearProgressIndicator(
                    progress = { s.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = TvChordAccent,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
            }
            is VocalSeparationUiState.Processing -> {
                Text(
                    text = "Separating vocals… ${(s.progress * 100f).roundToInt()}%",
                    style = TvType.Caption,
                    color = TvGlass.TextPrimary,
                )
                LinearProgressIndicator(
                    progress = { s.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                    color = TvChordAccent,
                    trackColor = Color.White.copy(alpha = 0.18f),
                )
            }
            is VocalSeparationUiState.Active -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = TvChordAccent, modifier = Modifier.size(15.dp))
                    Text(
                        text = "Sing · Vocals",
                        style = TvType.Caption.copy(fontWeight = FontWeight.W600),
                        color = TvGlass.TextPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${(s.vocalGain * 100f).roundToInt()}%",
                        style = TvType.Caption.copy(fontWeight = FontWeight.W700),
                        color = TvChordAccent,
                    )
                }
                LinearProgressIndicator(
                    progress = { s.vocalGain.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = TvChordAccent,
                    trackColor = Color.White.copy(alpha = 0.22f),
                )
                Text(
                    text = "Left / Right adjusts vocal level",
                    style = TvType.Caption.copy(fontSize = 10.sp),
                    color = TvGlass.TextTertiary,
                )
            }
            is VocalSeparationUiState.Error -> {
                Text(
                    text = s.message,
                    style = TvType.Caption,
                    color = Color(0xFFFCA5A5),
                    maxLines = 2,
                )
            }
            VocalSeparationUiState.Idle -> Unit
        }
    }
}

@Composable
private fun TvChordModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .height(26.dp)
            .tvLift(interaction, CircleShape, focusedScale = 1.05f, elevation = 6.dp)
            .tvPlatter(interaction, resting = if (selected) TvGlass.FillSelected else Color.Transparent)
            .tvClick(interaction, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TvType.Caption.copy(fontWeight = FontWeight.W600),
            color = if (focused) TvGlass.OnPlatter else if (selected) TvGlass.TextPrimary else TvGlass.TextSecondary,
        )
    }
}

@Composable
private fun TvMiniIconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(24.dp)
            .tvLift(interaction, CircleShape, focusedScale = 1.1f, elevation = 4.dp)
            .tvPlatter(interaction, resting = Color.Transparent)
            .tvClick(interaction, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (!enabled) TvGlass.TextTertiary else if (focused) TvGlass.OnPlatter else TvGlass.TextPrimary,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun TvGuitarDiagram(label: String, shape: GuitarShape, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.width(74.dp)) {
            shape.frets.forEach { fret ->
                Text(
                    text = if (fret < 0) "×" else if (fret == 0) "○" else " ",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = tint,
                    fontSize = 10.sp,
                )
            }
        }
        Canvas(Modifier.size(74.dp, 68.dp)) {
            val dx = size.width / 6f
            fun sx(s: Int) = dx * (s + 0.5f)
            val pad = 3.dp.toPx()
            val dy = (size.height - pad * 2) / 4f
            for (s in 0..5) {
                drawLine(tint.copy(alpha = 0.75f), Offset(sx(s), pad), Offset(sx(s), pad + dy * 4), 1.dp.toPx())
            }
            for (f in 0..4) {
                val y = pad + dy * f
                drawLine(tint.copy(alpha = 0.75f), Offset(sx(0), y), Offset(sx(5), y), if (f == 0 && shape.firstFret == 1) 2.2.dp.toPx() else 1.dp.toPx())
            }
            shape.frets.forEachIndexed { s, fret ->
                if (fret > 0) {
                    val rel = fret - shape.firstFret
                    if (rel in 0..3) {
                        drawCircle(tint, 4.dp.toPx(), Offset(sx(s), pad + dy * (rel + 0.5f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPianoDiagram(label: String, tint: Color) {
    val notes = remember(label) { pianoChordNotes(label).orEmpty().toSet() }
    val whiteKeys = remember { (0..23).filter { it % 12 in listOf(0, 2, 4, 5, 7, 9, 11) } }
    val noteNames = remember(notes, label) {
        val chromatic = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        notes.sorted().joinToString(" · ") { spellChord(chromatic[it % 12], label.contains('♭')) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(3.dp))
        Canvas(Modifier.fillMaxWidth().height(50.dp)) {
            val keyWidth = size.width / whiteKeys.size
            whiteKeys.forEachIndexed { idx, note ->
                val x = idx * keyWidth
                drawRoundRect(
                    color = if (note in notes) tint else Color.White.copy(alpha = 0.75f),
                    topLeft = Offset(x, 0f),
                    size = Size((keyWidth - 1.dp.toPx()).coerceAtLeast(1f), size.height),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }
            for (note in 0..23) {
                if (note % 12 !in listOf(1, 3, 6, 8, 10)) continue
                val leftWhites = whiteKeys.count { it < note }
                val x = leftWhites * keyWidth - keyWidth * 0.30f
                drawRect(if (note in notes) tint else Color(0xFF1E222D), Offset(x, 0f), Size(keyWidth * 0.60f, size.height * 0.60f))
            }
        }
        Text(noteNames, color = TvGlass.TextSecondary, fontSize = 10.sp, maxLines = 1)
    }
}
