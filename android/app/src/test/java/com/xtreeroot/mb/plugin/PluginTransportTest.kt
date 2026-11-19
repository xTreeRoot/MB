package com.xtreeroot.mb.plugin

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTransportTest {
    private val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["127.0.0.1"]}}"""))

    @Test fun manifestRejectsUnsupportedHostAndMatchesOnlyTheAllowedDomain() {
        listOf(
            """{"id":"../escape"}""",
            """{"id":"fixture","main":"../outside.js"}""",
            """{"id":"fixture","minAppVersion":"99.0.0"}""",
            """{"id":"fixture","permissions":{"process":["ffmpeg"]}}""",
            """{"id":"fixture","requestHeaders":[{"hosts":["unapproved.example.test"],"headers":{"Cookie":"fixture"}}]}""",
        ).forEach { text -> assertThrows(PluginException::class.java) { PluginManifest.parse(JSONObject(text)) } }
        val restricted = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["*.Example.test"]}}"""))
        assertTrue(restricted.reaches("media.example.test"))
        assertFalse(restricted.reaches("notexample.test"))
        assertFalse(restricted.reaches("example.test"))
    }

    @Test fun crossOriginRedirectStripsCredentialsAndKeepsSeparateSetCookieHeaders() = runBlocking {
        withServer { target ->
            target.enqueue(MockResponse().addHeader("Set-Cookie", "first=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT")
                .addHeader("Set-Cookie", "second=2; Path=/"))
            withServer { origin ->
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://127.0.0.1:${target.port}/receive"))
                val initial = PluginFetchRequest(uri("http://127.0.0.1:${origin.port}/request"), "POST",
                    mapOf("Cookie" to "test-only=1", "Authorization" to "Bearer fake-token"), "fixture")
                val result = PluginHttpTransport().fetch(initial, manifest)
                assertEquals(200, result.status)
                val redirected = checkNotNull(target.takeRequest(1, TimeUnit.SECONDS))
                assertEquals("GET", redirected.method)
                assertNull(redirected.getHeader("Cookie"))
                assertNull(redirected.getHeader("Authorization"))
                assertEquals(setOf("first=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT", "second=2; Path=/"), result.cookies.toSet())
            }
        }
    }

    @Test fun redirectChecksPermissionsBeforeContactingTarget() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://localhost:${server.port}/receive"))
            server.enqueue(MockResponse())
            val initial = PluginFetchRequest(uri("http://127.0.0.1:${server.port}/request"), "GET", emptyMap(), null)
            val error = runCatching { PluginHttpTransport().fetch(initial, manifest) }.exceptionOrNull()
            assertEquals("Request outside plugin network permissions", error?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun sameOrigin307RetainsRequestWhileCrossOriginCannotForwardBody() {
        val original = PluginFetchRequest(uri("https://example.test/current"), "POST", mapOf("Cookie" to "example=1", "Content-Type" to "text/plain"), "payload")
        val sameOrigin = original.redirect(307, "/other")!!
        assertEquals(original.headers, sameOrigin.headers)
        assertEquals(original.body, sameOrigin.body)
        assertEquals(original.method, sameOrigin.method)
        assertThrows(PluginException::class.java) { original.redirect(308, "https://other.example.test/") }
        assertThrows(PluginException::class.java) { original.redirect(302, "http://example.test/") }
        val seeOther = original.redirect(303, "/other")!!
        assertEquals("GET", seeOther.method)
        assertNull(seeOther.body)
        assertFalse(seeOther.headers.containsKey("Content-Type"))
    }

    private suspend fun withServer(run: suspend (MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            server.start()
            withTimeout(5000) { run(server) }
        }
    }
}
