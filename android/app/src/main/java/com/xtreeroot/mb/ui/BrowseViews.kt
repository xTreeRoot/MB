package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.R
import com.xtreeroot.mb.music.SuggestionSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.CancellationException

internal val LocalVideoBrowse = staticCompositionLocalOf { false }

@Composable
internal fun DiscoveryCategory.title(): String = when (this) {
    DiscoveryCategory.Recommended -> mb("为你推荐", "Recommended")
    DiscoveryCategory.Chart -> mb("音乐榜", "Music Charts")
    DiscoveryCategory.Trending -> mb("音乐区热门", "Trending Music")
    DiscoveryCategory.Fresh -> mb("新鲜投稿", "New Uploads")
}

@Composable
internal fun Discover(
    model: BrowseModel,
    connected: Boolean,
    play: (List<Track>, Track) -> Unit,
    open: (DiscoveryCategory) -> Unit,
) {
    val home by model.home.collectAsStateWithLifecycle()
    val wideCovers = LocalVideoBrowse.current
    RefreshableList(refreshing = home.loading && home.value.isNotEmpty(), refresh = { if (!home.loading) model.refresh() },
        contentPadding = PaddingValues(bottom = 20.dp)) {
        if (home.loading && home.value.isEmpty()) item { Loading() }
        home.error?.let { message -> item { Retry(message, model::refresh) } }
        home.value.forEach { section ->
            item(key = "header-${section.category}") {
                Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 3.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(section.category.title(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        section.week?.let { Text(mb("第 $it 周", "Week $it"), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = { open(section.category) }) {
                        Text(mb("更多", "More"))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
                    }
                }
            }
            section.error?.let { item { Retry(it, model::refresh) } }
            when {
                section.tracks.isEmpty() -> if (section.error == null) item { EmptyMessage(mb("暂无内容", "No Content")) }
                section.category != DiscoveryCategory.Chart -> item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.tracks, key = Track::id) { track ->
                            val lead = section.category == DiscoveryCategory.Recommended
                            val width = if (lead) 236.dp else if (wideCovers) 168.dp else 136.dp
                            Column(Modifier.width(width).clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = connected && !track.isUnavailable) { play(section.tracks, track) }) {
                                Box {
                                    Artwork(track, Modifier.fillMaxWidth().aspectRatio(if (wideCovers || lead) 16f / 9f else 1f))
                                    TrackActionsButton(track, Modifier.align(Alignment.TopEnd), onArtwork = true)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = if (lead) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold)
                                TrackSubtitle(track)
                            }
                        }
                    }
                }
                else -> items(section.tracks.take(3), key = { "${section.category}-${it.id}" }) { track ->
                    val rank = section.tracks.indexOf(track) + 1
                    if (wideCovers) VideoPreviewRow(track, connected) { play(section.tracks, track) }
                    else TrackRow(track, connected, rank = rank) { play(section.tracks, track) }
                }
            }
        }
    }
}

@Composable
internal fun CategoryView(model: BrowseModel, category: DiscoveryCategory, connected: Boolean,
                           play: (List<Track>, Track) -> Unit) {
    val details by model.details.collectAsStateWithLifecycle()
    val tracks = details.value?.takeIf { it.category == category }?.tracks.orEmpty()
    val refresh = { model.refreshCategory(category) }
    RefreshableList(refreshing = details.loading && tracks.isNotEmpty(), refresh = refresh) {
        when {
            details.error != null -> item { Retry(checkNotNull(details.error), refresh) }
            details.loading && tracks.isEmpty() -> item { Loading() }
            !details.loading && tracks.isEmpty() -> item { EmptyMessage(mb("暂无内容", "No Content")) }
        }
        if (tracks.isNotEmpty()) item {
            Button(enabled = connected, onClick = { play(tracks, tracks.first()) },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Text(mb("播放全部", "Play All")) }
        }
        items(tracks, key = Track::id) { track -> BrowsingTrackRow(track, connected) { play(tracks, track) } }
    }
}

@Composable
internal fun Search(model: BrowseModel, connected: Boolean, activeSource: String, plugin: PluginSource?, openCatalog: (() -> Unit)?,
                    play: (List<Track>, Track) -> Unit) {
    var supportsVideos by remember(plugin) { mutableStateOf(false) }
    LaunchedEffect(plugin) {
        supportsVideos = try { plugin?.catalog?.supports("searchVideos") == true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    var selected by rememberSaveable { mutableStateOf(SearchScope.Music) }
    val videoBrowsing = LocalVideoBrowse.current
    val showScopes = supportsVideos && videoBrowsing
    val mode = selected.takeIf { showScopes } ?: SearchScope.Music
    val focus = LocalFocusManager.current
    LaunchedEffect(videoBrowsing) { if (!videoBrowsing) selected = SearchScope.Music }
    LaunchedEffect(mode) {
        val inactive = if (mode == SearchScope.Music) SearchScope.Video else SearchScope.Music
        model.searchPages(inactive).cancel()
    }
    Column(Modifier.fillMaxSize()) {
        if (showScopes) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchScope.entries.forEach { option ->
                FilterChip(mode == option, onClick = { focus.clearFocus(); selected = option }, label = {
                    Text(if (option == SearchScope.Music) mb("音乐", "Music") else mb("视频", "Videos"))
                })
            }
        }
        val state = rememberSaveableStateHolder()
        state.SaveableStateProvider(mode.name) {
            key(mode) { SearchResults(model, mode, connected, activeSource, plugin, openCatalog, play) }
        }
    }
}

@Composable
private fun SearchResults(model: BrowseModel, mode: SearchScope, connected: Boolean, activeSource: String,
                          suggestionSource: SuggestionSource?, openCatalog: (() -> Unit)?, play: (List<Track>, Track) -> Unit) {
    val pages = model.searchPages(mode)
    val result by pages.state.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(result.value.query.takeIf { result.value.sourceId == activeSource }.orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val submit = { term: String ->
        query = term
        model.submitSearch(mode, term)
        focus.clearFocus()
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { focused = it.isFocused },
            singleLine = true, placeholder = { Text(mb("搜索歌曲或视频", "Search songs or videos")) },
            leadingIcon = { IconButton(onClick = { submit(query) }, enabled = query.isNotBlank()) { Icon(Icons.Default.Search, mb("搜索", "Search")) } },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) IconButton(onClick = { submit("") }) {
                        Icon(Icons.Default.Close, mb("清空搜索", "Clear Search"))
                    }
                    if (focused) TextButton(onClick = { focus.clearFocus() }) { Text(mb("完成", "Done")) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submit(query) }))
        SearchSuggestions(suggestionSource, query, focused, submit)
        if (openCatalog != null) TextButton(modifier = Modifier.padding(start = 12.dp), onClick = openCatalog) {
            Text(mb("分类搜索", "Search by Type"))
        }
        LazyColumn(Modifier.fillMaxSize().pointerInput(focus) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                waitForUpOrCancellation(PointerEventPass.Final)
                focus.clearFocus()
            }
        }) {
            if (result.value.query.isEmpty() && history.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(mb("搜索记录", "Search History"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = model::clearHistory) { Text(mb("清空", "Clear")) }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(history) { term -> FilterChip(false, { submit(term) }, label = { Text(term) }) }
                    }
                }
            }
            items(result.value.tracks, key = Track::id) { track ->
                BrowsingTrackRow(track, connected && track.sourceId == activeSource) { play(result.value.tracks, track) }
            }
            when {
                result.loading -> item { Loading() }
                result.error != null -> item { Retry(checkNotNull(result.error), pages::loadNext) }
                result.value.hasMore -> item { TextButton(onClick = pages::loadNext) { Text(mb("加载更多", "Load More")) } }
                result.value.query.isNotEmpty() && result.value.tracks.isEmpty() -> item { EmptyMessage(mb("未找到结果", "No results")) }
            }
        }
    }
}

@Composable
internal fun BrowsingTrackRow(track: Track, connected: Boolean, play: () -> Unit) {
    if (LocalVideoBrowse.current) VideoPreviewRow(track, connected, play = play) else TrackRow(track, connected, play = play)
}

@Composable
internal fun VideoPreviewRow(track: Track, connected: Boolean, selectedTrack: Boolean = false, play: () -> Unit) {
    ListItem(modifier = Modifier.clickable(enabled = connected && !track.isUnavailable, onClick = play)
        .semantics { selected = selectedTrack },
        headlineContent = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = if (selectedTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
        supportingContent = { TrackSubtitle(track) },
        leadingContent = { Artwork(track, Modifier.width(112.dp).aspectRatio(16f / 9f)) },
        trailingContent = { TrackActionsButton(track) })
}

@Composable
internal fun TrackRow(track: Track, enabled: Boolean = true, rank: Int? = null,
                      actions: @Composable () -> Unit = { TrackActionsButton(track) }, play: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
        .clickable(enabled = enabled && !track.isUnavailable, onClick = play).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        rank?.let { Text(it.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium,
            color = if (it == 1) colors.primary else colors.onSurfaceVariant, fontWeight = FontWeight.Bold) }
        Artwork(track, Modifier.size(44.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.onSurface)
            TrackSubtitle(track)
        }
        if (track.durationMs > 0) Text(timeLabel(track.durationMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        actions()
    }
}

@Composable
internal fun Artwork(track: Track, modifier: Modifier = Modifier) {
    Artwork(track.artworkUrl, track.artworkHeaders, modifier)
}

@Composable
internal fun Artwork(address: String?, headers: Map<String, String>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (address != null) {
            val request = remember(context, address, headers) {
                ImageRequest.Builder(context).data(address).apply {
                    headers.forEach { (name, value) -> addHeader(name, value) }
                }.build()
            }
            AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
internal fun EmptyMessage(message: String) {
    Text(message, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Retry(message: String, retry: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = retry) { Text(mb("重试", "Retry")) }
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

internal fun timeLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
