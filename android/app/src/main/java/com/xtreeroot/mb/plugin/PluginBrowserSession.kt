package com.xtreeroot.mb.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import java.util.UUID
import org.json.JSONObject

internal data class PluginWebLink(val title: String, val url: URI, val id: UUID = UUID.randomUUID())

internal const val pluginBrowserLogTag = "MBPluginBrowser"

internal enum class PluginBrowserIssue { MissingProvider, UnsupportedProvider, Startup, Navigation, ServerUnavailable, Renderer }

internal enum class PluginBrowserRoute(val issue: PluginBrowserIssue? = null) {
    Profile, DisposableProcess, MissingProvider(PluginBrowserIssue.MissingProvider),
    StartupFailure(PluginBrowserIssue.Startup), UnsupportedProvider(PluginBrowserIssue.UnsupportedProvider)
}

internal fun pluginBrowserRoute(disposableProcess: Result<Boolean>, provider: Result<Boolean>,
                                profiles: Result<Boolean>?, processIssue: PluginBrowserIssue? = null): PluginBrowserRoute = when {
    disposableProcess.getOrNull() == true -> PluginBrowserRoute.DisposableProcess
    provider.getOrNull() == true && profiles?.getOrNull() == true -> PluginBrowserRoute.Profile
    provider.isFailure || profiles?.isFailure == true -> PluginBrowserRoute.StartupFailure
    provider.getOrNull() == false -> PluginBrowserRoute.MissingProvider
    disposableProcess.isFailure || processIssue == PluginBrowserIssue.Startup ||
        processIssue == PluginBrowserIssue.MissingProvider -> PluginBrowserRoute.StartupFailure
    else -> PluginBrowserRoute.UnsupportedProvider
}

@MainThread
@SuppressLint("RequiresFeature")
internal class PluginBrowserSession private constructor(val view: WebView, private val cookies: CookieManager,
                                                        private val clearData: (() -> Unit) -> Unit) {
    private var rendererExited = false
    private var closed = false

    fun cookies(request: PluginWebSignIn): String? = request.completedCookies(cookies.getCookie(request.cookiesOf.toASCIIString()))

    fun open(page: PluginBrowserPage, onDocumentError: (String) -> Unit) {
        if (page.signIn?.html != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origin = setOf(URI("https", null, page.url.host, page.url.port, null, null, null).toASCIIString())
            WebViewCompat.addWebMessageListener(view, "mbSignInAndroid", origin) { _, message, source, mainFrame, _ ->
                if (closed || rendererExited || !mainFrame) return@addWebMessageListener
                val sourceOrigin = runCatching { URI(source.toString()) }.getOrNull() ?: return@addWebMessageListener
                if (!page.url.sameHttpsOrigin(sourceOrigin)) return@addWebMessageListener
                val data = message.data?.takeIf { it.length <= 4_096 } ?: return@addWebMessageListener
                val error = runCatching { JSONObject(data).opt("error") as? String }.getOrNull()
                if (error != null && error.isNotBlank() && error.encodeToByteArray().size <= 1_024 &&
                    error.none(Char::isISOControl)) onDocumentError(error)
            }
            WebViewCompat.addDocumentStartJavaScript(view, """
                if (window.top === window) {
                  window.webkit ??= {};
                  window.webkit.messageHandlers ??= {};
                  window.webkit.messageHandlers.mbSignIn = {postMessage(message) {
                    window.mbSignInAndroid.postMessage(JSON.stringify(message));
                  }};
                }
            """.trimIndent(), origin)
        }
        load(page)
    }

    fun reload(page: PluginBrowserPage) {
        if (page.signIn?.html != null) load(page) else view.reload()
    }

    private fun load(page: PluginBrowserPage) {
        val url = page.url.toASCIIString()
        val html = page.signIn?.html
        if (html == null) view.loadUrl(url) else view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
    }

    fun close(cleared: () -> Unit = {}) {
        closed = true
        if (!rendererExited) {
            view.stopLoading()
            if (isPluginBrowserProcess(view.context)) view.clearCache(true)
        }
        view.destroy()
        clearData(cleared)
    }

    companion object {
        private const val prefix = "mb-signin-"
        private var removedStaleProfiles = false

        fun available(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

        fun create(context: Context, userAgent: String? = null, onFailure: (PluginBrowserIssue) -> Unit = {},
                   onPage: (String) -> Unit = {}): PluginBrowserSession {
            if (!available()) throw PluginException("Isolated browsing requires a newer Android System WebView")
            if (!removedStaleProfiles) {
                val store = ProfileStore.getInstance()
                store.allProfileNames.filter { it.startsWith(prefix) }.forEach { name ->
                    runCatching { store.deleteProfile(name) }
                }
                removedStaleProfiles = true
            }
            return create(context, userAgent, onFailure, onPage) { view ->
                WebViewCompat.setProfile(view, prefix + UUID.randomUUID())
                val profile = WebViewCompat.getProfile(view)
                PluginBrowserSession(view, profile.cookieManager) { cleared ->
                    WebStorageCompat.deleteBrowsingData(profile.webStorage, cleared)
                }
            }
        }

        fun createInDisposableProcess(context: Context, userAgent: String?, onFailure: (PluginBrowserIssue) -> Unit,
                                      onPage: (String) -> Unit): PluginBrowserSession {
            check(isPluginBrowserProcess(context))
            return create(context, userAgent, onFailure, onPage) { view ->
                val manager = CookieManager.getInstance()
                PluginBrowserSession(view, manager) { cleared ->
                    WebStorage.getInstance().deleteAllData()
                    manager.removeAllCookies { manager.flush(); cleared() }
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        private fun create(context: Context, userAgent: String?, onFailure: (PluginBrowserIssue) -> Unit, onPage: (String) -> Unit,
                           bind: (WebView) -> PluginBrowserSession): PluginBrowserSession {
            val view = WebView(context)
            try {
                val session = bind(view)
                view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgent?.let { userAgentString = it }
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        val destination = url.toUri()
                        if (destination.scheme.equals("https", true) && destination.userInfo == null) {
                            destination.encodedAuthority?.let(onPage)
                        }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, navigation: WebResourceRequest): Boolean =
                        !navigation.url.scheme.equals("https", true) || navigation.url.host.isNullOrBlank() || navigation.url.userInfo != null

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (!request.isForMainFrame || session.closed || session.rendererExited) return
                        Log.w(pluginBrowserLogTag, "main frame error=${error.errorCode}")
                        onFailure(PluginBrowserIssue.Navigation)
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (!request.isForMainFrame || session.closed || session.rendererExited || response.statusCode !in 500..599) return
                        Log.w(pluginBrowserLogTag, "main frame HTTP status=${response.statusCode}")
                        onFailure(PluginBrowserIssue.ServerUnavailable)
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        Log.w(pluginBrowserLogTag, "renderer exited crashed=${detail.didCrash()}")
                        session.rendererExited = true
                        if (!session.closed) onFailure(PluginBrowserIssue.Renderer)
                        return true
                    }
                }
                return session
            } catch (failure: Exception) {
                view.destroy()
                throw failure
            }
        }
    }
}
