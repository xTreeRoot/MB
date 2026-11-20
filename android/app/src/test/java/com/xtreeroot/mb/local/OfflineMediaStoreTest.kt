package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun clearingCacheCannotInterruptLeasedFileOrRevivePendingWrite() {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        val cached = track("cached")
        val pending = track("pending")
        val download = track("manual")
        val ticket = checkNotNull(store.cacheTicket(pending.id))
        store.save(store.staging().apply { writeText("complete") }, cached, "m4a", store.cacheTicket(cached.id))
        store.save(store.staging().apply { writeText("manual download") }, download, "ogg")
        val lease = checkNotNull(store.lease(cached.id))
        store.clearCache()
        assertEquals("complete", lease.path.readText())
        assertNull(store.lease(cached.id))
        val staged = store.staging().apply { writeText("late") }
        store.save(staged, pending, "m4a", ticket)
        staged.delete()
        assertNull(store.lease(pending.id))
        assertEquals(listOf(download.id), store.snapshot.value.downloads.map { it.track.id })
        lease.close()
        assertFalse(lease.path.exists())
        val restarted = OfflineMediaStore(directory)
        restarted.refresh()
        assertEquals("manual download", restarted.lease(download.id)?.use { it.path.readText() })
    }

    @Test
    fun evictsLeastRecentlyAccessedAndPromotesWithoutDuplicatingBytes() {
        val directory = folder.newFolder()
        val store = OfflineMediaStore(directory)
        store.setLimit(256)
        val recent = track("recent")
        val evicted = track("evicted")
        val last = track("last")
        val size = 100L * 1024 * 1024
        fun cache(track: Track) {
            val path = store.staging()
            RandomAccessFile(path, "rw").use { it.setLength(size) }
            store.save(path, track, "m4a", checkNotNull(store.cacheTicket(track.id)))
        }
        cache(recent)
        cache(evicted)
        val path = store.lease(recent.id)?.use { it.path }
        cache(last)
        assertNull(store.lease(evicted.id))
        assertEquals(size * 2, store.snapshot.value.cacheBytes)
        assertTrue(store.promote(recent))
        assertEquals(size, store.snapshot.value.cacheBytes)
        store.setLimit(0)
        assertNull(store.cacheTicket(track("new").id))
        assertEquals(0L, store.snapshot.value.cacheBytes)
        assertEquals(path, store.lease(recent.id, downloadsOnly = true)?.use { it.path })
        assertNotNull(path)
    }

    @Test
    fun removingDownloadKeepsTheReaderAliveAfterPlaybackReleasesItsLease() {
        val store = OfflineMediaStore(folder.newFolder())
        val track = track("active")
        store.save(store.staging().apply { writeText("audio bytes") }, track, "m4a")
        val playback = checkNotNull(store.lease(track.id))
        val reader = playback.duplicate()
        store.removeDownload(track.id)
        playback.close()
        assertNull(store.lease(track.id))
        assertEquals("audio bytes", reader.path.readText())
        reader.close()
        assertFalse(reader.path.exists())
    }

    @Test
    fun restoresDownloadsBeforeThePrivateStoreExistsAndKeepsTheirPortableNames() {
        val directory = File(folder.root, "not-created-yet")
        val store = OfflineMediaStore(directory)
        val staged = folder.newFile().apply { writeText("saved audio") }
        val name = "11111111-1111-1111-1111-111111111111.m4a"
        val track = track("restored")
        store.restoreDownload(staged, track, name, addedAt = 450)
        assertEquals(name, store.snapshot.value.downloads.single().fileName)
        val reopened = OfflineMediaStore(directory)
        checkNotNull(reopened.lease(track.id)).use { assertEquals("saved audio", it.path.readText()) }
        assertEquals(450L, reopened.snapshot.value.downloads.single().used)
        assertEquals("saved audio", store.lease(track.id)?.use { it.path.readText() })
    }

    @Test
    fun restoresTruncatedDownloadsWithoutReplacingIntactLocalRenditions() {
        val store = OfflineMediaStore(folder.newFolder())
        val track = track("restored")
        store.save(store.staging().apply { writeText("local rendition") }, track, "m4a")
        val local = checkNotNull(store.lease(track.id)?.use { it.path })
        val backup = folder.newFile().apply { writeText("backup audio") }

        store.restoreDownload(backup, track, local.name)
        assertEquals("local rendition", local.readText())
        local.writeText("")
        assertFalse(store.hasIntactDownload(track.id))

        store.restoreDownload(backup, track, local.name)
        assertTrue(store.hasIntactDownload(track.id))
        assertEquals("backup audio", store.lease(track.id)?.use { it.path.readText() })
    }

    private fun track(id: String) = Track("fixture", id, id, "fixture")
}
