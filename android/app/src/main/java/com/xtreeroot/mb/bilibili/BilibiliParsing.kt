package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.Track
import java.net.URI
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.text(name: String): String? = opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()

internal fun JSONArray?.objects(): List<JSONObject> = this?.let { array ->
    (0 until array.length()).mapNotNull(array::optJSONObject)
}.orEmpty()

internal val bilibiliId = Regex("BV[0-9A-Za-z]+")

internal fun JSONObject.bilibiliTrack(
    identifier: String? = text("bvid") ?: text("creation_bvid"),
    owner: JSONObject? = optJSONObject("owner") ?: optJSONObject("author"),
    authorName: String? = text("author")?.takeUnless { owner != null } ?: text("creation_nickname"),
    authorId: String? = text("mid") ?: text("author_id"),
): Track? {
    val id = identifier?.takeIf(bilibiliId::matches) ?: return null
    val title = plainTitle(text("title") ?: text("creation_title") ?: "").ifBlank { id }
    return Track(
        sourceId = "bilibili",
        sourceTrackId = id,
        title = title,
        artist = owner?.text("name") ?: authorName.orEmpty(),
        artworkUrl = httpsCover(text("pic") ?: text("cover") ?: text("creation_cover")),
        durationMs = duration(opt("duration") ?: opt("creation_duration")),
        pageUrl = "https://www.bilibili.com/video/$id/",
        artistId = owner?.text("mid") ?: authorId,
        artworkHeaders = BilibiliSource.mediaHeaders,
    )
}

internal fun List<JSONObject>.tracks(): List<Track> = mapNotNull { it.bilibiliTrack() }.distinctBy(Track::id)

private val tag = Regex("<[^>]+>")
private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

private fun plainTitle(value: String): String = entity.replace(value.replace(tag, "")) { match ->
    val name = match.groupValues[1]
    when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> {
            val code = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
            if (code != null && Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else match.value
        }
    }
}.trim()

internal fun httpsCover(raw: String?): String? {
    val normal = when {
        raw == null -> return null
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("http://", ignoreCase = true) -> "https://${raw.substring(7)}"
        else -> raw
    }
    return normal.takeIf { isHttps(it) }
}

internal fun isHttps(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null
}.getOrDefault(false)

private fun duration(raw: Any?): Long {
    val seconds = when (raw) {
        is Number -> raw.toDouble()
        is String -> {
            val parts = raw.split(':').map { part -> part.toLongOrNull()?.takeIf { it >= 0 } ?: return 0 }
            parts.fold(0.0) { total, part -> total * 60 + part }
        }
        else -> return 0
    }
    return (seconds * 1_000).takeIf { it > 0 && it < Long.MAX_VALUE.toDouble() }?.roundToLong() ?: 0
}
