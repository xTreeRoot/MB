package com.xtreeroot.mb.ui

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.xtreeroot.mb.plugin.PluginBrowserSession
import com.xtreeroot.mb.plugin.PluginWebLink
import com.xtreeroot.mb.plugin.PluginWebSignIn
import com.xtreeroot.mb.plugin.PluginWebSignInBroker
import java.net.URI
import kotlinx.coroutines.delay

@Composable
internal fun PluginWebSignInView(request: PluginWebSignIn, broker: PluginWebSignInBroker, cancel: () -> Unit) {
    val unavailable = mb("无法开启隔离登录页，请更新 Android System WebView", "Could not open an isolated sign-in page. Update Android System WebView.")
    PluginBrowserDialog(request.title, request.url, request.userAgent, mb("取消", "Cancel"), cancel, unavailable,
        onFailure = { broker.fail(request.id, unavailable) }) { active ->
        while (true) {
            val completed = active.cookies(request)
            if (completed != null) { broker.finish(request.id, completed); break }
            delay(300)
        }
    }
}

@Composable
internal fun PluginWebLinkView(link: PluginWebLink, close: () -> Unit) {
    PluginBrowserDialog(link.title, link.url, null, mb("完成", "Done"), close,
        mb("无法开启隔离网页，请更新 Android System WebView", "Could not open an isolated web page. Update Android System WebView."))
}

@Composable
private fun PluginBrowserDialog(title: String, url: URI, userAgent: String?, closeLabel: String, close: () -> Unit,
                                unavailable: String, onFailure: () -> Unit = {}, observe: suspend (PluginBrowserSession) -> Unit = {}) {
    var session by remember { mutableStateOf<PluginBrowserSession?>(null) }
    var failed by remember { mutableStateOf(false) }
    var authority by remember(url) { mutableStateOf(url.rawAuthority) }
    LaunchedEffect(session) {
        session?.let { observe(it) }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(authority, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { session?.view?.reload() }, enabled = session != null) { Text(mb("刷新", "Reload")) }
                TextButton(onClick = close) { Text(closeLabel) }
            }
            if (failed) Text(unavailable, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            AndroidView(factory = { context ->
                FrameLayout(context).apply {
                    try {
                        val browser = PluginBrowserSession.create(context, userAgent) { authority = it }.also { browser ->
                            tag = browser
                            addView(browser.view, MATCH_PARENT, MATCH_PARENT)
                            browser.view.loadUrl(url.toASCIIString())
                        }
                        session = browser
                    } catch (_: Exception) {
                        failed = true
                        onFailure()
                    }
                }
            }, modifier = Modifier.fillMaxSize(), onRelease = { frame ->
                frame.removeAllViews()
                (frame.tag as? PluginBrowserSession)?.close()
                session = null
            }, update = {})
        }
    }
}
