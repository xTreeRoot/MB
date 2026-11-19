package com.xtreeroot.mb.plugin

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginWebSignInTest {
    private val manifest = PluginManifest.parse(JSONObject("""{"id":"fixture","permissions":{"network":["login.example.test"]}}"""))

    @Test fun bothTargetsRequireAuthorizedHttpsAndValidCookieNames() {
        val request = parse()
        assertEquals("login.example.test", request.cookiesOf.host)
        assertNull(request.completedCookies("session=; verified=yes"))
        assertNull(request.completedCookies("session=abc; verification=yes"))
        val header = "session=abc==; verified=yes; other=123"
        assertEquals(header, request.completedCookies(header))
        assertThrows(PluginException::class.java) { parse(url = "http://login.example.test/") }
        assertThrows(PluginException::class.java) { parse(target = "https://outside.example.test/") }
        assertThrows(PluginException::class.java) { parse(url = "https://someone@login.example.test/") }
        assertThrows(PluginException::class.java) { parse(names = "[\"session=\"]") }
    }

    @Test fun cancellationAndRequestIdentityRejectLateOrIncompleteHeaders() = runBlocking {
        val broker = PluginWebSignInBroker()
        val first = parse()
        val second = parse()
        val old = async(start = CoroutineStart.UNDISPATCHED) { broker.show(first) }
        assertEquals(first, broker.requests.first())
        assertEquals("Another sign-in page is already open", runCatching { broker.show(second) }.exceptionOrNull()?.message)
        old.cancelAndJoin()
        assertNull(broker.requests.first())
        val active = async(start = CoroutineStart.UNDISPATCHED) { broker.show(second) }
        broker.finish(first.id, "session=late; verified=yes")
        broker.finish(second.id, "session=missing")
        assertFalse(active.isCompleted)
        val header = "session=fixture; verified=yes"
        broker.finish(second.id, header)
        assertEquals(header, withTimeout(1_000) { active.await() })
        assertNull(broker.requests.first())
    }

    private fun parse(url: String = "https://login.example.test/login", target: String = "https://login.example.test/account",
                      names: String = "[\"session\",\"verified\"]") = PluginWebSignIn.parse(JSONObject(
        """{"options":{"url":"$url","cookiesOf":"$target","until":$names}}"""), manifest)
}
