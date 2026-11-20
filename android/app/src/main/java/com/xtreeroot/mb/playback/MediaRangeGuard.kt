package com.xtreeroot.mb.playback

import java.io.EOFException
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import okio.buffer

internal class MediaRangeGuard(private val sequentialFile: SequentialAudioFile? = null) : Interceptor {
    internal data class Representation(val url: HttpUrl, val size: Long?, val etag: String?, val partial: Boolean)
    internal data class Prefix(val origin: HttpUrl, val representation: Representation, val bytes: ByteArray, val type: MediaType?)
    private var previous: Representation? = null
    private var prefix: Prefix? = null

    fun prime(response: Response, origin: HttpUrl): Prefix? {
        val known = previous?.takeIf { it.partial && it.size != null } ?: return null
        val body = response.body ?: return null
        val size = minOf(checkNotNull(known.size), audioPrefixBytes.toLong())
        if (response.code != 206 || body.contentLength() != size) return null
        val bytes = body.source().readByteArray(size)
        if (!body.source().exhausted()) throw MediaFetchFailure()
        return Prefix(origin, known, bytes, body.contentType())
    }

    fun offer(value: Prefix) { prefix = value }

    override fun intercept(chain: Interceptor.Chain): Response {
        val incoming = chain.request()
        val prepared = prefix?.takeIf { it.origin == incoming.url && incoming.header("Range") == null }
        prefix = null
        if (prepared != null) {
            previous = prepared.representation
            try { return join(chain, incoming, prepared) }
            catch (_: IOException) { previous = null }
        }
        return checked(chain, incoming)
    }

    private fun join(chain: Interceptor.Chain, incoming: Request, prepared: Prefix): Response {
        val length = checkNotNull(prepared.representation.size)
        val tail = if (length > prepared.bytes.size) {
            checked(chain, incoming.newBuilder().header("Range", "bytes=${prepared.bytes.size}-").build()).also { response ->
                if (response.code != 206 || response.body == null) {
                    response.close()
                    throw MediaFetchFailure()
                }
            }
        } else null
        val body = object : ResponseBody() {
            private val head = Buffer().write(prepared.bytes)
            private val rest = tail?.body?.source()
            private val input = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long =
                    if (!head.exhausted()) head.read(sink, byteCount) else rest?.read(sink, byteCount) ?: -1
                override fun timeout(): Timeout = rest?.timeout() ?: Timeout.NONE
                override fun close() { tail?.close() }
            }.buffer()
            override fun source() = input
            override fun contentLength() = length
            override fun contentType() = prepared.type
        }
        val builder = tail?.newBuilder() ?: Response.Builder().protocol(Protocol.HTTP_1_1)
        builder.request(incoming.newBuilder().url(prepared.representation.url).build())
            .code(200).message("OK").removeHeader("Content-Range").removeHeader("Transfer-Encoding")
            .header("Content-Length", length.toString()).body(body)
        prepared.type?.let { builder.header("Content-Type", it.toString()) }
        return builder.build()
    }

    private fun checked(chain: Interceptor.Chain, incoming: Request): Response {
        val range = incoming.header("Range")?.let {
            requested.matchEntire(it) ?: throw MediaFetchFailure()
        }
        val start = range?.let { it.groupValues[1].toLongOrNull() ?: throw MediaFetchFailure() } ?: 0
        val end = range?.groupValues?.get(2)?.takeIf(String::isNotEmpty)?.let { it.toLongOrNull() ?: throw MediaFetchFailure() }
            ?: Long.MAX_VALUE
        if (start > end) throw MediaFetchFailure()
        sequentialFile?.open(this, incoming, start, end)?.let { return it }
        val known = previous
        val request = incoming.newBuilder().header("Accept-Encoding", "identity").removeHeader("If-Range")
        if (range != null && known?.etag != null) request.header("If-Range", known.etag)
        val response = chain.proceed(request.build())
        try {
            if (response.code == 416) {
                val size = response.header("Content-Range")?.let { unsatisfied.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
                if (range != null && size == start && (known?.size == null || known.size == size) &&
                    (known == null || known.url == response.request.url) &&
                    (known?.etag == null || response.header("ETag") == null || known.etag == response.header("ETag"))) {
                    return response
                }
                throw MediaFetchFailure(MediaRecovery.RefreshAddress, 416)
            }
            if (!response.isSuccessful) throw rejected(response)
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (contentType == "text/html" || contentType == "application/xhtml+xml") {
                throw MediaFetchFailure(status = response.code, html = true)
            }
            if (response.header("Content-Encoding")?.equals("identity", ignoreCase = true) == false) throw MediaFetchFailure()
            val body = response.body ?: throw MediaFetchFailure()
            val length = body.contentLength().takeIf { it >= 0 }
            val rangeHeader = response.header("Content-Range")
            val positions = rangeHeader?.let(satisfied::matchEntire)?.groupValues?.drop(1)?.map { it.toLongOrNull() }
            val first = positions?.get(0)
            val last = positions?.get(1)
            val total = positions?.get(2)
            val size = when (response.code) {
                206 -> {
                    if (range == null || first != start || last == null || total == null ||
                        last < start || last != minOf(end, total - 1) ||
                        (length != null && length != last - start + 1)) throw MediaFetchFailure(MediaRecovery.RefreshAddress, 206)
                    total
                }
                200 -> {
                    if (rangeHeader == null) length else {
                        if (first != 0L || total == null || total <= 0 || last != total - 1 ||
                            (length != null && length != total)) throw MediaFetchFailure(status = 200)
                        total
                    }
                }
                else -> throw MediaFetchFailure(status = response.code)
            }
            val etag = response.header("ETag")?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
            val next = Representation(response.request.url, size, etag, response.code == 206)
            if (known != null && (known.url != next.url || (known.etag != null && known.etag != etag) ||
                    (known.size != null && size != null && known.size != size))) throw MediaFetchFailure(MediaRecovery.RefreshAddress)
            if (response.code == 200 && start > 0) {
                if (known?.partial == true && known.etag == null) throw MediaFetchFailure(MediaRecovery.RefreshAddress, 200)
                if (size != null && start > size) throw MediaFetchFailure(status = 200)
                if (start > 8L * 1024 * 1024) {
                    val captured = sequentialFile?.capture(this, incoming, response, size, start, end, chain.call()::isCanceled)
                        ?: throw MediaFetchFailure(status = 200)
                    previous = next
                    return captured
                }
            }
            previous = next.copy(size = size ?: known?.size)
            if (length != null || size == null) return response
            val expected = if (response.code == 206) minOf(end, size - 1) - start + 1 else size
            return response.newBuilder().body(framed(body, expected)).build()
        } catch (failure: IOException) {
            response.close()
            throw failure
        }
    }

    private fun rejected(response: Response): MediaFetchFailure {
        val status = response.code
        if (status == 401 || status == 403) {
            val preview = runCatching { response.peekBody(2048).string() }.getOrDefault("")
            if (explicitExpiry.containsMatchIn(preview)) return MediaFetchFailure(MediaRecovery.RefreshAddress, status)
        }
        return MediaFetchFailure(if (status in transientStatus) MediaRecovery.Reopen else null, status)
    }

    private fun framed(body: ResponseBody, size: Long): ResponseBody = object : ResponseBody() {
        private val upstream = body.source()
        private val framed = object : ForwardingSource(upstream) {
            private var remaining = size
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (remaining == 0L) return -1
                val count = super.read(sink, minOf(remaining, byteCount))
                if (count < 0) throw EOFException("Media range ended early")
                remaining -= count
                if (remaining == 0L && upstream.request(1)) throw MediaFetchFailure(MediaRecovery.RefreshAddress)
                return count
            }
        }.buffer()
        override fun contentType() = body.contentType()
        override fun contentLength() = size
        override fun source() = framed
    }

    private companion object {
        val requested = Regex("bytes=(\\d+)-(\\d*)")
        val satisfied = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        val unsatisfied = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
        val explicitExpiry = Regex("<Code>(?:ExpiredToken|RequestExpired)</Code>")
        val transientStatus = setOf(408, 429, 500, 502, 503, 504)
    }
}
