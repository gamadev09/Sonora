package com.music.bitchord.ui.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.playback.playSongs
import com.music.bitchord.ui.MainViewModel
import com.music.bitchord.ui.tv.components.TvActivityIndicator
import com.music.bitchord.ui.tv.components.TvButton
import com.music.bitchord.ui.tv.components.TvChromeScrollEffect
import com.music.bitchord.ui.tv.components.TvEmptyState
import com.music.bitchord.ui.tv.components.TvErrorState
import com.music.bitchord.ui.tv.components.TvListRow
import com.music.bitchord.ui.tv.components.TvLockup
import com.music.bitchord.ui.tv.components.TvSegmented
import com.music.bitchord.ui.tv.components.TvShelf
import com.music.bitchord.ui.tv.components.TvShelfTitle
import com.music.bitchord.ui.tv.components.TvSongRow
import com.music.bitchord.ui.tv.components.tvClick
import com.music.bitchord.ui.tv.components.tvInitialFocus
import com.music.bitchord.ui.tv.components.tvLift
import com.music.bitchord.ui.tv.components.tvPlatter
import com.music.bitchord.ui.tv.dialogs.TvSongMenu
import com.music.bitchord.ui.tv.theme.TvDimensions
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.launch

private val SearchFilters = listOf(
    SearchFilter.ALL,
    SearchFilter.SONGS,
    SearchFilter.ALBUMS,
    SearchFilter.ARTISTS,
    SearchFilter.PLAYLISTS,
)

@Composable
fun TvSearchScreen(
    viewModel: MainViewModel,
    mediaController: MediaController?,
    onNavigateToDetail: (browseId: String, title: String, subtitle: String, thumbnailUrl: String?, type: BrowseType) -> Unit,
    onNavigateToNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query by viewModel.query.collectAsState()
    val resultsState by viewModel.results.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val history by viewModel.searchHistory.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val loadingMore by viewModel.searchLoadingMore.collectAsState()
    val scope = rememberCoroutineScope()
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var numericMode by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    TvChromeScrollEffect(listState)

    val playSong: (Song) -> Unit = { song ->
        scope.launch { mediaController?.playSongs(listOf(song), 0) }
        onNavigateToNowPlaying()
    }
    // Whatever is played or opened from here is remembered, as the phone does,
    // so it shows under Recent Searches next time.
    val playResult: (Song) -> Unit = { song ->
        viewModel.recordEntity(
            SearchHistoryEntity(
                id = song.videoId,
                title = song.title,
                subtitle = song.artist,
                artworkUrl = song.thumbnailUrl,
                entityType = EntityType.TRACK,
            ),
        )
        playSong(song)
    }
    val openBrowse: (BrowseItem) -> Unit = { item ->
        viewModel.recordEntity(
            SearchHistoryEntity(
                id = item.browseId,
                title = item.title,
                subtitle = item.subtitle,
                artworkUrl = item.thumbnailUrl,
                entityType = when (item.type) {
                    BrowseType.ALBUM -> EntityType.ALBUM
                    BrowseType.ARTIST -> EntityType.ARTIST
                    BrowseType.PLAYLIST -> EntityType.PLAYLIST
                    BrowseType.OTHER -> EntityType.OTHER
                },
            ),
        )
        onNavigateToDetail(item.browseId, item.title, item.subtitle, item.thumbnailUrl, item.type)
    }

    val results = (resultsState as? UiState.Success)?.data
    val deduped = remember(results) { results?.deduplicated().orEmpty() }
    if (results != null && filter != SearchFilter.ALL) {
        val nearEnd by remember(listState) {
            derivedStateOf {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                last >= listState.layoutInfo.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, results.size) { if (nearEnd) viewModel.loadMoreSearchResults() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(top = TvDimensions.ContentTop, start = TvDimensions.SafeMarginHorizontal, end = TvDimensions.SafeMarginHorizontal, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Left Pane: Non-IME query display + custom D-pad keyboard (never opens Android system keyboard)
        TvCustomSearchKeyboard(
            query = query,
            numericMode = numericMode,
            onToggleNumeric = { numericMode = !numericMode },
            onAppend = { ch -> viewModel.onQueryChange(query + ch) },
            onBackspace = { if (query.isNotEmpty()) viewModel.onQueryChange(query.dropLast(1)) },
            onClear = { viewModel.onQueryChange("") },
            onSubmit = { viewModel.submitSearch() },
            modifier = Modifier
                .width(344.dp)
                .fillMaxHeight()
                .focusGroup(),
        )

        // Right Pane: Filter pills, live suggestions, history, and search results
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusGroup(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (resultsState != null) {
                item(key = "filters") {
                    TvSegmented(
                        options = SearchFilters.map { it.label },
                        selectedIndex = SearchFilters.indexOf(filter).coerceAtLeast(0),
                        onSelect = { viewModel.onFilterChange(SearchFilters[it]) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            when (val state = resultsState) {
                null -> if (query.isNotBlank() && suggestions.isNotEmpty()) {
                    items(suggestions, key = { "suggest:$it" }) { suggestion ->
                        TvListRow(
                            title = suggestion,
                            leadingIcon = Icons.Rounded.Search,
                            resting = Color.White.copy(alpha = 0.05f),
                            onClick = { viewModel.searchFor(suggestion) },
                        )
                    }
                } else {
                    idleContent(
                        history = history,
                        onHistory = { entity ->
                            val type = when (entity.entityType) {
                                EntityType.ALBUM -> BrowseType.ALBUM
                                EntityType.ARTIST -> BrowseType.ARTIST
                                EntityType.PLAYLIST -> BrowseType.PLAYLIST
                                EntityType.TRACK -> null
                                else -> BrowseType.OTHER
                            }
                            when {
                                type != null -> onNavigateToDetail(entity.id, entity.title, entity.subtitle, entity.artworkUrl, type)
                                entity.id.startsWith("q:") || entity.id.isBlank() -> viewModel.searchFor(entity.title)
                                else -> playSong(
                                    Song(
                                        videoId = entity.id,
                                        title = entity.title,
                                        artist = entity.subtitle,
                                        thumbnailUrl = entity.artworkUrl,
                                    ),
                                )
                            }
                        },
                        onClearHistory = viewModel::clearSearchHistory,
                    )
                }
                UiState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        TvActivityIndicator()
                    }
                }
                is UiState.Error -> item(key = "error") {
                    TvErrorState(message = state.message, onRetry = viewModel::submitSearch, modifier = Modifier.height(256.dp))
                }
                is UiState.Success -> if (deduped.isEmpty()) {
                    item(key = "empty") {
                        TvEmptyState(
                            title = "No Results",
                            message = "Nothing matched “$query”. Check the spelling or try other words.",
                            modifier = Modifier.height(256.dp),
                        )
                    }
                } else if (filter == SearchFilter.ALL) {
                    groupedResults(deduped, playResult, openBrowse, onSongMenu = { menuSong = it })
                } else {
                    filteredResults(deduped, playResult, openBrowse, onSongMenu = { menuSong = it })
                    if (loadingMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                TvActivityIndicator()
                            }
                        }
                    }
                }
            }
        }
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

private val AlphaKeys = listOf(
    listOf("a", "b", "c", "d", "e", "f"),
    listOf("g", "h", "i", "j", "k", "l"),
    listOf("m", "n", "o", "p", "q", "r"),
    listOf("s", "t", "u", "v", "w", "x"),
    listOf("y", "z", "1", "2", "3", "123"),
)

private val NumericKeys = listOf(
    listOf("1", "2", "3", "4", "5", "6"),
    listOf("7", "8", "9", "0", "-", "'"),
    listOf("&", ".", "!", "?", "/", "+"),
    listOf("(", ")", ":", ",", "#", "@"),
    listOf("á", "é", "í", "ó", "ú", "ABC"),
)

/** Dedicated D-pad on-screen keyboard that never triggers Android's native soft keyboard. */
@Composable
private fun TvCustomSearchKeyboard(
    query: String,
    numericMode: Boolean,
    onToggleNumeric: () -> Unit,
    onAppend: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = if (numericMode) NumericKeys else AlphaKeys
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Non-editable query display box (zero IME focus)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0B0E17))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = TvGlass.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = query.ifEmpty { "Search songs, artists, albums…" },
                style = TvType.Body.copy(fontWeight = if (query.isEmpty()) FontWeight.W400 else FontWeight.W600),
                color = if (query.isEmpty()) TvGlass.TextTertiary else TvGlass.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // Action row: Space, Delete, Clear, Search
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TvKeyCap(
                label = "Space",
                onClick = { onAppend(" ") },
                modifier = Modifier.weight(1.35f).height(34.dp),
            )
            TvKeyCap(
                label = "Del",
                onClick = onBackspace,
                modifier = Modifier.weight(1f).height(34.dp),
            )
            TvKeyCap(
                label = "Clear",
                onClick = onClear,
                modifier = Modifier.weight(1f).height(34.dp),
            )
            TvKeyCap(
                label = "Go",
                accent = true,
                onClick = onSubmit,
                modifier = Modifier.weight(1f).height(34.dp),
            )
        }

        // 6-column D-pad character grid
        rows.forEachIndexed { rIdx, rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEachIndexed { cIdx, keyLabel ->
                    val isModeToggle = keyLabel == "123" || keyLabel == "ABC"
                    TvKeyCap(
                        label = if (isModeToggle) keyLabel else keyLabel.uppercase(),
                        onClick = { if (isModeToggle) onToggleNumeric() else onAppend(keyLabel) },
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .then(if (rIdx == 0 && cIdx == 0) Modifier.tvInitialFocus() else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvKeyCap(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val resting = if (accent) Color(0xFF4F46E5) else Color.White.copy(alpha = 0.09f)
    Box(
        modifier = modifier
            .tvLift(interaction, RoundedCornerShape(9.dp), focusedScale = 1.08f, elevation = 8.dp)
            .tvPlatter(interaction, resting = resting)
            .tvClick(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TvType.Body.copy(
                fontSize = if (label.length > 2) 12.sp else 15.sp,
                fontWeight = FontWeight.W600,
            ),
            color = if (focused) TvGlass.OnPlatter else TvGlass.TextPrimary,
            maxLines = 1,
        )
    }
}

/**
 * Before a search: what was searched for lately, laid out as the results are —
 * a grid of lockups — with a Clear on its heading.
 */
private fun LazyListScope.idleContent(
    history: List<com.music.bitchord.data.model.SearchHistoryEntity>,
    onHistory: (com.music.bitchord.data.model.SearchHistoryEntity) -> Unit,
    onClearHistory: () -> Unit,
) {
    if (history.isEmpty()) {
        item(key = "idleEmpty") {
            TvEmptyState(
                title = "Search Sonora",
                message = "Find songs, albums, artists and playlists.",
                modifier = Modifier.height(240.dp),
            )
        }
        return
    }
    item(key = "recentTitle") {
        TvShelfTitle(
            title = "Recent Searches",
            trailing = { TvButton(text = "Clear", onClick = onClearHistory) },
            modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
        )
    }
    items(history.chunked(GridColumns), key = { row -> "recent:" + row.first().let { "${it.entityType}:${it.id}:${it.title}" } }) { row ->
        TvGridRow(count = row.size) { index, cell ->
            val entity = row[index]
            TvLockup(
                title = entity.title,
                subtitle = entity.subtitle.takeIf { it.isNotBlank() },
                artworkUrl = entity.artworkUrl,
                circle = entity.entityType == EntityType.ARTIST,
                width = Dp.Unspecified,
                onClick = { onHistory(entity) },
                modifier = cell,
            )
        }
    }
}

/** Columns in Search's grids: the recent searches and a single-kind result list. */
private const val GridColumns = 5

/**
 * One row of a five-column grid, aligned to the safe margins. Short rows keep
 * their cells the width of a full row's.
 */
@Composable
private fun TvGridRow(count: Int, cell: @Composable RowScope.(index: Int, cell: Modifier) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(TvDimensions.CardSpacing),
    ) {
        repeat(count) { index -> cell(index, Modifier.weight(1f)) }
        repeat(GridColumns - count) { Spacer(Modifier.weight(1f)) }
    }
}

/** "All": the best matches first, then a shelf per kind. */
private fun LazyListScope.groupedResults(
    results: List<SearchResult>,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songs = results.mapNotNull { it.song() }
    val browse = results.filterIsInstance<SearchResult.Browse>().map { it.item }

    item(key = "top") {
        TvShelf(title = "Top Results", items = results.take(10), key = { it.resultKey() }) { result ->
            ResultLockup(result, onPlaySong, onOpen, onSongMenu)
        }
    }
    if (songs.isNotEmpty()) {
        item(key = "songsTitle") { TvShelfTitle(title = "Songs") }
        songGrid(songs.take(12), onPlaySong, onSongMenu, keyPrefix = "song")
    }
    listOf(
        BrowseType.ARTIST to "Artists",
        BrowseType.ALBUM to "Albums",
        BrowseType.PLAYLIST to "Playlists",
        BrowseType.OTHER to "More",
    ).forEach { (type, title) ->
        val group = browse.filter { it.type == type }
        if (group.isNotEmpty()) {
            item(key = "group:$type") {
                TvShelf(title = title, items = group, key = { it.browseId }) { item ->
                    TvLockup(
                        title = item.title,
                        subtitle = if (type == BrowseType.ARTIST) null else item.subtitle,
                        artworkUrl = item.thumbnailUrl,
                        circle = type == BrowseType.ARTIST,
                        width = if (type == BrowseType.ARTIST) 120.dp else 131.dp,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

/** One kind only: songs as a three-column list, everything else as a grid of lockups. */
private fun LazyListScope.filteredResults(
    results: List<SearchResult>,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val songs = results.mapNotNull { it.song() }
    if (songs.size >= results.size / 2) {
        songGrid(songs, onPlaySong, onSongMenu, keyPrefix = "fsong")
        return
    }
    val browse = results.filterIsInstance<SearchResult.Browse>().map { it.item }
    items(browse.chunked(GridColumns), key = { row -> "frow:${row.first().browseId}" }) { row ->
        TvGridRow(count = row.size) { index, cell ->
            val item = row[index]
            TvLockup(
                title = item.title,
                subtitle = if (item.type == BrowseType.ARTIST) null else item.subtitle,
                artworkUrl = item.thumbnailUrl,
                circle = item.type == BrowseType.ARTIST,
                width = Dp.Unspecified,
                onClick = { onOpen(item) },
                modifier = cell,
            )
        }
    }
}

private fun LazyListScope.songGrid(
    songs: List<Song>,
    onPlaySong: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    keyPrefix: String,
) {
    items(songs.chunked(3), key = { row -> "$keyPrefix:${row.first().videoId}" }) { row ->
        Row(
            modifier = Modifier.padding(horizontal = TvDimensions.SafeMarginHorizontal - 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { song ->
                TvSongRow(
                    song = song,
                    showArtwork = true,
                    onClick = { onPlaySong(song) },
                    onLongClick = { onSongMenu(song) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ResultLockup(
    result: SearchResult,
    onPlaySong: (Song) -> Unit,
    onOpen: (BrowseItem) -> Unit,
    onSongMenu: (Song) -> Unit,
) {
    val song = result.song()
    if (song != null) {
        TvLockup(
            title = song.title,
            subtitle = song.artist,
            artworkUrl = song.thumbnailUrl,
            width = 131.dp,
            onClick = { onPlaySong(song) },
            onLongClick = { onSongMenu(song) },
        )
    } else if (result is SearchResult.Browse) {
        val item = result.item
        TvLockup(
            title = item.title,
            subtitle = if (item.type == BrowseType.ARTIST) null else item.subtitle,
            artworkUrl = item.thumbnailUrl,
            circle = item.type == BrowseType.ARTIST,
            width = if (item.type == BrowseType.ARTIST) 120.dp else 131.dp,
            onClick = { onOpen(item) },
        )
    }
}

private fun SearchResult.song(): Song? = when (this) {
    is SearchResult.TopTrack -> song
    is SearchResult.Track -> song
    is SearchResult.Browse -> null
}

private fun SearchResult.resultKey(): String = when (this) {
    is SearchResult.TopTrack -> "t:${song.videoId}"
    is SearchResult.Track -> "t:${song.videoId}"
    is SearchResult.Browse -> "b:${item.browseId}"
}

/** Drops repeats: the same video id, the same title-and-artist, or the same browse id. */
private fun List<SearchResult>.deduplicated(): List<SearchResult> {
    val ids = HashSet<String>()
    val names = HashSet<String>()
    return filter { result ->
        when (result) {
            is SearchResult.Browse -> result.item.browseId.isBlank() || ids.add("b:" + result.item.browseId)
            else -> {
                val song = result.song() ?: return@filter true
                val name = song.title.trim().lowercase() + "|" + song.artist.trim().lowercase()
                (song.videoId.isBlank() || ids.add("t:" + song.videoId)) && names.add(name)
            }
        }
    }
}
