package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCollection
import com.xtreeroot.mb.plugin.PluginSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountPageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as MBApplication

    @Test fun builtInAccountActionsOpenTheRightPageAndRequireSignOutConfirmation() = runBlocking {
        val listener = SourceAccount("123456", "Fixture Listener", null)
        val signOut = CompletableDeferred<Unit>()
        val opened = Channel<LibraryRoute>(Channel.UNLIMITED)
        val source = object : AccountSource {
            override val id = "account-page-fixture"
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun account(): SourceAccount? = null
            override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit) = listener
            override suspend fun signOut() { signOut.complete(Unit) }
        }
        withPage(source, dark = false, content = { model -> AccountView(model) { opened.trySend(it) } }) {
            awaitNode("QR Sign In")
            assertNull(node("My Uploads"))
            capture("android-account-guest-light")
            tap("QR Sign In")
            awaitNode(listener.name)
            awaitNode("Watch History")
            capture("android-account-signed-in-light")
            tap("My Uploads")
            assertEquals(LibraryRoute.Creator(listener.id), withTimeout(5_000) { opened.receive() })
            tap("Watch History")
            assertEquals(LibraryRoute.History, withTimeout(5_000) { opened.receive() })
            tap("Sign Out")
            tap("Cancel")
            awaitNode(listener.name)
            assertFalse(signOut.isCompleted)
            tap("Sign Out")
            awaitNode("Sign out?")
            tap("Sign Out")
            withTimeout(5_000) { signOut.await() }
            awaitNode("QR Sign In")
            assertNull(node("My Uploads"))
        }
    }

    @Test fun pluginAccountDestinationsKeepTheirProfileAndCollectionRequests() = runBlocking {
        val id = "account-page-${UUID.randomUUID()}"
        val plugin = app.plugins.install(app.plugins.prepareZip { pluginZip(id).inputStream() })
        try {
            app.plugins.setEnabled(plugin, true)
            app.refreshPlugins()
            val source = app.playbackSource(id) as PluginSource
            val navigator = PluginCatalogNavigator()
            withPage(source, dark = true, content = { model ->
                val state by model.account.collectAsStateWithLifecycle()
                PluginPersonalContent(source, navigator, model, state)
            }) {
                awaitNode("Fixture Listener")
                awaitNode("Source Playlists")
                capture("android-account-plugin-dark")
                tap("My Profile")
                assertRequest(PluginCatalogRequest.Browse("personal", "My Profile"), navigator)
                tap("Source Playlists")
                assertRequest(PluginCatalogRequest.Collection(PluginCollection.Playlists, "Source Playlists"), navigator)
            }
        } finally {
            app.plugins.remove(plugin)
            app.refreshPlugins()
            plugin.dataDirectory.deleteRecursively()
        }
    }

    private suspend fun assertRequest(expected: PluginCatalogRequest, navigator: PluginCatalogNavigator) {
        val actual = withTimeoutOrNull(5_000) {
            var request: PluginCatalogRequest?
            do {
                delay(25)
                request = withContext(Dispatchers.Main) { (navigator.current as? CatalogDestination.Content)?.request }
            } while (request == null)
            withContext(Dispatchers.Main) { navigator.back() }
            request
        }
        assertEquals(expected, actual)
    }

    private suspend fun withPage(source: AccountSource, dark: Boolean, content: @Composable (AccountModel) -> Unit,
                                 run: suspend () -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val store = ViewModelStore()
        try {
            withContext(Dispatchers.Main) {
                val model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AccountModel::class.java]
                model.activate(source)
                activity.setContent {
                    MaterialTheme(colorScheme = if (dark) musicDark else musicLight) {
                        CompositionLocalProvider(LocalMBEnglish provides true) {
                            Surface(Modifier.fillMaxSize().safeDrawingPadding()) { content(model) }
                        }
                    }
                }
            }
            run()
        } finally {
            withContext(Dispatchers.Main) { store.clear(); activity.finish() }
        }
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
            return (0 until node.childCount).firstNotNullOfOrNull { index -> node.getChild(index)?.let(::find) }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private suspend fun awaitNode(text: String) {
        val visible = withTimeoutOrNull(5_000) {
            while (node(text) == null) delay(25)
            true
        }
        assertEquals("Expected to see $text", true, visible)
    }

    private suspend fun tap(text: String) {
        awaitNode(text)
        val button = generateSequence(node(text)) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        assertEquals("Expected to tap $text", true, button?.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("accountScreenshots") != "true") return
        instrumentation.waitForIdleSync()
        val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(app.cacheDir, "$name.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { frame.recycle() }
    }

    private fun pluginZip(id: String) = ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.json"))
            zip.write("""{"id":"$id","name":"Fixture Music"}""".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("index.js"))
            zip.write("""
                export function account() { return {id:'listener', name:'Fixture Listener', page:'personal'}; }
                export function playlists() { return [{id:'pl',name:'List',mine:true}]; }
                export function liked() { return []; }
            """.trimIndent().encodeToByteArray())
            zip.closeEntry()
        }
    }.toByteArray()
}
