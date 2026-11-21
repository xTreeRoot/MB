package com.xtreeroot.mb.playback

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@OptIn(UnstableApi::class)
internal class LoudnessMediaInput(private val factory: DataSource.Factory, private val uri: Uri,
                                  private val checkActive: () -> Unit) : MediaDataSource() {
    private val buffer = ByteArray(128 * 1024)
    private var source: DataSource? = null
    private var start = 0L
    private var count = 0
    private var size = C.LENGTH_UNSET.toLong()
    private var sizeProbed = false
    private var closed = false
    private var readFailure: Exception? = null

    @Synchronized
    override fun getSize(): Long = checked {
        checkActive()
        if (closed) throw IOException("Audio input closed")
        if (!sizeProbed) openAt(0)
        size
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = checked {
        checkActive()
        if (closed) throw IOException("Audio input closed")
        if (size == 0) return@checked 0
        var copied = 0
        while (copied < size) {
            val next = position + copied
            if (this.size != C.LENGTH_UNSET.toLong() && next >= this.size) break
            if (!loadWindow(next)) break
            val relative = (next - start).toInt()
            val available = minOf(size - copied, count - relative)
            this.buffer.copyInto(buffer, offset + copied, relative, relative + available)
            copied += available
        }
        if (copied > 0) copied else -1
    }

    private fun loadWindow(position: Long): Boolean {
        if (position < start || position > start + count || source == null) openAt(position)
        if (position == start + count) {
            start = position
            count = 0
            val reader = checkNotNull(source)
            while (count < this.buffer.size) {
                checkActive()
                val read = reader.read(this.buffer, count, this.buffer.size - count)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (this.size == C.LENGTH_UNSET.toLong()) this.size = start + count
                    else if (start + count < this.size) throw IOException("Incomplete audio input")
                    break
                }
                if (read <= 0) throw IOException("Audio input made no progress")
                count += read
            }
        }
        return count > 0
    }

    private fun openAt(position: Long) {
        checkActive()
        source?.close()
        val reader = factory.createDataSource()
        source = reader
        start = position
        count = 0
        val remaining = reader.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        sizeProbed = true
        if (size == C.LENGTH_UNSET.toLong() && remaining >= 0) size = position + remaining
    }

    @Synchronized
    fun checkReadFailure() { readFailure?.let { throw it } }

    private inline fun <Result> checked(block: () -> Result): Result = try { block() }
    catch (failure: Exception) {
        readFailure = failure
        throw failure
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        source?.close()
        source = null
    }
}
