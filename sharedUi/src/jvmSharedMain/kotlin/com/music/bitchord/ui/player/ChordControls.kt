package com.music.bitchord.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.chords.ChordEvent
import com.music.bitchord.playback.chords.chordIndexAt
import com.music.bitchord.playback.chords.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.music.bitchord.ui.icons.BitChordIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChordJobSnapshot(
    val loading: Boolean = false,
    val progress: Float = 0f,
    val events: List<ChordEvent>? = null,
    val error: String? = null,
    val generation: Long = 0L,
)

/** Analysis implementations own dispatching and caching; playback is never changed. */
interface ChordAnalysisSource {
    suspend fun cached(song: Song): List<ChordEvent>?
    suspend fun analyze(song: Song, onProgress: (Float) -> Unit): List<ChordEvent>
    fun jobState(song: Song): StateFlow<ChordJobSnapshot>? = null
    fun startBackground(song: Song): Boolean = false
    fun cancelBackground(song: Song) {}
}

internal class ChordUiState(private val scope: CoroutineScope) {
    var events by mutableStateOf<List<ChordEvent>?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var error by mutableStateOf<String?>(null)
    var show by mutableStateOf(true)
    var simplify by mutableStateOf(false)
    var notation by mutableStateOf(ChordNotation.AUTO)
    var options by mutableStateOf(false)
    var instrument by mutableStateOf(ChordInstrument.INLINE)
    val piano: Boolean get() = instrument == ChordInstrument.PIANO
    val guitar: Boolean get() = instrument == ChordInstrument.GUITAR
    var diagramsExpanded by mutableStateOf(true)
    var capo by mutableIntStateOf(0)
    var capoManuallySet by mutableStateOf(false)
    var accentColor by mutableStateOf(DEFAULT_CHORD_ACCENT)

    private val cachedDisplayEvents by derivedStateOf {
        displayChordEvents(events.orEmpty(), simplify, notation, if (guitar) capo else 0)
    }

    fun displayEvents(): List<ChordEvent> = cachedDisplayEvents
    var positionMs by mutableLongStateOf(0L)
    var job: Job? = null
    var canCancel by mutableStateOf(false)
    var appliedGeneration: Long = 0L

    fun startAnalysis(song: Song, source: ChordAnalysisSource) {
        if (loading) return
        error = null
        progress = 0f
        if (source.startBackground(song)) {
            loading = true
            canCancel = true
            return
        }
        loading = true
        canCancel = true
        job = scope.launch {
            try {
                events = source.analyze(song) { progress = it }
                show = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Unable to analyse this recording."
            } finally {
                loading = false
                canCancel = false
            }
        }
    }

    fun cancelAnalysis(song: Song, source: ChordAnalysisSource) {
        source.cancelBackground(song)
        job?.cancel()
        job = null
        loading = false
        canCancel = false
    }
}

internal enum class ChordInstrument { INLINE, GUITAR, PIANO }

@Composable
internal fun rememberChordState(
    song: Song, source: ChordAnalysisSource, playhead: LyricPlayhead, active: Boolean,
): ChordUiState {
    val scope = rememberCoroutineScope()
    // Key on stable videoId rather than Song data class equality so metadata updates
    // (likeStatus, albumId, links) or hiding/reopening controls never drop chord state.
    val state = remember(song.videoId, source) { ChordUiState(scope) }
    val artworkPalette = rememberArtworkColors(song.thumbnailUrl)
    val albumAccent = remember(artworkPalette) { chordAccentFromPalette(artworkPalette) }
    val animatedAccent by androidx.compose.animation.animateColorAsState(
        targetValue = albumAccent,
        label = "chordAlbumAccent",
    )
    SideEffect { state.accentColor = animatedAccent }
    LaunchedEffect(state, song.videoId, source) {
        try {
            if (state.events == null) {
                state.events = source.cached(song)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            /* A broken cache must not prevent a fresh analysis. */
        }
        val flow = source.jobState(song) ?: return@LaunchedEffect
        flow.collect { snap ->
            state.loading = snap.loading
            state.canCancel = snap.loading
            state.progress = snap.progress
            if (!snap.loading && snap.generation > state.appliedGeneration) {
                state.appliedGeneration = snap.generation
                if (snap.events != null) {
                    state.events = snap.events
                    state.show = true
                    state.error = null
                } else {
                    state.error = snap.error
                }
            }
        }
    }
    LaunchedEffect(state, active, playhead, state.show, !state.events.isNullOrEmpty()) {
        val currentEvents = state.events
        if (!active || !state.show || currentEvents.isNullOrEmpty()) return@LaunchedEffect
        var lastChordIdx = Int.MIN_VALUE
        while (true) {
            val nextPos = playhead.audioPositionMs
            val nextChordIdx = chordIndexAt(currentEvents, nextPos)
            if (nextChordIdx != lastChordIdx) {
                lastChordIdx = nextChordIdx
                state.positionMs = nextPos
            }
            delay(100)
        }
    }
    return state
}

/**
 * Disc toggle button placed beside the Translation toggle button,
 * styled identically to [TranslationToggleButton] and [CircleGlyph] with [BitChordIcons.MusicNote].
 * Tapping it triggers analysis if not yet analysed, or toggles chords visibility.
 */
@Composable
internal fun ChordAnalysisToggleButton(
    song: Song,
    source: ChordAnalysisSource,
    state: ChordUiState,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val active = (state.events != null && state.show) || state.loading
    val chrome = LocalPlayerChrome.current
    val tint = when {
        !enabled -> chromeWhite(0.42f)
        active -> Color.White.copy(alpha = if (chrome) CHROME_FULL_ALPHA else 1f)
        else -> chromeWhite(0.78f)
    }
    val discAlpha = animateFloatAsState(
        targetValue = if (active) 0.34f else 0.18f,
        label = "chordDiscAlpha",
    )
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .additiveChrome(chrome)
            .drawBehind { drawRect(Color.White.copy(alpha = discAlpha.value)) }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (state.loading) {
                        state.cancelAnalysis(song, source)
                    } else if (state.events == null) {
                        state.startAnalysis(song, source)
                    } else {
                        state.show = !state.show
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = tint,
                strokeWidth = 1.7.dp,
            )
        } else {
            Icon(
                imageVector = BitChordIcons.MusicNote,
                contentDescription = stringResource(when {
                    state.events == null -> Res.string.chord_find
                    state.show -> Res.string.chord_hide
                    else -> Res.string.chord_show
                }),
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
internal fun ChordControls(
    song: Song, source: ChordAnalysisSource, state: ChordUiState, active: Boolean, synced: Boolean,
) {
    if (!active) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize()
    ) {
        // Single compact toolbar combining instrument selection, inline capo stepper,
        // collapsible diagram toggle, and chord options — maximising lyrics viewport.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                state.loading -> {
                    Text(
                        stringResource(Res.string.chord_analyzing),
                        color = chromeWhite(0.75f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (state.canCancel || state.job != null) {
                        ChordGlassButton(label = stringResource(Res.string.chord_cancel), onClick = { state.cancelAnalysis(song, source) })
                    }
                }
                state.events == null -> {
                    ChordGlassButton(label = stringResource(Res.string.chord_find), onClick = { state.startAnalysis(song, source) })
                }
                else -> {
                    Row(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        listOf(
                            ChordInstrument.INLINE to stringResource(Res.string.chord_inline),
                            ChordInstrument.GUITAR to stringResource(Res.string.chord_guitar),
                            ChordInstrument.PIANO to stringResource(Res.string.chord_piano),
                        ).forEach { (instrument, label) ->
                            val selected = state.instrument == instrument
                            Box(
                                Modifier.height(42.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (selected) state.accentColor.copy(alpha = 0.35f) else Color.Transparent)
                                    .clickable(role = Role.RadioButton) {
                                        if (instrument == ChordInstrument.GUITAR && !state.capoManuallySet) {
                                            state.capo = suggestedCapo(state.events.orEmpty())
                                        }
                                        state.instrument = instrument
                                        state.show = true
                                    }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, color = if (selected) Color.White else chromeWhite(0.72f),
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (state.guitar) {
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.12f)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                state.capo = (state.capo - 1).coerceAtLeast(0)
                                state.capoManuallySet = true
                            }, enabled = state.capo > 0, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Remove, stringResource(Res.string.chord_capo_decrease), tint = Color.White)
                            }
                            Text(stringResource(Res.string.chord_capo_value, state.capo),
                                color = Color.White, style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = {
                                state.capo = (state.capo + 1).coerceAtMost(11)
                                state.capoManuallySet = true
                            }, enabled = state.capo < 11, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Add, stringResource(Res.string.chord_capo_increase), tint = Color.White)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { state.options = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Tune, stringResource(Res.string.chord_options), tint = Color.White)
                        }
                        DropdownMenu(expanded = state.options, onDismissRequest = { state.options = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chord_simplify)) },
                                onClick = { state.simplify = !state.simplify },
                                trailingIcon = { Switch(checked = state.simplify, onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedThumbColor = state.accentColor,
                                        checkedTrackColor = state.accentColor.copy(alpha = 0.5f))) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chord_show_diagrams)) },
                                onClick = { state.diagramsExpanded = !state.diagramsExpanded },
                                trailingIcon = { Switch(checked = state.diagramsExpanded, onCheckedChange = null,
                                    colors = SwitchDefaults.colors(checkedThumbColor = state.accentColor,
                                        checkedTrackColor = state.accentColor.copy(alpha = 0.5f))) },
                            )
                            listOf(
                                ChordNotation.AUTO to stringResource(Res.string.chord_notation_auto),
                                ChordNotation.SHARPS to stringResource(Res.string.chord_notation_sharps),
                                ChordNotation.FLATS to stringResource(Res.string.chord_notation_flats),
                            ).forEach { (notation, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { state.notation = notation; state.options = false },
                                    trailingIcon = { RadioButton(selected = state.notation == notation, onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = state.accentColor)) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.chord_reanalyse)) },
                                onClick = { state.options = false; state.startAnalysis(song, source) },
                            )
                        }
                    }
                }
            }
        }
        if (state.loading) {
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = state.progress.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.85f))
                )
            }
        }
        state.error?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
        if (state.events != null) {
            AnimatedVisibility(visible = (state.guitar || state.piano) && state.show && state.diagramsExpanded) {
                Column { InstrumentChordPanel(state) }
            }
            if (state.events!!.isEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(stringResource(Res.string.chord_no_audio), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
            } else if (!synced && state.show) {
                Spacer(Modifier.height(2.dp))
                CurrentChord(state)
            }
        }
    }
}

/** Lightweight action button shown only before or during analysis. */
@Composable
private fun ChordGlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun CurrentChord(state: ChordUiState) {
    val events = remember(state.events, state.simplify, state.notation, state.guitar, state.capo) { state.displayEvents() }
    val current by remember(events, state) { derivedStateOf { chordIndexAt(events, state.positionMs) } }
    Text(
        stringResource(Res.string.chord_current, events.getOrNull(current)?.label?.takeUnless { it == "N" } ?: "—"),
        color = state.accentColor,
        style = MaterialTheme.typography.titleMedium,
    )
}

private val DIM_CHORD_COLOR = Color.White.copy(alpha = 0.52f)

/** Text above each lyric, with the same row motion as the original and translation. */
@Composable
internal fun LyricChordLine(
    events: List<ChordEvent>,
    state: ChordUiState,
    alignEnd: Boolean,
    inset: Dp,
    modifier: Modifier = Modifier,
) {
    val current by remember(events, state) { derivedStateOf { chordIndexAt(events, state.positionMs) } }
    val lineModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = inset)
        .padding(top = 6.dp, bottom = 4.dp)
    if (events.size == 1) {
        val label = if (events[0].label == "N") "—" else events[0].label
        Text(
            text = label,
            color = if (current == 0) Color.White else DIM_CHORD_COLOR,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            modifier = lineModifier,
        )
        return
    }
    val chordText = remember(events, current) {
        buildAnnotatedString {
            events.forEachIndexed { index, event ->
                if (index > 0) append("   ")
                val label = if (event.label == "N") "—" else event.label
                withStyle(SpanStyle(color = if (current == index) Color.White else DIM_CHORD_COLOR)) {
                    append(label)
                }
            }
        }
    }
    Text(
        text = chordText,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        modifier = lineModifier,
    )
}


private val DEFAULT_CHORD_ACCENT = Color(0xFFFFD88A)

private fun chordAccentFromPalette(palette: MeshPalette): Color {
    val raw = palette.colors.maxByOrNull { color ->
        val hsl = FloatArray(3)
        com.music.bitchord.ui.graphics.ColorUtils.colorToHSL(
            (0xFF shl 24) or
                (((color.red * 255f).toInt() and 0xFF) shl 16) or
                (((color.green * 255f).toInt() and 0xFF) shl 8) or
                ((color.blue * 255f).toInt() and 0xFF),
            hsl,
        )
        hsl[1] * (1f - kotlin.math.abs(hsl[2] - 0.55f))
    } ?: return DEFAULT_CHORD_ACCENT
    val argb = (0xFF shl 24) or
        (((raw.red * 255f).toInt() and 0xFF) shl 16) or
        (((raw.green * 255f).toInt() and 0xFF) shl 8) or
        ((raw.blue * 255f).toInt() and 0xFF)
    val hsl = FloatArray(3)
    com.music.bitchord.ui.graphics.ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceIn(0.45f, 0.92f)
    hsl[2] = hsl[2].coerceIn(0.72f, 0.86f)
    return Color(com.music.bitchord.ui.graphics.ColorUtils.HSLToColor(hsl))
}

@Composable
private fun InstrumentChordPanel(state: ChordUiState) {
    val events = remember(state.events, state.simplify, state.notation, state.guitar, state.capo) { state.displayEvents() }
    val position = state.positionMs
    val window = chordFollowWindow(events, position)
    val current = window.firstOrNull()?.takeIf { position >= it.startMs && position < it.endMs }
    val progress = current?.let { ((position - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        window.forEachIndexed { index, event ->
            key(event.startMs, event.label) {
                val playing = event == current
                val tint = state.accentColor
                Column(
                    Modifier.width(if (state.piano) 220.dp else 122.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (playing) tint.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(if (playing) Res.string.chord_now else if (index == 0) Res.string.chord_next else Res.string.chord_up_next),
                        color = if (playing) tint else chromeWhite(0.82f), style = MaterialTheme.typography.labelSmall)
                    val shape = guitarShape(event.label)
                    if (state.piano && pianoChordNotes(event.label) != null) PianoDiagram(event.label, if (playing) tint else chromeWhite(0.9f))
                    else if (!state.piano && shape != null) GuitarDiagram(event.label, shape, if (playing) tint else chromeWhite(0.9f))
                    else Text(if (event.label == "N") "Rest" else event.label, color = chromeWhite(0.9f), modifier = Modifier.height(92.dp).padding(top = 18.dp))
                    if (playing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(if (state.piano) 180.dp else 88.dp).height(3.dp), color = tint, trackColor = tint.copy(alpha = 0.12f))
                    else Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun GuitarDiagram(label: String, shape: GuitarShape, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Row(Modifier.width(92.dp)) {
            shape.frets.forEach { fret ->
                Text(if (fret < 0) "×" else if (fret == 0) "○" else " ",
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = tint, fontSize = 12.sp)
            }
        }
        Canvas(Modifier.size(92.dp, 94.dp).semantics {
            contentDescription = "$label, low E to high E: ${shape.frets.joinToString { if (it < 0) "muted" else it.toString() }}, frets relative to capo"
        }) {
            val dx = size.width / 7
            val dy = size.height / 5
            for (string in 0..5) drawLine(tint.copy(alpha = 0.82f), Offset(dx * (string + 1), 0f), Offset(dx * (string + 1), dy * 4), 1.2.dp.toPx())
            for (fret in 0..4) drawLine(tint.copy(alpha = 0.82f), Offset(dx, dy * fret), Offset(dx * 6, dy * fret), if (fret == 0 && shape.firstFret == 1) 2.5.dp.toPx() else 1.2.dp.toPx())
            val firstPlayed = shape.frets.indexOfFirst { it >= 0 }
            val lastPlayed = shape.frets.indexOfLast { it >= 0 }
            val barre = firstPlayed >= 0 && lastPlayed > firstPlayed &&
                shape.frets[firstPlayed] == shape.firstFret &&
                shape.frets[lastPlayed] == shape.firstFret &&
                shape.frets.subList(firstPlayed, lastPlayed + 1).all { it >= shape.firstFret }
            if (barre) {
                val radius = 5.dp.toPx()
                drawRoundRect(tint, topLeft = Offset(dx * (firstPlayed + 1) - radius,
                    dy * 0.5f - radius), size = Size(dx * (lastPlayed - firstPlayed) + 2 * radius, 2 * radius),
                    cornerRadius = CornerRadius(radius))
            }
            shape.frets.forEachIndexed { string, fret ->
                if (fret > 0 && !(barre && fret == shape.firstFret))
                    drawCircle(tint, 5.dp.toPx(), Offset(dx * (string + 1), dy * (fret - shape.firstFret + 0.5f)))
            }
        }
        Row(Modifier.width(92.dp)) {
            listOf("E", "A", "D", "G", "B", "e").forEach { name ->
                Text(name, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = chromeWhite(0.85f), fontSize = 11.sp)
            }
        }
        if (shape.firstFret > 1) {
            Text("${shape.firstFret}fr", color = chromeWhite(0.85f), fontSize = 11.sp)
        }
    }
}


@Composable
private fun PianoDiagram(label: String, tint: Color) {
    val notes = remember(label) { pianoChordNotes(label).orEmpty().toSet() }
    val whiteKeys = remember { (0..23).filter { it % 12 in listOf(0, 2, 4, 5, 7, 9, 11) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(68.dp).semantics {
            contentDescription = "Piano $label, highlighted notes: ${notes.sorted().joinToString { spellChord(listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[it % 12], label.contains('♭')) }}"
        }) {
            val keyWidth = size.width / whiteKeys.size
            whiteKeys.forEachIndexed { index, note ->
                val x = index * keyWidth
                drawRect(if (note in notes) tint else Color.White.copy(alpha = 0.72f), Offset(x, 0f), Size((keyWidth - 1.dp.toPx()).coerceAtLeast(1f), size.height))
                if (note in notes) drawCircle(Color.Black.copy(alpha = 0.55f), keyWidth * 0.2f, Offset(x + keyWidth / 2, size.height * 0.85f))
            }
            for (note in 0..23) {
                if (note % 12 !in listOf(1, 3, 6, 8, 10)) continue
                val leftWhites = whiteKeys.count { it < note }
                val x = leftWhites * keyWidth - keyWidth * 0.32f
                drawRect(Color.Black, Offset(x - 1f, 0f), Size(keyWidth * 0.64f + 2f, size.height * 0.63f))
                drawRect(if (note in notes) tint else Color(0xFF292929), Offset(x, 0f), Size(keyWidth * 0.64f, size.height * 0.60f))
            }
        }
        Text(notes.sorted().joinToString(" · ") {
            spellChord(listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[it % 12], label.contains('♭'))
        }, color = chromeWhite(0.9f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
    }
}
