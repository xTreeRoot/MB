package com.xtreeroot.mb.local

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalPlaylist(val id: String, val name: String, val trackIds: List<String> = emptyList())

internal class LocalPlaylistStore(private val file: File) {
    private val state = MutableStateFlow(emptyList<LocalPlaylist>())
    val playlists = state.asStateFlow()
    private var loaded = false

    @Synchronized
    fun refresh() {
        if (loaded) return
        if (file.isFile) {
            val items = JSONArray(file.readText())
            state.value = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val tracks = item.getJSONArray("tracks")
                LocalPlaylist(item.getString("id"), item.getString("name"), (0 until tracks.length()).map(tracks::getString))
            }
        }
        loaded = true
    }

    @Synchronized
    fun create(name: String, tracks: List<String> = emptyList()): String {
        refresh()
        val playlist = LocalPlaylist(UUID.randomUUID().toString(), checkedName(name), tracks.distinct())
        commit(state.value + playlist)
        return playlist.id
    }

    @Synchronized
    fun rename(id: String, name: String) = edit(id) { it.copy(name = checkedName(name)) }

    @Synchronized
    fun delete(id: String) {
        refresh()
        if (state.value.none { it.id == id }) throw IOException("歌单不存在")
        commit(state.value.filterNot { it.id == id })
    }

    @Synchronized
    fun add(id: String, tracks: List<String>) = edit(id) { it.copy(trackIds = (it.trackIds + tracks).distinct()) }

    @Synchronized
    fun merge(restored: List<LocalPlaylist>) {
        refresh()
        val existing = state.value.mapTo(mutableSetOf(), LocalPlaylist::id)
        commit(state.value + restored.filter { existing.add(it.id) })
    }

    @Synchronized
    fun remove(id: String, track: String) = edit(id) { it.copy(trackIds = it.trackIds - track) }

    @Synchronized
    fun move(id: String, track: String, step: Int, available: Set<String>) = edit(id) { playlist ->
        require(step == -1 || step == 1)
        val visible = playlist.trackIds.filter { it in available }
        val index = visible.indexOf(track).takeIf { it >= 0 } ?: throw IOException("曲目位置已变化，请重试")
        val neighbor = visible.getOrNull(index + step) ?: throw IOException("曲目位置已变化，请重试")
        playlist.copy(trackIds = playlist.trackIds.map { item ->
            when (item) { track -> neighbor; neighbor -> track; else -> item }
        })
    }

    private fun checkedName(name: String): String = name.trim().takeUnless(String::isEmpty) ?: throw IOException("请输入歌单名称")

    private fun edit(id: String, change: (LocalPlaylist) -> LocalPlaylist) {
        refresh()
        if (state.value.none { it.id == id }) throw IOException("歌单不存在")
        commit(state.value.map { if (it.id == id) change(it) else it })
    }

    private fun commit(next: List<LocalPlaylist>) {
        if (next == state.value) return
        writeAtomic(file, JSONArray(next.map { playlist ->
            JSONObject().put("id", playlist.id).put("name", playlist.name).put("tracks", JSONArray(playlist.trackIds))
        }).toString())
        state.value = next
    }
}
