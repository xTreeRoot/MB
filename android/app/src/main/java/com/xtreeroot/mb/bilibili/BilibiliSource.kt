package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.AccountSource
import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.DiscoverySection
import com.xtreeroot.mb.music.DiscoverySource
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.LibrarySource
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.PlaybackSource
import com.xtreeroot.mb.music.SignInPrompt
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.music.VideoSource
import com.xtreeroot.mb.music.AudioQuality
import java.io.IOException
import java.net.URI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class BilibiliSource(
    private val transport: BilibiliTransport = UrlConnectionTransport(),
    sessionStore: BilibiliSessionStore? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1_000 },
) : DiscoverySource, PlaybackSource, VideoSource, AccountSource, LibrarySource, InteractionSource {
    override val id = "bilibili"
    private val signingLock = Mutex()
    private val visitorLock = Mutex()
    private var key: Pair<Long, String>? = null
    private var visitor: String? = null
    internal val session = BilibiliAccount(transport, sessionStore)

    override suspend fun account() = session.account()
    override suspend fun signOut() = session.signOut()
    override suspend fun signIn(prompt: suspend (SignInPrompt) -> Unit): SourceAccount = session.signIn(prompt)
    override suspend fun library(route: LibraryRoute, cursor: String?) = loadLibrary(route, cursor)
    override suspend fun isLiked(trackId: String) = trackLiked(trackId)
    override suspend fun like(trackId: String, enabled: Boolean) = setTrackLiked(trackId, enabled)
    override suspend fun comments(trackId: String, cursor: Int?) = trackComments(trackId, cursor ?: 1)
    override suspend fun postComment(trackId: String, text: String) = sendTrackComment(trackId, text)

    override suspend fun search(query: String, page: Int): TrackPage {
        val term = query.trim()
        if (term.isEmpty()) return TrackPage(emptyList(), false)
        val index = page.coerceAtLeast(1)
        val result = fetch(
            "/x/web-interface/wbi/search/type",
            mapOf("keyword" to term, "search_type" to "video", "page" to index.toString(),
                "page_size" to "20", "order" to "totalrank"), signed = true,
        )
        return TrackPage(result.optJSONArray("result").objects().tracks(), index < result.optInt("numPages"))
    }

    override suspend fun home(): List<DiscoverySection> = coroutineScope {
        DiscoveryCategory.entries.map { category ->
            async {
                try {
                    val result = loadDiscovery(category, 1)
                    result.copy(tracks = result.tracks.take(6))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    DiscoverySection(category, error = failure.message ?: "此分区加载失败")
                }
            }
        }.awaitAll()
    }

    override suspend fun discovery(category: DiscoveryCategory) = loadDiscovery(category, 2)

    private suspend fun loadDiscovery(category: DiscoveryCategory, freshPages: Int): DiscoverySection = when (category) {
        DiscoveryCategory.Recommended -> DiscoverySection(category,
            fetch("/x/web-interface/wbi/index/top/feed/rcmd", signed = true)
                .optJSONArray("item").objects().filter { it.text("goto") == "av" }.tracks())
        DiscoveryCategory.Chart -> {
            val periods = fetch("/x/copyright-music-publicity/toplist/all_period", mapOf("list_type" to "1"))
                .optJSONObject("list")
            val latest = periods?.keys()?.asSequence()?.flatMap { periods.optJSONArray(it).objects() }
                ?.filter { it.optLong("ID") > 0 }?.maxByOrNull { it.optLong("ID") }
            if (latest == null) DiscoverySection(category) else {
                val songs = fetch("/x/copyright-music-publicity/toplist/music_list", mapOf("list_id" to latest.optLong("ID").toString()))
                DiscoverySection(category, songs.optJSONArray("list").objects().tracks(), latest.text("priod")?.toLongOrNull())
            }
        }
        DiscoveryCategory.Trending -> DiscoverySection(category,
            fetch("/x/web-interface/ranking/v2", mapOf("rid" to "1003", "type" to "all"))
                .optJSONArray("list").objects().tracks())
        DiscoveryCategory.Fresh -> {
            val headers = mapOf("Origin" to webOrigin, "Cookie" to visitorCookie())
            val archives = (1..freshPages).flatMap { page ->
                fetch("/x/web-interface/region/feed/rcmd", mapOf("display_id" to page.toString(),
                    "request_cnt" to "20", "from_region" to "1003"), headers = headers)
                    .optJSONArray("archives").objects()
            }
            DiscoverySection(category, archives.filter { it.text("goto") in listOf(null, "av") }.tracks())
        }
    }

    override suspend fun audio(trackId: String): MediaResource = audio(trackId, AudioQuality.High)

    override suspend fun audio(trackId: String, quality: AudioQuality): MediaResource {
        val dash = playbackInfo(trackId).optJSONObject("dash")
        val available = dash?.optJSONArray("audio").objects().mapNotNull { audio ->
            val url = (audio.text("base_url") ?: audio.text("baseUrl"))?.takeIf(::isHttps) ?: return@mapNotNull null
            val mime = audio.text("mime_type") ?: audio.text("mimeType")
            val suffix = when (mime) { "audio/flac", "audio/x-flac" -> "flac"; "audio/aac" -> "aac"; else -> "m4a" }
            AudioVariant(MediaResource(url, mediaHeaders, suffix), audio.optLong("bandwidth"))
        }
        val selected = when (quality) {
            AudioQuality.Standard -> available.filter { it.bandwidth > 0 }.minByOrNull(AudioVariant::bandwidth)
            AudioQuality.High -> available.firstOrNull()
            AudioQuality.Lossless -> available.firstOrNull { it.resource.fileExtension == "flac" }
        } ?: available.firstOrNull() ?: throw PlaybackIssueException(PlaybackIssue.SourceUnavailable, "暂无可用音频")
        return selected.resource
    }

    private data class AudioVariant(val resource: MediaResource, val bandwidth: Long)

    override suspend fun video(trackId: String): MediaResource {
        val available = playbackInfo(trackId).optJSONObject("dash")?.optJSONArray("video").objects()
            .mapNotNull { entry ->
                val url = (entry.text("base_url") ?: entry.text("baseUrl"))?.takeIf(::isHttps)
                    ?: return@mapNotNull null
                VideoVariant(url, entry.optInt("height"), entry.optLong("bandwidth"),
                    entry.optInt("codecid") == 7 || entry.optString("codecs").startsWith("avc1"))
            }
        val avc = available.filter(VideoVariant::avc)
        val selected = avc.ifEmpty { available }.minWithOrNull(
            compareBy<VideoVariant> { abs(it.height.toLong() - 720) }.thenBy(VideoVariant::bandwidth),
        ) ?: throw IOException("暂无可用视频")
        return MediaResource(selected.url, mediaHeaders)
    }

    private data class VideoVariant(val url: String, val height: Int, val bandwidth: Long, val avc: Boolean)

    private suspend fun playbackInfo(trackId: String): JSONObject {
        val detail = detail(trackId)
        return fetch("/x/player/wbi/playurl", mapOf(
            "bvid" to trackId, "cid" to detail.cid, "qn" to "80", "fnval" to "4048", "fnver" to "0",
            "fourk" to "1", "gaia_source" to "view-card", "from_client" to "BROWSER", "is_main_page" to "false",
            "need_fragment" to "false", "isGaiaAvoided" to "true", "voice_balance" to "1", "web_location" to "1315873",
        ), signed = true)
    }

    override suspend fun captions(trackId: String): List<CaptionLine> {
        val detail = detail(trackId)
        val result = fetch("/x/player/wbi/v2", mapOf("aid" to detail.aid, "cid" to detail.cid,
            "isGaiaAvoided" to "false", "web_location" to "1315873"), signed = true)
        val subtitles = result.optJSONObject("subtitle")?.optJSONArray("subtitles").objects()
        val selected = subtitles.firstOrNull { it.text("lan")?.startsWith("zh", ignoreCase = true) == true }
            ?: subtitles.firstOrNull()
        if (selected == null) {
            if (result.optBoolean("need_login_subtitle")) throw IOException("此视频的字幕需要登录后获取")
            return emptyList()
        }
        val raw = selected.getString("subtitle_url")
        val url = if (raw.startsWith("//")) "https:$raw" else raw
        if (!isHttps(url) || !URI(url).host.endsWith(".hdslb.com", ignoreCase = true)) throw invalidResponse()
        return transport.get(url, mediaHeaders).optJSONArray("body").objects().mapNotNull { line ->
            val start = line.optDouble("from") * 1_000
            val end = line.optDouble("to") * 1_000
            val content = line.text("content").orEmpty()
            if (start >= 0 && end >= start && end < Long.MAX_VALUE.toDouble() && content.isNotEmpty()) {
                CaptionLine(start.roundToLong(), end.roundToLong(), content)
            } else null
        }.sortedBy(CaptionLine::startMs)
    }

    internal data class Detail(val aid: String, val cid: String)

    internal suspend fun detail(trackId: String): Detail {
        require(bilibiliId.matches(trackId))
        val response = fetch("/x/web-interface/view", mapOf("bvid" to trackId))
        if (response.optLong("aid") <= 0 || response.optLong("cid") <= 0) throw invalidResponse()
        return Detail(response.optLong("aid").toString(), response.optLong("cid").toString())
    }

    private suspend fun signingKey(snapshot: BilibiliAccount.Snapshot): String = signingLock.withLock {
        key?.takeIf { it.first == snapshot.revision }?.second ?: run {
            val headers = withCookie(emptyMap(), snapshot.cookie)
            val result = transport.get("$apiOrigin/x/web-interface/nav", headers)
            session.checkRevision(snapshot.revision)
            val code = result.getInt("code")
            if (code !in setOf(0, -101)) throw apiError(code)
            val images = result.optJSONObject("data")?.optJSONObject("wbi_img") ?: throw invalidResponse()
            WbiSigner.key(images.getString("img_url"), images.getString("sub_url")).also { key = snapshot.revision to it }
        }
    }

    private suspend fun visitorCookie(): String = visitorLock.withLock {
        visitor ?: run {
            val result = fetch("/x/frontend/finger/spi")
            val first = result.text("b_3")?.takeIf { it.isNotBlank() } ?: throw invalidResponse()
            val second = WbiSigner.encode(result.text("b_4").orEmpty())
            "buvid3=$first; buvid4=$second; b_nut=${now()}".also { visitor = it }
        }
    }

    internal suspend fun fetch(
        path: String,
        parameters: Map<String, String> = emptyMap(),
        signed: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val snapshot = session.snapshot()
        val query = if (signed) WbiSigner.sign(parameters, signingKey(snapshot), now()) else WbiSigner.query(parameters)
        val url = "$apiOrigin$path" + if (query.isEmpty()) "" else "?$query"
        session.checkRevision(snapshot.revision)
        val envelope = transport.get(url, withCookie(headers, snapshot.cookie))
        session.checkRevision(snapshot.revision)
        return envelope.payload()
    }

    private fun withCookie(headers: Map<String, String>, cookie: String): Map<String, String> {
        val combined = listOf(headers["Cookie"].orEmpty(), cookie).filter(String::isNotEmpty).joinToString("; ")
        return if (combined.isEmpty()) mediaHeaders + headers else mediaHeaders + headers + ("Cookie" to combined)
    }

    internal suspend fun post(path: String, fields: Map<String, String>, signed: Boolean = false): JSONObject? {
        val snapshot = session.snapshot()
        val csrf = snapshot.cookie.split(';').map(String::trim).firstOrNull { it.startsWith("bili_jct=") }
            ?.substringAfter('=')?.takeIf(String::isNotEmpty) ?: throw IOException("请先登录哔哩哔哩")
        val query = if (signed) {
            fun token() = "ABCDEFGHIJK".toList().shuffled().take(2).joinToString("")
            "?" + WbiSigner.sign(mapOf("dm_img_list" to "[]", "dm_img_str" to token(), "dm_cover_img_str" to token(),
                "dm_img_inter" to "{\"ds\":[],\"wh\":[0,0,0],\"of\":[0,0,0]}"), signingKey(snapshot), now())
        } else ""
        val form = WbiSigner.query(fields + mapOf("csrf" to csrf, "csrf_token" to csrf))
        session.checkRevision(snapshot.revision)
        val response = transport.post("$apiOrigin$path$query", withCookie(emptyMap(), snapshot.cookie), form)
        session.checkRevision(snapshot.revision)
        return when (val code = response.getInt("code")) {
            0 -> response.optJSONObject("data")
            -101 -> { session.expire(snapshot.revision); throw IOException("登录已过期，请重新登录哔哩哔哩") }
            else -> throw apiError(code)
        }
    }

    private fun invalidResponse() = IOException("哔哩哔哩响应格式不符合预期")
    private fun apiError(code: Int) = IOException("哔哩哔哩接口返回错误（$code）")

    companion object {
        private const val apiOrigin = "https://api.bilibili.com"
        private const val webOrigin = "https://www.bilibili.com"
        internal val mediaHeaders = mapOf(
            "Referer" to "$webOrigin/",
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/134.0.0.0 Safari/537.36",
        )
    }
}
