package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.PlaybackMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedSearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication
    private val ids = listOf("search-fixture-a", "search-fixture-b")

    private suspend fun fixture(test: suspend (MainActivity) -> Unit) {
        val preferences = app.settings.values.value
        var activity: MainActivity? = null
        try {
            ids.forEachIndexed { index, id ->
                val name = if (index == 0) "First" else "Second"
                val candidate = app.plugins.prepareArchive { file -> ZipOutputStream(file.outputStream()).use { zip ->
                    val manifest = JSONObject().put("id", id).put("name", name).put("version", "1.0.0").put("main", "index.js")
                        .put("navigation", org.json.JSONArray("""[{"title":"Daily","page":"daily"}]"""))
                    val code = """
                        const song = {id:"song",name:"$name song",artists:[{id:"artist",name:"Artist"}],duration:180000};
                        export function home(){return {sections:[{title:"Daily",tracks:[song],morePage:"daily"}]};}
                        export function browse(){return {sections:[{title:"Daily",tracks:[song]}]};}
                        export function search(){return {tracks:[{...song,name:"$name music"}],more:false};}
                        export function searchVideos(){return {tracks:[{...song,id:"video",name:"$name video"}],more:false};}
                    """.trimIndent()
                    for ((entry, content) in listOf("plugin.json" to manifest.toString(), "index.js" to code)) {
                        zip.putNextEntry(ZipEntry(entry)); zip.write(content.toByteArray()); zip.closeEntry()
                    }
                } }
                app.plugins.install(candidate)
            }
            val installed = app.plugins.installed().filter { it.id in ids }
            installed.forEach { app.plugins.rename(it, "") }
            app.plugins.move(ids.first(), -1)
            app.settings.update { it.copy(builtInEnabled = false, selectedSourceId = ids.first(), language = AppLanguage.English, playbackMode = PlaybackMode.Music) }
            app.refreshPlugins()
            activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            await("First")
            test(activity)
        } finally {
            activity?.let { withContext(Dispatchers.Main) { it.finish() } }
            app.resourceBookmarks.tracks.value.filter { it.sourceId in ids }.forEach { app.resourceBookmarks.remove(it.id) }
            app.plugins.installed().filter { it.id in ids }.forEach { app.plugins.remove(it) }
            app.settings.update { preferences }; app.refreshPlugins()
        }
    }

    @Test fun searchDropdownStaysBelowInputAndSavesCrossPlatformResults() = runBlocking {
        fixture {
            tap("Search"); await("First ▾")
            tap("First ▾"); await("All Platforms")
            val input = Rect(); nodes("Search songs or videos").first().getBoundsInScreen(input)
            val choice = Rect(); nodes("All Platforms").first().getBoundsInScreen(choice)
            assertTrue("Options must not cover the search input", choice.top >= input.bottom)
            screenshot("android-search-platforms.png")
            tap("All Platforms"); await("All Platforms ▾")
            setText(nodes("Search songs or videos").first(), "music")
            tap("Search")
            await("First music"); await("Second music"); await("First video")
            screenshot("android-search-results.png")
            tap("Actions for First music"); tap("Save to Library")
            withTimeout(5_000) { while (app.resourceBookmarks.tracks.value.none { it.title == "First music" }) delay(25) }
            tap("Library"); await("Saved Resources"); await("First music")
            assertEquals("music", app.resourceBookmarks.tracks.value.first { it.title == "First music" }.resourceType)
        }
    }

    @Test fun renameOrderSwipeAndModeTogglePropagate() = runBlocking {
        fixture {
            tap("More Actions"); tap("Settings"); tap("Plugins"); await("Rename")
            tap("Rename"); await("Local Name")
            val field = allNodes().first { it.className?.toString() == "android.widget.EditText" }
            setText(field, "Local First"); tap("Save"); await("Local First")
            tap("Sort"); await("Up")
            val up = nodes("Up").first { node -> generateSequence(node) { it.parent }.any { it.isClickable && it.isEnabled } }
            click(up)
            withTimeout(5_000) { while (app.plugins.installed().first().id != ids.last()) delay(25) }
            tap("Done"); tap("Done"); await("Local First")
            swipeRight()
            withTimeout(5_000) { while (app.settings.values.value.selectedSourceId != ids.last()) delay(25) }
            tap("Music Mode")
            withTimeout(5_000) { while (app.settings.values.value.playbackMode != PlaybackMode.Video) delay(25) }
            await("Video Mode"); screenshot("android-discovery-platforms.png")
            tap("Search"); await("Second ▾")
            tap("Second ▾"); await("Local First")
        }
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        fun visit(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::visit).orEmpty() }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit).orEmpty()
    }
    private fun nodes(text: String) = allNodes().filter { it.isVisibleToUser && (it.text?.toString() == text || it.contentDescription?.toString() == text) }
    private suspend fun await(text: String) = withTimeout(8_000) { while (nodes(text).isEmpty()) delay(25) }
    private fun click(node: AccessibilityNodeInfo) = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    private suspend fun tap(text: String) = withTimeout(5_000) { while (nodes(text).none(::click)) delay(25) }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
    private fun swipeRight() {
        val metrics = app.resources.displayMetrics
        val down = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, down + step * 20, action, metrics.widthPixels * (0.15f + step / 20f * 0.7f), metrics.heightPixels * 0.17f, 0)
            instrumentation.sendPointerSync(event); event.recycle()
        }
    }
    private fun screenshot(name: String) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { image.recycle() }
    }
}
