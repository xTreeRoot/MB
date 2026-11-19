package com.xtreeroot.mb.plugin

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

internal data class PluginWebSignIn(
    val title: String,
    val url: URI,
    val cookiesOf: URI,
    val until: Set<String>,
    val userAgent: String?,
    val id: UUID = UUID.randomUUID(),
) {
    fun completedCookies(header: String?): String? {
        if (header.isNullOrBlank() || header.any(Char::isISOControl)) return null
        val present = header.split(';').mapNotNull { entry ->
            val parts = entry.trim().split('=', limit = 2)
            parts.first().trim().takeIf { parts.getOrNull(1)?.isNotBlank() == true }
        }.toSet()
        return header.takeIf { present.containsAll(until) }
    }

    companion object {
        private val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

        fun parse(event: JSONObject, manifest: PluginManifest): PluginWebSignIn {
            val options = event.optJSONObject("options") ?: throw PluginException("Invalid web sign-in request")
            fun target(key: String): URI {
                val address = (options.opt(key) as? String)?.let(::uri)
                return address?.takeIf { it.isHttp() && it.scheme.equals("https", true) && manifest.reaches(it.host.orEmpty()) }
                    ?: throw PluginException("Web sign-in target is outside plugin permissions")
            }
            val names = options.optJSONArray("until") ?: throw PluginException("Missing web sign-in cookie names")
            val until = (0 until names.length()).map { index ->
                (names.opt(index) as? String)?.trim()?.takeIf(cookieName::matches)
                    ?: throw PluginException("Invalid web sign-in cookie name")
            }.toSet()
            if (until.isEmpty()) throw PluginException("Missing web sign-in cookie names")
            val userAgent = options.opt("userAgent") as? String
            if (userAgent?.any(Char::isISOControl) == true) throw PluginException("Invalid web sign-in user agent")
            return PluginWebSignIn(manifest.name, target("url"), target("cookiesOf"), until, userAgent)
        }
    }
}

internal class PluginWebSignInBroker {
    private class Attempt(val request: PluginWebSignIn, val result: CompletableDeferred<String> = CompletableDeferred())
    private val pending = MutableStateFlow<Attempt?>(null)
    val requests = pending.map { it?.request }

    suspend fun show(request: PluginWebSignIn): String {
        val attempt = Attempt(request)
        if (!pending.compareAndSet(null, attempt)) throw PluginException("Another sign-in page is already open")
        try { return attempt.result.await() }
        finally { pending.compareAndSet(attempt, null) }
    }

    fun finish(id: UUID, header: String) {
        val attempt = pending.value?.takeIf { it.request.id == id } ?: return
        attempt.request.completedCookies(header)?.let(attempt.result::complete)
    }

    fun cancel(id: UUID) { pending.value?.takeIf { it.request.id == id }?.result?.cancel() }

    fun fail(id: UUID, message: String) {
        pending.value?.takeIf { it.request.id == id }?.result?.completeExceptionally(PluginException(message))
    }
}
