package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.CommentPage
import com.xtreeroot.mb.music.TrackComment
import java.io.IOException
import org.json.JSONObject

internal suspend fun BilibiliSource.trackLiked(trackId: String): Boolean {
    session.requireAccount()
    return fetch("/x/web-interface/archive/relation", mapOf("aid" to detail(trackId).aid)).getBoolean("like")
}

internal suspend fun BilibiliSource.setTrackLiked(trackId: String, enabled: Boolean) {
    session.requireAccount()
    post("/x/web-interface/archive/like", mapOf("aid" to detail(trackId).aid, "like" to if (enabled) "1" else "2"))
}

internal suspend fun BilibiliSource.trackComments(trackId: String, page: Int): CommentPage {
    val index = page.coerceAtLeast(1)
    val data = fetch("/x/v2/reply", mapOf("type" to "1", "oid" to detail(trackId).aid,
        "pn" to index.toString(), "ps" to "20", "sort" to "2"))
    val replies = data.optJSONArray("replies").objects()
    val top = data.optJSONObject("top")
    val pinned = if (index == 1) listOfNotNull(top?.optJSONObject("upper"), top?.optJSONObject("admin")) else emptyList()
    val count = data.getJSONObject("page").getInt("count")
    return CommentPage((pinned + replies).mapNotNull(JSONObject::comment).distinctBy(TrackComment::id), count,
        (index + 1).takeIf { replies.isNotEmpty() && index * 20L < count })
}

internal suspend fun BilibiliSource.sendTrackComment(trackId: String, text: String): TrackComment {
    session.requireAccount()
    val message = text.trim().takeIf { it.isNotEmpty() } ?: throw IOException("评论不能为空")
    val result = post("/x/v2/reply/add", mapOf("oid" to detail(trackId).aid, "type" to "1", "message" to message,
        "plat" to "1", "statistics" to "{\"appId\":100,\"platform\":5}", "gaia_source" to "main_web"), signed = true)
    return result?.optJSONObject("reply")?.comment() ?: throw IOException("哔哩哔哩未返回评论内容")
}

private fun JSONObject.comment(): TrackComment? {
    val identifier = text("rpid_str") ?: text("rpid") ?: return null
    val content = optJSONObject("content")?.text("message") ?: return null
    val member = optJSONObject("member")
    return TrackComment(identifier, member?.text("uname").orEmpty(), httpsCover(member?.text("avatar")),
        content, optInt("like"), optLong("ctime") * 1_000)
}
