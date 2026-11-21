package com.xtreeroot.mb.playback

import java.io.InterruptedIOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SequentialAudioFileTest {
    @get:Rule val files = TemporaryFolder()
    private val owner = MediaRangeGuard()
    private val request = Request.Builder().url("https://fixture.invalid/audio").header("Authorization", "Fixture").build()

    @Test fun reusesVerifiedBytesOnlyForTheSameReaderAndCredentials() {
        val directory = files.newFolder()
        val cache = SequentialAudioFile(directory, maximumBytes = 12)
        val ranged = request.newBuilder().header("Range", "bytes=3-5").build()
        cache.capture(owner, ranged, full("0123456789"), 10, 3, 5) { false }.use {
            assertEquals(206, it.code)
            assertEquals("bytes 3-5/10", it.header("Content-Range"))
            assertEquals("345", it.body!!.string())
        }
        assertNull(cache.open(MediaRangeGuard(), request, 0, Long.MAX_VALUE))
        assertNull(cache.open(owner, request.newBuilder().removeHeader("Authorization").build(), 0, Long.MAX_VALUE))
        cache.open(owner, request, 0, Long.MAX_VALUE)!!.use { assertEquals("0123456789", it.body!!.string()) }
        directory.listFiles()!!.single().delete()
        assertNull(cache.open(owner, request, 0, Long.MAX_VALUE))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun doesNotPublishIncompleteOversizedOrCanceledTransfers() {
        val directory = files.newFolder()
        val cache = SequentialAudioFile(directory, maximumBytes = 8)
        assertThrows(MediaFetchFailure::class.java) {
            full("0123").use { cache.capture(owner, request, it, 6, 2, 5) { false } }
        }
        assertThrows(MediaFetchFailure::class.java) {
            full("012345678").use { cache.capture(owner, request, it, null, 2, 5) { false } }
        }
        var checks = 0
        assertThrows(InterruptedIOException::class.java) {
            full("012345").use { cache.capture(owner, request, it, 6, 2, 5) { ++checks == 3 } }
        }
        assertNull(cache.open(owner, request, 0, Long.MAX_VALUE))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private fun full(bytes: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("OK").body(bytes.toResponseBody("audio/wav".toMediaType())).build()
}
