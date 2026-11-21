package com.xtreeroot.mb.plugin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object GitHubStarsList {
    private val container = Regex("""<div\s+[^>]*\bid\s*=\s*["']user-list-repositories["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val div = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val headingLink = Regex("""<h2\s+[^>]*\bclass\s*=\s*["'](?:[^"']*\s)?h3(?:\s[^"']*)?["'][^>]*>\s*<a\s+[^>]*\bhref\s*=\s*["']/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)["'][^>]*>""",
        RegexOption.IGNORE_CASE)
    private val links = Regex("""<a\s+([^>]+)>""", RegexOption.IGNORE_CASE)
    private val nextLink = Regex("""\brel\s*=\s*["']next["']""", RegexOption.IGNORE_CASE)
    private val href = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun address(input: String): HttpUrl? {
        val url = input.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != "github.com" || url.pathSegments.firstOrNull() != "stars") return null
        if (url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
            url.pathSegments.size != 4 || url.pathSegments[2] != "lists" ||
            !url.pathSegments[1].matches(Regex("[A-Za-z0-9-]+")) || url.pathSegments[3].isBlank()) {
            throw PluginException("Use a public GitHub Stars list URL")
        }
        return url.newBuilder().query(null).build()
    }

    fun read(html: String, address: HttpUrl): Page {
        val start = container.find(html)?.range?.last?.plus(1) ?: throw PluginException("GitHub Stars repository list is missing")
        var depth = 1
        val close = div.findAll(html, start).firstOrNull { tag ->
            depth += if (tag.groupValues[1].isEmpty()) 1 else -1
            depth == 0
        } ?: throw PluginException("GitHub Stars repository list is incomplete")
        val contents = html.substring(start, close.range.first)
        val repositories = headingLink.findAll(contents).mapNotNull { match ->
            try { PluginRepository.parse(match.groupValues[1]) } catch (_: PluginException) { null }
        }.distinctBy { it.lowercase() }.toList()
        val next = links.findAll(contents).map { it.groupValues[1] }.firstOrNull { nextLink.containsMatchIn(it) }
            ?.let { attributes -> href.find(attributes)?.groupValues?.get(1) }
            ?.let { address.resolve(it.replace("&amp;", "&")) }
            ?.takeIf { it.isHttps && it.host == address.host && it.port == address.port &&
                it.encodedPath == address.encodedPath && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
        return Page(repositories, next)
    }

    data class Page(val repositories: List<String>, val next: HttpUrl?)
}
