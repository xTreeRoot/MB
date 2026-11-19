package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.SignInStatus
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliAccountTest {
    @Test
    fun refreshesExpiredCodeAndReusesValidatedCookieAfterSignOut() = runTest {
        val store = MemoryStore()
        val statuses = mutableListOf<SignInStatus>()
        var generated = 0
        var polled = 0
        val transport = transport { url, headers ->
            when (url.path.substringAfterLast('/')) {
                "generate" -> {
                    generated++
                    assertNull(headers["Cookie"])
                    response("""{"url":"https://account.bilibili.com/qr?key=$generated","qrcode_key":"key$generated"}""")
                }
                "poll" -> {
                    assertNull(headers["Cookie"])
                    assertEquals("qrcode_key=key$generated", url.query)
                    when (++polled) {
                        1 -> response("""{"code":86038}""")
                        2 -> response("""{"code":86090}""")
                        else -> response("""{"code":0}""", cookies)
                    }
                }
                "nav" -> {
                    assertEquals("DedeUserID=123; SESSDATA=fixture; bili_jct=csrf", headers["Cookie"])
                    response("""{"isLogin":true,"mid":123,"uname":"演示"}""")
                }
                else -> error(url.path)
            }
        }
        val account = BilibiliAccount(transport, store) {}
        assertEquals("123", account.signIn { statuses += it.status }.id)
        assertEquals(listOf(SignInStatus.Scan, SignInStatus.Refreshed, SignInStatus.Confirm), statuses)
        assertTrue(store.value.active)
        account.signOut()
        assertFalse(store.value.active)
        assertTrue(store.value.value.isNotBlank())
        assertNull(account.account())
        assertEquals("演示", account.signIn { error("不应重新扫码") }.name)
        assertEquals(2, generated)
    }

    @Test
    fun lateSuccessfulPollCannotReactivateSignedOutSession() = runTest {
        val enteredPoll = CompletableDeferred<Unit>()
        val releasePoll = CompletableDeferred<Unit>()
        val store = MemoryStore()
        val transport = transport { url, _ ->
            when (url.path.substringAfterLast('/')) {
                "generate" -> response("""{"url":"https://account.bilibili.com/qr","qrcode_key":"key"}""")
                "poll" -> { enteredPoll.complete(Unit); releasePoll.await(); response("""{"code":0}""", cookies) }
                else -> error("取消后的轮询不应再验证 Cookie")
            }
        }
        val account = BilibiliAccount(transport, store) {}
        val attempt = async { runCatching { account.signIn {} }.exceptionOrNull() }
        enteredPoll.await()
        account.signOut()
        releasePoll.complete(Unit)
        assertTrue(attempt.await() is CancellationException)
        assertFalse(store.value.active)
        assertTrue(store.value.value.isEmpty())
    }

    @Test
    fun validatesFallbackCookieHostAndDoesNotDoubleDecodePlus() {
        val params = "DedeUserID=123&SESSDATA=one%2Btwo+three&bili_jct=csrf&other=ignored"
        assertEquals("DedeUserID=123; SESSDATA=one+two+three; bili_jct=csrf",
            BilibiliAccount.loginCookie(emptyList(), "https://passport.biligame.com/crossDomain?$params"))
        assertThrows(IOException::class.java) {
            BilibiliAccount.loginCookie(emptyList(), "https://www.bilibili.com.example.invalid/?$params")
        }
        assertThrows(IOException::class.java) {
            BilibiliAccount.loginCookie(emptyList(), "https://www.bilibili.com/?DedeUserID=123&SESSDATA=one%0A&bili_jct=csrf")
        }
    }

    private class MemoryStore(var value: SessionCookie = SessionCookie()) : BilibiliSessionStore {
        override fun load() = value
        override fun save(value: SessionCookie) { this.value = value }
    }

    private fun transport(request: suspend (URI, Map<String, String>) -> BilibiliResponse): BilibiliTransport =
        object : BilibiliTransport {
            override suspend fun get(address: String, headers: Map<String, String>) = getResponse(address, headers).body
            override suspend fun getResponse(address: String, headers: Map<String, String>) = request(URI(address), headers)
        }

    private fun response(data: String, headers: List<String> = emptyList()) = BilibiliResponse(
        JSONObject("""{"code":0,"data":$data}"""), headers,
    )

    private companion object {
        val cookies = listOf("SESSDATA=fixture; HttpOnly", "DedeUserID=123; Secure", "bili_jct=csrf", "untrusted=ignored")
    }
}
