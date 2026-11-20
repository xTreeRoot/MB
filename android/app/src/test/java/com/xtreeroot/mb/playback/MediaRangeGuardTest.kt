package com.xtreeroot.mb.playback

import java.io.EOFException
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test

class MediaRangeGuardTest {
    @get:Rule val server = MockWebServer()
    private val client = OkHttpClient.Builder().addInterceptor(MediaRangeGuard()).callTimeout(2, SECONDS).build()

    @Test fun checkedRangeUsesStrongValidatorAndEndsAtKnownEof() {
        server.enqueue(part("bytes 0-2/6", "abc", "\"v1\""))
        server.enqueue(part("bytes 3-5/6", "def", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */6"))

        assertEquals("abc", get("bytes=0-2"))
        assertEquals("def", get("bytes=3-99"))
        assertEquals("", get("bytes=6-"))
        assertEquals("identity", server.takeRequest(2, SECONDS)?.getHeader("Accept-Encoding"))
        assertEquals("\"v1\"", server.takeRequest(2, SECONDS)?.getHeader("If-Range"))
    }

    @Test fun changedRepresentationOrSwappedRangeCannotBeRead() {
        server.enqueue(part("bytes 0-1/4", "ab", "\"v1\""))
        server.enqueue(MockResponse().setBody("WXYZ").setHeader("ETag", "\"v2\""))
        server.enqueue(part("bytes 1-2/4", "bc", "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */2"))

        assertEquals("ab", get("bytes=0-1"))
        repeat(3) {
            assertEquals(MediaRecovery.RefreshAddress, assertThrows(MediaFetchFailure::class.java) { get("bytes=2-") }.recovery)
        }
    }

    @Test fun smallIgnoredRangeIsSequentialButCannotSkipUnboundedPrefix() {
        server.enqueue(MockResponse().setBody("abcdef"))
        server.enqueue(MockResponse().setBody("abcdef"))
        assertEquals("abcdef", get("bytes=3-"))
        assertNull(assertThrows(MediaFetchFailure::class.java) { get("bytes=9000000-") }.recovery)
    }

    @Test fun fullBodyWithContentRangeCanStartPlaybackButPartial200Cannot() {
        server.enqueue(part("bytes 0-5/6", "abcdef", "\"v1\"").setResponseCode(200))
        server.enqueue(part("bytes 3-5/6", "def", "\"v1\"").setResponseCode(200))
        assertEquals("abcdef", get())
        assertThrows(MediaFetchFailure::class.java) { get("bytes=3-") }
    }

    @Test fun shortOrOversizedChunkedBodyIsNotAValidSegment() {
        server.enqueue(part("bytes 0-2/3", "ab", null).setChunkedBody("ab", 1))
        server.enqueue(part("bytes 0-2/3", "abcd", null).setChunkedBody("abcd", 1))
        assertThrows(EOFException::class.java) { get("bytes=0-2") }
        assertThrows(MediaFetchFailure::class.java) { get("bytes=0-2") }
    }

    @Test fun mislabelledHtmlIsRejectedWithoutChangingTheStreamRepresentation() {
        server.enqueue(MockResponse().setBody("\uFEFF \r\n<!DOCTYPE html><html>Unavailable</html>")
            .setHeader("Content-Type", "application/octet-stream").setHeader("ETag", "\"error\""))
        server.enqueue(MockResponse().setBody("<HTML lang=en>Unavailable</HTML>"))
        server.enqueue(part("bytes 0-3/10", "RIFF", "\"media\""))
        server.enqueue(part("bytes 4-9/10", "<html>", "\"media\""))

        repeat(2) {
            assertEquals(true, assertThrows(MediaFetchFailure::class.java) { get() }.html)
        }
        assertEquals("RIFF", get("bytes=0-3"))
        assertEquals("<html>", get("bytes=4-9"))
    }

    @Test fun forbiddenDoesNotTriggerRenewalWithoutExplicitExpiry() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>AccessDenied</Code></Error>"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>ExpiredToken</Code></Error>"))
        server.enqueue(MockResponse().setResponseCode(503))
        assertNull(assertThrows(MediaFetchFailure::class.java) { get() }.recovery)
        assertEquals(MediaRecovery.RefreshAddress, assertThrows(MediaFetchFailure::class.java) { get() }.recovery)
        assertEquals(MediaRecovery.Reopen, assertThrows(MediaFetchFailure::class.java) { get() }.recovery)
    }

    @Test fun refreshAndReconnectAreBoundedIndependently() {
        val budget = MediaRecoveryBudget()
        assertEquals(600L, budget.delayFor(MediaRecovery.Reopen))
        assertEquals(150L, budget.delayFor(MediaRecovery.RefreshAddress))
        assertEquals(1200L, budget.delayFor(MediaRecovery.Reopen))
        assertNull(budget.delayFor(MediaRecovery.Reopen))
        assertNull(budget.delayFor(MediaRecovery.RefreshAddress))
    }

    private fun get(range: String? = null): String = client.newCall(Request.Builder().url(server.url("/audio"))
        .apply { if (range != null) header("Range", range) }.build()).execute().use { it.body!!.string() }

    private fun part(range: String, body: String, etag: String?) = MockResponse().setResponseCode(206)
        .setHeader("Content-Range", range).setBody(body).apply { if (etag != null) setHeader("ETag", etag) }
}
