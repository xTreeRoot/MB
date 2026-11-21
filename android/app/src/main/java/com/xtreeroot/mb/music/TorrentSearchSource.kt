package com.xtreeroot.mb.music

import com.xtreeroot.mb.plugin.PluginFetchRequest
import com.xtreeroot.mb.plugin.PluginHttpTransport
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/** Knaben v2 公开元数据合同：https://knaben.org/api/v2/；不下载或播放 BT 内容。 */
internal class TorrentSearchSource(private val transport: PluginHttpTransport) : MusicSource {
    override val id = "knaben"
    override suspend fun search(query: String, page: Int): TrackPage {
        val url = URI("https://api.knaben.org/v2/search?q=${URLEncoder.encode(query, "UTF-8")}&o=seeders&s=30&f=${(page - 1).coerceAtLeast(0) * 30}")
        return transport.receive(PluginFetchRequest(url, "GET", mapOf("User-Agent" to "MB/1"), null),
            { it.scheme == "https" && it.host == "api.knaben.org" }) { response, _ ->
            check(response.code == 200) { "种子搜索服务返回 HTTP ${response.code}" }
            val output = ByteArrayOutputStream()
            checkNotNull(response.body).byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 4 * 1024 * 1024) { "搜索响应过大" }
                    output.write(buffer, 0, count)
                }
            }
            decode(output.toString("UTF-8"), page)
        }
    }
    companion object {
        fun decode(text: String, page: Int): TrackPage {
            val response = JSONObject(text)
            val hits = response.getJSONArray("hits")
            val tracks = (0 until hits.length()).mapNotNull { index ->
                val hit = hits.getJSONObject(index)
                val resource = listOf("magnetUrl", "link").mapNotNull { key -> (hit.opt(key) as? String)?.takeIf(::resourceLink) }.firstOrNull()
                    ?: return@mapNotNull null
                val title = hit.getString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val category = hit.optString("category").lowercase()
                val type = when {
                    "audio" in category -> "music"
                    listOf("video", "movie", "tv", "anime").any { it in category } -> "video"
                    "book" in category -> "book"
                    listOf("pc", "console", "software").any { it in category } -> "software"
                    else -> "other"
                }
                val details = (hit.opt("details") as? String)?.takeIf { runCatching { URI(it).scheme == "https" && URI(it).host != null }.getOrDefault(false) }
                val info = listOfNotNull((hit.opt("tracker") as? String) ?: "Knaben",
                    (hit.opt("bytes") as? Number)?.let { "${it.toLong() / (1024 * 1024)} MB" },
                    (hit.opt("seeders") as? Number)?.let { "$it seeders" }).joinToString(" · ")
                Track("knaben", hit.getString("id"), title, info, pageUrl = details, resourceType = type, resourceUrl = resource)
            }
            return TrackPage(tracks, hits.length() > 0 && page * 30 < response.getJSONObject("total").getInt("value"))
        }
        private fun resourceLink(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host != null || uri.scheme == "magnet" && value.contains("xt=urn:btih:", true)
        }.getOrDefault(false)
    }
}
