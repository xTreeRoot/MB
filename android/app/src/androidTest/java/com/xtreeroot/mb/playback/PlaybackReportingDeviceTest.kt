package com.xtreeroot.mb.playback

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PluginSource
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportingDeviceTest {
    @Test fun unavailableSourceDoesNotPreventAnotherSongFromPlaying() = runBlocking {
        withFixture { player ->
            val removed = first.copy(sourceId = "removed-fixture", title = "Removed")
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.connected }
                withContext(Dispatchers.Main) { model.play(listOf(removed, first, second), second) }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
                withContext(Dispatchers.Main) {
                    assertEquals(listOf(first.id, second.id), model.playback.value.tracks.map(Track::id))
                    model.play(listOf(removed, first, second), removed)
                    assertEquals(second.id, model.playback.value.current?.id)
                    assertEquals(PlaybackIssue.SourceUnavailable,
                        app.trackPlaybackIssues.values.value[removed.playbackKey]?.issue)
                    model.playGroup(listOf(removed, first, second), shuffle = false)
                }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id }
            } finally {
                app.trackPlaybackIssues.playing(removed)
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MBApplication

    @Test fun selectingASongReconnectsAfterPlaybackServiceBecomesAvailable() = runBlocking {
        val service = ComponentName(app, PlaybackService::class.java)
        val packages = app.packageManager
        val original = packages.getComponentEnabledSetting(service)
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            eventually { model.playback.value.connectionIssue == PlaybackConnectionIssue.Unavailable && !model.playback.value.connected }
            withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
            }
            withFixture { player ->
                withContext(Dispatchers.Main) { model.play(listOf(first), first) }
                eventually { model.playback.value.connected && model.playback.value.connectionIssue == null }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id && player.currentPosition > 0 }
            }
        } finally {
            withContext(Dispatchers.Main) {
                packages.setComponentEnabledSetting(service, original, PackageManager.DONT_KILL_APP)
                store.clear()
            }
        }
    }

    @Test fun connectingPlaysOnlyTheLatestSelectionAndDiscardsAClearedSelection() = runBlocking {
        withFixture { player ->
            val cancelledStore = ViewModelStore()
            val playbackStore = ViewModelStore()
            try {
                val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app)
                val cancelled = withContext(Dispatchers.Main) {
                    ViewModelProvider(cancelledStore, factory)[PlaybackModel::class.java].also { model ->
                        assertTrue(!model.playback.value.connected)
                        model.play(listOf(first), first)
                        model.clear()
                    }
                }
                eventually { cancelled.playback.value.connected }
                assertTrue(cancelled.playback.value.tracks.isEmpty())
                val model = withContext(Dispatchers.Main) {
                    ViewModelProvider(playbackStore, factory)[PlaybackModel::class.java].also { connecting ->
                        assertTrue(!connecting.playback.value.connected)
                        connecting.play(listOf(first, second), first)
                        connecting.play(listOf(first, second), second)
                    }
                }
                eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id && player.currentPosition > 0 }
                assertEquals(second.id, model.playback.value.current?.id)
                suspend fun reports() = source.search("reports", 1).tracks.map(Track::title)
                withTimeout(6_000) { while (reports().isEmpty()) delay(20) }
                assertEquals(listOf("second"), reports())
            } finally {
                withContext(Dispatchers.Main) { cancelledStore.clear(); playbackStore.clear() }
            }
        }
    }

    @Test fun scrubbingPositionsAPausedQueueBeforePreparationAndRejectsAnOldSelection() = runBlocking {
        withFixture { player ->
            val store = ViewModelStore()
            val model = withContext(Dispatchers.Main) {
                player.pause()
                player.stop()
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
            }
            try {
                eventually { model.playback.value.seekable && model.playback.value.current?.id == first.id }
                withContext(Dispatchers.Main) { model.seekCurrent(first.id, 0, 20_000) }
                eventually { player.currentPosition == 20_000L }
                withContext(Dispatchers.Main) {
                    assertEquals(Player.STATE_IDLE, player.playbackState)
                    assertTrue(!player.playWhenReady)
                    player.seekTo(1, 5_000)
                }
                eventually { model.playback.value.current?.id == second.id && player.currentPosition == 5_000L }
                withContext(Dispatchers.Main) {
                    model.seekCurrent(first.id, 0, 40_000)
                    assertEquals(5_000L, player.currentPosition)
                    assertEquals(5_000L, model.playback.value.positionMs)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY && player.currentPosition == 5_000L }
            } finally {
                withContext(Dispatchers.Main) { store.clear() }
            }
        }
    }

    @Test fun mediaTitleUsesCurrentLyricWithoutChangingQueueMetadata() = runBlocking {
        val original = app.settings.values.value.nowPlayingLyrics
        try {
            app.settings.update { it.copy(nowPlayingLyrics = true) }
            withFixture { player ->
                suspend fun expect(title: String, artist: String) = eventually {
                    player.mediaMetadata.title?.toString() == title && player.mediaMetadata.artist?.toString() == artist
                }
                withContext(Dispatchers.Main) {
                    player.pause()
                    player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                    player.prepare()
                }
                eventually { player.playbackState == Player.STATE_READY }
                expect(first.title, first.title)
                withContext(Dispatchers.Main) { player.seekTo(2_000) }
                expect("First line", first.title)
                withContext(Dispatchers.Main) { player.seekTo(4_000) }
                expect("Next line", first.title)
                withContext(Dispatchers.Main) {
                    assertEquals(first, player.currentMediaItem?.track())
                    assertEquals(second, player.getMediaItemAt(1).track())
                    player.seekToNextMediaItem()
                }
                expect(second.title, second.title)
                app.settings.update { it.copy(nowPlayingLyrics = false) }
                expect(second.title, second.artist)
                withContext(Dispatchers.Main) { assertEquals(second, player.currentMediaItem?.track()) }
            }
        } finally {
            app.settings.update { it.copy(nowPlayingLyrics = original) }
        }
    }

    @Test fun reportsOnlyWhenASelectionStartsPlayingThroughTheMediaSession() = runBlocking {
        withFixture { player ->
            suspend fun reports() = source.search("reports", 1).tracks.map(Track::title)
            suspend fun expect(vararg ids: String) {
                withTimeout(6_000) { while (reports().size < ids.size) delay(20) }
                assertEquals(ids.toList(), reports())
            }
            val queue = listOf(first.mediaItem(), second.mediaItem())
            withContext(Dispatchers.Main) {
                player.pause()
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
                player.setMediaItems(queue, 0, 0)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY && player.currentMediaItem?.mediaId == first.id }
            expect()

            withContext(Dispatchers.Main) { player.play() }
            expect("first")
            withContext(Dispatchers.Main) { player.pause() }
            eventually { !player.isPlaying }
            val reopened = connect().also { active = it }
            withContext(Dispatchers.Main) {
                MediaController.releaseFuture(connections.removeAt(0))
                reopened.seekTo(65_000)
                reopened.play()
            }
            eventually { reopened.isPlaying && reopened.currentPosition >= 65_000 }
            expect("first")

            withContext(Dispatchers.Main) { reopened.stop() }
            eventually { reopened.playbackState == Player.STATE_IDLE }
            withContext(Dispatchers.Main) { reopened.prepare(); reopened.play() }
            eventually { reopened.isPlaying }
            expect("first")

            withContext(Dispatchers.Main) { reopened.seekToNextMediaItem() }
            expect("first", "second")
            withContext(Dispatchers.Main) { reopened.seekToPreviousMediaItem() }
            expect("first", "second", "first")

            withContext(Dispatchers.Main) {
                reopened.setMediaItems(queue, 0, 0)
                reopened.prepare()
                reopened.play()
            }
            expect("first", "second", "first", "first")

            withContext(Dispatchers.Main) {
                reopened.repeatMode = Player.REPEAT_MODE_ONE
                reopened.seekTo(89_900)
            }
            expect("first", "second", "first", "first", "first")
        }
    }

    @Test fun recordsRecentOnlyAfterDownloadedAudioPlaysAndSkipsCacheOnlyAudio() = runBlocking {
        withFixture(cacheSecond = true) { player ->
            suspend fun recent() = app.local.catalog.activity().filter { it.trackId in setOf(first.id, second.id) }
            withContext(Dispatchers.Main) {
                player.pause()
                player.setMediaItems(listOf(first.mediaItem(), second.mediaItem()), 0, 0)
                player.prepare()
            }
            eventually { player.playbackState == Player.STATE_READY }
            assertTrue(recent().isEmpty())
            withContext(Dispatchers.Main) { player.play() }
            val firstPlay = withTimeout(6_000) {
                while (recent().isEmpty()) delay(20)
                recent().single().also { assertEquals(first.id, it.trackId) }.playedAt
            }
            withContext(Dispatchers.Main) { player.seekToNextMediaItem() }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) { player.seekToPreviousMediaItem() }
            withTimeout(6_000) {
                while (recent().none { it.trackId == first.id && it.playedAt > firstPlay }) delay(20)
            }
            assertEquals(listOf(first.id), recent().map { it.trackId })
            app.plugins.setEnabled(plugin, false)
            app.refreshPlugins()
            assertNull(app.playbackSource(first.sourceId))
            withContext(Dispatchers.Main) {
                player.pause()
                player.setMediaItem(first.mediaItem(), 3_000)
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && player.mediaItemCount == 1 && player.currentMediaItem?.mediaId == first.id }
        }
    }

    @Test fun shuffleStartsAtTheSelectedSongAndStillPlaysTheRestWithoutRepeat() = runBlocking {
        withFixture { player ->
            val queue = listOf(first.mediaItem(), second.mediaItem())
            withContext(Dispatchers.Main) {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.setMediaItems(queue, 1, 89_850)
                player.shuffleModeEnabled = true
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == first.id }
            withContext(Dispatchers.Main) {
                player.setMediaItems(queue, 0, 89_850)
                player.prepare()
            }
            eventually { player.isPlaying && player.currentMediaItem?.mediaId == second.id }
            withContext(Dispatchers.Main) { player.shuffleModeEnabled = false }
        }
    }

    @Test fun leavingStationCancelsPendingPluginRequestAndKeepsThePlayingQueue() = runBlocking {
        withFixture { player ->
            val station = PlaybackStation(first.sourceId, "daily", "Daily")
            withContext(Dispatchers.Main) {
                player.setMediaItems(listOf(first.mediaItem().withStation(station), second.mediaItem()), 0, 12_000)
                player.prepare()
                player.play()
            }
            eventually { player.isPlaying && app.stationStatus.value.station == station }
            val journal = File(plugin.dataDirectory, "storage.json")
            withTimeout(6_000) {
                while (!withContext(Dispatchers.IO) { journal.takeIf(File::isFile)?.readText()?.contains("stationRequest") == true }) delay(20)
            }
            val position = withContext(Dispatchers.Main) { player.currentPosition }
            var discontinuities = 0
            withContext(Dispatchers.Main) {
                player.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        discontinuities++
                    }
                })
            }
            val result = withContext(Dispatchers.Main) { player.sendCustomCommand(leaveStation, Bundle.EMPTY) }
            assertEquals(SessionResult.RESULT_SUCCESS, withContext(Dispatchers.IO) { result.get(2, TimeUnit.SECONDS).resultCode })
            assertEquals("daily", withTimeout(3_000) { source.search("station", 1).tracks.single().title })
            withContext(Dispatchers.Main) {
                assertNull(app.stationStatus.value.station)
                assertTrue(player.isPlaying)
                assertEquals(0, discontinuities)
                assertEquals(listOf(first.id, second.id), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
                val persisted = checkNotNull(playbackStorage.getString("queue", null)?.let(QueueSnapshot::decode))
                assertNull(persisted.station)
                assertTrue("Playback position reset from $position to ${persisted.positionMs}", persisted.positionMs >= position - 500)
                assertEquals(listOf(first.id, second.id), persisted.tracks.map(Track::id))
            }
        }
    }

    private val playbackStorage get() = app.getSharedPreferences("playback", Context.MODE_PRIVATE)

    private inner class Fixture(val plugin: InstalledPlugin, val first: Track, val second: Track) {
        val connections = mutableListOf<ListenableFuture<MediaController>>()
        var active: MediaController? = null
        val source get() = app.playbackSource(plugin.id) as PluginSource

        suspend fun connect(): MediaController {
            val future = withContext(Dispatchers.Main) {
                MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
            }
            connections.add(future)
            return withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
        }
    }

    private suspend fun withFixture(cacheSecond: Boolean = false, action: suspend Fixture.(MediaController) -> Unit) {
        val id = "played-fixture-${UUID.randomUUID()}"
        val first = Track(id, "first", "First", "Fixture artist")
        val second = first.copy(sourceTrackId = "second", title = "Second")
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        val fixture = Fixture(plugin, first, second)
        val store = app.offline.store
        try {
            app.refreshPlugins()
            withContext(Dispatchers.IO) {
                val audio = wave(90)
                for (track in listOf(first, second)) {
                    val staged = store.staging()
                    val cache = if (cacheSecond && track == second) checkNotNull(store.cacheTicket(track.id)) else null
                    try { staged.writeBytes(audio); store.save(staged, track, "wav", cache) }
                    finally { staged.delete() }
                }
            }
            val player = fixture.connect()
            withContext(Dispatchers.Main) {
                assertEquals("Run on a dedicated installation with an empty queue", 0, player.mediaItemCount)
            }
            fixture.active = player
            fixture.action(player)
        } finally {
            try {
                fixture.active?.let { player ->
                    withContext(Dispatchers.Main) { player.pause(); player.clearMediaItems() }
                    eventually { player.mediaItemCount == 0 && !playbackStorage.contains("queue") }
                    withContext(Dispatchers.IO) { playbackStorage.edit().commit() }
                }
            } finally {
                withContext(Dispatchers.Main) { fixture.connections.forEach(MediaController::releaseFuture) }
                app.plugins.remove(plugin)
                app.refreshPlugins()
                withContext(Dispatchers.IO) {
                    store.removeDownload(first.id)
                    if (cacheSecond) store.promote(second)
                    store.removeDownload(second.id)
                    app.local.catalog.writableDatabase.delete("track_activity", "track_id IN (?, ?)", arrayOf(first.id, second.id))
                    plugin.dataDirectory.deleteRecursively()
                }
            }
        }
    }

    private suspend fun eventually(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(8_000) { while (!condition()) delay(20) }
    }

    private fun fixtureZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id"}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                export function played({id}) {
                    const reports = host.storage.get('plays') ?? [];
                    host.storage.set('plays', [...reports, id]);
                }
                export function lyrics({id}) {
                    return {lrc: id === 'first' ? '[00:02.00]First  line\n[00:04.00]Next line' : ''};
                }
                export function stationNext({id}) {
                    host.storage.set('stationRequest', id);
                    return new Promise(() => {});
                }
                export function search({query}) {
                    if (query === 'station') return {tracks: [{id: 'station', name: host.storage.get('stationRequest')}]};
                    return {tracks: (host.storage.get('plays') ?? []).map((name, index) => ({id: String(index), name}))};
                }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
