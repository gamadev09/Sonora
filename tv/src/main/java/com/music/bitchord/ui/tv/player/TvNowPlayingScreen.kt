package com.music.bitchord.ui.tv.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.music.bitchord.data.LikeState
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TvSettings
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.toggleAutoplay
import com.music.bitchord.playback.toggleShuffle
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.tv.dialogs.TvSongActionMenuDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long the transport stays up after the last key press. */
private const val ControlsTimeoutMs = 5_000L

/** One D-pad press on the scrubber. */
private const val ScrubStepMs = 10_000L

/** How long the scrubber waits for more presses before it actually seeks. */
private const val ScrubCommitDelayMs = 650L

private enum class PlayerSheet { Menu }

/**
 * Now Playing, as Apple Music has it on Apple TV.
 *
 * The screen is the song: the artwork-coloured backdrop, the cover and — when
 * there are lyrics — the lyrics beside it. The transport (title, buttons and
 * scrubber) rises on any press and steps away after a few idle seconds. Down
 * from the scrubber opens Up Next. Back closes Up Next, then the transport,
 * then the player.
 *
 * The playhead is read only inside [TvScrubber]'s draw pass and its once-a-second
 * time labels, so playback ticks recompose nothing else on this screen.
 */
@Composable
fun TvNowPlayingScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val song = playerState.song
    if (song == null) {
        BackHandler(onBack = onBack)
        TvNothingPlaying(onBrowse = onBack, modifier = modifier)
        return
    }

    val scope = rememberCoroutineScope()
    val isPlaying = playerState.isPlaying

    // Keep the TV awake (no screensaver) while a song plays on this screen.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }
    val position = playerState.position
    val durationMs = playerState.durationMs

    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val lyricsChecked by viewModel.lyricsChecked.collectAsStateWithLifecycle()
    val likeOverrides by LikeState.overrides.collectAsStateWithLifecycle()
    val isLiked = likeOverrides[song.videoId] == LikeStatus.LIKE
    val shuffle by com.music.bitchord.playback.QueueShuffle.enabled.collectAsStateWithLifecycle()
    val autoplay by AppSettings.autoplay.collectAsStateWithLifecycle()
    val playerStyle by TvSettings.tvPlayerStyle.collectAsStateWithLifecycle()

    // Avoid restarting lyrics fetches on minor duration updates during buffering/DASH adaptation.
    val hasDuration = durationMs > 0L
    LaunchedEffect(song.videoId, hasDuration) {
        if (hasDuration || lyrics == null) {
            viewModel.loadLyrics(
                videoId = song.videoId,
                title = song.title,
                artist = song.artist,
                durationMs = durationMs.coerceAtLeast(0L),
                album = song.albumName,
            )
        }
    }

    var lyricsHidden by rememberSaveable { mutableStateOf(false) }
    val hasLyrics = !lyrics.isNullOrEmpty()
    val showLyrics = hasLyrics && !lyricsHidden

    var controlsVisible by remember { mutableStateOf(true) }
    var upNextOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    // Signal idle timer without triggering full-screen recomposition on every D-pad key press.
    val inputSignals = remember { Channel<Unit>(Channel.CONFLATED) }
    var flash by remember { mutableStateOf<TvFlash?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    // A scrub in progress: where the playhead will go once the presses stop.
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubJob by remember { mutableStateOf<Job?>(null) }

    // Cancel any pending scrub if the active song changes or this screen leaves composition.
    DisposableEffect(song.videoId) {
        onDispose {
            scrubJob?.cancel()
            scrubJob = null
            scrubTarget = null
        }
    }

    val stageFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }

    val currentController by rememberUpdatedState(mediaController)
    val currentDurationMs by rememberUpdatedState(durationMs)
    val currentPosition by rememberUpdatedState(position)

    val togglePlay: () -> Unit = remember {
        { currentController?.let { if (it.isPlaying) it.pause() else it.play() } }
    }
    val showFlash: (TvFlash) -> Unit = remember {
        {
            flash = it
            flashTick++
        }
    }
    val skipNext: () -> Unit = remember {
        {
            currentController?.seekToNextMediaItem()
            if (!controlsVisible) showFlash(TvFlash.Next)
        }
    }
    val skipPrevious: () -> Unit = remember {
        {
            currentController?.seekToPrevious()
            if (!controlsVisible) showFlash(TvFlash.Previous)
        }
    }
    val commitScrub: () -> Unit = remember {
        {
            scrubJob?.cancel()
            scrubTarget?.let { currentController?.seekTo(it) }
            scrubTarget = null
        }
    }
    val scrubBy: (Long) -> Unit = remember(scope) {
        { delta ->
            val from = scrubTarget ?: currentPosition.positionMs
            scrubTarget = (from + delta).coerceIn(0L, currentDurationMs.coerceAtLeast(0L))
            scrubJob?.cancel()
            scrubJob = scope.launch {
                delay(ScrubCommitDelayMs)
                commitScrub()
            }
        }
    }
    val revealControls: () -> Unit = remember { { controlsVisible = true } }
    val hideControls: () -> Unit = remember {
        {
            upNextOpen = false
            controlsVisible = false
        }
    }
    val onSeek: (Long) -> Unit = remember { { ms -> currentController?.seekTo(ms) } }

    // The transport steps away after a few idle seconds — never while Up Next
    // or a sheet is open, a scrub is pending, or the music is paused.
    LaunchedEffect(controlsVisible, upNextOpen, sheet, isPlaying, scrubTarget) {
        if (controlsVisible && !upNextOpen && sheet == null && isPlaying && scrubTarget == null) {
            while (true) {
                val received = withTimeoutOrNull(ControlsTimeoutMs) { inputSignals.receive() }
                if (received == null) {
                    hideControls()
                    break
                }
            }
        }
    }
    // Focus follows what's on screen: the scrubber when the transport rises,
    // the stage (which takes the remote's presses) when it goes.
    LaunchedEffect(controlsVisible) {
        delay(60)
        runCatching { if (controlsVisible) scrubberFocus.requestFocus() else stageFocus.requestFocus() }
    }
    LaunchedEffect(upNextOpen) {
        if (upNextOpen) {
            delay(60)
            runCatching { upNextFocus.requestFocus() }
        } else if (controlsVisible) {
            runCatching { scrubberFocus.requestFocus() }
        }
    }

    BackHandler(enabled = sheet == null) {
        when {
            upNextOpen -> upNextOpen = false
            controlsVisible -> hideControls()
            else -> onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                inputSignals.trySend(Unit)
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        togglePlay()
                        if (!controlsVisible) showFlash(if (isPlaying) TvFlash.Pause else TvFlash.Play)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { mediaController?.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { mediaController?.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { skipNext(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skipPrevious(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { revealControls(); scrubBy(ScrubStepMs); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { revealControls(); scrubBy(-ScrubStepMs); true }
                    else -> false
                }
            },
    ) {
        TvPlayerBackground(artworkUrl = song.thumbnailUrl, style = playerStyle)

        // The stage: takes the remote while the transport is down. Select or a
        // vertical press brings the transport up; left and right change track.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(stageFocus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || controlsVisible) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { revealControls(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> { skipPrevious(); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { skipNext(); true }
                        else -> false
                    }
                }
                .focusable(),
        )

        val dim = animateFloatAsState(if (upNextOpen) 0.12f else 1f, tween(280), label = "stageDim")
        TvNowPlayingStage(
            song = song,
            isPlaying = isPlaying,
            position = position,
            lyrics = lyrics.orEmpty(),
            showLyrics = showLyrics,
            lyricsLoading = !lyricsChecked && lyrics == null && !lyricsHidden,
            controlsVisible = controlsVisible,
            onSeek = onSeek,
            modifier = Modifier.graphicsLayer { alpha = dim.value },
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(240)) + slideInVertically(tween(320)) { it / 6 },
            exit = fadeOut(tween(320)) + slideOutVertically(tween(320)) { it / 6 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            TvTransport(
                song = song,
                isPlaying = isPlaying,
                isLoading = playerState.isLoading,
                position = position,
                durationMs = durationMs,
                scrubTarget = scrubTarget,
                isLiked = isLiked,
                lyricsAvailable = hasLyrics,
                lyricsOn = showLyrics,
                upNextOpen = upNextOpen,
                scrubberFocus = scrubberFocus,
                onScrub = scrubBy,
                onSelectOnScrubber = {
                    if (scrubTarget != null) commitScrub() else togglePlay()
                },
                onOpenUpNext = { upNextOpen = true },
                onToggleLike = { viewModel.toggleLike(song.videoId) },
                onToggleLyrics = { lyricsHidden = !lyricsHidden },
                onToggleUpNext = { upNextOpen = !upNextOpen },
                onMore = { sheet = PlayerSheet.Menu },
                upNext = {
                    TvUpNext(
                        queue = playerState.queue,
                        currentIndex = playerState.queueIndex,
                        shuffle = shuffle,
                        repeatMode = playerState.repeatMode,
                        autoplay = autoplay,
                        firstCardFocus = upNextFocus,
                        onJump = { index ->
                            mediaController?.seekToDefaultPosition(index)
                            mediaController?.play()
                        },
                        onToggleShuffle = { mediaController?.toggleShuffle() },
                        onCycleRepeat = {
                            mediaController?.let {
                                val next = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                                AppSettings.setRepeatMode(next)
                                it.repeatMode = next
                            }
                        },
                        onToggleAutoplay = { mediaController?.toggleAutoplay() },
                    )
                },
            )
        }

        TvFlashGlyph(
            flash = flash,
            tick = flashTick,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    when (sheet) {
        PlayerSheet.Menu -> TvSongActionMenuDialog(
            song = song,
            isLiked = isLiked,
            viewModel = viewModel,
            mediaController = mediaController,
            onToggleLike = { viewModel.toggleLike(song.videoId) },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}
