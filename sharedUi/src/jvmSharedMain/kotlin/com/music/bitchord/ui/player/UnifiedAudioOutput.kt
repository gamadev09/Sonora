package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.audio_output
import com.music.bitchord.sharedui.resources.audio_output_playing
import com.music.bitchord.sharedui.resources.listen_together
import com.music.bitchord.sharedui.resources.output_available_devices
import com.music.bitchord.sharedui.resources.output_connecting
import com.music.bitchord.sharedui.resources.output_discovery_protocols
import com.music.bitchord.sharedui.resources.output_no_devices
import com.music.bitchord.sharedui.resources.output_remote_connected
import com.music.bitchord.sharedui.resources.output_searching
import com.music.bitchord.sharedui.resources.output_session_active
import com.music.bitchord.sharedui.resources.output_this_phone
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** One drawer for active output, party and concurrently discovered routes. */
@Composable
internal fun UnifiedAudioOutputSheet(
    hazeState: HazeState,
    accountName: String?,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenAirPlay: () -> Unit,
    onListenTogether: (() -> Unit)?,
    onOpenListenTogetherMembers: (() -> Unit)?,
    onEditPartyServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = PlayerPlatform.host
    val outputs = rememberAudioOutputs()
    val devices by host.discoveredOutputs.collectAsStateWithLifecycle()
    val cast by host.castState.collectAsStateWithLifecycle()
    val airplay by host.airPlayState.collectAsStateWithLifecycle()
    val dlna by host.dlnaState.collectAsStateWithLifecycle()
    val party by host.party.collectAsStateWithLifecycle()
    val watch by host.watchOutput.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(true) }
    var showParty by remember { mutableStateOf(false) }
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    PlayerBackHandler(enabled = showParty) { showParty = false }
    DisposableEffect(host) { onDispose(host.beginOutputDiscovery()) }
    LaunchedEffect(Unit) {
        host.refreshWatchOutput()
        delay(6000)
        searching = false
    }

    val remoteName = cast.connectedName ?: airplay.connectedName ?: dlna.connectedName
    val remoteProtocol = when {
        cast.connectedName != null -> OutputProtocol.CAST
        airplay.connectedName != null -> OutputProtocol.AIRPLAY
        dlna.connectedName != null -> OutputProtocol.DLNA
        else -> null
    }
    val local = outputs.firstOrNull { it.isActive } ?: outputs.firstOrNull()
    val candidates = devices.filterNot { it.connected } // Active receiver is already the top row.

    PlayerDrawer(
        hazeState = hazeState,
        title = stringResource(if (showParty) Res.string.listen_together else Res.string.audio_output),
        onDismiss = onDismiss,
        onBack = if (showParty) ({ showParty = false }) else null,
        modifier = modifier,
        maxHeight = 560.dp,
    ) {
        AnimatedContent(
            targetState = showParty,
            transitionSpec = {
                if (reduceAnimation) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                } else {
                    val direction = if (targetState) 1 else -1
                    slideInHorizontally(tween(220)) { direction * it / 4 } togetherWith
                        slideOutHorizontally(tween(220)) { -direction * it / 4 }
                }
            },
            label = "audioOutputPartyContent",
        ) { partyPage ->
        if (partyPage) {
            host.ListenTogetherDrawerContent(
                signedIn = signedIn,
                onSignIn = { onDismiss(); onListenTogether?.invoke() },
                onEditServer = onEditPartyServer,
            )
        } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ActiveOutputRow(
            title = remoteName ?: local?.let { outputLabel(it, accountName) }.orEmpty(),
            subtitle = if (remoteProtocol == null) stringResource(Res.string.audio_output_playing)
                else stringResource(Res.string.output_remote_connected, remoteProtocol.label()),
            icon = when (remoteProtocol) {
                OutputProtocol.CAST -> Icons.Rounded.Cast
                OutputProtocol.AIRPLAY -> Icons.Rounded.Airplay
                OutputProtocol.DLNA -> DlnaIcon
                null -> local?.let(::iconFor) ?: Icons.Rounded.PhoneAndroid
            },
        )
        Spacer(Modifier.height(10.dp))
        UnifiedDeviceRow(
            title = stringResource(Res.string.listen_together),
            subtitle = if (party.inParty) stringResource(Res.string.output_session_active, party.members.size) else null,
            icon = Icons.Rounded.Groups,
            onClick = {
                if (host.supportsEmbeddedParty) showParty = true
                else {
                    onDismiss()
                    onListenTogether?.invoke()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.output_discovery_protocols),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
        Text(
            stringResource(
                when {
                    candidates.isNotEmpty() || outputs.count { !it.isActive } > 0 -> Res.string.output_available_devices
                    searching -> Res.string.output_searching
                    else -> Res.string.output_no_devices
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.64f),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(252.dp).clip(ROW_SHAPE)
                .background(Color.White.copy(alpha = 0.08f)),
        ) {
            if (remoteName == null && candidates.isEmpty() && outputs.none { !it.isActive } && !watch.available) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    if (searching) CircularProgressIndicator(Modifier.size(22.dp),
                        color = Color.White.copy(alpha = 0.72f), strokeWidth = 2.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(if (searching) Res.string.output_searching else Res.string.output_no_devices),
                        color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (remoteName != null) {
                        item(key = "phone") {
                            UnifiedDeviceRow(
                                title = local?.let { outputLabel(it, accountName) }
                                    ?: stringResource(Res.string.output_this_phone),
                                subtitle = stringResource(Res.string.output_this_phone),
                                icon = local?.let(::iconFor) ?: Icons.Rounded.PhoneAndroid,
                                onClick = host::returnToPhone,
                            )
                        }
                    }
                    items(outputs.filterNot { it.isActive }, key = { "local:${it.id}" }) { device ->
                        UnifiedDeviceRow(outputLabel(device, accountName), null, iconFor(device)) {
                            host.selectAudioOutput(device.id)
                        }
                    }
                    if (watch.available && !watch.active) item(key = "watch") {
                        UnifiedDeviceRow(watch.name.orEmpty(), null, Icons.Rounded.PhoneAndroid) {
                            host.selectWatchOutput()
                        }
                    }
                    items(candidates, key = { "${it.protocol}:${it.id}" }) { device ->
                        UnifiedDeviceRow(device.name,
                            if (device.connecting) stringResource(Res.string.output_connecting, device.protocol.label())
                            else device.protocol.label(), device.protocol.icon(),
                            onClick = {
                                if (device.protocol == OutputProtocol.AIRPLAY && device.requiresPairing) {
                                    onOpenAirPlay()
                                } else {
                                    host.connectNetworkOutput(device.protocol, device.id)
                                }
                            })
                    }
                }
            }
        }
        }
        }
        }
    }
}

@Composable
private fun ActiveOutputRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val volume = PlayerPlatform.host.volume
    val reported by volume.level.collectAsStateWithLifecycle()
    var level by remember(volume) { mutableFloatStateOf(reported) }
    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(1) }
    LaunchedEffect(reported) { if (!dragging) level = reported }
    LaunchedEffect(title) { volume.refresh() }
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(ROW_SHAPE)
            .background(Color.White.copy(alpha = 0.10f))
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(volume, width) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragging = false },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragging = true
                        level = (level + amount / width).coerceIn(0f, 1f)
                        volume.set(level)
                    },
                )
            }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(level, 0f..1f)
                setProgress { requested ->
                    level = requested.coerceIn(0f, 1f)
                    volume.set(level)
                    true
                }
            },
    ) {
        Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(58.dp)
            .background(Color.White.copy(alpha = 0.21f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutputIcon(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color.White.copy(alpha = 0.73f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1)
            }
            Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UnifiedDeviceRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(58.dp).clip(ROW_SHAPE)
        .background(Color.White.copy(alpha = 0.07f)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OutputIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.66f),
                style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            tint = Color.White.copy(alpha = 0.75f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OutputIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

private fun OutputProtocol.label(): String = when (this) {
    OutputProtocol.CAST -> "Google Cast"
    OutputProtocol.AIRPLAY -> "AirPlay"
    OutputProtocol.DLNA -> "DLNA"
}

private fun OutputProtocol.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    OutputProtocol.CAST -> Icons.Rounded.Cast
    OutputProtocol.AIRPLAY -> Icons.Rounded.Airplay
    OutputProtocol.DLNA -> DlnaIcon
}
