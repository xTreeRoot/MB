package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RefreshableList(refreshing: Boolean, refresh: () -> Unit, modifier: Modifier = Modifier,
                             contentPadding: PaddingValues = PaddingValues(), state: LazyListState = rememberLazyListState(),
                             content: LazyListScope.() -> Unit) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = contentPadding, content = content)
    }
}
