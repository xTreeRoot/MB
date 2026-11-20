package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.local.LocalTrack
import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.wave
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibrarySearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation
    private val app = instrumentation.targetContext.applicationContext as MBApplication

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
            capture()

            tap("Harbor Sessions")
            node("在此页面搜索", "Search This Page")
            node("Harbor Overture")
            setQuery("No matching album song")
            node("暂无本地曲目", "No Local Tracks")
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

    private fun addTrack(title: String, artist: String, album: String, albumArtist: String): Track {
        val audio = wave(2).also { UUID.randomUUID().toString().encodeToByteArray().copyInto(it, 128) }
        val id = MessageDigest.getInstance("SHA-256").digest(audio).joinToString("") { "%02x".format(it) }
        val track = Track(localSourceId, id, title, artist, durationMs = 2_000)
        val file = app.local.catalog.file(id)
        file.parentFile?.mkdirs()
        file.writeBytes(audio)
        try { app.local.catalog.write(LocalTrack(track, album, albumArtist, "", 1, 1), null) }
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

    private fun capture() {
        if (InstrumentationRegistry.getArguments().getString("librarySearchScreenshot") != "true") return
        automation.waitForIdle(250, 5_000)
        val frame = checkNotNull(automation.takeScreenshot())
        try { File(app.cacheDir, "android-library-global-search.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }
}
