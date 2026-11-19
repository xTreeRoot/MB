package com.xtreeroot.mb.plugin

import androidx.annotation.Keep
import kotlinx.coroutines.suspendCancellableCoroutine

@Keep
internal fun interface QuickJsHost {
    fun call(operation: ByteArray, arguments: ByteArray): ByteArray
}

@Keep
internal class QuickJsContext(host: QuickJsHost) {
    private val lifetime = Any()
    private var handle = nativeCreate(host).also { check(it != 0L) { "Runtime allocation failed" } }

    suspend fun evaluate(source: String, name: String, module: Boolean, budget: Int) = interruptible {
        nativeEvaluate(handle, source.encodeToByteArray(), name.encodeToByteArray(), module, budget)
    }

    suspend fun start(name: String, json: String) = interruptible {
        nativeStart(handle, name.encodeToByteArray(), json.encodeToByteArray(), callBudgetMs)
    }

    fun settle(id: Int, success: Boolean, json: String) =
        nativeSettle(handle, id, success, json.encodeToByteArray(), callBudgetMs)

    fun supports(name: String) = nativeHasExport(handle, name.encodeToByteArray())
    fun event() = nativeRead(handle, event = true)?.decodeToString()
    fun result() = nativeRead(handle, event = false)?.decodeToString() ?: "null"
    fun errorCode() = nativeErrorCode(handle)?.decodeToString()

    fun close() = synchronized(lifetime) {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private suspend fun interruptible(action: () -> Int): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            synchronized(lifetime) { if (handle != 0L) nativeCancel(handle) }
        }
        if (continuation.isActive) continuation.resumeWith(runCatching(action))
    }

    private external fun nativeCreate(host: QuickJsHost): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeEvaluate(handle: Long, source: ByteArray, name: ByteArray, module: Boolean, budget: Int): Int
    private external fun nativeStart(handle: Long, name: ByteArray, arguments: ByteArray, budget: Int): Int
    private external fun nativeSettle(handle: Long, id: Int, success: Boolean, value: ByteArray, budget: Int): Int
    private external fun nativeHasExport(handle: Long, name: ByteArray): Boolean
    private external fun nativeRead(handle: Long, event: Boolean): ByteArray?
    private external fun nativeErrorCode(handle: Long): ByteArray?

    companion object {
        private const val callBudgetMs = 100
        init { System.loadLibrary("mbquickjs") }
    }
}
