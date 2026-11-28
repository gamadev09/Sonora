package com.music.bitchord.ui.tv.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.auth.WebSessionMode
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TvSettings
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.onSignedIn
import com.music.bitchord.ui.tv.TvAppMark
import com.music.bitchord.ui.tv.TvDeviceCapabilities
import com.music.bitchord.ui.tv.TvWeakHardwareDialog
import com.music.bitchord.ui.tv.audio.TvSpatialAudioEngine
import com.music.bitchord.ui.tv.auth.TvAuthServer
import com.music.bitchord.ui.tv.auth.TvQrCodeView
import com.music.bitchord.ui.tv.components.TvActivityIndicator
import com.music.bitchord.ui.tv.components.TvButton
import com.music.bitchord.ui.tv.components.TvListRow
import com.music.bitchord.ui.tv.components.tvClick
import com.music.bitchord.ui.tv.components.tvInitialFocus
import com.music.bitchord.ui.tv.components.tvLift
import com.music.bitchord.ui.tv.dialogs.TvAccountDialog
import com.music.bitchord.ui.tv.personalization.AppThemeOption
import com.music.bitchord.ui.tv.theme.TvDarkPalette
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvMidnightPalette
import com.music.bitchord.ui.tv.theme.TvOledPalette
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class SetupStep { Welcome, SignIn, Appearance, Preferences, Done }

/**
 * First-run setup, in the manner of Apple TV's: one decision per screen, a
 * centred title and explanation, and the choices stacked at the bottom. Back
 * steps back; nothing is saved until the last screen.
 */
@Composable
fun TvSetupScreen(
    viewModel: MainViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableStateOf(SetupStep.Welcome) }
    var theme by remember { mutableStateOf(AppThemeOption.fromId(TvSettings.tvTheme.value)) }

    val finish = {
        TvSettings.setTvPersonalization(
            nickname = TvSettings.tvNickname.value,
            themeId = theme.id,
            version = 1,
        )
        onComplete()
    }

    BackHandler(enabled = step != SetupStep.Welcome) {
        step = SetupStep.entries[step.ordinal - 1]
    }

    AnimatedContent(
        targetState = step,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            (slideInHorizontally(tween(320)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(320)))
                .togetherWith(slideOutHorizontally(tween(240)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(200)))
        },
        label = "setupStep",
        modifier = modifier.fillMaxSize(),
    ) { current ->
        when (current) {
            SetupStep.Welcome -> TvSetupPage(
                title = "Welcome to Sonora",
                message = "Your music, made for the big screen.",
                hero = { TvAppMark(size = 106.dp) },
            ) {
                TvSetupButton("Continue", initialFocus = true) { step = SetupStep.SignIn }
            }
            SetupStep.SignIn -> TvSignInStep(
                viewModel = viewModel,
                onContinue = { step = SetupStep.Appearance },
            )
            SetupStep.Appearance -> TvSetupPage(
                title = "Choose a Look",
                message = "You can change this later in Settings.",
                hero = {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        AppThemeOption.entries.forEach { option ->
                            TvThemeSwatch(
                                option = option,
                                selected = option == theme,
                                initialFocus = option == theme,
                                onClick = { theme = option },
                            )
                        }
                    }
                },
            ) {
                TvSetupButton("Continue") {
                    TvSettings.setTvTheme(theme.id)
                    step = SetupStep.Preferences
                }
            }
            SetupStep.Preferences -> TvPreferencesStep(onContinue = { step = SetupStep.Done })
            SetupStep.Done -> TvSetupPage(
                title = "You're All Set",
                message = "Find music in Home and Explore. Everything you save is in Library.",
                hero = {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(77.dp),
                    )
                },
            ) {
                TvSetupButton("Start Listening", initialFocus = true) { finish() }
            }
        }
    }
}

/** The common frame of every setup screen. */
@Composable
private fun TvSetupPage(
    title: String,
    message: String,
    hero: @Composable () -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 38.dp, vertical = 35.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        hero()
        Spacer(modifier = Modifier.height(29.dp))
        Text(text = title, style = TvType.LargeTitle, color = TvGlass.TextPrimary, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = TvType.Body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W400),
            color = TvGlass.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 448.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier.width(304.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = buttons,
        )
    }
}

@Composable
private fun TvSetupButton(text: String, initialFocus: Boolean = false, onClick: () -> Unit) {
    TvButton(
        text = text,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvInitialFocus(initialFocus),
    )
}

@Composable
private fun TvSignInStep(viewModel: MainViewModel, onContinue: () -> Unit) {
    val signedIn by viewModel.signedIn.collectAsState()
    val account by viewModel.account.collectAsState()
    var showTvWebLogin by remember { mutableStateOf(false) }
    var pairingInfo by remember { mutableStateOf<TvAuthServer.EncryptedPairingInfo?>(null) }
    var statusText by remember { mutableStateOf("Waiting for your phone…") }
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(signedIn) {
        if (signedIn) {
            TvAuthServer.stop()
            onContinue()
        }
    }

    DisposableEffect(signedIn, showTvWebLogin, refreshTick) {
        if (signedIn || showTvWebLogin) {
            TvAuthServer.stop()
            onDispose { }
        } else {
            val job = scope.launch {
                statusText = "Waiting for your phone…"
                pairingInfo = TvAuthServer.startEncryptedPairing(
                    onSessionPaired = { session ->
                        scope.launch(Dispatchers.Main) {
                            statusText = "Signing in…"
                            viewModel.onWebSession(session, WebSessionMode.SIGN_IN) { ok ->
                                if (!ok) statusText = "Sign-in failed. Try refreshing the code."
                            }
                        }
                    },
                    onLegacyCookieReceived = { cookie ->
                        scope.launch(Dispatchers.Main) {
                            statusText = "Signing in…"
                            viewModel.onSignedIn(cookie) { ok ->
                                if (!ok) statusText = "Sign-in failed. Try again."
                            }
                        }
                    },
                )
            }
            onDispose {
                job.cancel()
                TvAuthServer.stop()
            }
        }
    }

    TvSetupPage(
        title = if (signedIn) "Signed In" else "Sign In with Your Phone",
        message = if (signedIn) {
            "Sonora will use ${account?.name ?: "your account"}'s library and recommendations."
        } else {
            "Scan with your phone camera or Sonora Settings → Pair with TV. Encrypted over your Wi-Fi."
        },
        hero = {
            if (signedIn) {
                TvAppMark(size = 84.dp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        val qr = pairingInfo?.qrUri
                        if (qr != null) TvQrCodeView(content = qr, size = 166.dp)
                        else TvActivityIndicator(color = Color.Black)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = statusText,
                        style = TvType.Caption,
                        color = Color(0xFF93C5FD),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) {
        if (signedIn) {
            TvSetupButton("Continue", initialFocus = true, onClick = onContinue)
        } else {
            TvSetupButton("Not Now", initialFocus = true, onClick = onContinue)
            TvSetupButton("Sign In on TV") { showTvWebLogin = true }
        }
    }

    if (showTvWebLogin && !signedIn) {
        TvAccountDialog(
            viewModel = viewModel,
            onDismiss = { showTvWebLogin = false },
            allowPhoneSignIn = false,
        )
    }
}

@Composable
private fun TvPreferencesStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    val hwProfile = remember(context) { TvDeviceCapabilities.profile(context) }

    // Apply clean TV defaults once on entering setup: all toggles On except Spatial Audio (Off),
    // and keep heavy DSP features (Automix / Sing) Off until confirmed on weak TVs.
    LaunchedEffect(hwProfile) {
        AppSettings.setSyncedLyrics(true)
        AppSettings.setAnimatedCanvas(true)
        TvSettings.setTvChordsEnabled(true)
        TvSettings.setSpatialAudioEnabled(false)
        TvSpatialAudioEngine.setEnabled(false)
        if (!hwProfile.isWeakTv) {
            AppSettings.setSmartFadeEnabled(true)
            TvSettings.setTvSingEnabled(true)
        } else {
            AppSettings.setSmartFadeEnabled(false)
            TvSettings.setTvSingEnabled(false)
        }
    }

    val lyrics by AppSettings.syncedLyrics.collectAsState()
    val canvas by AppSettings.animatedCanvas.collectAsState()
    val chords by TvSettings.tvChordsEnabled.collectAsState()
    val automix by AppSettings.smartFadeEnabled.collectAsState()
    val sing by TvSettings.tvSingEnabled.collectAsState()
    val spatial by TvSettings.spatialAudioEnabled.collectAsState()

    var weakWarningFeature by remember { mutableStateOf<String?>(null) }

    TvSetupPage(
        title = "Listening",
        message = "You can toggle lyrics anytime from Now Playing.",
        hero = {
            Column(
                modifier = Modifier.width(440.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TvListRow(
                    title = "Synced Lyrics",
                    value = if (lyrics) "On" else "Off",
                    modifier = Modifier.tvInitialFocus(),
                    onClick = { AppSettings.setSyncedLyrics(!lyrics) },
                )
                TvListRow(
                    title = "Motion Artwork",
                    value = if (canvas) "On" else "Off",
                    onClick = { AppSettings.setAnimatedCanvas(!canvas) },
                )
                TvListRow(
                    title = "Live Chords",
                    value = if (chords) "On" else "Off",
                    onClick = { TvSettings.setTvChordsEnabled(!chords) },
                )
                TvListRow(
                    title = "Automix",
                    value = if (automix) "On" else "Off",
                    onClick = {
                        if (!automix && hwProfile.isWeakTv) {
                            weakWarningFeature = "Automix"
                        } else {
                            AppSettings.setSmartFadeEnabled(!automix)
                        }
                    },
                )
                TvListRow(
                    title = "Sing (Vocal Separation)",
                    value = if (sing) "On" else "Off",
                    onClick = {
                        if (!sing && hwProfile.isWeakTv) {
                            weakWarningFeature = "Sing"
                        } else {
                            TvSettings.setTvSingEnabled(!sing)
                        }
                    },
                )
                TvListRow(
                    title = "Spatial Audio",
                    value = if (spatial) "On" else "Off",
                    onClick = {
                        TvSettings.setSpatialAudioEnabled(!spatial)
                        TvSpatialAudioEngine.setEnabled(!spatial)
                    },
                )
            }
        },
    ) {
        TvSetupButton("Continue", onClick = onContinue)
    }

    weakWarningFeature?.let { feature ->
        TvWeakHardwareDialog(
            featureName = feature,
            profile = hwProfile,
            onKeepOff = { weakWarningFeature = null },
            onEnableAnyway = {
                if (feature == "Automix") AppSettings.setSmartFadeEnabled(true)
                else if (feature == "Sing") TvSettings.setTvSingEnabled(true)
                weakWarningFeature = null
            },
        )
    }
}

/** A small picture of the theme's canvas with two placeholder cards, its name under it. */
@Composable
private fun TvThemeSwatch(
    option: AppThemeOption,
    selected: Boolean,
    initialFocus: Boolean,
    onClick: () -> Unit,
) {
    val palette = when (option) {
        AppThemeOption.MIDNIGHT -> TvMidnightPalette
        AppThemeOption.PURE_BLACK -> TvOledPalette
        AppThemeOption.DYNAMIC_ARTWORK -> TvDarkPalette
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(16f / 10f)
                .tvInitialFocus(initialFocus)
                .tvLift(interaction, RoundedCornerShape(11.dp), focusedScale = 1.08f, elevation = 18.dp)
                .tvClick(interaction, onClick = onClick)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (option == AppThemeOption.DYNAMIC_ARTWORK) Color(0xFF3A2A44) else palette.canvasTop,
                            palette.canvasBottom,
                        ),
                    ),
                )
                .padding(13.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.White.copy(alpha = 0.16f)),
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(19.dp)
                        .clip(CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.height(11.dp))
        Text(
            text = option.title,
            style = TvType.CardTitle,
            color = if (focused || selected) TvGlass.TextPrimary else TvGlass.TextSecondary,
        )
    }
}
