package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SignInStatus
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.SourceResponseFailure
import java.io.IOException
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class SessionCookie(val value: String = "", val active: Boolean = false)

internal interface BilibiliSessionStore {
    fun load(): SessionCookie
    fun save(value: SessionCookie)
}

internal class BilibiliAccount(
    private val transport: BilibiliTransport,
    private val store: BilibiliSessionStore?,
    private val pollDelay: suspend () -> Unit = { delay(2_000) },
) {
    private val lock = Any()
    private var revision = 0L
    private var signInGeneration = 0L
    private var stored: SessionCookie? = null
    private var accountSnapshot: SourceAccount? = null

    data class Snapshot(val revision: Long, val cookie: String)

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(revision, current().takeIf(SessionCookie::active)?.value.orEmpty())
    }

    suspend fun checkRevision(expected: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (expected != revision) throw IOException("登录状态已变化，请重试")
        }
    }

    suspend fun account(): SourceAccount? {
        val snapshot = snapshot()
        if (snapshot.cookie.isEmpty()) return null
        val account = validate(snapshot.cookie)
        checkRevision(snapshot.revision)
        synchronized(lock) {
            if (snapshot.revision != revision) throw CancellationException()
            if (account == null) setSession(SessionCookie(), null)
            else accountSnapshot = account
        }
        return account
    }

    suspend fun requireAccount(): SourceAccount = synchronized(lock) { accountSnapshot }
        ?: account() ?: throw IOException("请先登录哔哩哔哩")

    fun signOut() = synchronized(lock) { setSession(current().copy(active = false), null) }

    fun deactivateForPlayback(expected: Snapshot): Snapshot? = synchronized(lock) {
        if (expected.revision != revision || expected.cookie.isEmpty()) return null
        setSession(current().copy(active = false), null)
        Snapshot(revision, "")
    }

    fun expire(expected: Long) = synchronized(lock) {
        if (expected == revision) setSession(SessionCookie(), null)
    }

    suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount {
        val (attempt, previous) = synchronized(lock) { ++signInGeneration to current() }
        if (previous.value.isNotEmpty()) {
            val existing = validate(previous.value)
            if (existing != null) return activate(attempt, previous.value, existing)
        }
        checkAttempt(attempt)
        var code = newCode()
        checkAttempt(attempt)
        prompt(SignInPrompt(code.url, SignInStatus.Scan))
        repeat(140) {
            pollDelay()
            checkAttempt(attempt)
            val response = transport.getResponse("$passport/x/passport-login/web/qrcode/poll?" +
                WbiSigner.query(mapOf("qrcode_key" to code.key)), BilibiliSource.mediaHeaders)
            checkAttempt(attempt)
            val poll = response.body.payload()
            when (poll.getInt("code")) {
                86101 -> Unit
                86090 -> prompt(SignInPrompt(code.url, SignInStatus.Confirm))
                86038 -> {
                    code = newCode()
                    checkAttempt(attempt)
                    prompt(SignInPrompt(code.url, SignInStatus.Refreshed))
                }
                0 -> {
                    val cookie = loginCookie(response.setCookies, poll.text("url"))
                    val account = validate(cookie) ?: throw invalidLogin()
                    return activate(attempt, cookie, account)
                }
                else -> throw invalidLogin()
            }
        }
        throw invalidLogin()
    }

    private suspend fun activate(attempt: Long, value: String, account: SourceAccount): SourceAccount {
        checkAttempt(attempt)
        synchronized(lock) {
            if (attempt != signInGeneration) throw CancellationException()
            setSession(SessionCookie(value, active = true), account)
        }
        return account
    }

    private suspend fun validate(cookie: String): SourceAccount? {
        val response = transport.get("https://api.bilibili.com/x/web-interface/nav",
            BilibiliSource.mediaHeaders + ("Cookie" to cookie))
        if (response.getInt("code") == -101) return null
        val nav = response.payload()
        val userId = nav.optLong("mid").takeIf { it > 0 }?.toString() ?: return null
        if (!nav.optBoolean("isLogin")) return null
        return SourceAccount(userId, nav.text("uname") ?: userId, httpsCover(nav.text("face")))
    }

    private suspend fun newCode(): QrCode {
        val result = transport.get("$passport/x/passport-login/web/qrcode/generate", BilibiliSource.mediaHeaders).payload()
        val key = result.text("qrcode_key")?.takeIf { it.isNotBlank() } ?: throw invalidLogin()
        val url = result.text("url")?.takeIf(::isHttps) ?: throw invalidLogin()
        if (URI(url).host !in setOf("account.bilibili.com", "passport.bilibili.com")) throw invalidLogin()
        return QrCode(url, key)
    }

    private fun current(): SessionCookie = stored ?: (store?.load() ?: SessionCookie()).also { stored = it }

    private suspend fun checkAttempt(expected: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (expected != signInGeneration) throw CancellationException() }
    }

    private fun setSession(value: SessionCookie, account: SourceAccount?) {
        store?.save(value)
        stored = value
        accountSnapshot = account
        revision++
        signInGeneration++
    }

    private data class QrCode(val url: String, val key: String)

    companion object {
        private const val passport = "https://passport.bilibili.com"
        private val required = setOf("SESSDATA", "DedeUserID", "bili_jct")
        private val allowed = required + setOf("sid", "DedeUserID__ckMd5")

        fun loginCookie(headers: List<String>, callback: String?): String {
            val values = sortedMapOf<String, String>()
            fun save(key: String, value: String) {
                if (key in allowed && value.isNotBlank() && value.none { it == ';' || it.code <= 31 || it.code == 127 }) {
                    values.putIfAbsent(key, value)
                }
            }
            headers.forEach { header ->
                runCatching { HttpCookie.parse(header) }.getOrDefault(emptyList()).forEach { cookie ->
                    save(cookie.name, cookie.value)
                }
            }
            if (!values.keys.containsAll(required)) {
                val url = callback?.takeIf(::isHttps)?.let(::URI) ?: throw invalidLogin()
                if (url.host != "www.bilibili.com" && (url.host != "passport.biligame.com" || url.path != "/crossDomain")) {
                    throw invalidLogin()
                }
                url.rawQuery.orEmpty().split('&').forEach { entry ->
                    val valuesPair = entry.split('=', limit = 2)
                    if (valuesPair.size == 2) runCatching {
                        save(decode(valuesPair[0]), decode(valuesPair[1]))
                    }
                }
            }
            if (!values.keys.containsAll(required)) throw invalidLogin()
            return values.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }

        private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        private fun invalidLogin() = IOException("登录未成功或二维码已超时，请重新登录")
    }
}

internal fun JSONObject.payload(): JSONObject {
    val code = getInt("code")
    if (code != 0) throw apiError(code)
    return optJSONObject("data") ?: throw IOException("哔哩哔哩响应格式不符合预期")
}

internal fun apiError(code: Int) = SourceResponseFailure(SourceResponseFailure.Kind.API, code, "哔哩哔哩接口返回错误（$code）")
