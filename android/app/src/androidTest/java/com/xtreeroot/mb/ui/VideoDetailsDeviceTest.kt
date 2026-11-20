package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.playback.wave
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun videoTabsShareTheLoadedCommentPageAndResetOnTrackChange() = runBlocking {
        val id = "video-comments-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { fixtureZip(id).inputStream() })
        var activity: MainActivity? = null
        try {
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            val tracks = listOf(Track(id, "first", "First Video", "Fixture Artist"), Track(id, "second", "Second Video", "Fixture Artist"))
            val selection = mutableStateOf(0)
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            withContext(Dispatchers.Main) {
                val player = ViewModelProvider(screen)[PlaybackModel::class.java]
                val preferences = app.settings.values.value
                screen.setContent {
                    MaterialTheme(colorScheme = musicDark) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            VideoDetailsScreen(player, PlaybackState(tracks = tracks, index = selection.value), preferences,
                                accountId = null, fullScreen = false, setFullScreen = {}, browse = {}, openMusic = {})
                        }
                    }
                }
            }
            await("First Video")
            tap("Comments · 123")
            await("First page request 1")
            tap("More comments")
            await("Second page of first video")
            tap("Info")
            tap("Comments · 123")
            await("First page request 1")
            await("Second page of first video")
            withContext(Dispatchers.Main) { selection.value = 1 }
            await("Second Video")
            tap("Comments · 17")
            await("Only the second video")
            assertFalse(matches("Second page of first video").isNotEmpty())
        } finally {
            withContext(Dispatchers.Main) { activity?.finish() }
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
        }
    }

    @Test fun selectingFromVideoInfoChangesTheCurrentItemWithoutReplacingTheQueue() = runBlocking {
        val id = "video-queue-${UUID.randomUUID()}"
        val tracks = listOf(Track(id, "first", "First Queue Video", "Fixture Artist", durationMs = 30_000),
            Track(id, "second", "Second Queue Video", "Fixture Artist", durationMs = 30_000))
        var activity: MainActivity? = null
        var model: PlaybackModel? = null
        try {
            tracks.forEach { track ->
                val staged = app.offline.store.staging()
                try { staged.writeBytes(wave(30)); app.offline.store.save(staged, track, "wav") }
                finally { staged.delete() }
            }
            val screen = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            val player = withContext(Dispatchers.Main) {
                ViewModelProvider(screen)[PlaybackModel::class.java].also { player ->
                    model = player
                    val preferences = app.settings.values.value
                    screen.setContent {
                        val playback by player.playback.collectAsStateWithLifecycle()
                        MaterialTheme(colorScheme = musicDark) {
                            CompositionLocalProvider(LocalMBEnglish provides true) {
                                Surface {
                                    VideoDetailsScreen(player, playback, preferences, accountId = null, fullScreen = false,
                                        setFullScreen = {}, browse = {}, openMusic = {})
                                }
                            }
                        }
                    }
                    player.play(tracks, tracks.first())
                }
            }
            await("Queue · 2")
            if (InstrumentationRegistry.getArguments().getString("videoQueueScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-video-info-queue.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            await("Queue selection: ${tracks.first().title}", ::isCurrentQueueRow)
            tap("Queue selection: ${tracks.last().title}")
            withTimeout(5_000) {
                while (player.playback.value.let { it.current?.id != tracks.last().id || !it.playing }) delay(25)
            }
            assertEquals(tracks.map { it.id }, player.playback.value.tracks.map { it.id })
            if (InstrumentationRegistry.getArguments().getString("videoQueueScreenshot") == "true") {
                val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(app.cacheDir, "android-video-info-queue-selected.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { frame.recycle() }
            }
            tap("Queue")
            await("Queue selection: ${tracks.last().title}", ::isCurrentQueueRow)
        } finally {
            withContext(Dispatchers.Main) { model?.clear(); activity?.finish() }
            tracks.forEach { app.offline.store.removeDownload(it.id) }
        }
    }

    private fun matches(text: String): List<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node).filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) } +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
    }

    private suspend fun await(text: String, condition: (AccessibilityNodeInfo) -> Boolean = { true }) {
        val found = withTimeoutOrNull(5_000) {
            while (matches(text).none(condition)) delay(25)
            true
        }
        fun describe(node: AccessibilityNodeInfo): List<String> =
            listOf("${node.text}/${node.contentDescription} checked=${node.isChecked} clickable=${node.isClickable}") +
                (0 until node.childCount).flatMap { node.getChild(it)?.let(::describe).orEmpty() }
        assertTrue("Missing video detail: $text. ${if (found != true) instrumentation.uiAutomation.rootInActiveWindow?.let(::describe) else ""}", found == true)
    }

    private fun isCurrentQueueRow(node: AccessibilityNodeInfo): Boolean =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }?.isChecked == true

    private suspend fun tap(text: String) = await(text) { node ->
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun fixtureZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id","name":"Video Comment Fixture"}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                let firstRequests = 0;
                export function comments({ trackId, offset }) {
                    const author = 'Fixture Listener';
                    if (trackId !== 'first') return {total: 17, more: false, comments: [{id: 'second', author, text: 'Only the second video'}]};
                    if (offset > 0) return {total: 123, more: false, comments: [{id: 'later', author, text: 'Second page of first video'}]};
                    return {total: 123, more: true, comments: [{id: 'early', author, text: 'First page request ' + ++firstRequests}]};
                }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
