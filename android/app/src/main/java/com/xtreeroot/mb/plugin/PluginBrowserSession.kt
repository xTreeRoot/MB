package com.xtreeroot.mb.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.core.net.toUri
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import java.util.UUID

internal data class PluginWebLink(val title: String, val url: URI, val id: UUID = UUID.randomUUID())

@MainThread
@SuppressLint("RequiresFeature")
internal class PluginBrowserSession private constructor(val view: WebView, private val profile: Profile) {
    fun cookies(request: PluginWebSignIn): String? = request.completedCookies(profile.cookieManager.getCookie(request.cookiesOf.toASCIIString()))

    fun close(cleared: () -> Unit = {}) {
        view.stopLoading()
        view.destroy()
        WebStorageCompat.deleteBrowsingData(profile.webStorage, cleared)
    }

    companion object {
        private const val prefix = "mb-signin-"
        private var removedStaleProfiles = false

        fun available(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)

        @SuppressLint("SetJavaScriptEnabled")
        fun create(context: Context, userAgent: String? = null, onPage: (String) -> Unit = {}): PluginBrowserSession {
            if (!available()) throw PluginException("Isolated browsing requires a newer Android System WebView")
            if (!removedStaleProfiles) {
                val store = ProfileStore.getInstance()
                store.allProfileNames.filter { it.startsWith(prefix) }.forEach { store.deleteProfile(it) }
                removedStaleProfiles = true
            }
            val view = WebView(context)
            try {
                WebViewCompat.setProfile(view, prefix + UUID.randomUUID())
                val profile = WebViewCompat.getProfile(view)
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
                }
                return PluginBrowserSession(view, profile)
            } catch (failure: Exception) {
                view.destroy()
                throw failure
            }
        }
    }
}
