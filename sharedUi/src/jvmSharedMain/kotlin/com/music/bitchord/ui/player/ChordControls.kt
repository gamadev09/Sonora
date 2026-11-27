package com.music.bitchord.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlin.math.abs
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
    /** Discrete position updated on chord transitions and seeks so diagrams/lyrics avoid per-frame recomposition. */
    var positionMs by mutableLongStateOf(0L)
    /** Authoritative audio playhead for isolated continuous chord-progress drawing. */
    var playhead: LyricPlayhead? = null
    var active by mutableStateOf(false)
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
    SideEffect {
        state.accentColor = animatedAccent
        state.playhead = playhead
        state.active = active
    }
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
    // Discrete chord-selection watcher: tracks displayed chord intervals (respecting
    // simplify/capo) and seek discontinuities so lyrics and diagram cards only recompose
    // when the active chord actually changes, while [ChordDurationProgressBar] animates
    // continuously off the authoritative playhead in its own isolated scope.
    LaunchedEffect(state, active, playhead, state.show, state.events, state.simplify, state.notation, state.guitar, state.capo) {
        val displayed = state.displayEvents()
        if (!active || !state.show || displayed.isEmpty()) return@LaunchedEffect
        var lastChordIdx = Int.MIN_VALUE
        var lastDiscontinuity = Long.MIN_VALUE
        while (true) {
            val nextPos = playhead.audioPositionMs
            val nextChordIdx = chordIndexAt(displayed, nextPos)
            val discontinuity = playhead.discontinuity
            if (nextChordIdx != lastChordIdx || discontinuity != lastDiscontinuity) {
                lastChordIdx = nextChordIdx
                lastDiscontinuity = discontinuity
                state.positionMs = nextPos
            }
            delay(50)
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
    val tint = when {
        !enabled -> chromeWhite(0.42f)
        active -> state.accentColor
        else -> Color.White
    }
    CircleGlyphContainer(
        active = active,
        enabled = enabled,
        onClick = {
            if (state.loading) {
                state.cancelAnalysis(song, source)
            } else if (state.events == null) {
                state.startAnalysis(song, source)
            } else {
                state.show = !state.show
            }
        },
        modifier = modifier,
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

/**
 * Compact text-only glass capsule selector matching Sonora's FloatingBottomBar:
 * blurred translucent capsule backdrop ([playerBarSurface]), top-lit [glassRim],
 * evenly distributed options, and a spring-animated sliding active pill with
 * subtle stretch/squash motion (respecting `reduceAnimation` and `reduceDynamicBlur`).
 */
@Composable
private fun GlassCapsuleSelector(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    accentColor: Color,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 36.dp,
    innerInset: Dp = 4.dp,
    useBackdropFrost: Boolean = true,
) {
    val pillShape = RoundedCornerShape(percent = 50)
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val spec: AnimationSpec<Float> = if (reduceAnimation) {
        snap()
    } else {
        spring(dampingRatio = 0.68f, stiffness = 420f)
    }
    val density = LocalDensity.current
    var rowSize by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { 4.dp.toPx() }
    val n = options.size.coerceAtLeast(1)
    val tabWidthPx = if (rowSize.width > 0) (rowSize.width - gapPx * (n - 1)) / n else 0f
    val tabStepPx = if (rowSize.width > 0) (rowSize.width + gapPx) / n else 0f
    val targetOffset = if (tabStepPx > 0f) selectedIndex.coerceIn(0, n - 1) * tabStepPx else 0f
    val animatedOffset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = spec,
        label = "capsuleSelectorOffset",
    )
    val lag = if (tabStepPx > 0f) (abs(targetOffset - animatedOffset) / tabStepPx).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .then(
                if (useBackdropFrost) {
                    Modifier
                        .playerBarSurface(pillShape, hazeState)
                        .glassRim(pillShape)
                } else {
                    Modifier
                        .clip(pillShape)
                        .background(Color.White.copy(alpha = 0.09f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.12f), pillShape)
                },
            )
            .padding(horizontal = innerInset, vertical = innerInset),
    ) {
        if (tabWidthPx > 0f && rowSize.height > 0) {
            Box(
                modifier = Modifier
                    .width(with(density) { tabWidthPx.toDp() })
                    .height(with(density) { rowSize.height.toDp() })
                    .graphicsLayer {
                        translationX = animatedOffset
                        scaleX = 1f + lag * 0.14f
                        scaleY = 1f - lag * 0.14f * 0.35f
                    }
                    .clip(pillShape)
                    .background(accentColor.copy(alpha = 0.32f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.22f), pillShape),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowSize = it },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(itemHeight)
                        .clip(pillShape)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else chromeWhite(0.68f),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChordControls(
    song: Song,
    source: ChordAnalysisSource,
    state: ChordUiState,
    active: Boolean,
    synced: Boolean,
    hazeState: HazeState? = null,
) {
    if (!active) return
    val instruments = remember {
        listOf(ChordInstrument.INLINE, ChordInstrument.GUITAR, ChordInstrument.PIANO)
    }
    val instrumentLabels = listOf(
        stringResource(Res.string.chord_inline),
        stringResource(Res.string.chord_guitar),
        stringResource(Res.string.chord_piano),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
            .animateContentSize()
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val inlineCapoFits = maxWidth >= 390.dp
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    state.loading -> {
                        Text(
                            stringResource(Res.string.chord_analyzing),
                            color = chromeWhite(0.75f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.canCancel || state.job != null) {
                            ChordGlassButton(label = stringResource(Res.string.chord_cancel), onClick = { state.cancelAnalysis(song, source) })
                        }
                    }
                    state.events == null -> {
                        ChordGlassButton(label = stringResource(Res.string.chord_find), onClick = { state.startAnalysis(song, source) })
                    }
                    else -> {
                        GlassCapsuleSelector(
                            options = instrumentLabels,
                            selectedIndex = instruments.indexOf(state.instrument).coerceAtLeast(0),
                            onSelect = { idx ->
                                val instrument = instruments[idx]
                                if (instrument == ChordInstrument.GUITAR && !state.capoManuallySet) {
                                    val raw = state.events.orEmpty()
                                    val basis = if (state.simplify) simplifyChordEvents(raw) else raw
                                    state.capo = suggestedCapo(basis)
                                }
                                state.instrument = instrument
                                state.show = true
                            },
                            accentColor = state.accentColor,
                            hazeState = hazeState,
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 264.dp),
                        )
                        if (state.guitar && inlineCapoFits) {
                            Spacer(Modifier.weight(0.15f))
                            val capoShape = RoundedCornerShape(percent = 50)
                            Row(
                                Modifier
                                    .playerBarSurface(capoShape, hazeState)
                                    .glassRim(capoShape),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        state.capo = (state.capo - 1).coerceAtLeast(0)
                                        state.capoManuallySet = true
                                    },
                                    enabled = state.capo > 0,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Rounded.Remove, stringResource(Res.string.chord_capo_decrease), tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    stringResource(Res.string.chord_capo_value, state.capo),
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                IconButton(
                                    onClick = {
                                        state.capo = (state.capo + 1).coerceAtMost(11)
                                        state.capoManuallySet = true
                                    },
                                    enabled = state.capo < 11,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Rounded.Add, stringResource(Res.string.chord_capo_increase), tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (state.guitar && !inlineCapoFits) {
                            val badgeShape = RoundedCornerShape(percent = 50)
                            Box(
                                Modifier
                                    .height(34.dp)
                                    .playerBarSurface(badgeShape, hazeState)
                                    .glassRim(badgeShape)
                                    .clickable(role = Role.Button) { state.options = true }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(Res.string.chord_capo_short, state.capo),
                                    color = state.accentColor,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                        Box(contentAlignment = Alignment.Center) {
                            CircleGlyphContainer(
                                active = state.options,
                                onClick = { state.options = !state.options },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = stringResource(Res.string.chord_options),
                                    tint = if (state.options) state.accentColor else Color.White,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                            ChordOptionsPopover(
                                expanded = state.options,
                                onDismiss = { state.options = false },
                                state = state,
                                song = song,
                                source = source,
                                showCapoInMenu = state.guitar && !inlineCapoFits,
                                hazeState = hazeState,
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
            color = if (current == 0) state.accentColor else DIM_CHORD_COLOR,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            modifier = lineModifier,
        )
        return
    }
    val accent = state.accentColor
    val chordText = remember(events, current, accent) {
        buildAnnotatedString {
            events.forEachIndexed { index, event ->
                if (index > 0) append("   ")
                val label = if (event.label == "N") "—" else event.label
                withStyle(SpanStyle(color = if (current == index) accent else DIM_CHORD_COLOR)) {
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

/**
 * Blurred chord-options popover matching Sonora's overflow menu (`playerMenuSurface`,
 * `glassRim`, spring scale/alpha animation, hairline dividers, and shared
 * [GlassCapsuleSelector] for notation).
 */
@Composable
private fun ChordOptionsPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: ChordUiState,
    song: Song,
    source: ChordAnalysisSource,
    showCapoInMenu: Boolean,
    hazeState: HazeState?,
) {
    var mounted by remember { mutableStateOf(expanded) }
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        if (expanded) {
            mounted = true
            progress.animateTo(
                1f,
                if (reduceAnimation) {
                    tween(durationMillis = 120)
                } else {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)
                },
            )
        } else if (mounted) {
            progress.animateTo(
                0f,
                tween(durationMillis = if (reduceAnimation) 100 else 170, easing = FastOutSlowInEasing),
            )
            mounted = false
        }
    }
    if (!mounted) return

    val density = LocalDensity.current
    val yOffsetPx = with(density) { 42.dp.roundToPx() }
    val menuShape = RoundedCornerShape(22.dp)
    val notations = remember { listOf(ChordNotation.AUTO, ChordNotation.SHARPS, ChordNotation.FLATS) }
    val notationLabels = listOf(
        stringResource(Res.string.chord_notation_auto),
        stringResource(Res.string.chord_notation_sharps),
        stringResource(Res.string.chord_notation_flats),
    )

    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, yOffsetPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .width(272.dp)
                .graphicsLayer {
                    val t = progress.value.coerceIn(0f, 1f)
                    val s = 0.88f + 0.12f * t
                    scaleX = s
                    scaleY = s
                    alpha = t
                    transformOrigin = TransformOrigin(1f, 0f)
                }
                .shadow(
                    elevation = 16.dp,
                    shape = menuShape,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.45f),
                )
                .playerMenuSurface(menuShape, hazeState)
                .glassRim(menuShape)
                .padding(vertical = 6.dp),
        ) {
            if (showCapoInMenu) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.chord_capo_value, state.capo),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                state.capo = (state.capo - 1).coerceAtLeast(0)
                                state.capoManuallySet = true
                            },
                            enabled = state.capo > 0,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Rounded.Remove, stringResource(Res.string.chord_capo_decrease), tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                state.capo = (state.capo + 1).coerceAtMost(11)
                                state.capoManuallySet = true
                            },
                            enabled = state.capo < 11,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(Icons.Rounded.Add, stringResource(Res.string.chord_capo_increase), tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clickable { state.simplify = !state.simplify }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.chord_simplify),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChordOptionToggle(checked = state.simplify, accent = state.accentColor)
            }
            if (state.guitar || state.piano) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(horizontal = 14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clickable { state.diagramsExpanded = !state.diagramsExpanded }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(Res.string.chord_show_diagrams),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ChordOptionToggle(checked = state.diagramsExpanded, accent = state.accentColor)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                GlassCapsuleSelector(
                    options = notationLabels,
                    selectedIndex = notations.indexOf(state.notation).coerceAtLeast(0),
                    onSelect = { state.notation = notations[it] },
                    accentColor = state.accentColor,
                    hazeState = null,
                    itemHeight = 32.dp,
                    innerInset = 3.dp,
                    useBackdropFrost = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clickable {
                        onDismiss()
                        state.startAnalysis(song, source)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.chord_reanalyse),
                    color = state.accentColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/** Compact Sonora-styled toggle pill for chord options, avoiding oversized Material switches. */
@Composable
private fun ChordOptionToggle(checked: Boolean, accent: Color) {
    val thumbBias by animateFloatAsState(targetValue = if (checked) 1f else 0f, label = "chordToggle")
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(CircleShape)
            .background(if (checked) accent.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.18f))
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { translationX = thumbBias * 16.dp.toPx() }
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * Isolated, frame-aligned chord duration progress bar driven by the authoritative
 * audio [playhead]. Reading the progress state inside [LinearProgressIndicator]'s
 * lambda updates only the indicator's draw phase without recomposing the diagram cards.
 */
@Composable
private fun ChordDurationProgressBar(
    event: ChordEvent,
    playhead: LyricPlayhead?,
    active: Boolean,
    color: Color,
    width: Dp,
) {
    val progressState = remember(event.startMs, event.endMs) {
        mutableFloatStateOf(
            playhead?.let { chordProgressFraction(event, it.audioPositionMs) } ?: 0f,
        )
    }
    LaunchedEffect(event.startMs, event.endMs, playhead, active) {
        if (!active || playhead == null) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                val interpolatedMs = interpolatedChordPositionMs(
                    sampledPositionMs = playhead.audioPositionMs,
                    sampledAtNanos = playhead.sampledAtNanos,
                    nowNanos = System.nanoTime(),
                    advancing = playhead.isAdvancing(true),
                )
                val next = chordProgressFraction(event, interpolatedMs)
                if (kotlin.math.abs(next - progressState.floatValue) >= 0.001f || next == 0f || next == 1f) {
                    progressState.floatValue = next
                }
            }
        }
    }
    LinearProgressIndicator(
        progress = { progressState.floatValue },
        modifier = Modifier.width(width).height(3.dp),
        color = color,
        trackColor = color.copy(alpha = 0.14f),
    )
}

@Composable
private fun InstrumentChordPanel(state: ChordUiState) {
    val events = remember(state.events, state.simplify, state.notation, state.guitar, state.capo) { state.displayEvents() }
    val position = state.positionMs
    val window = chordFollowWindow(events, position)
    val current = window.firstOrNull()?.takeIf { position >= it.startMs && position < it.endMs }
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        window.forEachIndexed { index, event ->
            key(event.startMs, event.endMs, event.label) {
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
                    else Text(if (event.label == "N") stringResource(Res.string.chord_rest) else event.label, color = chromeWhite(0.9f), modifier = Modifier.height(92.dp).padding(top = 18.dp))
                    if (playing) {
                        ChordDurationProgressBar(
                            event = event,
                            playhead = state.playhead,
                            active = state.active && state.show,
                            color = tint,
                            width = if (state.piano) 180.dp else 88.dp,
                        )
                    } else {
                        Spacer(Modifier.height(3.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuitarDiagram(label: String, shape: GuitarShape, tint: Color) {
    val mutedWord = stringResource(Res.string.chord_muted)
    val fretSummary = remember(shape.frets, mutedWord) {
        shape.frets.joinToString { if (it < 0) mutedWord else it.toString() }
    }
    val accessibilityText = stringResource(Res.string.chord_guitar_description, label, fretSummary)
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
            contentDescription = accessibilityText
        }) {
            // Divide into 6 equal string columns so muted/open markers above, strings,
            // finger markers, barre, and E-A-D-G-B-e labels below share identical centers.
            val dx = size.width / 6f
            fun stringX(string: Int): Float = dx * (string + 0.5f)
            val topPad = 4.dp.toPx()
            val bottomPad = 4.dp.toPx()
            val dy = (size.height - topPad - bottomPad) / 4f
            for (string in 0..5) {
                val x = stringX(string)
                drawLine(tint.copy(alpha = 0.82f), Offset(x, topPad), Offset(x, topPad + dy * 4), 1.2.dp.toPx())
            }
            for (fret in 0..4) {
                val y = topPad + dy * fret
                drawLine(
                    tint.copy(alpha = 0.82f),
                    Offset(stringX(0), y),
                    Offset(stringX(5), y),
                    if (fret == 0 && shape.firstFret == 1) 2.5.dp.toPx() else 1.2.dp.toPx(),
                )
            }
            val firstPlayed = shape.frets.indexOfFirst { it >= 0 }
            val lastPlayed = shape.frets.indexOfLast { it >= 0 }
            val barre = shape.firstFret > 0 &&
                firstPlayed >= 0 && lastPlayed > firstPlayed &&
                shape.frets[firstPlayed] == shape.firstFret &&
                shape.frets[lastPlayed] == shape.firstFret &&
                shape.frets.count { it == shape.firstFret } >= 2 &&
                shape.frets.subList(firstPlayed, lastPlayed + 1).all { it >= shape.firstFret }
            if (barre) {
                val radius = 5.dp.toPx()
                val startX = stringX(firstPlayed)
                val endX = stringX(lastPlayed)
                drawRoundRect(
                    tint,
                    topLeft = Offset(startX - radius, topPad + dy * 0.5f - radius),
                    size = Size((endX - startX) + 2 * radius, 2 * radius),
                    cornerRadius = CornerRadius(radius),
                )
            }
            shape.frets.forEachIndexed { string, fret ->
                if (fret > 0 && !(barre && fret == shape.firstFret)) {
                    drawCircle(
                        tint,
                        5.dp.toPx(),
                        Offset(stringX(string), topPad + dy * (fret - shape.firstFret + 0.5f)),
                    )
                }
            }
        }
        Row(Modifier.width(92.dp)) {
            listOf("E", "A", "D", "G", "B", "e").forEach { name ->
                Text(name, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = chromeWhite(0.85f), fontSize = 11.sp)
            }
        }
        if (shape.firstFret > 1) {
            Text(stringResource(Res.string.chord_fret, shape.firstFret), color = chromeWhite(0.85f), fontSize = 11.sp)
        }
    }
}


@Composable
private fun PianoDiagram(label: String, tint: Color) {
    val notes = remember(label) { pianoChordNotes(label).orEmpty().toSet() }
    val whiteKeys = remember { (0..23).filter { it % 12 in listOf(0, 2, 4, 5, 7, 9, 11) } }
    val noteNames = remember(notes, label) {
        val chromatic = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        notes.sorted().joinToString(" · ") { spellChord(chromatic[it % 12], label.contains('♭')) }
    }
    val accessibilityText = stringResource(Res.string.chord_piano_description, label, noteNames)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = tint, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(68.dp).semantics {
            contentDescription = accessibilityText
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
        Text(noteNames, color = chromeWhite(0.9f), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
    }
}
