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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    var piano by mutableStateOf(false)
    var guitar by mutableStateOf(false)
    var capo by mutableIntStateOf(0)

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

@Composable
internal fun rememberChordState(
    song: Song, source: ChordAnalysisSource, playhead: LyricPlayhead, active: Boolean,
): ChordUiState {
    val scope = rememberCoroutineScope()
    // Key on stable videoId rather than Song data class equality so metadata updates
    // (likeStatus, albumId, links) or hiding/reopening controls never drop chord state.
    val state = remember(song.videoId, source) { ChordUiState(scope) }
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
                contentDescription = if (state.events == null) "Find chords" else if (state.show) "Hide chords" else "Show chords",
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
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize()
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chords · estimates",
                color = chromeWhite(0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
            if (state.loading) {
                if (state.canCancel || state.job != null) {
                    ChordGlassButton(label = "Cancel", onClick = { state.cancelAnalysis(song, source) })
                }
            } else if (state.events == null) {
                ChordGlassButton(label = "Find chords", onClick = { state.startAnalysis(song, source) })
            } else {
                ChordGlassPill(label = if (state.options) "Done" else "Options", active = state.options, onClick = { state.options = !state.options })
            }
        }
        if (state.loading) {
            Spacer(Modifier.height(6.dp))
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
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
        }
        if (state.events != null) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChordGlassPill(label = "Piano", active = state.piano, onClick = {
                    state.piano = !state.piano
                    if (state.piano) { state.guitar = false; state.show = true }
                })
                ChordGlassPill(label = "Guitar", active = state.guitar, onClick = {
                    state.guitar = !state.guitar
                    if (state.guitar) { state.piano = false; state.show = true }
                    if (state.guitar) state.capo = suggestedCapo(if (state.simplify) simplifyChordEvents(state.events.orEmpty()) else state.events.orEmpty())
                })
            }
            AnimatedVisibility(visible = state.options) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChordGlassPill(label = "Simplify", active = state.simplify, onClick = { state.simplify = !state.simplify })
                        ChordGlassPill(
                            label = "Notes: ${state.notation.name.lowercase()}", active = state.notation != ChordNotation.AUTO,
                            onClick = { state.notation = ChordNotation.entries[(state.notation.ordinal + 1) % ChordNotation.entries.size] },
                        )
                    }
                    if (!state.loading) ChordGlassButton(label = "Analyse again", onClick = { state.startAnalysis(song, source) })
                }
            }
            AnimatedVisibility(visible = (state.guitar || state.piano) && state.show) { Column { InstrumentChordPanel(state) } }
            if (state.events!!.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Not enough audio to estimate chords.", color = Color.White.copy(alpha = 0.8f))
            } else if (!synced && state.show) {
                // No invented lyric alignment when the lyric provider has no timestamps.
                Spacer(Modifier.height(4.dp))
                Text(
                    "Timed lyrics needed to place chords above lines.",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                CurrentChord(state)
            }
        }
    }
}

/** Glass pill button consistent with BitChord player design (frosted glass rim, responsive alpha). */
@Composable
private fun ChordGlassPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalPlayerChrome.current
    val discAlpha = animateFloatAsState(
        targetValue = if (active) 0.32f else 0.14f,
        label = "chordPillAlpha",
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .vibrantChrome(chrome)
                .drawBehind { drawRect(Color.White.copy(alpha = discAlpha.value)) }
        )
        Box(Modifier.matchParentSize().glassRim(CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.3.sp,
            ),
            color = Color.White.copy(alpha = if (active) 1f else 0.72f),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/** Action button (Find chords / Analyse again / Cancel) in BitChord glass aesthetic. */
@Composable
private fun ChordGlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalPlayerChrome.current
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .vibrantChrome(chrome)
                .background(Color.White.copy(alpha = 0.16f))
        )
        Box(Modifier.matchParentSize().glassRim(CircleShape))
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
        "Now: ${events.getOrNull(current)?.label?.takeUnless { it == "N" } ?: "—"}",
        color = Color.White,
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


@Composable
private fun InstrumentChordPanel(state: ChordUiState) {
    val events = remember(state.events, state.simplify, state.notation, state.guitar, state.capo) { state.displayEvents() }
    val suggestion = remember(state.events, state.simplify) {
        suggestedCapo(if (state.simplify) simplifyChordEvents(state.events.orEmpty()) else state.events.orEmpty())
    }
    val position = state.positionMs
    val window = chordFollowWindow(events, position)
    val current = window.firstOrNull()?.takeIf { position >= it.startMs && position < it.endMs }
    val progress = current?.let { ((position - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (state.piano) "Piano · follow song" else "Guitar · capo ${state.capo}", color = chromeWhite(0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        if (state.guitar && !state.options) TextButton(onClick = { state.options = true }) { Text("Adjust", color = Color.White) }
    }
    AnimatedVisibility(visible = state.options && state.guitar) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Capo ${state.capo} · suggested $suggestion", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { state.capo = (state.capo - 1).coerceAtLeast(0) }) { Text("−", color = Color.White) }
                TextButton(onClick = { state.capo = (state.capo + 1).coerceAtMost(11) }) { Text("+", color = Color.White) }
            }
            Text("Shapes relative to capo · standard tuning", color = chromeWhite(0.65f), style = MaterialTheme.typography.labelSmall)
        }
    }
    Row(Modifier.fillMaxWidth().animateContentSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        window.forEachIndexed { index, event ->
            key(event.startMs, event.label) {
                val playing = event == current
                val glow by animateFloatAsState(if (playing) 1f else 0f, label = "playingChordGlow")
                val tint = Color(0xFFFFD88A)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(tint.copy(alpha = 0.03f + glow * 0.16f))
                        .border(1.dp, tint.copy(alpha = 0.1f + glow * 0.7f), RoundedCornerShape(16.dp))
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(if (playing) "NOW" else if (index == 0) "NEXT" else "UP NEXT", color = if (playing) tint else chromeWhite(0.45f), style = MaterialTheme.typography.labelSmall)
                    val shape = guitarShape(event.label)
                    if (state.piano && pianoChordNotes(event.label) != null) PianoDiagram(event.label, if (playing) tint else chromeWhite(0.45f))
                    else if (!state.piano && shape != null) GuitarDiagram(event.label, shape, if (playing) tint else chromeWhite(0.45f))
                    else Text(if (event.label == "N") "Rest" else event.label, color = chromeWhite(0.65f), modifier = Modifier.height(100.dp).padding(top = 24.dp))
                    if (playing) LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(60.dp).height(3.dp), color = tint, trackColor = tint.copy(alpha = 0.12f))
                    else Spacer(Modifier.height(3.dp))
                }
            }
        }
    }
}

@Composable
private fun GuitarDiagram(label: String, shape: GuitarShape, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold)
        Text(shape.frets.joinToString(" ") { if (it < 0) "×" else if (it == 0) "○" else "·" }, color = tint, fontSize = 12.sp)
        Canvas(Modifier.size(72.dp, 82.dp).semantics {
            contentDescription = "$label, low E to high E: ${shape.frets.joinToString { if (it < 0) "muted" else it.toString() }}, frets relative to capo"
        }) {
            val dx = size.width / 7
            val dy = size.height / 5
            for (string in 0..5) drawLine(tint.copy(alpha = 0.6f), Offset(dx * (string + 1), 0f), Offset(dx * (string + 1), dy * 4), 1.dp.toPx())
            for (fret in 0..4) drawLine(tint.copy(alpha = 0.6f), Offset(dx, dy * fret), Offset(dx * 6, dy * fret), if (fret == 0 && shape.firstFret == 1) 3.dp.toPx() else 1.dp.toPx())
            shape.frets.forEachIndexed { string, fret ->
                if (fret > 0) drawCircle(tint, 4.dp.toPx(), Offset(dx * (string + 1), dy * (fret - shape.firstFret + 0.5f)))
            }
        }
        Text("${shape.firstFret} fr · E A D G B e", color = chromeWhite(0.65f), fontSize = 10.sp)
    }
}


@Composable
private fun PianoDiagram(label: String, tint: Color) {
    val notes = remember(label) { pianoChordNotes(label).orEmpty().toSet() }
    val whiteKeys = remember { (0..23).filter { it % 12 in listOf(0, 2, 4, 5, 7, 9, 11) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 5.dp).height(74.dp).semantics {
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
        Text("C · two octaves", color = chromeWhite(0.65f), fontSize = 10.sp, modifier = Modifier.padding(vertical = 5.dp))
    }
}
