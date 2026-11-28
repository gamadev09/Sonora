package com.music.bitchord.ui.tv

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.settings.TvSettings
import com.music.bitchord.playback.PlayerState
import com.music.bitchord.playback.playSongs
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.tv.auth.TvAuthServer
import com.music.bitchord.ui.tv.auth.TvRemoteAdvertiser
import com.music.bitchord.ui.tv.auth.TvRemoteStatus
import com.music.bitchord.ui.tv.components.LocalTvChrome
import com.music.bitchord.ui.tv.components.TvArtwork
import com.music.bitchord.ui.tv.components.TvChrome
import com.music.bitchord.ui.tv.components.TvTabIcon
import com.music.bitchord.ui.tv.components.TvTabPill
import com.music.bitchord.ui.tv.components.rememberDominantCardColor
import com.music.bitchord.ui.tv.components.tvClick
import com.music.bitchord.ui.tv.components.tvLift
import com.music.bitchord.ui.tv.dialogs.TvAccountDialog
import com.music.bitchord.ui.tv.dialogs.TvUpdateDialog
import com.music.bitchord.ui.tv.player.TvNowPlayingScreen
import com.music.bitchord.ui.tv.screens.TvDetailScreen
import com.music.bitchord.ui.tv.screens.TvExploreScreen
import com.music.bitchord.ui.tv.screens.TvHomeScreen
import com.music.bitchord.ui.tv.screens.TvLibraryScreen
import com.music.bitchord.ui.tv.screens.TvSearchScreen
import com.music.bitchord.ui.tv.screens.TvSettingsScreen
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tabs of the top bar. Now Playing is a tab too, but it opens the player rather than a page. */
enum class TvDestination(val label: String) {
    HOME("Home"),
    EXPLORE("New"),
    LIBRARY("Library"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

/** A pushed album, playlist or artist page. */
data class TvDetailRoute(
    val browseId: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val type: BrowseType,
)

typealias TvOpenDetail = (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit

@Composable
fun TvApp(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    modifier: Modifier = Modifier,
) {
    val setupVersionCompleted by TvSettings.tvSetupVersionCompleted.collectAsState()
    var isRunningSetup by remember(setupVersionCompleted) { mutableStateOf(setupVersionCompleted == 0) }
    var isNowPlayingOpen by remember { mutableStateOf(false) }
    val googleAccounts by viewModel.googleAccounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()

    val initialProfileLocked = remember {
        val profId = viewModel.activeProfileId.value
            ?: viewModel.googleAccounts.value.firstOrNull { it.accountId == viewModel.activeAccountId.value }
                ?.let { acc ->
                    if (acc.profiles.isNotEmpty()) {
                        acc.profiles.firstOrNull { !it.isBrandAccount }?.profileId
                            ?: acc.profiles.firstOrNull()?.profileId
                    } else {
                        "default:${acc.accountId}"
                    }
                }
            ?: viewModel.googleAccounts.value.firstOrNull()?.let { acc ->
                if (acc.profiles.isNotEmpty()) {
                    acc.profiles.firstOrNull { !it.isBrandAccount }?.profileId
                        ?: acc.profiles.firstOrNull()?.profileId
                } else {
                    "default:${acc.accountId}"
                }
            }
        profId != null && TvSettings.isProfileLocked(profId)
    }

    var sessionAuthenticated by remember { mutableStateOf(!initialProfileLocked) }
    var showAccountDialog by remember { mutableStateOf(initialProfileLocked) }

    val currentActiveProfileId = remember(googleAccounts, activeAccountId, activeProfileId) {
        if (activeProfileId != null) {
            activeProfileId
        } else {
            val account = googleAccounts.firstOrNull { it.accountId == activeAccountId }
                ?: googleAccounts.firstOrNull()
            if (account != null) {
                if (account.profiles.isNotEmpty()) {
                    account.profiles.firstOrNull { !it.isBrandAccount }?.profileId
                        ?: account.profiles.firstOrNull()?.profileId
                } else {
                    "default:${account.accountId}"
                }
            } else {
                null
            }
        }
    }
    val isCurrentProfileLocked = currentActiveProfileId != null && TvSettings.isProfileLocked(currentActiveProfileId)

    LaunchedEffect(isCurrentProfileLocked) {
        if (isCurrentProfileLocked && !sessionAuthenticated) {
            showAccountDialog = true
        }
    }

    // Keep the local LAN remote server & mDNS advertiser active while Sonora is open on TV
    // so the phone's AirPlay / Output sheet can discover and control playback.
    val appContext = LocalContext.current.applicationContext
    val remoteScope = rememberCoroutineScope()
    val latestController by rememberUpdatedState(mediaController)
    val latestPlayerState by rememberUpdatedState(playerState)

    LaunchedEffect(appContext) {
        val started = TvAuthServer.ensureRunning()
        if (started != null) {
            TvRemoteAdvertiser.start(appContext, started.first)
        }
    }
    DisposableEffect(appContext) {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        TvAuthServer.statusProvider = {
            val ps = latestPlayerState
            val s = ps.song
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
            val curVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVol / 2)
            val volPct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)
            TvRemoteStatus(
                isPlaying = ps.isPlaying,
                title = s?.title ?: "Nothing playing",
                artist = s?.artist?.ifBlank { "Sonora" } ?: "Sonora",
                artworkUrl = s?.thumbnailUrl,
                currentPositionMs = ps.position.positionMs,
                durationMs = ps.durationMs,
                volumePercent = volPct,
                videoId = s?.videoId,
            )
        }
        TvAuthServer.onPlayPauseAction = {
            remoteScope.launch(Dispatchers.Main) {
                latestController?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
        TvAuthServer.onNextAction = {
            remoteScope.launch(Dispatchers.Main) { latestController?.seekToNextMediaItem() }
        }
        TvAuthServer.onPreviousAction = {
            remoteScope.launch(Dispatchers.Main) { latestController?.seekToPrevious() }
        }
        TvAuthServer.onSeekAction = { ms ->
            remoteScope.launch(Dispatchers.Main) { latestController?.seekTo(ms.coerceAtLeast(0L)) }
        }
        TvAuthServer.onVolumeAction = { percent ->
            audioManager?.let { am ->
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val target = ((percent / 100f) * maxVol).toInt().coerceIn(0, maxVol)
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            }
        }
        TvAuthServer.onPlaySongAction = { song ->
            remoteScope.launch(Dispatchers.Main) {
                latestController?.playSongs(listOf(song), 0)
                isNowPlayingOpen = true
            }
        }
        onDispose {
            TvRemoteAdvertiser.stop()
            TvAuthServer.statusProvider = null
            TvAuthServer.onPlayPauseAction = null
            TvAuthServer.onNextAction = null
            TvAuthServer.onPreviousAction = null
            TvAuthServer.onSeekAction = null
            TvAuthServer.onVolumeAction = null
            TvAuthServer.onPlaySongAction = null
        }
    }

    // Offered once per launch, and not over first-time setup. Settings'
    // "Software Update" row reopens it after it is dismissed.
    val update by viewModel.updateAvailable.collectAsState()
    var updateDismissed by remember { mutableStateOf(false) }

    val spatialAudio by TvSettings.spatialAudioEnabled.collectAsState()
    LaunchedEffect(spatialAudio) {
        com.music.bitchord.ui.tv.audio.TvSpatialAudioEngine.setEnabled(spatialAudio)
    }

    val signedIn by viewModel.signedIn.collectAsState()
    LaunchedEffect(signedIn) {
        if (!signedIn) {
            TvSettings.wipeAllPins()
        }
    }


    Box(
        modifier = modifier
            .fillMaxSize()
            .tvCanvas(playerState.song?.thumbnailUrl, covered = isNowPlayingOpen),
    ) {
        AnimatedContent(
            targetState = isRunningSetup,
            transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(160))) },
            label = "tvAppView",
            modifier = Modifier.fillMaxSize(),
        ) { setup ->
            if (setup) {
                com.music.bitchord.ui.tv.onboarding.TvSetupScreen(
                    viewModel = viewModel,
                    onComplete = { isRunningSetup = false },
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The pages stay alive under the player, so closing it comes
                    // back to the same tab, scroll position and focused card.
                    TvMainShell(
                        viewModel = viewModel,
                        mediaController = mediaController,
                        playerState = playerState,
                        covered = isNowPlayingOpen,
                        onOpenNowPlaying = { isNowPlayingOpen = true },
                        onOpenAccount = {
                            sessionAuthenticated = false
                            showAccountDialog = true
                        },
                        onRunSetupAgain = { isRunningSetup = true },
                    )
                    AnimatedVisibility(
                        visible = isNowPlayingOpen,
                        enter = scaleIn(initialScale = 0.94f, animationSpec = tween(260)) + fadeIn(tween(220)),
                        exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.97f, animationSpec = tween(180)),
                    ) {
                        // The player places its own focus (scrubber or stage).
                        Box(modifier = Modifier.fillMaxSize().focusGroup()) {
                            TvNowPlayingScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                playerState = playerState,
                                onBack = { isNowPlayingOpen = false },
                            )
                        }
                    }
                }
            }
        }

        if (showAccountDialog && !isRunningSetup) {
            TvAccountDialog(
                viewModel = viewModel,
                onDismiss = {
                    sessionAuthenticated = true
                    showAccountDialog = false
                },
                allowPhoneSignIn = true,
            )
        }

        val pendingUpdate = update
        if (pendingUpdate != null && !updateDismissed && !isRunningSetup && !showAccountDialog) {
            TvUpdateDialog(update = pendingUpdate, onDismiss = { updateDismissed = true })
        }
    }
}

/**
 * The screen-filling background: a soft top-to-bottom gradient with a wash of the
 * playing song's artwork colour across the top, as Apple Music tints its canvas.
 * Drawn behind everything in one pass; the tint animates in the draw phase.
 */
@Composable
private fun Modifier.tvCanvas(artworkUrl: String?, covered: Boolean = false): Modifier {
    val palette = TvThemeColors.current
    val tintSource = rememberDominantCardColor(
        artworkUrl = if (palette.tintsCanvas && !covered) artworkUrl else null,
        defaultColor = palette.canvasTop,
    )
    val tint = animateColorAsState(tintSource, tween(900), label = "canvasTint")
    val top = palette.canvasTop
    val bottom = palette.canvasBottom
    return drawWithCache {
        val baseBrush = Brush.verticalGradient(listOf(top, bottom))
        val radialCenter = Offset(size.width * 0.5f, -size.height * 0.15f)
        val radialRadius = size.width * 0.75f
        onDrawBehind {
            if (covered) return@onDrawBehind
            drawRect(baseBrush)
            if (palette.tintsCanvas) {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(tint.value.copy(alpha = 0.55f), Color.Transparent),
                        center = radialCenter,
                        radius = radialRadius,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TvMainShell(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    playerState: PlayerState,
    covered: Boolean,
    onOpenNowPlaying: () -> Unit,
    onOpenAccount: () -> Unit,
    onRunSetupAgain: () -> Unit,
) {
    val chrome = remember { TvChrome() }
    // What had focus when the player opened over this shell; it gets it back on close.
    var focusBeforePlayer by remember { mutableStateOf<FocusRequester?>(null) }
    val openNowPlaying: () -> Unit = {
        focusBeforePlayer = chrome.lastFocused
        onOpenNowPlaying()
    }
    val stateHolder = rememberSaveableStateHolder()
    var destination by remember { mutableStateOf(TvDestination.HOME) }
    val detailStack = remember { mutableStateListOf<TvDetailRoute>() }
    val tabFocus = remember { FocusRequester() }
    var barHasFocus by remember { mutableStateOf(false) }
    // The card each pushed page was opened from, so Back can hand focus back to it.
    val returnFocus = remember { mutableListOf<FocusRequester?>() }

    val signedIn by viewModel.signedIn.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    LaunchedEffect(signedIn, activeProfileId) {
        if (!signedIn) {
            if (detailStack.isNotEmpty()) {
                detailStack.clear()
                returnFocus.clear()
                viewModel.clearDetail()
            }
            chrome.contentScrolled = false
            destination = TvDestination.HOME
        } else if (detailStack.isNotEmpty()) {
            detailStack.clear()
            returnFocus.clear()
            viewModel.clearDetail()
        }
    }

    val select: (TvDestination) -> Unit = { next ->
        if (detailStack.isNotEmpty()) {
            detailStack.clear()
            returnFocus.clear()
            viewModel.clearDetail()
        }
        if (next != destination) {
            chrome.contentScrolled = false
            destination = next
        }
    }
    val openDetail: TvOpenDetail = { browseId, title, subtitle, thumb, type ->
        returnFocus.add(chrome.lastFocused)
        detailStack.add(TvDetailRoute(browseId, title, subtitle, thumb, type))
    }
    val activeDetail = detailStack.lastOrNull()
    val contentFocus = remember { FocusRequester() }
    val detailFocus = remember { FocusRequester() }
    var pendingReturn by remember { mutableStateOf<FocusRequester?>(null) }
    val popDetail: () -> Unit = {
        detailStack.removeAt(detailStack.lastIndex)
        pendingReturn = returnFocus.removeLastOrNull()
        chrome.epoch++
        viewModel.closeDetail()
    }
    // Leaving the last pushed page: put focus back on the card that opened it.
    var hadDetail by remember { mutableStateOf(false) }
    LaunchedEffect(activeDetail == null) {
        if (activeDetail != null) {
            hadDetail = true
            barHasFocus = false
        } else if (hadDetail) {
            hadDetail = false
            delay(16)
            val back = pendingReturn
            pendingReturn = null
            if (back == null || !runCatching { back.requestFocus() }.getOrDefault(false)) {
                runCatching { contentFocus.requestFocus() }
            }
        }
    }

    // tvOS Back: pop a pushed page; otherwise go back up to the tab bar; from
    // there, back to Home; from Home's tab, leave the app.
    BackHandler(enabled = !covered && (activeDetail != null || !barHasFocus || destination != TvDestination.HOME)) {
        when {
            activeDetail != null -> popDetail()
            !barHasFocus -> {
                chrome.scrollToTopRequests++
                runCatching { tabFocus.requestFocus() }
            }
            else -> select(TvDestination.HOME)
        }
    }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { tabFocus.requestFocus() }
    }
    // The tab bar only ever sits over a page at its top: taking focus sends the
    // page home, rather than leaving the bar floating over scrolled content.
    LaunchedEffect(barHasFocus) {
        if (barHasFocus) chrome.scrollToTopRequests++
    }
    var wasCovered by remember { mutableStateOf(false) }
    LaunchedEffect(covered) {
        if (covered) {
            wasCovered = true
        } else if (wasCovered) {
            wasCovered = false
            delay(40)
            val back = focusBeforePlayer
            focusBeforePlayer = null
            if (back == null || !runCatching { back.requestFocus() }.getOrDefault(false)) {
                runCatching { tabFocus.requestFocus() }
            }
        }
    }

    // The bar floats over pushed pages too, stepping aside as they scroll.
    val barVisible = !chrome.contentScrolled || barHasFocus
    val barProgress = animateFloatAsState(if (barVisible) 1f else 0f, tween(260), label = "barProgress")

    CompositionLocalProvider(LocalTvChrome provides chrome) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (covered) 0f else 1f }
                .focusProperties { onEnter = { if (covered) cancelFocusChange() } }
                .focusGroup(),
        ) {
            // The tab's page stays composed under a pushed page — not drawn, and
            // fenced off from focus — so Back lands on the card that pushed it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (activeDetail == null) 1f else 0f }
                    .focusProperties { onEnter = { if (detailStack.isNotEmpty()) cancelFocusChange() } }
                    .focusGroup(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Down from the tab bar enters the page at its top, never at
                        // a row remembered from further down.
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) {
                    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                    val browseDensity = remember(baseDensity) {
                        androidx.compose.ui.unit.Density(
                            density = baseDensity.density * 1.20f,
                            fontScale = baseDensity.fontScale * 1.16f,
                        )
                    }
                    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides browseDensity) {
                    stateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            TvDestination.HOME -> TvHomeScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.EXPLORE -> TvExploreScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.LIBRARY -> TvLibraryScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                playerState = playerState,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                                onOpenAccount = onOpenAccount,
                            )
                            TvDestination.SEARCH -> TvSearchScreen(
                                viewModel = viewModel,
                                mediaController = mediaController,
                                onNavigateToDetail = openDetail,
                                onNavigateToNowPlaying = openNowPlaying,
                            )
                            TvDestination.SETTINGS -> TvSettingsScreen(
                                viewModel = viewModel,
                                onOpenAccount = onOpenAccount,
                                onRunSetupAgain = onRunSetupAgain,
                            )
                        }
                    }
                    }
                }
            }

            if (activeDetail != null) {
                androidx.compose.runtime.key(activeDetail) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(detailFocus)
                            .focusGroup(),
                    ) {
                    TvDetailScreen(
                        browseId = activeDetail.browseId,
                        initialTitle = activeDetail.title,
                        initialSubtitle = activeDetail.subtitle,
                        initialThumbnailUrl = activeDetail.thumbnailUrl,
                        type = activeDetail.type,
                        viewModel = viewModel,
                        mediaController = mediaController,
                        playerState = playerState,
                        onNavigateToDetail = openDetail,
                        onNavigateToNowPlaying = openNowPlaying,
                        onBack = popDetail,
                    )
                    }
                }
            }

            TvTopBar(
                destination = destination,
                viewModel = viewModel,
                tabFocus = tabFocus,
                // Down from the bar goes into whatever page is on top.
                contentFocus = if (activeDetail != null) detailFocus else contentFocus,
                onSelect = select,
                onOpenNowPlaying = openNowPlaying,
                onOpenAccount = onOpenAccount,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(10f)
                    .graphicsLayer {
                        val p = barProgress.value
                        alpha = p
                        translationY = -(1f - p) * 72.dp.toPx()
                    }
                    .onFocusChanged { barHasFocus = it.hasFocus },
            )
        }
    }
}

/**
 * The Apple Music tvOS tab bar: a translucent capsule centred at the top holding
 * the four tabs and, at its end, Search and Settings. Home, Explore and Library
 * switch as focus lands on them, the way tvOS tabs do; the rest act on select.
 */
@Composable
private fun TvTopBar(
    destination: TvDestination,
    viewModel: MainViewModel,
    tabFocus: FocusRequester,
    contentFocus: FocusRequester,
    onSelect: (TvDestination) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val googleAccounts by viewModel.googleAccounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val account by viewModel.account.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    val avatarUrl = remember(googleAccounts, activeAccountId, activeProfileId, account) {
        googleAccounts.firstOrNull { it.accountId == activeAccountId }
            ?.profiles?.firstOrNull { it.profileId == activeProfileId }
            ?.avatar
            ?: account?.thumbnailUrl
    }

    // Pages fill the screen from its top edge so they can scroll under this bar,
    // which puts them outside a plain "below" search from a tab. Point Down at them.
    val downToContent = Modifier.focusProperties { down = contentFocus }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, start = 38.dp, end = 38.dp),
    ) {
        // The mark only, as the phone's Home heading shows it.
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = "Sonora",
            tint = TvGlass.TextPrimary,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .height(21.dp)
                .aspectRatio(730f / 484f),
        )
        TvAvatarButton(
            imageUrl = if (signedIn) avatarUrl else null,
            onClick = onOpenAccount,
            modifier = Modifier.align(Alignment.CenterEnd).then(downToContent),
        )
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(CircleShape)
                .background(TvGlass.Bar)
                .padding(4.dp)
                .focusRestorer(tabFocus)
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(TvDestination.HOME, TvDestination.EXPLORE, TvDestination.LIBRARY).forEach { tab ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                LaunchedEffect(focused) {
                    if (focused && destination != tab) {
                        delay(140)
                        onSelect(tab)
                    }
                }
                TvTabPill(
                    label = tab.label,
                    selected = destination == tab,
                    interactionSource = interaction,
                    onClick = { onSelect(tab) },
                    modifier = downToContent.then(if (destination == tab) Modifier.focusRequester(tabFocus) else Modifier),
                )
            }
            TvTabPill(
                label = "Now Playing",
                selected = false,
                modifier = downToContent,
                onClick = onOpenNowPlaying,
            )
            TvTabIcon(
                icon = Icons.Rounded.Search,
                contentDescription = "Search",
                selected = destination == TvDestination.SEARCH,
                onClick = { onSelect(TvDestination.SEARCH) },
                modifier = downToContent.then(if (destination == TvDestination.SEARCH) Modifier.focusRequester(tabFocus) else Modifier),
            )
            TvTabIcon(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                selected = destination == TvDestination.SETTINGS,
                onClick = { onSelect(TvDestination.SETTINGS) },
                modifier = downToContent.then(if (destination == TvDestination.SETTINGS) Modifier.focusRequester(tabFocus) else Modifier),
            )
        }
    }
}

/** The account's picture at the bar's leading edge; opens Account. */
@Composable
private fun TvAvatarButton(
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(32.dp)
            .tvLift(interaction, CircleShape, focusedScale = 1.15f, elevation = 8.dp)
            .tvClick(interaction, onClick = onClick)
            .background(TvGlass.Fill),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = "Account",
                tint = TvGlass.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            TvArtwork(url = imageUrl, px = 256, shape = CircleShape, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The Sonora mark, for the few places that show the app itself. */
@Composable
fun TvAppMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 96.dp) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(size * 0.225f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(Color(0xFF131722), Color(0xFF080A10))),
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0x3D6366F1), Color(0x1438BDF8), Color.Transparent),
                        center = Offset(this.size.width * 0.5f, this.size.height * 0.5f),
                        radius = this.size.width * 0.55f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_logo),
            contentDescription = "Sonora",
            tint = Color(0xFFF2F7FF),
            modifier = Modifier.size(size * 0.56f),
        )
    }
}
