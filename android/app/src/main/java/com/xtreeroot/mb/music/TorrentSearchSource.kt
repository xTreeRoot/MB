package com.xtreeroot.mb.music


/** 本地识别磁力链；关键词查询仅委托已安装的资源搜索插件。 */
internal class TorrentSearchSource(private val provider: (suspend (String, Int) -> TrackPage)? = null) : MusicSource {
    override val id = "magnet"
    override suspend fun search(query: String, page: Int): TrackPage {
        if (query.trim().startsWith("magnet:", true)) {
            val track = directMagnet(query) ?: run {
                val hash = Regex("urn:btih:([^&]*)", RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)
                error(if (hash != null && hash.length !in listOf(32, 40)) "磁力链接不完整（哈希 ${hash.length} 位），请重新复制完整链接" else "磁力链接格式无效，请检查完整链接")
            }
            return TrackPage(if (page <= 1) listOf(track) else emptyList(), false)
        }
        return provider?.invoke(query, page) ?: error("请先安装并启用磁力链搜索插件")
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
            Track("magnet", "magnet:${hash.lowercase()}", name, "磁力链接", resourceType = "other", resourceUrl = value)
        }.getOrNull()

    }
}
