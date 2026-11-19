package com.xtreeroot.mb.preferences

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPolicyTest {
    @Test fun githubPagesApisAndArchiveRedirectsConsultConfiguredProxyWithoutTouchingOtherHosts() {
        val consulted = mutableListOf<String>()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.fixture", 8080))
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { consulted += uri.host; return listOf(proxy) }
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }
        val routing = NetworkRouting({ selector }) { ProxyPolicy() }.proxySelector
        val targets = listOf("github.com/stars/owner/lists/plugin", "api.github.com/repos/owner/plugin/releases/latest",
            "codeload.github.com/owner/plugin/zip/main", "release-assets.githubusercontent.com/download/plugin",
            "objects.githubusercontent.com/download/plugin")
        targets.forEach { assertEquals(listOf(proxy), routing.select(URI("https://$it"))) }
        assertEquals(listOf(Proxy.NO_PROXY), routing.select(URI("https://api.github.com.example/")))
        assertEquals(targets.map { it.substringBefore('/') }, consulted)
        val direct = NetworkRouting({ null }) { ProxyPolicy() }.proxySelector
        targets.forEach { assertEquals(listOf(Proxy.NO_PROXY), direct.select(URI("https://$it"))) }
    }

    @Test fun oldCustomRulesGetGithubDefaultsWithoutUndoingSubsequentEdits() {
        val old = AppPreferences.parse(JSONObject().put("proxyHosts", "private.example"))
        assertTrue(old.proxy.usesSystemProxy("private.example"))
        assertTrue(ProxyPolicy.githubHosts.all(old.proxy::usesSystemProxy))
        val edited = old.copy(proxy = ProxyPolicy.parse("private.example github.com"))
        assertEquals(edited, AppPreferences.parse(edited.toJson()))
    }
}
