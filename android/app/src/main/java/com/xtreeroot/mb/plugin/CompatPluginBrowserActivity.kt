package com.xtreeroot.mb.plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.xtreeroot.mb.ui.LocalMBEnglish
import com.xtreeroot.mb.ui.PluginBrowserPanel
import com.xtreeroot.mb.ui.mb
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val processName = ":plugin_browser"
private const val directorySuffixPrefix = "mb-plugin-browser-"
internal const val compatDataDirectoryPrefix = "app_webview_$directorySuffixPrefix"

internal fun isPluginBrowserProcess(packageName: String): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Application.getProcessName() == "$packageName$processName"

internal data class PluginBrowserPage(val title: String, val url: URI, val id: UUID, val signIn: PluginWebSignIn? = null)

internal class CompatPluginBrowserActivity : ComponentActivity() {
    private val closing = mutableStateOf(false)
    private var ownsProcess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val page = intent.page() ?: return finish()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return finishWithIssue(PluginBrowserIssue.UnsupportedProvider)
        }
        if (!isPluginBrowserProcess(packageName)) {
            Log.w(pluginBrowserLogTag, "compat activity is outside the isolated process")
            return finishWithIssue(PluginBrowserIssue.Startup)
        }
        val suffix = directorySuffix ?: return finishWithIssue(PluginBrowserIssue.Startup)
        if (attempt != null && attempt != page.id) return finishWithIssue(PluginBrowserIssue.Startup)
        try {
            if (attempt == null) {
                File(applicationInfo.dataDir).listFiles { file ->
                    file.name.startsWith(compatDataDirectoryPrefix) && file.name != "app_webview_$suffix"
                }?.forEach(File::deleteRecursively)
                cacheDir.listFiles { file ->
                    file.name.startsWith("webview_$directorySuffixPrefix") && file.name != "webview_$suffix"
                }?.forEach(File::deleteRecursively)
                attempt = page.id
            }
            ownsProcess = true
        } catch (failure: RuntimeException) {
            Log.w(pluginBrowserLogTag, "compat data cleanup: ${failure.javaClass.simpleName}")
            return finishWithIssue(PluginBrowserIssue.Startup)
        }
        enableEdgeToEdge()
        setContent {
            val english = intent.getBooleanExtra("english", false)
            val dark = intent.getBooleanExtra("dark", false)
            CompositionLocalProvider(LocalMBEnglish provides english) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    BackHandler { complete(Activity.RESULT_CANCELED) }
                    if (!closing.value) PluginBrowserPanel(page, mb("取消", "Cancel").takeIf { page.signIn != null }
                        ?: mb("完成", "Done"), close = { complete(Activity.RESULT_CANCELED) },
                        create = PluginBrowserSession::createInDisposableProcess,
                        onFailure = { complete(failed, Intent().putExtra(issueKey, it.name)) },
                        onDocumentError = { complete(failed, Intent().putExtra(documentErrorKey, it)) },
                        onCookies = { complete(Activity.RESULT_OK, Intent().putExtra(cookieKey, it)) },
                        onReleased = { if (closing.value) finish() })
                }
            }
        }
    }

    private fun complete(result: Int, data: Intent? = null) {
        if (closing.value) return
        setResult(result, data)
        closing.value = true
        lifecycleScope.launch { delay(1_000); finish() }
    }

    private fun finishWithIssue(issue: PluginBrowserIssue) {
        setResult(failed, Intent().putExtra(issueKey, issue.name))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ownsProcess && isFinishing) Process.killProcess(Process.myPid())
    }

    companion object {
        private var directorySuffix: String? = null
        private var attempt: UUID? = null
        const val failed = Activity.RESULT_FIRST_USER
        const val cookieKey = "completed-cookies"
        const val issueKey = "browser-issue"
        const val documentErrorKey = "browser-document-error"

        fun prepareProcess() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || directorySuffix != null) return
            val suffix = directorySuffixPrefix + UUID.randomUUID()
            try {
                WebView.setDataDirectorySuffix(suffix)
                directorySuffix = suffix
            } catch (failure: RuntimeException) {
                Log.w(pluginBrowserLogTag, "compat data directory: ${failure.javaClass.simpleName}")
            }
        }

        fun intent(context: Context, page: PluginBrowserPage, english: Boolean, dark: Boolean): Intent =
            Intent(context, CompatPluginBrowserActivity::class.java)
                .putExtra("id", page.id.toString()).putExtra("title", page.title).putExtra("url", page.url.toASCIIString())
                .putExtra("english", english).putExtra("dark", dark).apply {
                    page.signIn?.let { signIn ->
                        putExtra("cookies-of", signIn.cookiesOf.toASCIIString())
                        putExtra("until", signIn.until.toTypedArray())
                        putExtra("user-agent", signIn.userAgent)
                        signIn.html?.let { putExtra("html-utf8", it.encodeToByteArray()) }
                    }
                }

        private fun Intent.page(): PluginBrowserPage? {
            val id = getStringExtra("id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val url = address("url") ?: return null
            val title = getStringExtra("title") ?: return null
            val signIn = if (hasExtra("cookies-of")) {
                val cookiesOf = address("cookies-of") ?: return null
                val names = getStringArrayExtra("until")?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
                val agent = getStringExtra("user-agent")
                val html = if (hasExtra("html-utf8")) {
                    val bytes = getByteArrayExtra("html-utf8") ?: return null
                    if (bytes.isEmpty() || bytes.size > PluginWebSignIn.htmlLimit || !url.sameHttpsOrigin(cookiesOf)) return null
                    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
                } else null
                PluginWebSignIn(title, url, cookiesOf, names, agent, id, html)
            } else null
            return PluginBrowserPage(title, url, id, signIn)
        }

        private fun Intent.address(key: String): URI? =
            getStringExtra(key)?.let(::uri)?.takeIf { it.isHttp() && it.scheme.equals("https", true) }
    }
}
