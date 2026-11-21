package com.xtreeroot.mb.preferences

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyPolicyTest {
    @Test fun defaultGitHubAndGoogleRoutesConsultConfiguredProxyWithoutTouchingOtherHosts() {
        val consulted = mutableListOf<String>()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.fixture", 8080))
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { consulted += uri.host; return listOf(proxy) }
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }
        val routing = NetworkRouting({ selector }) { ProxyPolicy() }.proxySelector
        val targets = listOf("github.com/stars/owner/lists/plugin", "api.github.com/repos/owner/plugin/releases/latest",
            "codeload.github.com/owner/plugin/zip/main", "release-assets.githubusercontent.com/download/plugin",
            "objects.githubusercontent.com/download/plugin", "raw.githubusercontent.com/owner/plugin/main/manifest.json",
            "accounts.google.com/o/oauth2/v2/auth", "oauth2.googleapis.com/token", "youtube.googleapis.com/youtube/v3/channels")
        targets.forEach { assertEquals(listOf(proxy), routing.select(URI("https://$it"))) }
        assertEquals(listOf(Proxy.NO_PROXY), routing.select(URI("https://api.github.com.example/")))
        assertEquals(listOf(Proxy.NO_PROXY), routing.select(URI("https://drive.googleapis.com/")))
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

    @Test fun oldGoogleOAuthRulesAddOnlyTheAccountApiWithoutUndoingManualRemoval() {
        val old = AppPreferences.parse(JSONObject().put("githubProxyRulesApplied", true)
            .put("proxyHosts", "private.example oauth2.googleapis.com"))
        assertEquals(listOf("private.example", "oauth2.googleapis.com", "youtube.googleapis.com"), old.proxy.hosts)
        val edited = old.copy(proxy = ProxyPolicy.parse("private.example oauth2.googleapis.com"))
        assertEquals(edited, AppPreferences.parse(edited.toJson()))
    }

    @Test fun oldEmptyOrUnrelatedRulesDoNotGainGoogleRoutes() {
        assertTrue(AppPreferences.parse(JSONObject().put("proxyHosts", "")).proxy.hosts.isEmpty())
        val custom = AppPreferences.parse(JSONObject().put("githubProxyRulesApplied", true)
            .put("proxyHosts", "private.example accounts.google.com"))
        assertEquals(listOf("private.example", "accounts.google.com"), custom.proxy.hosts)
        assertFalse(custom.proxy.usesSystemProxy("youtube.googleapis.com"))
    }
}
