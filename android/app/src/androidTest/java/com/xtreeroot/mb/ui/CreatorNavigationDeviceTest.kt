package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.DiscoverySection
import com.xtreeroot.mb.music.DiscoverySource
import com.xtreeroot.mb.music.LibraryPage
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.playback.wave
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.PlaybackMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val source = FixtureSource(app.bilibili.id)

    @Test fun closingCreatorReturnsToSearchWithInputAndResults() = runBlocking {
        withRoot {
            tap("Search")
            val query = "Creator navigation query"
            val field = await("search field") { it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }))
            val searchBounds = Rect().also(field::getBoundsInScreen)
            click("search submit") { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.label("Search") && bounds.top < searchBounds.bottom && bounds.bottom > searchBounds.top
            }
            openFromTrack(source.searchTrack)
            capture("android-creator-uploads.png")
            tap("Back")
            await("restored query") { it.isEditable && it.text?.toString() == query }
            await(source.searchTrack.title) { it.label(source.searchTrack.title) }
            assertEquals(1, source.searchRequests.get())
        }
    }

    @Test fun closingCreatorReturnsToDiscoveryCategory() = runBlocking {
        withRoot {
            tap("More", textOnly = true)
            openFromTrack(source.categoryTrack)
            tap("Back")
            await("Trending Music") { it.label("Trending Music") }
            await(source.categoryTrack.title) { it.label(source.categoryTrack.title) }
            assertEquals(1, source.categoryRequests.get())
            openFromTrack(source.categoryTrack)
            withContext(Dispatchers.Main) { app.settings.update { it.copy(builtInEnabled = false) } }
            await("No available sources") { it.label("No available sources") }
            withContext(Dispatchers.Main) { app.settings.update { it.copy(builtInEnabled = true) } }
            await("More") { it.label("More") }
        }
    }

    @Test fun playerCreatorDismissalKeepsTrackAndSelectionReturnsToPlayer() = runBlocking {
        withRoot { player ->
            listOf(source.searchTrack, source.upload).forEach { track ->
                val staged = app.offline.store.staging()
                staged.writeBytes(wave(30))
                app.offline.store.save(staged, track, "wav")
            }
            withContext(Dispatchers.Main) { player.play(listOf(source.searchTrack), source.searchTrack) }
            tap(source.searchTrack.title)
            await("Close Player") { it.label("Close Player") }
            tap(source.searchTrack.artist)
            await(source.upload.title) { it.label(source.upload.title) }
            capture("android-player-creator-uploads.png")
            tap("Done")
            await("Close Player") { it.label("Close Player") }
            assertEquals(source.searchTrack.id, player.playback.value.current?.id)
            tap(source.searchTrack.artist)
            tap(source.upload.title)
            await("Close Player") { it.label("Close Player") }
            withTimeout(5_000) {
                while (player.playback.value.current?.id != source.upload.id) delay(25)
            }
        }
    }

    private suspend fun openFromTrack(track: Track) {
        val row = await(track.title) { it.label(track.title) && it.actionable(longPress = true) != null }
        assertTrue(row.actionable(longPress = true)?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true)
        tap("View Creator Uploads")
        await(source.upload.title) { it.label(source.upload.title) }
    }

    private suspend fun withRoot(run: suspend (PlaybackModel) -> Unit) {
        val previous = app.settings.values.value
        val store = ViewModelStore()
        val browseStore = ViewModelStore()
        val models = ViewModelProvider(browseStore, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(when (modelClass) {
                BrowseModel::class.java -> BrowseModel(app, source)
                LibraryModel::class.java -> LibraryModel(app, source)
                else -> error("Unexpected fixture model")
            }))
        })
        val player = withContext(Dispatchers.Main) {
            ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[PlaybackModel::class.java]
        }
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withContext(Dispatchers.Main) {
                app.settings.update { it.copy(language = AppLanguage.English, selectedSourceId = source.id,
                    builtInEnabled = true, playbackMode = PlaybackMode.Music) }
                player.clear()
                val browse = models[BrowseModel::class.java]
                val library = models[LibraryModel::class.java]
                activity.setContent { MBRoot(browser = browse, player = player, library = library) }
            }
            run(player)
        } finally {
            withContext(Dispatchers.Main) {
                player.clear()
                activity.finish()
                browseStore.clear()
                store.clear()
                app.settings.update { previous }
            }
            listOf(source.searchTrack, source.upload).forEach { app.offline.store.removeDownload(it.id) }
        }
    }

    private suspend fun tap(label: String, textOnly: Boolean = false) {
        click(label) { if (textOnly) it.text?.toString() == label else it.label(label) }
    }

    private suspend fun click(label: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        await("clickable $label") { node ->
            matches(node) && node.actionable()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }

    private fun AccessibilityNodeInfo.label(label: String) = text?.toString() == label || contentDescription?.toString() == label

    private fun AccessibilityNodeInfo.actionable(longPress: Boolean = false) = generateSequence(this) { it.parent }
        .firstOrNull { it.isEnabled && if (longPress) it.isLongClickable else it.isClickable }

    private suspend fun await(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isVisibleToUser && matches(node)) return node
            return (0 until node.childCount).firstNotNullOfOrNull { node.getChild(it)?.let(::find) }
        }
        val result = withTimeoutOrNull(5_000) {
            var node: AccessibilityNodeInfo?
            do {
                node = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
                if (node == null) delay(40)
            } while (node == null)
            node
        }
        return checkNotNull(result) { "Expected to see $label" }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("creatorScreenshot") != "true") return
        instrumentation.uiAutomation.waitForIdle(350, 4_000)
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }

    private class FixtureSource(override val id: String) : DiscoverySource, LibrarySource {
        val searchTrack = Track(id, "BVFixtureA", "Creator Search Result", "Fixture Artist", durationMs = 30_000, artistId = "123")
        val categoryTrack = searchTrack.copy(sourceTrackId = "BVFixtureB", title = "Creator Category Result")
        val upload = searchTrack.copy(sourceTrackId = "BVFixtureC", title = "Creator New Upload")
        val searchRequests = AtomicInteger()
        val categoryRequests = AtomicInteger()

        override suspend fun search(query: String, page: Int): TrackPage {
            searchRequests.incrementAndGet()
            return TrackPage(listOf(searchTrack), false)
        }

        override suspend fun home() = listOf(DiscoverySection(DiscoveryCategory.Trending))

        override suspend fun discovery(category: DiscoveryCategory): DiscoverySection {
            categoryRequests.incrementAndGet()
            return DiscoverySection(category, listOf(categoryTrack))
        }

        override suspend fun library(route: LibraryRoute, cursor: String?): LibraryPage {
            assertEquals(LibraryRoute.Creator("123"), route)
            return LibraryPage(title = "Fixture Artist Uploads", tracks = listOf(upload))
        }
    }
}
