package com.xtreeroot.mb.preferences

import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.io.IOException
import java.net.URLConnection

internal data class ProxyPolicy(val hosts: List<String> = defaults) {
    fun usesSystemProxy(hostname: String?): Boolean {
        val host = hostname?.lowercase()?.trimEnd('.') ?: return false
        if (host in localHosts) return false
        return hosts.any { rule -> if (rule.startsWith("*.")) host.endsWith(rule.drop(1)) else host == rule }
    }

    companion object {
        val githubHosts = listOf("github.com", "api.github.com", "codeload.github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        private val defaults = listOf("*.youtube.com", "*.googlevideo.com", "*.ytimg.com", "*.ggpht.com",
            "*.googleusercontent.com", "api.betterlyrics.org") + githubHosts
        private val localHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
        private val domain = Regex("^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

        fun parse(text: String): ProxyPolicy = ProxyPolicy(text.split(Regex("\\s+")).filter(String::isNotBlank).map { entry ->
            val host = entry.lowercase().removeSuffix(".")
            val bare = host.removePrefix("*.")
            require(bare.length <= 253 && domain.matches(bare)) { "请输入域名或 *.域名；不能包含 URL、端口、账号或全局通配符" }
            host
        }.distinct())
    }
}

internal class NetworkRouting(private val systemProxy: () -> ProxySelector? = ProxySelector::getDefault,
                              private val policy: () -> ProxyPolicy) {
    fun open(url: URL): URLConnection = if (policy().usesSystemProxy(url.host)) url.openConnection() else url.openConnection(Proxy.NO_PROXY)

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = if (policy().usesSystemProxy(uri.host)) {
            systemProxy()?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
        } else listOf(Proxy.NO_PROXY)

        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) {
            if (policy().usesSystemProxy(uri.host)) systemProxy()?.connectFailed(uri, address, failure)
        }
    }
}
