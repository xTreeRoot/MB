package com.xtreeroot.mb.plugin

import com.xtreeroot.mb.local.writeAtomic
import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal class PluginSignInDiagnostics(private val directory: File) {
    private val started = System.currentTimeMillis()
    private val entries = ArrayDeque<JSONObject>(32)
    private var finished = false

    fun record(address: URI, response: PluginFetchResponse? = null, failure: Throwable? = null) {
        if (finished) return
        val hash = MessageDigest.getInstance("SHA-256").digest(address.path.orEmpty().encodeToByteArray()).toHexString()
        val entry = JSONObject().put("host", address.host).put("pathHash", hash)
        response?.let {
            entry.put("status", it.status)
            val codes = serviceCodes(it.bytes)
            if (codes.isNotEmpty()) entry.put("serviceCodes", JSONArray(codes))
        }
        failure?.let { entry.put("failureType", it.javaClass.simpleName) }
        if (entries.size == 32) entries.removeFirst()
        entries.addLast(entry)
    }

    fun finish(completed: Boolean) {
        finished = true
        val report = JSONObject().put("started", started).put("completed", completed).put("entries", JSONArray(entries))
        runCatching { writeAtomic(File(directory, "sign-in-diagnostics.json"), report.toString()) }
    }

    private fun serviceCodes(bytes: ByteArray): List<Int> {
        if (bytes.size > 2 * 1024 * 1024) return emptyList()
        val body = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull() ?: return emptyList()
        val status = body.optJSONObject("status_info")
        return listOf(body.opt("code"), body.opt("status_code"), body.opt("error_code"),
            status?.opt("status_code"), status?.opt("code"), body.optJSONObject("data")?.opt("error_code"))
            .mapNotNull { value ->
                when (value) {
                    is Int -> value
                    is Long -> value.toInt().takeIf { it.toLong() == value }
                    is String -> value.toIntOrNull()
                    else -> null
                }
            }.distinct().sorted()
    }
}
