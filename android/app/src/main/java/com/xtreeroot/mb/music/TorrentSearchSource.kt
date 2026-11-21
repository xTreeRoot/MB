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
        if (query.trim().startsWith("magnet:", true)) {
            val track = directMagnet(query) ?: run {
                val hash = Regex("urn:btih:([^&]*)", RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)
                error(if (hash != null && hash.length !in listOf(32, 40)) "磁力链接不完整（哈希 ${hash.length} 位），请重新复制完整链接" else "磁力链接格式无效，请检查完整链接")
            }
            return TrackPage(if (page <= 1) listOf(track) else emptyList(), false)
        }
        val url = URI("https://api.knaben.org/v2/search?q=${URLEncoder.encode(query, "UTF-8")}&o=seeders&s=30&f=${(page - 1).coerceAtLeast(0) * 30}")
        return transport.receive(PluginFetchRequest(url, "GET", mapOf("User-Agent" to "MB/1"), null),
            { it.scheme == "https" && it.host == "api.knaben.org" }) { response, _ ->
            check(response.code == 200) { "磁力链搜索服务返回 HTTP ${response.code}" }
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
        /** 直接导入标准磁力链接；索引站未收录也能保存、分享或交给下载工具。 */
        fun directMagnet(input: String): Track? = runCatching {
            val value = input.trim()
            if (value.length > 16_384 || value.any { it.code < 32 } || !value.startsWith("magnet:?", true)) return null
            val parameters = value.substringAfter('?').split('&').map { part ->
                java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            val xt = parameters.filter { it.first.equals("xt", true) }.map { it.second }.firstOrNull {
                Regex("urn:btih:([A-Fa-f0-9]{40}|[A-Za-z2-7]{32})", RegexOption.IGNORE_CASE).matches(it) || Regex("urn:btmh:1220[A-Fa-f0-9]{64}", RegexOption.IGNORE_CASE).matches(it)
            } ?: return null
            val hash = xt.substringAfterLast(':')
            val name = parameters.firstOrNull { it.first == "dn" }?.second?.takeIf { it.isNotBlank() }?.take(500)
                ?: "磁力资源 · ${hash.take(12)}…"
            Track("knaben", "magnet:${hash.lowercase()}", name, "磁力链接", resourceType = "other", resourceUrl = value)
        }.getOrNull()

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
