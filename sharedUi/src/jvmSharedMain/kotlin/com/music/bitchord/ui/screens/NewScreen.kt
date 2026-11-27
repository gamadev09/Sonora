package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.new_music
import com.music.bitchord.sharedui.resources.global_charts
import com.music.bitchord.sharedui.resources.retry
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PullToRefresh
import com.music.bitchord.ui.components.feedSkeleton
import org.jetbrains.compose.resources.stringResource

/** Release feed with worldwide content always after listener-relevant releases. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScreen(
    state: UiState<List<HomeShelf>>,
    globalShelves: List<HomeShelf>,
    listState: LazyListState,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    contentPadding: PaddingValues,
    onShelfItemClick: (ShelfItem) -> Unit,
) {
    PullToRefresh(refreshing = refreshing, onRefresh = onRefresh, state = pullState) {
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "new:title") {
                Text(
                    text = stringResource(Res.string.new_music),
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }
            when (state) {
                UiState.Loading -> feedSkeleton()
                is UiState.Error -> item(key = "new:error") {
                    MessageState(state.message, actionLabel = stringResource(Res.string.retry), onAction = onRetry)
                }
                is UiState.Success -> {
                    items(state.data, key = { "new:personal:${it.title}" }) { shelf ->
                        Shelf(shelf = shelf, onItemClick = onShelfItemClick)
                    }
                    if (globalShelves.isNotEmpty()) {
                        item(key = "new:global-header") {
                            Text(
                                text = stringResource(Res.string.global_charts),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = PAGE_GUTTER, top = 20.dp, bottom = 4.dp),
                            )
                        }
                        items(globalShelves, key = { "new:global:${it.title}" }) { shelf ->
                            Shelf(shelf = shelf, onItemClick = onShelfItemClick)
                        }
                    }
                }
            }
        }
    }
}
