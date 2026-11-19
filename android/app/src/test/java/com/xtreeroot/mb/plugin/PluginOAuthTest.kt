package com.xtreeroot.mb.plugin

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginOAuthTest {
    private val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["login.example.test"]}}"""))

    @Test fun authorizationAndTokenEndpointsRequireHttpsPermissionAndRegisteredCallback() {
        parse()
        val rejected = listOf(
            "authorizationEndpoint" to "http://login.example.test/auth",
            "authorizationEndpoint" to "https://login.example.test:8443/auth",
            "tokenEndpoint" to "https://elsewhere.test/token",
            "tokenEndpoint" to "https://user@login.example.test/token",
            "tokenEndpoint" to "https://login.example.test/token#fragment",
            "redirectURI" to "other.app:/oauth2redirect",
            "redirectURI" to "$pluginOAuthRedirect?extra=1",
            "clientID" to "two words",
            "scopes" to JSONArray(listOf("test", "test")),
            "scopes" to JSONArray(listOf("test", 12)),
        )
        for ((key, value) in rejected) assertThrows(PluginException::class.java) { parse { put(key, value) } }
    }

    @Test fun authorizationConsumesOnlyOneExactCallbackAndExchangesWithTheMatchingVerifier() = runBlocking {
        val broker = PluginOAuthBroker()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { broker.authorize(parse()) }
        val page = checkNotNull(broker.requests.first())
        assertFalse(broker.callback(uri("$pluginOAuthRedirect?state=fake&code=fake")))
        val authorization = checkNotNull(broker.begin(page.id)).toASCIIString().toHttpUrl()
        assertNull(broker.begin(page.id))
        assertEquals("code", authorization.queryParameter("response_type"))
        assertEquals("S256", authorization.queryParameter("code_challenge_method"))
        assertEquals(pluginOAuthRedirect, authorization.queryParameter("redirect_uri"))
        assertEquals("playlist.read profile", authorization.queryParameter("scope"))
        val state = checkNotNull(authorization.queryParameter("state"))
        fun callback(target: String) = broker.callback(uri(target))
        val valid = "$pluginOAuthRedirect?state=$state&code=code%2Btest%3D"
        assertFalse(callback(valid.replace("/oauth2redirect", "/other")))
        assertFalse(callback(valid.replace("/oauth2redirect", "/oauth%32redirect")))
        assertFalse(callback("$valid#fragment"))
        assertFalse(callback("$valid&%73tate=$state"))
        assertFalse(callback("$valid&code=extra"))
        assertFalse(callback("$valid&error=access_denied"))
        assertFalse(callback(valid.replace(state, "wrong")))
        assertFalse(pending.isCompleted)
        assertTrue(callback(valid))
        assertFalse(callback(valid))
        val exchange = pending.await()
        assertEquals("https://login.example.test/token", exchange.url.toASCIIString())
        assertEquals("POST", exchange.method)
        assertEquals(PluginRedirectMode.Error, exchange.redirectMode)
        assertEquals(oauthResponseLimit, exchange.maximumResponseBytes)
        val fields = checkNotNull(exchange.body).split('&').associate { pair ->
            pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
        }
        assertEquals("code+test=", fields["code"])
        assertEquals("client", fields["client_id"])
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals(pluginOAuthRedirect, fields["redirect_uri"])
        assertFalse(fields.containsKey("state"))
        val verifier = checkNotNull(fields["code_verifier"])
        assertEquals(43, verifier.length)
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray())
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(hash), authorization.queryParameter("code_challenge"))
        assertNull(broker.requests.first())
    }

    @Test fun cancellationInvalidatesStateAndProviderDenialDoesNotLeakProviderText() = runBlocking {
        val broker = PluginOAuthBroker()
        val old = async(start = CoroutineStart.UNDISPATCHED) { broker.authorize(parse()) }
        suspend fun authorizationState(): String {
            val page = checkNotNull(broker.requests.first())
            return checkNotNull(checkNotNull(broker.begin(page.id)).toASCIIString().toHttpUrl().queryParameter("state"))
        }
        val stale = authorizationState()
        old.cancelAndJoin()
        assertNull(broker.requests.first())
        val active = async(start = CoroutineStart.UNDISPATCHED) { runCatching { broker.authorize(parse()) } }
        val state = authorizationState()
        assertNotEquals(stale, state)
        assertFalse(broker.callback(uri("$pluginOAuthRedirect?state=$stale&code=late")))
        assertTrue(broker.callback(uri("$pluginOAuthRedirect?state=$state&error=access_denied&error_description=irrelevant")))
        assertEquals("OAuth authorization did not complete", active.await().exceptionOrNull()?.message)
    }

    @Test fun tokenResponseRequiresSuccessfulBoundedBearerJson() {
        fun response(body: String, status: Int = 200) = PluginFetchResponse(status, uri("https://login.example.test/token"),
            emptyMap(), emptyList(), body.encodeToByteArray())
        val valid = """{"access_token":"fixture-access","token_type":"Bearer","refresh_token":"fixture-refresh"}"""
        assertEquals("fixture-refresh", oauthToken(response(valid)).getString("refresh_token"))
        val invalid = listOf(response(valid, 302), response(valid + " ".repeat(oauthResponseLimit)), response("not-json"),
            response("""{"access_token":7,"token_type":"Bearer"}"""), response("""{"access_token":"bad token","token_type":"Bearer"}"""),
            response("""{"access_token":"fixture","token_type":"Basic"}"""))
        invalid.forEach { value -> assertThrows(PluginException::class.java) { oauthToken(value) } }
    }

    private fun parse(changes: JSONObject.() -> Unit = {}) = PluginOAuthRequest.parse(JSONObject().put("options", JSONObject()
        .put("authorizationEndpoint", "https://login.example.test/authorize?response_type=token")
        .put("tokenEndpoint", "https://login.example.test/token").put("clientID", "client")
        .put("redirectURI", pluginOAuthRedirect).put("scopes", JSONArray(listOf("playlist.read", "profile"))).apply(changes)), manifest)
}
