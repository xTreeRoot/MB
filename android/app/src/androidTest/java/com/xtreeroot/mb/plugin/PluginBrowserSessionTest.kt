package com.xtreeroot.mb.plugin

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.MainActivity
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
    @Test fun interruptedSignInLaunchFallsBackToAnIsolatedProfile() =
        signInFallsBackToAnIsolatedProfile(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))

    @Test fun providerMissingOnlyInTheBrowserProcessFallsBackToAnIsolatedProfile() = signInFallsBackToAnIsolatedProfile(
        Instrumentation.ActivityResult(CompatPluginBrowserActivity.failed,
            Intent().putExtra(CompatPluginBrowserActivity.issueKey, PluginBrowserIssue.MissingProvider.name)),
    )

    private fun signInFallsBackToAnIsolatedProfile(result: Instrumentation.ActivityResult) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(disposableBrowserSupport(context).getOrNull() == true)
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
        val app = context.applicationContext as MBApplication
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val target = URI("https://${UUID.randomUUID()}.fixture.test/")
        val ordinary = withContext(Dispatchers.Main) { CookieManager.getInstance() }
        withContext(Dispatchers.Main) { ordinary.store(target.toString(), "ordinary=fixture; Secure; Path=/") }
        val request = PluginWebSignIn("Interrupted sign-in fixture", target, target, setOf("session"), null,
            html = """<script>
                if (!document.cookie.includes('ordinary=fixture')) document.cookie = 'session=fallback; Secure; Path=/';
            </script>""")
        val monitor = instrumentation.addMonitor(CompatPluginBrowserActivity::class.java.name, result, true)
        try {
            withTimeout(15_000) {
                assertEquals("session=fallback", app.pluginWebSignIn.show(request))
                assertEquals(1, monitor.hits)
                assertEquals("ordinary=fixture", withContext(Dispatchers.Main) { ordinary.getCookie(target.toString()) })
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            withContext(Dispatchers.Main) { ordinary.store(target.toString(), "ordinary=; Max-Age=0; Secure; Path=/") }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun signInFromMainActivityUsesDisposableProcessAndReturnsTheSession() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(disposableBrowserSupport(context).getOrNull() == true)
        assumeTrue(WebViewCompat.getCurrentWebViewPackage(context) != null)
        val app = context.applicationContext as MBApplication
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val target = URI("https://${UUID.randomUUID()}.fixture.test/")
        val request = PluginWebSignIn("Disposable sign-in fixture", target, target, setOf("session"), null,
            html = """<script>setTimeout(() => document.cookie = 'session=fixture; Secure; Path=/', 3000)</script>""")
        try {
            withTimeout(15_000) {
                val result = async { app.pluginWebSignIn.show(request) }
                val isolated = withContext(Dispatchers.IO) {
                    var process = ""
                    while (process.isEmpty() && !result.isCompleted) {
                        process = instrumentation.uiAutomation.executeShellCommand("pidof ${context.packageName}:plugin_browser")
                            .let { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { stream -> stream.readText().trim() } }
                        if (process.isEmpty()) delay(50)
                    }
                    process
                }
                assertTrue("The login page did not launch in a separate process", isolated.isNotEmpty())
                assertEquals("session=fixture", result.await())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun compatibilityBrowserStartsInADifferentProcessAndDiscardsPreviousAttemptData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(disposableBrowserSupport(context).getOrNull() == true)
        assumeTrue(WebViewCompat.getCurrentWebViewPackage(context) != null)
        val automation = instrumentation.uiAutomation
        fun profiles() = File(context.applicationInfo.dataDir).listFiles { file ->
            file.isDirectory && file.name.startsWith(compatDataDirectoryPrefix)
        }.orEmpty()
        val target = URI("https://${UUID.randomUUID()}.fixture.test/")
        val ordinary = withContext(Dispatchers.Main) { CookieManager.getInstance() }
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
                """<script>setTimeout(() => {
                    if (!document.cookie.includes('ordinary=fixture')) document.cookie = 'session=fixture; Secure; Path=/';
                }, 1500);</script>"""
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
            withContext(Dispatchers.Main) {
                ordinary.store(target.toString(), "ordinary=fixture; Secure; Path=/")
                ordinary.flush()
            }
            val first = open()
            File(first, "fixture").writeText("Disposable test data")
            close()
            val second = open(completeWithCookie = true)
            assertTrue(first != second)
            assertTrue(!first.exists())
            waitFor { processId().isEmpty() }
            assertEquals("ordinary=fixture", withContext(Dispatchers.Main) { ordinary.getCookie(target.toString()) })
        } finally {
            if (processId().isNotEmpty()) shell("input keyevent KEYCODE_BACK")
            withContext(Dispatchers.Main) { ordinary.store(target.toString(), "ordinary=; Max-Age=0; Secure; Path=/") }
        }
    }

    @Test fun compatibilityBrowserReturnsNavigationFailureToItsCaller() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(disposableBrowserSupport(context).getOrNull() == true)
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

    @Test fun compatibilityBrowserDistinguishesAnInvalidLaunchFromUserCancellation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(disposableBrowserSupport(context).getOrNull() == true)
        assumeTrue(WebViewCompat.getCurrentWebViewPackage(context) != null)
        val automation = instrumentation.uiAutomation
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val target = URI("https://example.invalid/")
        val request = PluginWebSignIn("Compatibility cancellation fixture", target, target, setOf("session"), null,
            html = "<p>Waiting for cancellation</p>")
        val page = PluginBrowserPage(request.title, target, request.id, request)
        try {
            withTimeout(15_000) {
                withContext(Dispatchers.Main) {
                    val results = Channel<ActivityResult>(Channel.UNLIMITED)
                    val launcher = activity.activityResultRegistry.register("browser-cancel-fixture",
                        ActivityResultContracts.StartActivityForResult()) { results.trySend(it) }
                    try {
                        launcher.launch(Intent(context, CompatPluginBrowserActivity::class.java))
                        val invalid = results.receive()
                        assertEquals(CompatPluginBrowserActivity.failed, invalid.resultCode)
                        assertEquals(PluginBrowserIssue.Startup.name, invalid.data?.getStringExtra(CompatPluginBrowserActivity.issueKey))

                        launcher.launch(CompatPluginBrowserActivity.intent(context, page, english = true, dark = false))
                        fun hasCancel(node: AccessibilityNodeInfo): Boolean =
                            (node.isVisibleToUser && node.text?.toString() == "Cancel") ||
                                (0 until node.childCount).any { index -> node.getChild(index)?.let(::hasCancel) == true }
                        withContext(Dispatchers.IO) {
                            while (automation.rootInActiveWindow?.let(::hasCancel) != true) delay(50)
                            automation.executeShellCommand("input keyevent KEYCODE_BACK").let {
                                ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
                            }
                        }
                        val cancelled = results.receive()
                        assertEquals(Activity.RESULT_CANCELED, cancelled.resultCode)
                        assertTrue(cancelled.data?.getBooleanExtra(CompatPluginBrowserActivity.userCancelledKey, false) == true)
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

    @Test fun profileBrowserReportsMainDocument503ButNotSubresourceFailures() = runBlocking {
        withContext(Dispatchers.Main) {
            assumeTrue(PluginBrowserSession.available())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val issues = mutableListOf<PluginBrowserIssue>()
            val browser = PluginBrowserSession.create(context, onFailure = { issues.add(it) })
            fun httpError(path: String, mainFrame: Boolean, status: Int, reason: String) {
                val request = object : WebResourceRequest {
                    override fun getUrl(): Uri = Uri.parse("https://fixture.invalid/$path")
                    override fun isForMainFrame(): Boolean = mainFrame
                    override fun isRedirect(): Boolean = false
                    override fun hasGesture(): Boolean = false
                    override fun getMethod(): String = "GET"
                    override fun getRequestHeaders(): Map<String, String> = emptyMap()
                }
                browser.view.webViewClient.onReceivedHttpError(browser.view, request,
                    WebResourceResponse("text/html", "UTF-8", status, reason, emptyMap(), null))
            }
            try {
                httpError("image.png", false, 503, "Service Unavailable")
                httpError("frame", false, 503, "Service Unavailable")
                httpError("sign-in", true, 401, "Unauthorized")
                assertTrue(issues.isEmpty())
                httpError("sign-in", true, 503, "Service Unavailable")
                assertEquals(listOf(PluginBrowserIssue.ServerUnavailable), issues)
            } finally { browser.clear() }
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
