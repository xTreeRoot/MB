package com.xtreeroot.mb.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import org.junit.Test

class LocalLibrarySearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val app = instrumentation.targetContext.applicationContext as MBApplication

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

    private suspend fun node(vararg labels: String) = await(labels.joinToString()) {
        labels.any { label -> it.text?.toString() == label || it.contentDescription?.toString() == label }
    }

    private suspend fun tap(vararg labels: String) {
        val button = generateSequence(node(*labels)) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertEquals("Expected to tap ${labels.joinToString()}", true, button?.performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
