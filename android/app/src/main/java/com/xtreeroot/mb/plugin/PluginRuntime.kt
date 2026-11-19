package com.xtreeroot.mb.plugin

import android.content.res.AssetManager
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.TrackUnavailableReason
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.coroutineContext

internal class PluginRuntime(
    private val assets: AssetManager,
    private val directory: File,
    private val dataDirectory: File,
    private val manifest: PluginManifest,
    settings: Map<String, String> = emptyMap(),
    language: String = "zh-CN",
    private val transport: PluginTransport = PluginHttpTransport(),
    private val webSignIn: suspend (PluginWebSignIn) -> String = { throw PluginException("Web sign-in is unavailable") },
    private val openURL: suspend (PluginWebLink) -> Unit = { throw PluginException("Opening web links is unavailable") },
) {
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val calls = Mutex()
    private var host: PluginHostState? = null
    private var settings = settings.toMap()
    private var language = language
    private var machine: Machine? = null
    private var closed = false

    private class Machine(val context: QuickJsContext) {
        val events = mutableMapOf<Int, Job>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var signInRequests: MutableSet<Int>? = null
        var status = 0
        var failure: Exception? = null
    }

    suspend fun supports(name: String): Boolean = useMachine { it.context.supports(name) }

    suspend fun headersFor(address: URI, kind: String): Map<String, String> = withContext(dispatcher) {
        check(!closed) { "Plugin runtime is closed" }
        hostState().headers(address, manifest, kind)
    }

    suspend fun invoke(name: String, arguments: JSONObject = JSONObject(), timeoutMs: Long = 30_000): Any = useMachine { runtime ->
        invoke(runtime, name, arguments, timeoutMs)
    }

    suspend fun stream(arguments: JSONObject): Any = useMachine { runtime ->
        val response = invoke(runtime, "__mbAndroidStream", arguments, 30_000) as? JSONObject
            ?: throw PluginException("Invalid plugin stream response")
        val code = response.optString("code")
        val issue = TrackUnavailableReason.fromCode(code)?.issue ?: PlaybackIssue.Network.takeIf { code == "network" }
        if (issue != null) throw PlaybackIssueException(issue, response.optString("message").ifBlank { "Plugin stream unavailable" })
        response.opt("value") ?: JSONObject.NULL
    }

    suspend fun signIn(prompt: (PluginPrompt) -> Unit): Any = useMachine { runtime ->
        val requests = mutableSetOf<Int>()
        runtime.signInRequests = requests
        hostState().onPrompt = prompt
        try { invoke(runtime, "signIn", JSONObject(), 300_000) }
        finally {
            if (requests.isNotEmpty()) reset()
            runtime.signInRequests = null
            hostState().onPrompt = null
        }
    }

    private suspend fun invoke(runtime: Machine, name: String, arguments: JSONObject, timeoutMs: Long): Any = withTimeout(timeoutMs) {
        runtime.status = runtime.context.start(name, arguments.toString())
        finish(runtime)
    }

    suspend fun configure(settings: Map<String, String>, language: String) = withContext(dispatcher) {
        check(!closed) { "Plugin runtime is closed" }
        this@PluginRuntime.settings = settings.toMap()
        this@PluginRuntime.language = language
        host?.let {
            it.settings = settings.toMap()
            it.language = language
        }
    }

    suspend fun close() = withContext(NonCancellable + dispatcher) {
        calls.withLock {
            closed = true
            reset()
            host = null
            scope.cancel()
        }
    }

    private suspend fun <T> useMachine(operation: suspend (Machine) -> T): T = withContext(dispatcher) {
        calls.withLock {
            check(!closed) { "Plugin runtime is closed" }
            try {
                machine?.failure?.let { reset() }
                operation(machine ?: load())
            } catch (error: Throwable) {
                reset()
                throw error
            }
        }
    }

    private fun hostState(): PluginHostState = host ?: PluginHostState(directory, dataDirectory, settings, language).also {
        it.loadStorage()
        host = it
    }

    private suspend fun load(): Machine {
        val state = hostState()
        val runtime = Machine(QuickJsContext(state)).also { machine = it }
        val bootstrap = assets.open("PluginBootstrap.js").bufferedReader().use { it.readText() }
        val platform = "platform: \"ios\", arch: \"arm64\""
        check(bootstrap.indexOf(platform) >= 0 && bootstrap.indexOf(platform) == bootstrap.lastIndexOf(platform)) {
            "Unsupported plugin bootstrap"
        }
        val arch = when (System.getProperty("os.arch")) {
            "aarch64" -> "arm64"
            "armv7l", "armv8l", "arm" -> "arm"
            "amd64", "x86_64" -> "x64"
            "x86", "i686" -> "ia32"
            else -> "unknown"
        }
        withTimeout(10_000) {
            runtime.status = runtime.context.evaluate(bootstrap.replace(platform, "platform: \"android\", arch: \"$arch\""), "host.js", false, 100)
            finish(runtime)
            val entry = JSONObject.quote("./${manifest.entry}")
            val bridge = """
                import * as plugin from $entry;
                export * from $entry;
                const playbackCodes = ${JSONArray(TrackUnavailableReason.entries.map { it.code } + "network")};
                export async function __mbAndroidStream(args) {
                  const stream = plugin.stream;
                  if (typeof stream !== "function") return {code: "noSource"};
                  try { return {value: await stream(args)}; }
                  catch (error) {
                    const code = error?.code;
                    if (playbackCodes.includes(code)) {
                      return {code, message: typeof error.message === "string" ? error.message : ""};
                    }
                    throw error;
                  }
                }
            """.trimIndent()
            runtime.status = runtime.context.evaluate(bridge, ".mb-android-entry.js", true, 500)
            finish(runtime)
        }
        return runtime
    }

    private fun reset() {
        val runtime = machine ?: return
        machine = null
        runtime.events.values.forEach(Job::cancel)
        runtime.wake.close()
        runtime.context.close()
    }

    private suspend fun finish(runtime: Machine): Any {
        while (true) {
            coroutineContext.ensureActive()
            runtime.failure?.let { throw it }
            checkStatus(runtime)
            schedule(runtime)
            if (runtime.status > 0) return JSONTokener(runtime.context.result()).nextValue() ?: JSONObject.NULL
            runtime.wake.receive()
        }
    }

    private fun checkStatus(runtime: Machine) {
        if (runtime.status == -2) throw PluginException("Invalid plugin invocation input")
        if (runtime.status < 0) throw PluginException(runtime.context.result())
    }

    private fun schedule(runtime: Machine) {
        while (true) {
            val event = JSONObject(runtime.context.event() ?: return)
            val kind = event.optString("kind")
            if (kind == "cancel") {
                val cancelled = event.optInt("request")
                runtime.events.remove(cancelled)?.cancel()
                runtime.signInRequests?.remove(cancelled)
                continue
            }
            if (runtime.events.size >= 512) throw PluginException("Plugin host request limit exceeded")
            val id = event.getInt("id")
            if (id <= 0 || runtime.events.containsKey(id)) throw PluginException("Invalid plugin host request")
            val webAllowed = kind == "webSignIn" && runtime.signInRequests?.add(id) == true
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val (success, response) = try {
                    val value = when (kind) {
                        "timer" -> {
                            delay(event.optLong("delay").coerceIn(0, 300_000))
                            "null"
                        }
                        "fetch" -> transport.fetch(PluginFetchRequest.parse(event, manifest), manifest).toJson().toString()
                        "webSignIn" -> {
                            if (!webAllowed) throw PluginException("Web sign-in requires an active sign-in request")
                            val cookie = webSignIn(PluginWebSignIn.parse(event, manifest))
                            JSONObject().put("cookie", cookie).toString()
                        }
                        "openURL" -> {
                            val address = (event.opt("url") as? String)?.let(::uri)?.takeIf(URI::isHttp)
                                ?: throw PluginException("Not a web address")
                            if (!address.scheme.equals("https", true)) throw PluginException(
                                if (language == "en") "In-app web links require HTTPS" else "应用内网页链接需要使用 HTTPS")
                            openURL(PluginWebLink(manifest.name, address))
                            "null"
                        }
                        else -> throw PluginException("Host request is unavailable on Android")
                    }
                    true to value
                } catch (error: CancellationException) {
                    if (!coroutineContext[Job]!!.isActive) return@launch
                    false to JSONObject.quote("Plugin host request timed out")
                } catch (error: PluginException) {
                    false to JSONObject.quote(error.message)
                } catch (_: Exception) {
                    false to JSONObject.quote("Plugin host request failed")
                }
                if (machine !== runtime) return@launch
                runtime.events.remove(id)
                if (webAllowed) runtime.signInRequests?.remove(id)
                try {
                    runtime.status = runtime.context.settle(id, success, response)
                    checkStatus(runtime)
                    schedule(runtime)
                } catch (error: Exception) {
                    runtime.failure = error
                    runtime.events.values.forEach(Job::cancel)
                    runtime.events.clear()
                } finally {
                    runtime.wake.trySend(Unit)
                }
            }
            runtime.events[id] = job
            job.start()
        }
    }
}
