package com.xtreeroot.mb.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.local.LocalTrack
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackService
import com.xtreeroot.mb.playback.wave
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalLibrarySearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun libraryHomeCreatesOpensRenamesAndDeletesAPlaylistWithoutRemovingAudio() = runBlocking {
        val initial = "Rain Room Fixture"
        val renamed = "Rain Room Renamed"
        val track = addTrack("Rain Room Intro", "Fixture Artist", "", "", artwork = albumArt())
        var activity: MainActivity? = null
        var playlistId: String? = null
        try {
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            tap("添加到资料库", "Add to Library")
            node("导入音频", "Import Audio")
            tap("新建歌单", "New Playlist")
            node("保存", "Save")
            setQuery(initial)
            expectQuery(initial)
            tap("保存", "Save")
            withTimeout(5_000) {
                while (app.playlists.playlists.value.none { it.name == initial }) delay(50)
            }
            val id = app.playlists.playlists.value.single { it.name == initial }.id
            playlistId = id
            tap(initial)
            node("歌单暂无歌曲", "This Playlist Is Empty")
            tap("添加歌曲", "Add Songs")
            tap(track.title)
            tap("添加", "Add")
            withTimeout(5_000) {
                while (app.playlists.playlists.value.single { it.id == id }.trackIds != listOf(track.id)) delay(50)
            }
            tap("返回资料库", "Back to Library")
            node("我的歌单", "My Playlists")
            tap("操作歌单 $initial", "Playlist Actions $initial")
            tap("重命名歌单", "Rename Playlist")
            node("保存", "Save")
            setQuery(renamed)
            expectQuery(renamed)
            tap("保存", "Save")
            node(renamed)
            capture("libraryHomeScreenshot", "android-library-home-playlists.png")
            tap("操作歌单 $renamed", "Playlist Actions $renamed")
            tap("删除歌单", "Delete Playlist")
            tap("取消", "Cancel")
            assertEquals(listOf(track.id), app.playlists.playlists.value.single { it.id == id }.trackIds)
            tap("操作歌单 $renamed", "Playlist Actions $renamed")
            tap("删除歌单", "Delete Playlist")
            tap("删除", "Delete")
            withTimeout(5_000) {
                while (app.playlists.playlists.value.any { it.id == id }) delay(50)
            }
            assertTrue(app.local.catalog.file(track.sourceTrackId).isFile)
        } catch (failure: Throwable) {
            capture("libraryHomeScreenshot", "android-library-home-failed.png")
            throw failure
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            app.playlists.playlists.value.filter { it.id == playlistId || it.name == initial || it.name == renamed }
                .forEach { app.playlists.delete(it.id) }
            app.local.delete(track.sourceTrackId)
        }
    }

    @Test fun recentCoverPlaysTheSelectedDownloadInAMixedQueue() = runBlocking {
        val tracks = mutableListOf<Track>()
        val downloaded = Track("bilibili", "recent-download-${UUID.randomUUID()}", "Evening Download", "Fixture Artist", durationMs = 12_000)
        val store = app.offline.store
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        var activity: MainActivity? = null
        try {
            val oldest = addTrack("Sunrise Import", "Fixture Artist", "", "", addedAt = 100).also(tracks::add)
            store.restoreDownload(store.staging().apply { writeBytes(wave(12)) }, downloaded,
                "${UUID.randomUUID()}.wav", addedAt = 200)
            val newest = addTrack("Midnight Import", "Fixture Artist", "", "", addedAt = 300).also(tracks::add)
            val player = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            tap("播放 ${downloaded.title}", "Play ${downloaded.title}")
            withTimeout(8_000) {
                while (!withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId == downloaded.id && player.isPlaying && player.currentPosition > 0
                }) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertEquals(listOf(newest.id, downloaded.id, oldest.id),
                    (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            capture("recentAdditionsScreenshot", "android-library-recent-additions.png")
        } finally {
            withContext(Dispatchers.Main) {
                if (future.isDone) runCatching { future.get() }.getOrNull()?.let { player ->
                    if ((0 until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId == downloaded.id }) {
                        player.pause()
                        player.clearMediaItems()
                    }
                }
                MediaController.releaseFuture(future)
                activity?.finish()
            }
            store.removeDownload(downloaded.id)
            tracks.forEach { app.local.delete(it.sourceTrackId) }
        }
    }

    @Test fun oneQueryFindsFourResultTypesAndKeepsResultsWhenReturningFromDetails() = runBlocking {
        val tracks = mutableListOf<Track>()
        var playlist: String? = null
        var activity: MainActivity? = null
        try {
            tracks.add(addTrack("Harbor Overture", "Coastal Artist", "Harbor Sessions", "Harbor Quartet"))
            tracks.add(addTrack("Summit Chorus", "Mountain Artist", "Summer Peaks", ""))
            playlist = app.playlists.create("Harbor Mix", listOf(tracks.first().id))
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            node("Harbor Overture")
            tap("歌手", "Artists")
            setQuery("Harbor")
            node("Harbor Overture")
            node("Harbor Sessions")
            node("Harbor Quartet")
            node("Harbor Mix")
            assertNull(find { it.text?.toString() == "Summit Chorus" })
            capture("librarySearchScreenshot", "android-library-global-search.png")

            tap("Harbor Sessions")
            node("在此页面搜索", "Search This Page")
            node("Harbor Overture")
            setQuery("No matching album song")
            node("没有匹配的歌曲", "No Matching Songs")
            tap("返回资料库", "Back to Library")
            expectQuery("Harbor")

            tap("Harbor Quartet")
            node("在此页面搜索", "Search This Page")
            node("Harbor Overture")
            tap("返回资料库", "Back to Library")
            expectQuery("Harbor")

            tap("Harbor Mix")
            node("添加歌曲", "Add Songs")
            node("Harbor Overture")
            tap("返回资料库", "Back to Library")
            expectQuery("Harbor")
            node("Harbor Mix")

            setQuery("No matching fixture")
            node("没有匹配的本地内容", "No Matching Library Items")
            tap("清空搜索", "Clear Search")
            node("Mountain Artist")
            assertNull(find { it.text?.toString() == "Harbor Mix" })
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            playlist?.let(app.playlists::delete)
            tracks.forEach { app.local.delete(it.sourceTrackId) }
        }
    }

    @Test fun albumPlaybackUsesFilteredTracksWithoutChangingTheAlbumCount() = runBlocking {
        val tracks = mutableListOf<Track>()
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        var activity: MainActivity? = null
        try {
            val artist = "Lighthouse Trio"
            val album = "Tide Lines"
            val first = addTrack("Harbor Prelude", artist, album, artist, number = 1, seconds = 12, artwork = albumArt()).also(tracks::add)
            val second = addTrack("Harbor Finale", artist, album, artist, number = 2, seconds = 12).also(tracks::add)
            tracks.add(addTrack("Salt Outro", artist, album, artist, number = 3, seconds = 12))
            val player = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            tap("专辑", "Albums")
            tap(album)
            node("3 首", "3 songs")
            node("Salt Outro")
            capture("albumDetailScreenshot", "android-library-album-detail.png")
            setQuery("Harbor")
            withTimeout(5_000) { while (find { it.text?.toString() == "Salt Outro" } != null) delay(50) }
            node("3 首", "3 songs")
            tap("播放全部", "Play All")
            withTimeout(8_000) {
                while (!withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId == first.id && player.isPlaying && player.currentPosition > 0
                }) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertEquals(listOf(first.id, second.id), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            tap("随机播放", "Shuffle Play")
            withTimeout(5_000) {
                while (!withContext(Dispatchers.Main) { player.shuffleModeEnabled }) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertEquals(listOf(first.id, second.id), (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
            }
            setQuery("No matching song")
            node("没有匹配的歌曲", "No Matching Songs")
            for (control in listOf(arrayOf("播放全部", "Play All"), arrayOf("随机播放", "Shuffle Play"))) {
                assertFalse(generateSequence(node(*control)) { it.parent }.any { it.isClickable && it.isEnabled })
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (future.isDone) runCatching { future.get() }.getOrNull()?.let { player ->
                    if (tracks.any { it.id == player.currentMediaItem?.mediaId }) {
                        player.pause()
                        player.clearMediaItems()
                        player.shuffleModeEnabled = false
                    }
                }
                MediaController.releaseFuture(future)
                activity?.finish()
            }
            tracks.forEach { app.local.delete(it.sourceTrackId) }
        }
    }

    @Test fun playingAndPausedSongStaysMarkedAndAnotherSongsLongPressDoesNotReplaceIt() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val album = "Night Signals"
        val artist = "Neon Studio"
        val first = addTrack("Midnight Transmission", artist, album, artist, number = 1, seconds = 45, artwork = albumArt())
        val second = addTrack("The Last Train Across the City", artist, album, artist, number = 2, seconds = 45, artwork = albumArt())
        val future = withContext(Dispatchers.Main) {
            MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        }
        var activity: MainActivity? = null
        try {
            val player = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            tap("专辑", "Albums")
            tap(album)
            tap(first.title)
            suspend fun expectMarker(vararg states: String) {
                await("${first.title}: ${states.joinToString()}") { node ->
                    node.matchesLabel(arrayOf(first.title)) && generateSequence(node) { it.parent }.any {
                        it.stateDescription?.toString() in states
                    }
                }
            }
            expectMarker("正在播放", "Now Playing")
            capture("trackRowScreenshot", "android-track-row-playing.png")
            tap("暂停", "Pause")
            expectMarker("已暂停", "Paused")
            val position = 12_000L
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { player.playWhenReady || !player.isCurrentMediaItemSeekable }) delay(50)
            }
            withContext(Dispatchers.Main) { player.seekTo(position) }
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { player.currentPosition != position }) delay(50)
            }
            val row = await("Long press ${second.title}") { node ->
                node.matchesLabel(arrayOf(second.title)) && generateSequence(node) { it.parent }.any { it.isLongClickable }
            }
            val longPress = generateSequence(row) { it.parent }.first { it.isLongClickable }
            assertNull(longPress.stateDescription)
            assertTrue(longPress.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
            node("下一首播放", "Play Next")
            node(second.title)
            capture("trackRowScreenshot", "android-track-action-sheet.png")
            tap("下一首播放", "Play Next")
            withTimeout(5_000) {
                while (withContext(Dispatchers.Main) { player.mediaItemCount } != 3) delay(50)
            }
            withContext(Dispatchers.Main) {
                assertEquals(first.id, player.currentMediaItem?.mediaId)
                assertEquals(second.id, player.getMediaItemAt(1).mediaId)
                assertEquals(position, player.currentPosition)
                assertFalse(player.playWhenReady)
            }
            expectMarker("已暂停", "Paused")
        } finally {
            withContext(Dispatchers.Main) {
                if (future.isDone) runCatching { future.get() }.getOrNull()?.let { player ->
                    if (player.currentMediaItem?.mediaId == first.id) {
                        player.pause()
                        player.clearMediaItems()
                    }
                }
                MediaController.releaseFuture(future)
                activity?.finish()
            }
            listOf(first, second).forEach { app.local.delete(it.sourceTrackId) }
        }
    }

    @Test fun songActionsLikeAnOfflineSongAndRemoveItFromLikedSongs() = runBlocking {
        val album = "Afterglow"
        val track = addTrack("Afterglow Favourite", "Fixture Artist", album, "Fixture Artist", artwork = albumArt())
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            tap("资料库", "Library")
            tap("专辑", "Albums")
            tap(album)
            longPress(track.title)
            tap("本地喜欢", "Like Locally")
            withTimeout(5_000) {
                while (app.local.catalog.activity().none { it.trackId == track.id && it.likedAt > 0 }) delay(50)
            }
            tap("返回资料库", "Back to Library")
            if (find { it.matchesLabel(arrayOf("喜欢", "Liked Songs")) } == null) {
                val categories = generateSequence(node("歌手", "Artists")) { it.parent }.first { candidate ->
                    candidate.actionList.any { it == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT }
                }
                assertTrue(categories.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id))
            }
            tap("喜欢", "Liked Songs")
            node(track.title)
            capture("trackFavoriteScreenshot", "android-liked-songs-from-track-menu.png")
            longPress(track.title)
            tap("取消本地喜欢", "Unlike Locally")
            node("暂无本地曲目", "No Local Tracks")
            assertEquals(0L, app.local.catalog.activity().single { it.trackId == track.id }.likedAt)
            assertTrue(app.local.catalog.file(track.sourceTrackId).isFile)
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            app.local.delete(track.sourceTrackId)
        }
    }

    private suspend fun longPress(title: String) {
        fun clickable(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isLongClickable && it.isEnabled }
        val row = await("Long press $title") { it.matchesLabel(arrayOf(title)) && clickable(it) != null }
        assertEquals("Expected to long press $title", true, clickable(row)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }

    private fun albumArt(): ByteArray {
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(22, 66, 89))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 190, 115) }
            canvas.drawCircle(132f, 63f, 31f, paint)
            paint.color = Color.rgb(66, 153, 155)
            canvas.rotate(-18f, 96f, 145f)
            canvas.drawRect(-40f, 126f, 250f, 250f, paint)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun addTrack(title: String, artist: String, album: String, albumArtist: String, addedAt: Long = 0,
                         number: Int = 1, seconds: Int = 2, artwork: ByteArray? = null): Track {
        val audio = wave(seconds).also { UUID.randomUUID().toString().encodeToByteArray().copyInto(it, 128) }
        val id = MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
        val track = Track(localSourceId, id, title, artist, durationMs = seconds * 1_000L)
        val file = app.local.catalog.file(id)
        file.parentFile?.mkdirs()
        file.writeBytes(audio)
        try { app.local.catalog.write(LocalTrack(track, album, albumArtist, "", 1, number, addedAt = addedAt), artwork) }
        catch (failure: Exception) { file.delete(); throw failure }
        return track
    }

    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::visit) }
        }
        return automation.rootInActiveWindow?.let(::visit)
    }

    private suspend fun await(description: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
        withTimeoutOrNull(5_000) {
            var item = find(matches)
            while (item == null) { delay(50); item = find(matches) }
            item
        } ?: error("Missing UI element: $description")

    private fun AccessibilityNodeInfo.matchesLabel(labels: Array<out String>) =
        labels.any { label -> text?.toString() == label || contentDescription?.toString() == label }

    private suspend fun node(vararg labels: String) = await(labels.joinToString()) { it.matchesLabel(labels) }

    private suspend fun tap(vararg labels: String) {
        fun clickable(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        val node = await("Enabled ${labels.joinToString()}") { it.matchesLabel(labels) && clickable(it) != null }
        assertEquals("Expected to tap ${labels.joinToString()}", true, clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private suspend fun searchField() = await("Library search field") { it.isEditable }

    private suspend fun expectQuery(query: String) {
        await("Library search field containing $query") { it.isEditable && it.text?.toString() == query }
    }

    private suspend fun setQuery(query: String) {
        assertTrue(searchField().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }))
    }

    private fun capture(argument: String, name: String) {
        if (InstrumentationRegistry.getArguments().getString(argument) != "true") return
        automation.waitForIdle(250, 5_000)
        val frame = checkNotNull(automation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }
}
