package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableList(refreshing: Boolean, refresh: () -> Unit, modifier: Modifier = Modifier,
                             contentPadding: PaddingValues = PaddingValues(), state: LazyListState = rememberLazyListState(),
                             content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableDiscoveryGrid(video: Boolean, refreshing: Boolean, refresh: () -> Unit, content: LazyGridScope.() -> Unit) {
    val edge = if (video) 10.dp else 0.dp
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = if (video) GridCells.Adaptive(155.dp) else GridCells.Fixed(1), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = edge, end = edge, bottom = 20.dp), content = content)
    }
}
