package com.xtreeroot.mb.playback

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okio.source

internal class SequentialAudioFile(private val directory: File, private val maximumBytes: Long = 256L * 1024 * 1024) {
    private data class Entry(val owner: MediaRangeGuard, val address: HttpUrl, val headers: Headers, val finalAddress: HttpUrl,
                             val file: File, val size: Long, val type: MediaType?)
    private var entry: Entry? = null
    @Volatile private var generation = 0L

    init { directory.listFiles()?.forEach(File::delete) }

    @Synchronized
    fun clear() {
        generation++
        entry?.file?.delete()
        entry = null
    }

    @Synchronized
    fun open(owner: MediaRangeGuard, request: Request, start: Long, end: Long): Response? {
        val cached = entry?.takeIf { it.owner === owner && it.address == request.url && it.headers == request.mediaHeaders() }
            ?: return null
        return try { cached.response(request, start, end) }
        catch (_: FileNotFoundException) { clear(); null }
    }

    fun capture(owner: MediaRangeGuard, request: Request, response: Response, expected: Long?, start: Long, end: Long,
                cancelled: () -> Boolean): Response {
        val body = response.body ?: throw MediaFetchFailure(status = 200)
        val type = body.contentType()
        val subtype = type?.subtype.orEmpty()
        if (type?.type == "text" || subtype == "json" || subtype == "xml" || subtype.endsWith("+json") || subtype.endsWith("+xml")) {
            throw MediaFetchFailure(status = 200)
        }
        val attempt = generation
        fun checkCurrent() {
            if (cancelled() || generation != attempt) throw InterruptedIOException("Audio fallback was canceled")
        }
        checkCurrent()
        if (!directory.isDirectory && !directory.mkdirs()) throw MediaFetchFailure(status = 200)
        val budget = minOf(maximumBytes, (directory.usableSpace - 64L * 1024 * 1024).coerceAtLeast(0))
        if (budget <= 0 || (expected != null && expected > budget)) throw MediaFetchFailure(status = 200)
        val candidate = File.createTempFile("audio-", ".part", directory)
        try {
            var size = 0L
            response.use {
                body.byteStream().use { input ->
                    candidate.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkCurrent()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > budget || (expected != null && size > expected)) throw MediaFetchFailure(status = 200)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (size <= start || (expected != null && size != expected)) throw MediaFetchFailure(status = 200)
            synchronized(this) {
                checkCurrent()
                val next = Entry(owner, request.url, request.mediaHeaders(), response.request.url, candidate, size, type)
                val result = next.response(request, start, end)
                entry?.file?.delete()
                entry = next
                return result
            }
        } finally {
            synchronized(this) { if (entry?.file != candidate) candidate.delete() }
        }
    }

    private fun Request.mediaHeaders() = headers.newBuilder().removeAll("Range").removeAll("If-Range").build()

    private fun Entry.response(request: Request, start: Long, end: Long): Response {
        val builder = Response.Builder().request(request.newBuilder().url(finalAddress).build()).protocol(Protocol.HTTP_1_1)
        if (start >= size) return builder.code(416).message("Range Not Satisfiable").header("Content-Range", "bytes */$size")
            .body(byteArrayOf().toResponseBody()).build()
        val last = minOf(end, size - 1)
        val length = last - start + 1
        val stream = FileInputStream(file)
        try { stream.channel.position(start) } catch (failure: IOException) { stream.close(); throw failure }
        val body = object : ResponseBody() {
            private val input = object : ForwardingSource(stream.source()) {
                private var remaining = length
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (remaining == 0L) return -1
                    val count = super.read(sink, minOf(remaining, byteCount))
                    if (count < 0) throw EOFException("Temporary audio ended early")
                    remaining -= count
                    return count
                }
            }.buffer()
            override fun source() = input
            override fun contentLength() = length
            override fun contentType() = type
        }
        if (request.header("Range") == null) builder.code(200).message("OK")
        else builder.code(206).message("Partial Content").header("Content-Range", "bytes $start-$last/$size")
        return builder.header("Content-Length", length.toString()).body(body).build()
    }
}
