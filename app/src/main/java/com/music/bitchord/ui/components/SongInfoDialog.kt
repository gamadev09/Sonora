package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.SongInfoRepository
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.ArtistRef
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.completeArtistCredits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import java.util.Locale

// Body height adapts to the 5 standard rows (measured), so it fits any phone/font like before.

/**
 * Drawn as the same UIAlertController-style card as [AlertScaffold] — centred
 * title, hairline [AlertRule]s, full-width [AlertAction]s — rather than a
 * Material [AlertDialog], so it reads as one of the app's own alerts.
 *
 * Kept in a window [Dialog] because it opens from the song actions menu, which
 * can sit inside the player's own window; an in-tree overlay would be drawn
 * underneath it. A
 * separate window can't sample the app's haze, so the card takes the plain
 * surface the other alerts fall back to under "reduce dynamic blur".
 */
@Composable
internal fun SongInfoDialog(
    song: Song,
    onOpenArtist: ((String) -> Unit)? = null,
    onOpenAlbum: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var retry by remember(song.videoId) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = SongInfoRepository.load(song)
    }
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val audioFormatLabel = remember(nerdStats, song.videoId) {
        val snap = nerdStats?.takeIf { it.mediaId == song.videoId } ?: return@remember null
        buildList {
            NerdStats.codecLabel(snap.mimeType)?.let(::add)
            snap.bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
            snap.sampleRateHz?.takeIf { it > 0 }?.let { hz ->
                val khz = if (hz % 1000 == 0) "${hz / 1000} kHz" else String.format(Locale.US, "%.1f kHz", hz / 1000.0)
                add(khz)
            }
            if (snap.isLossless) snap.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
    val artistCredits = remember(song.artist, song.artists) {
        completeArtistCredits(song.artist, song.artists)
    }
    val artistIdByName = remember(artistCredits) {
        artistCredits.mapNotNull { ref ->
            val id = ref.browseId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ref.name.trim().lowercase(Locale.ROOT) to id
        }.toMap()
    }
    val creditsScroll = rememberScrollState()
    val onSurface = MaterialTheme.colorScheme.onSurface

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(ALERT_WIDTH)
                .clip(RoundedCornerShape(ALERT_CORNER))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                song.thumbnailUrl?.artworkAt(ROW_ART_PX)?.let { artUrl ->
                    AsyncImage(
                        model = artUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
                Text(
                    text = stringResource(R.string.song_info),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = song.title,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artistCredits.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        artistCredits.forEachIndexed { idx, artist ->
                            if (idx > 0) {
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                    color = onSurface.copy(alpha = 0.4f),
                                )
                            }
                            val browseId = artist.browseId?.takeIf { it.isNotBlank() }
                            val clickable = browseId != null && onOpenArtist != null
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                    textDecoration = if (clickable) TextDecoration.Underline else TextDecoration.None,
                                ),
                                color = if (clickable) MaterialTheme.colorScheme.primary else onSurface.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (clickable) {
                                    Modifier.clickable { onOpenArtist!!(browseId!!) }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                } else if (song.artist.isNotBlank()) {
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AlertRule()
            val surface = MaterialTheme.colorScheme.surface
            SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                val rowsHeight = subcompose("rows") { SongInfoStandardRows(info, song, audioFormatLabel, onOpenAlbum) }
                    .first().measure(constraints).height
                val body = subcompose("content") {
                    Box(Modifier.fillMaxWidth().height(rowsHeight.toDp())) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(creditsScroll)
                                .drawWithContent {
                                    drawContent()
                                    if (creditsScroll.canScrollForward) {
                                        drawRect(
                                            Brush.verticalGradient(
                                                0f to Color.Transparent,
                                                1f to surface,
                                                startY = size.height - 28.dp.toPx(),
                                                endY = size.height,
                                            )
                                        )
                                    }
                                },
                        ) {
                            SongInfoStandardRows(info, song, audioFormatLabel, onOpenAlbum)
                            info?.credits?.takeIf { it.isNotEmpty() }?.forEach { credit ->
                                AlertRule(Modifier.padding(start = 16.dp))
                                SongInfoCreditRow(
                                    role = credit.role,
                                    names = credit.names,
                                    artistIdByName = artistIdByName,
                                    onOpenArtist = onOpenArtist,
                                )
                            }
                            if (info?.failed == true) {
                                Text(
                                    text = stringResource(R.string.song_info_error),
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (creditsScroll.canScrollForward) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = onSurface.copy(alpha = 0.35f),
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(16.dp),
                            )
                        }
                    }
                }.first().measure(Constraints.fixed(constraints.maxWidth, rowsHeight))
                layout(constraints.maxWidth, rowsHeight) { body.place(0, 0) }
            }

            AlertRule()
            if (info?.failed == true) {
                AlertAction(label = stringResource(R.string.song_info_retry), emphasised = false, onClick = { retry++ })
                AlertRule()
            }
            AlertAction(label = stringResource(R.string.done), emphasised = true, onClick = onDismiss)
        }
    }
}

@Composable
private fun SongInfoStandardRows(
    info: SongInfo?,
    song: Song,
    audioFormatLabel: String?,
    onOpenAlbum: ((String) -> Unit)?,
) {
    val unavailable = stringResource(R.string.song_info_unavailable)
    val albumId = song.albumId?.takeIf { it.isNotBlank() }
    val rows = buildList {
        add(Triple(R.string.song_info_album, info?.album ?: song.albumName, albumId?.let { id -> onOpenAlbum?.let { { it(id) } } }))
        add(Triple(R.string.song_info_release_date, info?.releaseDate ?: info?.releaseYear, null))
        if (audioFormatLabel != null) {
            add(Triple(R.string.audio_quality, audioFormatLabel, null))
        }
        add(Triple(R.string.song_info_views, info?.views?.let(::compactCount), null))
        add(Triple(R.string.song_info_likes, info?.likes, null))
        add(Triple(R.string.song_info_comments, info?.comments, null))
    }
    Column {
        rows.forEachIndexed { index, (label, value, action) ->
            if (index > 0) AlertRule(Modifier.padding(start = 16.dp))
            SongInfoRow(
                label = stringResource(label),
                value = value ?: unavailable.takeIf { info != null },
                onClick = action,
            )
        }
    }
}

/** Label on the left, value on the right; a small spinner stands in for the value while it loads. */
@Composable
private fun SongInfoRow(label: String, value: String?, onClick: (() -> Unit)? = null) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .then(if (onClick != null && value != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (value == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = onSurface.copy(alpha = 0.5f),
                    strokeWidth = 1.75.dp,
                )
            } else {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        textDecoration = if (onClick != null) TextDecoration.Underline else TextDecoration.None,
                    ),
                    color = if (onClick != null) MaterialTheme.colorScheme.primary else onSurface,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun SongInfoCreditRow(
    role: String,
    names: List<String>,
    artistIdByName: Map<String, String>,
    onOpenArtist: ((String) -> Unit)?,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = onSurface.copy(alpha = 0.6f),
        )
        Row(
            modifier = Modifier
                .padding(top = 2.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            names.forEachIndexed { index, name ->
                if (index > 0) {
                    Text(
                        text = ",",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = onSurface.copy(alpha = 0.5f),
                    )
                }
                val browseId = artistIdByName[name.trim().lowercase(Locale.ROOT)]
                val navigable = browseId != null && onOpenArtist != null
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.sp,
                        textDecoration = if (navigable) TextDecoration.Underline else TextDecoration.None,
                    ),
                    color = if (navigable) MaterialTheme.colorScheme.primary else onSurface,
                    modifier = if (navigable) Modifier.clickable { onOpenArtist!!(browseId!!) } else Modifier,
                )
            }
        }
    }
}
