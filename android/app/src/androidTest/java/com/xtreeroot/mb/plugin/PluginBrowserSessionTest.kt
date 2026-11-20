package com.xtreeroot.mb.plugin

import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.webkit.CookieManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xtreeroot.mb.MainActivity
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PluginBrowserSessionTest {
    @Test fun compatibilityBrowserStartsInADifferentProcessAndDiscardsPreviousAttemptData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        assumeTrue(WebViewCompat.getCurrentWebViewPackage(context) != null)
        val automation = instrumentation.uiAutomation
        fun profiles() = File(context.applicationInfo.dataDir).listFiles { file ->
            file.isDirectory && file.name.startsWith(compatDataDirectoryPrefix)
        }.orEmpty()
        val target = URI("https://example.invalid/")
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(10_000) {
            while (!condition()) delay(50)
        }
        fun shell(command: String): String = automation.executeShellCommand(command)
            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { it.readText().trim() } }
        fun processId(): String = shell("pidof ${context.packageName}:plugin_browser")
        suspend fun open(completeWithCookie: Boolean = false): File {
            val previous = profiles().toSet()
            val title = "Compatibility fixture"
            val id = UUID.randomUUID()
            val html = if (completeWithCookie) {
                """<script>setTimeout(() => { document.cookie = 'session=fixture; Secure; Path=/'; }, 1500);</script>"""
            } else "<p>Waiting for cancel</p>"
            val request = PluginWebSignIn(title, target, target, setOf("session"), null, id, html = html)
            val page = PluginBrowserPage(title, target, id, request)
            context.startActivity(CompatPluginBrowserActivity.intent(context, page, english = true, dark = false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            var profile: File? = null
            waitFor {
                profile = profiles().singleOrNull()?.takeUnless { it in previous }
                profile != null && processId().isNotEmpty() && shell("dumpsys activity activities")
                    .lineSequence().any { "topResumedActivity=" in it && ".plugin.CompatPluginBrowserActivity" in it }
            }
            assertTrue(processId() != Process.myPid().toString())
            return checkNotNull(profile)
        }
        suspend fun close() {
            shell("input keyevent KEYCODE_BACK")
            waitFor { processId().isEmpty() }
        }
        try {
            val first = open()
            File(first, "fixture").writeText("Disposable test data")
            close()
            val second = open(completeWithCookie = true)
            assertTrue(first != second)
            assertTrue(!first.exists())
            waitFor { processId().isEmpty() }
        } finally {
            if (processId().isNotEmpty()) shell("input keyevent KEYCODE_BACK")
        }
    }

    @Test fun compatibilityBrowserReturnsNavigationFailureToItsCaller() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        assumeTrue(WebViewCompat.getCurrentWebViewPackage(context) != null)
        val target = unavailableAddress()
        val request = PluginWebSignIn("Fixture", target, target, setOf("session"), null)
        val page = PluginBrowserPage(request.title, target, request.id, request)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            withTimeout(10_000) {
                withContext(Dispatchers.Main) {
                    val result = CompletableDeferred<ActivityResult>()
                    val launcher = activity.activityResultRegistry.register("browser-failure-fixture",
                        ActivityResultContracts.StartActivityForResult()) { result.complete(it) }
                    try {
                        launcher.launch(CompatPluginBrowserActivity.intent(context, page, english = true, dark = false))
                        val failure = result.await()
                        assertEquals(CompatPluginBrowserActivity.failed, failure.resultCode)
                        assertEquals(PluginBrowserIssue.Navigation.name,
                            failure.data?.getStringExtra(CompatPluginBrowserActivity.issueKey))
                    } finally { launcher.unregister() }
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun profileBrowserReportsFailedMainPageButIgnoresFailedImages() = runBlocking {
        val target = unavailableAddress()
        withTimeout(10_000) {
            withContext(Dispatchers.Main) {
                assumeTrue(PluginBrowserSession.available())
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val issues = Channel<PluginBrowserIssue>(Channel.UNLIMITED)
                val inline = PluginWebSignIn("Fixture", target, target, setOf("session"), null, html = """
                    <img src="/missing-image" onerror="window.webkit.messageHandlers.mbSignIn.postMessage({error:'image unavailable'})">
                """.trimIndent())
                val messages = Channel<String>(Channel.UNLIMITED)
                val browser = PluginBrowserSession.create(context, onFailure = { issues.trySend(it) })
                try {
                    browser.open(PluginBrowserPage(inline.title, target, inline.id, inline)) { messages.trySend(it) }
                    assertEquals("image unavailable", messages.receive())
                    assertTrue(issues.tryReceive().isFailure)
                    val remote = PluginWebSignIn("Fixture", target, target, setOf("session"), null)
                    browser.open(PluginBrowserPage(remote.title, target, remote.id, remote)) { messages.trySend(it) }
                    assertEquals(PluginBrowserIssue.Navigation, issues.receive())
                } finally { browser.clear() }
            }
        }
    }

    @Test fun browserAttemptsDoNotShareCookiesAndClosingClearsAttemptData() = runBlocking {
        withTimeout(10_000) {
            withContext(Dispatchers.Main) {
                assumeTrue("System WebView lacks isolated data deletion", PluginBrowserSession.available())
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val target = URI("https://${UUID.randomUUID()}.fixture.test/account")
                val request = PluginWebSignIn("Fixture", target, target, setOf("session"), null)
                val address = target.toASCIIString()
                val ordinary = CookieManager.getInstance()
                ordinary.store(address, "session=ordinary")
                try {
                    val first = PluginBrowserSession.create(context)
                    val local = WebViewCompat.getProfile(first.view).cookieManager
                    try {
                        assertNull(first.cookies(request))
                        local.store(address, "session=fixture; Path=/account; Secure; HttpOnly")
                        assertEquals("session=fixture", first.cookies(request))
                        assertEquals("session=ordinary", ordinary.getCookie(address))
                    } finally { first.clear() }
                    assertNull(local.getCookie(address))
                    assertEquals("session=ordinary", ordinary.getCookie(address))
                    val second = PluginBrowserSession.create(context)
                    try { assertNull(second.cookies(request)) }
                    finally { second.clear() }
                } finally { ordinary.store(address, "session=empty; Max-Age=0") }
            }
        }
    }

    @Test fun inlineDocumentUsesHttpsOriginAndOnlyMainFrameCanReportFailure() = runBlocking {
        withTimeout(10_000) {
            withContext(Dispatchers.Main) {
                assumeTrue(PluginBrowserSession.available())
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
                assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val target = URI("https://${UUID.randomUUID()}.fixture.test/login")
                val html = """
                    <iframe srcdoc='<script>window.mbSignInAndroid?.postMessage(JSON.stringify({error:"from frame"}));</script>'></iframe>
                    <script>
                      document.cookie = 'session=fixture; Secure; Path=/';
                      addEventListener('load', () => {
                        window.webkit.messageHandlers.mbSignIn.postMessage({error: '字'.repeat(400)});
                        window.webkit.messageHandlers.mbSignIn.postMessage({error: 'scope=' + location.origin});
                      });
                    </script>
                """.trimIndent()
                val request = PluginWebSignIn("Fixture", target, target, setOf("session"), null, html = html)
                val page = PluginBrowserPage(request.title, target, request.id, request)
                val messages = Channel<String>(Channel.UNLIMITED)
                val browser = PluginBrowserSession.create(context)
                try {
                    browser.open(page) { messages.trySend(it) }
                    val expected = "scope=https://${target.host}"
                    assertEquals(expected, messages.receive())
                    assertEquals("session=fixture", browser.cookies(request))
                    assertNull(CookieManager.getInstance().getCookie(target.toASCIIString()))
                    browser.reload(page)
                    assertEquals(expected, messages.receive())
                } finally { browser.clear() }
            }
        }
    }

    private fun unavailableAddress(): URI = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
        URI("https://127.0.0.1:${socket.localPort}/login")
    }

    private suspend fun CookieManager.store(address: String, cookie: String) {
        val result = CompletableDeferred<Boolean>()
        setCookie(address, cookie) { result.complete(it) }
        assertTrue(result.await())
    }

    private suspend fun PluginBrowserSession.clear() {
        val result = CompletableDeferred<Unit>()
        close { result.complete(Unit) }
        result.await()
    }
}
