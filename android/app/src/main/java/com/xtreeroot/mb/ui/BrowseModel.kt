package com.xtreeroot.mb.ui

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.DiscoverySection
import com.xtreeroot.mb.music.DiscoverySource
import com.xtreeroot.mb.music.MusicSource
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginSearchKind
import com.xtreeroot.mb.plugin.PluginSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

internal data class LoadState<T>(val value: T, val loading: Boolean = false, val error: String? = null)

internal enum class SearchScope { Music, Video }

internal class BrowseModel(application: Application, private val source: DiscoverySource) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as MBApplication).bilibili)

    private val app = application as MBApplication
    private val prefs = application.getSharedPreferences("browse", 0)
    private var homeJob: Job? = null
    private var categoryJob: Job? = null
    private val homeState = MutableStateFlow(LoadState(emptyList<DiscoverySection>()))
    private val detailsState = MutableStateFlow(LoadState<DiscoverySection?>(null))
    private var searchSource: MusicSource? = null
    private val searches = SearchScope.entries.associateWith { mode ->
        SearchPages(viewModelScope, if (mode == SearchScope.Music) 1 else 0) { sourceId, query, cursor ->
            val selected = searchSource?.takeIf { it.id == sourceId }
                ?: throw IllegalStateException("音源不可用")
            if (mode == SearchScope.Video) {
                val plugin = selected as? PluginSource ?: throw IllegalStateException("音源不支持视频搜索")
                val result = plugin.catalog.load(PluginCatalogRequest.Search(query, PluginSearchKind.Videos), cursor)
                TrackPage(result.sections.flatMap { it.tracks }, result.nextOffset != null) to (result.nextOffset ?: cursor)
            } else selected.search(query, cursor) to (cursor + 1)
        }
    }
    private val historyState = MutableStateFlow(readHistory())
    val home = homeState.asStateFlow()
    val details = detailsState.asStateFlow()
    val history = historyState.asStateFlow()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "search_history") historyState.value = readHistory()
    }

    init { prefs.registerOnSharedPreferenceChangeListener(preferenceListener) }

    override fun onCleared() { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener) }

    fun refresh() {
        if (!app.settings.values.value.builtInEnabled) return
        homeJob?.cancel()
        homeJob = viewModelScope.launch { load(homeState) { source.home().keepingFailedSections(homeState.value.value) } }
    }

    fun sourceDisabled() {
        homeJob?.cancel()
        categoryJob?.cancel()
        homeState.value = LoadState(emptyList())
        detailsState.value = LoadState(null)
    }

    fun open(category: DiscoveryCategory) {
        if (!app.settings.values.value.builtInEnabled) return
        categoryJob?.cancel()
        detailsState.value = LoadState(DiscoverySection(category))
        categoryJob = viewModelScope.launch { load(detailsState) { source.discovery(category) } }
    }

    fun refreshCategory(category: DiscoveryCategory) {
        if (!app.settings.values.value.builtInEnabled || categoryJob?.isActive == true || detailsState.value.value?.category != category) return
        categoryJob = viewModelScope.launch { load(detailsState) { source.discovery(category) } }
    }

    fun activateSearch(source: MusicSource?) {
        val selected = if (source === app.bilibili) this.source else source
        if (searchSource === selected) return
        searchSource = selected
        searches.values.forEach { it.submit(selected?.id.orEmpty(), "") }
    }

    fun searchPages(mode: SearchScope) = searches.getValue(mode)

    fun submitSearch(mode: SearchScope, input: String) {
        val source = searchSource ?: return
        val term = input.trim()
        if (term.isNotEmpty()) writeHistory((listOf(term) + historyState.value).distinct().take(30))
        searchPages(mode).submit(source.id, term)
    }

    fun removeHistory(term: String) = writeHistory(historyState.value.filterNot { it == term })

    fun clearHistory() = writeHistory(emptyList())

    private suspend fun <Value> load(state: MutableStateFlow<LoadState<Value>>, work: suspend () -> Value) {
        state.update { it.copy(loading = true, error = null) }
        try {
            val next = work()
            currentCoroutineContext().ensureActive()
            state.value = LoadState(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            state.update { it.copy(loading = false, error = error.message ?: "加载失败，请重试") }
        }
    }

    private fun readHistory(): List<String> = runCatching {
        val values = JSONArray(prefs.getString("search_history", "[]"))
        (0 until values.length()).map(values::getString)
    }.getOrDefault(emptyList())

    private fun writeHistory(terms: List<String>) {
        historyState.value = terms
        prefs.edit().putString("search_history", JSONArray(terms).toString()).apply()
        app.portableLibrary.userStateChanged()
    }
}

internal fun List<DiscoverySection>.keepingFailedSections(previous: List<DiscoverySection>): List<DiscoverySection> {
    val byCategory = previous.associateBy(DiscoverySection::category)
    return map { section ->
        val old = byCategory[section.category]
        if (section.error != null && section.tracks.isEmpty() && old != null) old.copy(error = section.error) else section
    }
}
