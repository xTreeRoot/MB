package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.LibraryLink
import com.xtreeroot.mb.music.LibraryPage
import com.xtreeroot.mb.music.LibraryRoute
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal suspend fun BilibiliSource.loadLibrary(route: LibraryRoute, cursor: String?): LibraryPage {
    val account = if (route is LibraryRoute.Creator) null else session.requireAccount()
    return when (route) {
        LibraryRoute.Favorites -> {
            val data = fetch("/x/v3/fav/folder/created/list-all", mapOf("up_mid" to checkNotNull(account).id))
            val folders = data.optJSONArray("list")
            if (folders == null && data.optInt("count", -1) != 0) throw invalidLibraryResponse()
            LibraryPage(links = folders.objects().mapNotNull { folder ->
                val id = folder.text("id")?.takeIf(::numericId) ?: return@mapNotNull null
                val info = try { fetch("/x/v3/fav/folder/info", mapOf("media_id" to id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val count = info?.optInt("media_count", -1)?.takeIf { it >= 0 } ?: folder.optInt("media_count")
                LibraryLink(id, info?.text("title") ?: folder.text("title") ?: id, "$count 个视频",
                    httpsCover(info?.text("cover") ?: folder.text("cover")), LibraryRoute.Folder(id))
            })
        }
        is LibraryRoute.Folder -> {
            require(numericId(route.id))
            val page = pageNumber(cursor)
            val data = fetch("/x/v3/fav/resource/list", mapOf("media_id" to route.id, "pn" to page.toString(),
                "ps" to "20", "keyword" to "", "order" to "mtime", "type" to "0", "tid" to "0", "platform" to "web"))
            val tracks = data.optJSONArray("medias").objects().mapNotNull { item ->
                item.bilibiliTrack(identifier = item.text("bvid") ?: item.text("bv_id"), owner = item.optJSONObject("upper"))
            }.distinctBy { it.id }
            val more = data.optBoolean("has_more") || data.optInt("has_more") == 1
            LibraryPage(title = data.optJSONObject("info")?.text("title"), tracks = tracks,
                nextCursor = (page + 1).toString().takeIf { more })
        }
        LibraryRoute.Liked -> LibraryPage(tracks = fetch("/x/space/like/video", mapOf("vmid" to checkNotNull(account).id))
            .getJSONArray("list").objects().tracks())
        LibraryRoute.History -> {
            val parameters = mapOf("type" to "all", "ps" to "20") + (cursor?.let(::decodeHistoryCursor).orEmpty())
            val data = fetch("/x/web-interface/history/cursor", parameters)
            val items = data.getJSONArray("list").objects()
            val tracks = items.mapNotNull { item ->
                val history = item.optJSONObject("history") ?: return@mapNotNull null
                if (history.text("business") !in listOf(null, "archive")) return@mapNotNull null
                item.bilibiliTrack(identifier = history.text("bvid"), authorName = item.text("author_name"),
                    authorId = item.text("author_mid"))
            }.distinctBy { it.id }
            val next = data.getJSONObject("cursor").takeIf { items.isNotEmpty() && it.optLong("max") != 0L }
                ?.let(::encodeHistoryCursor)
            LibraryPage(tracks = tracks, nextCursor = next?.takeUnless { it == cursor })
        }
        LibraryRoute.Following -> {
            val page = pageNumber(cursor)
            val data = fetch("/x/relation/followings", mapOf("vmid" to checkNotNull(account).id, "ps" to "20",
                "pn" to page.toString(), "order" to "desc", "order_type" to ""))
            val list = data.getJSONArray("list").objects()
            LibraryPage(links = list.mapNotNull { person ->
                val id = person.text("mid")?.takeIf(::numericId) ?: return@mapNotNull null
                LibraryLink(id, person.text("uname") ?: id, person.text("sign").orEmpty(), httpsCover(person.text("face")),
                    LibraryRoute.Creator(id))
            }, nextCursor = (page + 1).toString().takeIf { list.isNotEmpty() && page * 20 < data.optLong("total") })
        }
        is LibraryRoute.Creator -> {
            require(numericId(route.id))
            val page = pageNumber(cursor)
            val profile = fetch("/x/space/wbi/acc/info", mapOf("mid" to route.id), signed = true)
            val data = fetch("/x/space/wbi/arc/search", mapOf("mid" to route.id, "order" to "pubdate", "ps" to "25",
                "pn" to page.toString(), "index" to "1", "order_avoided" to "true", "platform" to "web", "web_location" to "333.1387",
                "dm_img_list" to "[]", "dm_img_str" to "V2ViR0wgMS4wIChPcGVuR0wgRVMgMi4wIENocm9taXVtKQ",
                "dm_cover_img_str" to "", "dm_img_inter" to "{}"), signed = true)
            val items = data.getJSONObject("list").getJSONArray("vlist").objects()
            LibraryPage(title = profile.text("name"), tracks = items.tracks(), nextCursor = (page + 1).toString()
                .takeIf { items.isNotEmpty() && page * 25 < data.getJSONObject("page").optLong("count") })
        }
    }
}

private fun numericId(value: String): Boolean = value.isNotEmpty() && value.all { it in '0'..'9' }

private fun pageNumber(cursor: String?): Int = (cursor ?: "1").toIntOrNull()?.takeIf { it in 1..1_000_000 }
    ?: throw invalidLibraryResponse()

private val cursorFields = listOf("max", "view_at", "business")

private fun encodeHistoryCursor(cursor: JSONObject): String {
    val canonical = JSONObject(cursorFields.associateWith { cursor.text(it) ?: throw invalidLibraryResponse() })
    return Base64.getEncoder().encodeToString(canonical.toString().toByteArray(Charsets.UTF_8))
}

private fun decodeHistoryCursor(cursor: String): Map<String, String> = runCatching {
    val decoded = JSONObject(String(Base64.getDecoder().decode(cursor), Charsets.UTF_8))
    cursorFields.associateWith(decoded::getString)
}.getOrElse { throw invalidLibraryResponse() }

private fun invalidLibraryResponse() = IOException("哔哩哔哩资料库响应格式不符合预期")
