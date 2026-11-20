package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.cache.DiskResponseCache
import com.xtreeroot.mb.music.LibraryRoute
import com.xtreeroot.mb.music.toCacheJson
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BilibiliLibraryCacheTest {
    @Test fun personalRowsReuseDiskAcrossReopenAndSeparateAccountsAndCursors() = runBlocking {
        val directory = Files.createTempDirectory("mb-bilibili-cache").toFile()
        var pageRequests = 0
        var activeUser = "1"
        val transport = object : BilibiliTransport {
            override suspend fun get(address: String, headers: Map<String, String>): JSONObject = when (URI(address).path) {
                "/x/web-interface/nav" -> JSONObject("""{"code":0,"data":{"mid":$activeUser,"uname":"User $activeUser","isLogin":true}}""")
                "/x/v3/fav/resource/list" -> {
                    pageRequests++
                    JSONObject("""{"code":0,"data":{"info":{"title":"User $activeUser"},"medias":[
                        {"bvid":"BVfixture","title":"歌 $activeUser","cover":"https://image.example/$activeUser","duration":10}],"has_more":true}}""")
                }
                else -> error("Unexpected fixture path")
            }
        }
        val store = object : BilibiliSessionStore {
            override fun load() = SessionCookie("SESSDATA=fixture", true)
            override fun save(value: SessionCookie) = Unit
        }
        fun source() = BilibiliSource(transport, store, libraryCache = DiskResponseCache(directory))
        try {
            val first = source()
            val page = first.library(LibraryRoute.Folder("12"), null)
            assertEquals("https://image.example/1", page.tracks.single().artworkUrl)
            assertEquals("2", page.nextCursor)
            assertEquals(page, first.library(LibraryRoute.Folder("12"), null))
            assertEquals(page, source().library(LibraryRoute.Folder("12"), null))
            assertEquals(1, pageRequests)
            first.library(LibraryRoute.Folder("12"), "2")
            assertEquals(2, pageRequests)
            first.invalidateLibraryCache()
            first.library(LibraryRoute.Folder("12"), null)
            assertEquals(3, pageRequests)
            activeUser = "2"
            assertEquals("User 2", source().library(LibraryRoute.Folder("12"), null).title)
            assertEquals(4, pageRequests)
        } finally { directory.deleteRecursively() }
    }

    @Test fun accountChangeDuringRequestCannotPersistLateRows() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = DiskResponseCache()
        val transport = object : BilibiliTransport {
            override suspend fun get(address: String, headers: Map<String, String>): JSONObject = when (URI(address).path) {
                "/x/web-interface/nav" -> JSONObject("""{"code":0,"data":{"mid":1,"uname":"User","isLogin":true}}""")
                "/x/space/like/video" -> {
                    entered.complete(Unit)
                    release.await()
                    JSONObject("""{"code":0,"data":{"list":[]}}""")
                }
                else -> error("Unexpected fixture path")
            }
        }
        val store = object : BilibiliSessionStore {
            override fun load() = SessionCookie("SESSDATA=fixture", true)
            override fun save(value: SessionCookie) = Unit
        }
        val source = BilibiliSource(transport, store, libraryCache = cache)
        val pending = async { runCatching { source.library(LibraryRoute.Liked, null) }.exceptionOrNull() }
        entered.await()
        source.signOut()
        release.complete(Unit)
        assertNotNull(pending.await())
        val key = DiskResponseCache.key(source.id, "user:1", LibraryRoute.Liked.toCacheJson().toString(), "")
        assertEquals("new", cache.data(key) { "new".toByteArray() }.decodeToString())
    }
}
