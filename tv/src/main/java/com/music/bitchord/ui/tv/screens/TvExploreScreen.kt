package com.music.bitchord.ui.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.playback.playSongs
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.screens.MOOD_CARD_ASPECT
import com.music.bitchord.ui.tv.components.TvChromeScrollEffect
import com.music.bitchord.ui.tv.components.TvErrorState
import com.music.bitchord.ui.tv.components.TvLockup
import com.music.bitchord.ui.tv.components.TvMoodCard
import com.music.bitchord.ui.tv.components.TvRow
import com.music.bitchord.ui.tv.dialogs.TvSongMenu
import com.music.bitchord.ui.tv.theme.TvDimensions
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.launch

/**
 * New tab: personalized YouTube Music new releases & recommendations based on
 * what the user likes, followed by the Moods & Genres discovery grid.
 */
@Composable
fun TvExploreScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by viewModel.selectedMoodGenre.collectAsState()
    val mood = selected
    if (mood == null) {
        val explore by viewModel.explore.collectAsState()
        val home by viewModel.home.collectAsState()
        val discoverySections = remember(home) {
            val all = (home as? UiState.Success)?.data.orEmpty()
            val prioritized = all.filter { section ->
                val t = section.title.lowercase()
                t.contains("new") ||
                    t.contains("release") ||
                    t.contains("recommend") ||
                    t.contains("chart") ||
                    t.contains("trend") ||
                    t.contains("discover") ||
                    t.contains("mix") ||
                    t.contains("for you") ||
                    t.contains("similar") ||
                    t.contains("fresh")
            }
            (prioritized.ifEmpty { all.drop(1) }).take(4)
        }
        val scope = rememberCoroutineScope()
        var menuSong by remember { mutableStateOf<Song?>(null) }
        TvMoodGrid(
            state = explore,
            discoverySections = discoverySections,
            onPlaySong = { song ->
                scope.launch {
                    mediaController?.playSongs(listOf(song), 0)
                    onNavigateToNowPlaying()
                }
            },
            onOpenDetail = onNavigateToDetail,
            onSongMenu = { menuSong = it },
            onCategory = viewModel::openMoodGenre,
            onRetry = { viewModel.loadExplore() },
            modifier = modifier,
        )
        menuSong?.let { song ->
            TvSongMenu(
                song = song,
                viewModel = viewModel,
                mediaController = mediaController,
                onNavigateToDetail = onNavigateToDetail,
                onDismiss = { menuSong = null },
            )
        }
    } else {
        BackHandler { viewModel.closeMoodGenre() }
        TvMoodPage(
            mood = mood,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToNowPlaying = onNavigateToNowPlaying,
            modifier = modifier,
        )
    }
}

@Composable
private fun TvMoodGrid(
    state: UiState<List<MoodGenreSection>>,
    discoverySections: List<HomeShelf>,
    onPlaySong: (Song) -> Unit,
    onOpenDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onSongMenu: (Song) -> Unit,
    onCategory: (MoodGenre) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    TvChromeScrollEffect(gridState)

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(MOOD_MIN_WIDTH),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = TvDimensions.SafeMarginHorizontal,
            end = TvDimensions.SafeMarginHorizontal,
            top = TvDimensions.ContentTop + 3.dp,
            bottom = 38.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(MOOD_SPACING),
        verticalArrangement = Arrangement.spacedBy(MOOD_SPACING),
    ) {
        if (discoverySections.isNotEmpty()) {
            discoverySections.forEach { section ->
                item(
                    key = "new_shelf_${section.title}",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    androidx.compose.foundation.layout.Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = section.title,
                            style = TvType.Title,
                            color = TvGlass.TextPrimary,
                        )
                        TvRow(
                            items = section.items,
                            key = { it -> "${section.title}_${it.videoId ?: it.browseId ?: it.title}" },
                        ) { _, item ->
                            TvLockup(
                                title = item.title,
                                subtitle = item.subtitle,
                                artworkUrl = item.thumbnailUrl,
                                onClick = { openShelfItem(item, onPlaySong, onOpenDetail) },
                                onLongClick = item.toSong()?.let { song -> { onSongMenu(song) } },
                            )
                        }
                    }
                }
            }
            item(key = "moods_header", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Moods & Genres",
                    style = TvType.Title,
                    color = TvGlass.TextPrimary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
        }
        when (state) {
            UiState.Loading -> items(16) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(MOOD_CARD_ASPECT)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                )
            }
            is UiState.Error -> item(span = { GridItemSpan(maxLineSpan) }) {
                TvErrorState(message = state.message, onRetry = onRetry, modifier = Modifier.padding(top = 48.dp))
            }
            is UiState.Success -> {
                val moods = state.data.flatMap(MoodGenreSection::items).distinctBy { it.browseId to it.params }
                items(moods, key = { "${it.browseId}|${it.params}" }) { item ->
                    TvMoodCard(item = item, onClick = { onCategory(item) })
                }
            }
        }
    }
}

@Composable
private fun TvMoodPage(
    mood: MoodGenre,
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shelves by viewModel.moodGenreShelves.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }

    when (val state = shelves) {
        UiState.Loading -> TvShelvesLoading(modifier)
        is UiState.Error -> TvErrorState(
            message = state.message,
            onRetry = { viewModel.openMoodGenre(mood) },
            modifier = modifier,
        )
        is UiState.Success -> TvShelfFeed(
            shelves = state.data,
            onPlaySong = { song ->
                scope.launch { mediaController?.playSongs(listOf(song), 0) }
                onNavigateToNowPlaying()
            },
            onSongMenu = { menuSong = it },
            onNavigateToDetail = onNavigateToDetail,
            header = {
                Text(
                    text = mood.title,
                    style = TvType.LargeTitle,
                    color = TvGlass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = TvDimensions.SafeMarginHorizontal,
                        end = TvDimensions.SafeMarginHorizontal,
                        bottom = 3.dp,
                    ),
                )
            },
            modifier = modifier,
        )
    }

    menuSong?.let { song ->
        TvSongMenu(
            song = song,
            viewModel = viewModel,
            mediaController = mediaController,
            onNavigateToDetail = onNavigateToDetail,
            onDismiss = { menuSong = null },
        )
    }
}

/** The phone's own floor for a mood card's width; the grid fits as many columns as that allows. */
private val MOOD_MIN_WIDTH = 176.dp
private val MOOD_SPACING = 14.dp
