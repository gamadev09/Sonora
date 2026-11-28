package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.playback.airplay.AirPlayAuthRequiredException
import com.music.bitchord.playback.airplay.AirPlayController
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.playback.dlna.DlnaController
import com.music.bitchord.playback.tvremote.TvRemoteController
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.DlnaIcon
import dev.chrisbanes.haze.HazeState

/**
 * Google Cast / Chromecast receiver picker, opened from the output drawer's Cast row.
 */
@Composable
fun CastDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cast by CastController.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var picked by remember { mutableStateOf(false) }

    DisposableEffect(cast.supported) {
        onDispose(CastController.discover(active = true))
    }
    LaunchedEffect(cast.connectedName, picked) {
        if (picked && cast.connectedName != null) onDismiss()
    }

    AudioPopupCard(
        hazeState = hazeState,
        title = stringResource(R.string.cast_dialog_title),
        subtitle = stringResource(R.string.cast_dialog_subtitle),
        doneLabel = stringResource(R.string.done),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            PipelineRule(Modifier)
            if (cast.devices.isEmpty()) {
                SearchingRow()
            } else {
                cast.devices.forEach { device ->
                    CastDeviceRow(
                        name = device.name,
                        subtitle = null,
                        icon = if (cast.casting && device.connected) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                        connecting = device.connecting,
                        connected = cast.casting && device.connected,
                        connectedLabel = stringResource(R.string.cast_device_connected),
                        onClick = {
                            if (!device.connected) {
                                haptics.play(Haptic.Select)
                                if (AirPlayController.state.value.connected || AirPlayController.state.value.connecting) {
                                    AirPlayController.disconnect(resumeHere = false)
                                }
                                if (DlnaController.state.value.connected || DlnaController.state.value.connecting) {
                                    DlnaController.disconnect(resumeHere = false)
                                }
                                picked = true
                                CastController.connect(device.id)
                            }
                        },
                    )
                    PipelineRule(Modifier.padding(start = 56.dp))
                }
            }
            if (cast.casting) {
                CastActionRow(
                    label = stringResource(R.string.cast_stop),
                    onClick = {
                        haptics.play(Haptic.Select)
                        CastController.disconnect(resumeHere = true)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * AirPlay / AirPlay 2 receiver picker, opened from the output drawer's AirPlay row.
 */
@Composable
fun AirPlayDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val airplay by AirPlayController.state.collectAsStateWithLifecycle()
    val tvRemote by TvRemoteController.state.collectAsStateWithLifecycle()
    val pairingPrompt by AirPlayController.pairingPrompt.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var picked by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val stopAirPlay = AirPlayController.discover(context)
        val stopTvRemote = TvRemoteController.discover(context)
        onDispose {
            stopAirPlay()
            stopTvRemote()
        }
    }
    LaunchedEffect(airplay.connectedHost, picked, pairingPrompt) {
        if (picked && airplay.connectedHost != null && pairingPrompt == null) onDismiss()
    }

    val dismissWithCancel = {
        if (pairingPrompt != null) AirPlayController.cancelPairingPrompt()
        onDismiss()
    }

    AudioPopupCard(
        hazeState = hazeState,
        title = stringResource(R.string.airplay_dialog_title),
        subtitle = stringResource(R.string.airplay_dialog_subtitle),
        doneLabel = stringResource(R.string.done),
        onDismiss = dismissWithCancel,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            PipelineRule(Modifier)

            if (pairingPrompt != null) {
                AirPlayPairingCard(
                    prompt = pairingPrompt!!,
                    onSubmit = { AirPlayController.submitPairingSecret(it) },
                    onCancel = { AirPlayController.cancelPairingPrompt() },
                )
                PipelineRule(Modifier)
            }

            if (!airplay.error.isNullOrBlank()) {
                Text(
                    text = airplay.error!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF6B63).copy(alpha = 0.14f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Color(0xFFFF9B96),
                )
                PipelineRule(Modifier)
            }

            if (tvRemote.devices.isNotEmpty()) {
                tvRemote.devices.forEach { tvDevice ->
                    val isTvConnected = tvRemote.connectedDevice?.id == tvDevice.id
                    CastDeviceRow(
                        name = tvDevice.name,
                        subtitle = if (isTvConnected) {
                            tvRemote.status?.title ?: "Remote control active"
                        } else {
                            "Control playback on TV"
                        },
                        icon = Icons.Rounded.Tv,
                        connecting = false,
                        connected = isTvConnected,
                        connectedLabel = "Controlling",
                        onClick = {
                            haptics.play(Haptic.Select)
                            if (isTvConnected) {
                                TvRemoteController.disconnect()
                            } else {
                                TvRemoteController.connect(tvDevice)
                            }
                        },
                    )
                    if (isTvConnected) {
                        TvRemoteControlPanel(
                            status = tvRemote.status,
                            error = tvRemote.error,
                            onPrevious = {
                                haptics.play(Haptic.Select)
                                TvRemoteController.previous()
                            },
                            onPlayPause = {
                                haptics.play(Haptic.Select)
                                TvRemoteController.playPause()
                            },
                            onNext = {
                                haptics.play(Haptic.Select)
                                TvRemoteController.next()
                            },
                            onVolumeChange = { TvRemoteController.setVolume(it) },
                            onDisconnect = {
                                haptics.play(Haptic.Select)
                                TvRemoteController.disconnect()
                            },
                        )
                    }
                    PipelineRule(Modifier.padding(start = 56.dp))
                }
            }

            if (airplay.devices.isEmpty() && tvRemote.devices.isEmpty()) {
                SearchingRow()
            } else {
                airplay.devices.forEach { device ->
                    val isConnected = airplay.connectedHost == device.host
                    val isConnecting = airplay.connectingHost == device.host
                    val protoLabel = stringResource(
                        if (device.supportsAirPlay2) R.string.airplay_protocol_v2 else R.string.airplay_protocol_v1,
                    )
                    val subtitle = if (device.requiresPassword) {
                        "$protoLabel · ${stringResource(R.string.airplay_password_required_hint)}"
                    } else {
                        protoLabel
                    }
                    CastDeviceRow(
                        name = device.name,
                        subtitle = subtitle,
                        icon = Icons.Rounded.Airplay,
                        connecting = isConnecting,
                        connected = isConnected,
                        connectedLabel = stringResource(R.string.airplay_device_connected),
                        onClick = {
                            if (!isConnected && !isConnecting) {
                                haptics.play(Haptic.Select)
                                if (CastController.state.value.casting) {
                                    CastController.disconnect(resumeHere = false)
                                }
                                if (DlnaController.state.value.connected || DlnaController.state.value.connecting) {
                                    DlnaController.disconnect(resumeHere = false)
                                }
                                picked = true
                                AirPlayController.connect(device)
                            }
                        },
                    )
                    PipelineRule(Modifier.padding(start = 56.dp))
                }
            }

            if (airplay.connected || airplay.connecting) {
                CastActionRow(
                    label = stringResource(R.string.airplay_stop),
                    onClick = {
                        haptics.play(Haptic.Select)
                        AirPlayController.disconnect(resumeHere = true)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * DLNA / UPnP MediaRenderer picker, opened from the output drawer's DLNA row.
 */
@Composable
fun DlnaDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dlna by DlnaController.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var picked by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose(DlnaController.discover(context))
    }
    LaunchedEffect(dlna.connectedUdn, picked) {
        if (picked && dlna.connectedUdn != null) onDismiss()
    }

    AudioPopupCard(
        hazeState = hazeState,
        title = stringResource(R.string.dlna_dialog_title),
        subtitle = stringResource(R.string.dlna_dialog_subtitle),
        doneLabel = stringResource(R.string.done),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            PipelineRule(Modifier)

            if (!dlna.error.isNullOrBlank()) {
                Text(
                    text = dlna.error!!,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFF6B63).copy(alpha = 0.14f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Color(0xFFFF9B96),
                )
                PipelineRule(Modifier)
            }

            if (dlna.devices.isEmpty()) {
                SearchingRow()
            } else {
                dlna.devices.forEach { device ->
                    val isConnected = dlna.connectedUdn == device.udn
                    val isConnecting = dlna.connectingUdn == device.udn
                    CastDeviceRow(
                        name = device.name,
                        subtitle = device.host,
                        icon = DlnaIcon,
                        connecting = isConnecting,
                        connected = isConnected,
                        connectedLabel = stringResource(R.string.dlna_device_connected),
                        onClick = {
                            if (!isConnected && !isConnecting) {
                                haptics.play(Haptic.Select)
                                if (CastController.state.value.casting) {
                                    CastController.disconnect(resumeHere = false)
                                }
                                if (AirPlayController.state.value.connected || AirPlayController.state.value.connecting) {
                                    AirPlayController.disconnect(resumeHere = false)
                                }
                                picked = true
                                DlnaController.connect(device)
                            }
                        },
                    )
                    PipelineRule(Modifier.padding(start = 56.dp))
                }
            }

            if (dlna.connected || dlna.connecting) {
                CastActionRow(
                    label = stringResource(R.string.dlna_stop),
                    onClick = {
                        haptics.play(Haptic.Select)
                        DlnaController.disconnect(resumeHere = true)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun AirPlayPairingCard(
    prompt: AirPlayController.PairingPrompt,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var secret by remember(prompt) { mutableStateOf("") }
    val isPin = prompt.kind == AirPlayAuthRequiredException.Kind.PIN

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(if (isPin) R.string.airplay_pin_title else R.string.airplay_password_title),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.W600),
            color = Color.White,
        )
        Text(
            text = stringResource(
                if (isPin) R.string.airplay_pin_subtitle else R.string.airplay_password_subtitle,
                prompt.name,
            ),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = Color.White.copy(alpha = 0.72f),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            singleLine = true,
            placeholder = {
                Text(
                    text = stringResource(if (isPin) R.string.airplay_pin_placeholder else R.string.airplay_password_placeholder),
                    color = Color.White.copy(alpha = 0.45f),
                )
            },
            visualTransformation = if (isPin) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPin) KeyboardType.Number else KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (secret.isNotBlank()) onSubmit(secret)
                },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.White.copy(alpha = 0.7f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(android.R.string.cancel), color = Color.White.copy(alpha = 0.75f))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSubmit(secret) },
                enabled = secret.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.25f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
            ) {
                Text(stringResource(R.string.airplay_connect))
            }
        }
    }
}

@Composable
private fun CastDeviceRow(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    connecting: Boolean,
    connected: Boolean,
    connectedLabel: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pressed) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (connected) 1f else 0.8f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp,
                    fontWeight = if (connected) FontWeight.W600 else FontWeight.Normal,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val statusLine = when {
                connecting -> stringResource(R.string.cast_device_connecting)
                connected -> connectedLabel
                else -> subtitle
            }
            if (!statusLine.isNullOrBlank()) {
                Text(
                    text = statusLine,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            connecting -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 1.75.dp,
            )
            connected -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Shown until the first receiver turns up; it is also what an empty network looks like. */
@Composable
private fun SearchingRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = Color.White.copy(alpha = 0.7f),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.cast_searching),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = Color.White,
        )
        Text(
            text = stringResource(R.string.cast_searching_hint),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CastActionRow(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (pressed) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.W600,
            ),
            color = Color(0xFFFF6B63),
        )
    }
}
