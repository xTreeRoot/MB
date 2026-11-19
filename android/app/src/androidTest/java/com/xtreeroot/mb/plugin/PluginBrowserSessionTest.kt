package com.xtreeroot.mb.plugin

import android.webkit.CookieManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PluginBrowserSessionTest {
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
