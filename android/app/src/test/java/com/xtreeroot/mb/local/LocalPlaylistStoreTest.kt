package com.xtreeroot.mb.local

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaylistStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun persistsIdentityDeduplicationAndMovesOnlyVisibleTracks() {
        val file = folder.root.resolve("playlists.json")
        val store = LocalPlaylistStore(file)
        val id = store.create("  我的歌单  ")
        store.add(id, listOf("local:first", "bilibili:missing", "local:last", "local:first"))
        store.move(id, "local:last", -1, setOf("local:first", "local:last"))
        store.rename(id, "已改名")
        val restarted = LocalPlaylistStore(file)
        restarted.refresh()
        assertEquals(LocalPlaylist(id, "已改名", listOf("local:last", "bilibili:missing", "local:first")),
            restarted.playlists.value.single())
        restarted.remove(id, "local:last")
        assertEquals(listOf("bilibili:missing", "local:first"), restarted.playlists.value.single().trackIds)
        restarted.delete(id)
        assertEquals(emptyList<LocalPlaylist>(), restarted.playlists.value)
    }

    @Test
    fun movingAcrossSeveralRowsKeepsUnavailableTracksAndRejectsStaleOrder() {
        val file = folder.root.resolve("playlists.json")
        val store = LocalPlaylistStore(file)
        val visible = listOf("local:first", "local:second", "local:last")
        val id = store.create("测试", listOf(visible[0], "bilibili:missing", visible[1], visible[2]))
        store.reorder(id, 0, 2, visible)
        assertThrows(IOException::class.java) { store.reorder(id, 0, 1, visible) }
        val reloaded = LocalPlaylistStore(file).apply { refresh() }
        assertEquals(listOf(visible[1], "bilibili:missing", visible[2], visible[0]), reloaded.playlists.value.single().trackIds)
    }

    @Test
    fun invalidEditDoesNotChangePlaylist() {
        val store = LocalPlaylistStore(folder.root.resolve("playlists.json"))
        assertThrows(IOException::class.java) { store.create(" ") }
        val id = store.create("测试")
        store.add(id, listOf("local:first", "local:last"))
        assertThrows(IOException::class.java) { store.move(id, "local:first", -1, setOf("local:first", "local:last")) }
        assertEquals(listOf("local:first", "local:last"), store.playlists.value.single().trackIds)
    }
}
