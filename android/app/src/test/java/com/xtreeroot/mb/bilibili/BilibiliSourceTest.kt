package com.xtreeroot.mb.bilibili

import com.xtreeroot.mb.music.DiscoveryCategory
import com.xtreeroot.mb.music.AudioQuality
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliSourceTest {
    @Test fun choosesAvailableAudioQualityAndFallsBackWithoutInventingVariants() = runTest {
        var lossless = true
        val source = BilibiliSource(FixtureTransport { path, _, _ ->
            when (path) {
                "/x/web-interface/nav" -> navigation
                "/x/web-interface/view" -> """{"code":0,"data":{"aid":321,"cid":"456"}}"""
                "/x/player/wbi/playurl" -> {
                    val extra = if (lossless) """,{"baseUrl":"https://media.example/lossless","mimeType":"audio/flac","bandwidth":900}""" else ""
                    """{"code":0,"data":{"dash":{"audio":[
                        {"baseUrl":"http://media.example/insecure","bandwidth":50},
                        {"baseUrl":"https://media.example/high","bandwidth":300},
                        {"base_url":"https://media.example/unknown"},
                        {"base_url":"https://media.example/standard","bandwidth":100}$extra]}}}"""
                }
                else -> error(path)
            }
        })
        assertEquals("https://media.example/standard", source.audio("BVtrack", AudioQuality.Standard).url)
        assertEquals("https://media.example/high", source.audio("BVtrack", AudioQuality.High).url)
        assertEquals("flac", source.audio("BVtrack", AudioQuality.Lossless).fileExtension)
        lossless = false
        assertEquals("https://media.example/high", source.audio("BVtrack", AudioQuality.Lossless).url)
    }
    @Test
    fun signsKnownVectorAndEncodesUnicode() {
        val key = WbiSigner.key(image, sub)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee",
            WbiSigner.sign(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169))
        assertTrue(WbiSigner.sign(mapOf("keyword" to "中文 !'()*+&", "page" to "1"), key, 1)
            .startsWith("keyword=%E4%B8%AD%E6%96%87%20%2B%26&page=1&wts=1&w_rid="))
    }

    @Test
    fun searchUsesGuestWbiAndNormalizesDistinctTracks() = runTest {
        val transport = FixtureTransport { path, query, _ ->
            when (path) {
                "/x/web-interface/nav" -> navigation
                "/x/web-interface/wbi/search/type" -> {
                    assertTrue(query.contains("keyword=%E5%A4%9C%E6%9B%B2"))
                    assertTrue(query.contains("page=1&"))
                    assertTrue(query.substringAfter("w_rid=").length == 32)
                    """{"code":0,"data":{"numPages":2,"result":[$video,$video,{"bvid":"invalid"}]}}"""
                }
                else -> error(path)
            }
        }
        val page = BilibiliSource(transport) { 1 }.search(" 夜曲 ", 0)
        val track = page.tracks.single()
        assertTrue(page.hasMore)
        assertEquals("夜曲 & 现场", track.title)
        assertEquals("UP", track.artist)
        assertEquals("https://i0.hdslb.com/cover.jpg", track.artworkUrl)
        assertEquals(187_000L, track.durationMs)
    }

    @Test
    fun discoveryRetainsOtherSectionsWhenOneEndpointFails() = runTest {
        val transport = FixtureTransport { path, query, headers ->
            when (path) {
                "/x/web-interface/nav" -> navigation
                "/x/web-interface/wbi/index/top/feed/rcmd" -> """{"code":-412,"data":[]} """
                "/x/web-interface/ranking/v2" -> """{"code":0,"data":{"list":[$video]}}"""
                "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"one","b_4":"two+three"}}"""
                "/x/web-interface/region/feed/rcmd" -> {
                    assertEquals("buvid3=one; buvid4=two%2Bthree; b_nut=1", headers["Cookie"])
                    """{"code":0,"data":{"archives":[$video]}}"""
                }
                "/x/copyright-music-publicity/toplist/all_period" ->
                    """{"code":0,"data":{"list":{"year":[{"ID":"12"},{"ID":13,"priod":42}]}}}"""
                "/x/copyright-music-publicity/toplist/music_list" -> {
                    assertEquals("list_id=13", query)
                    """{"code":0,"data":{"list":[{"creation_bvid":"BVtrack","creation_title":"榜单"}]}}"""
                }
                else -> error(path)
            }
        }
        val source = BilibiliSource(transport) { 1 }
        val home = source.home().associateBy { it.category }
        assertTrue(home.getValue(DiscoveryCategory.Recommended).error.orEmpty().contains("-412"))
        assertEquals(42L, home.getValue(DiscoveryCategory.Chart).week)
        assertEquals("榜单", home.getValue(DiscoveryCategory.Chart).tracks.single().title)
        assertEquals(1, home.getValue(DiscoveryCategory.Trending).tracks.size)
        assertEquals(1, source.discovery(DiscoveryCategory.Fresh).tracks.size)
        assertEquals(1, transport.paths.count { it == "/x/frontend/finger/spi" })
        assertEquals(3, transport.paths.count { it == "/x/web-interface/region/feed/rcmd" })
    }

    @Test
    fun audioUsesRealContractAndCaptionsPreferChinese() = runTest {
        val transport = FixtureTransport { path, query, headers ->
            assertEquals("https://www.bilibili.com/", headers["Referer"])
            when (path) {
                "/x/web-interface/view" -> """{"code":0,"data":{"aid":321,"cid":"456"}}"""
                "/x/web-interface/nav" -> navigation
                "/x/player/wbi/playurl" -> {
                    assertTrue(query.contains("cid=456"))
                    assertTrue(query.contains("fnval=4048"))
                    """{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://media.example/audio.m4s"}]}}}"""
                }
                "/x/player/wbi/v2" -> {
                    assertTrue(query.contains("aid=321"))
                    """{"code":0,"data":{"subtitle":{"subtitles":[
                        {"lan":"en","subtitle_url":"//i0.hdslb.com/en.json"},
                        {"lan":"zh-CN","subtitle_url":"//i0.hdslb.com/zh.json"}]}}}"""
                }
                "/zh.json" -> """{"body":[{"from":1.125,"to":2.5,"content":"一"},{"from":3,"to":2,"content":"跳过"}]}"""
                else -> error(path)
            }
        }
        val source = BilibiliSource(transport) { 1 }
        assertEquals("https://media.example/audio.m4s", source.audio("BVtrack").url)
        val line = source.captions("BVtrack").single()
        assertEquals(1125L, line.startMs)
        assertEquals(2500L, line.endMs)
        assertEquals("一", line.text)
        assertFalse(transport.paths.contains("/en.json"))
    }

    @Test
    fun expiredAccountRetriesPublicPlaybackOnceWithoutSendingTheCookie() = runTest {
        val expired = "SESSDATA=expired-fixture"
        var saved = SessionCookie(expired, active = true)
        val store = object : BilibiliSessionStore {
            override fun load() = saved
            override fun save(value: SessionCookie) { saved = value }
        }
        val requestedCookies = mutableListOf<String?>()
        val source = BilibiliSource(FixtureTransport { path, _, headers ->
            when (path) {
                "/x/web-interface/view" -> """{"code":0,"data":{"aid":321,"cid":"456"}}"""
                "/x/web-interface/nav" -> navigation
                "/x/player/wbi/playurl" -> {
                    requestedCookies.add(headers["Cookie"])
                    if (headers["Cookie"] != null) """{"code":-101,"data":null}"""
                    else """{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://media.example/public.m4s"}]}}}"""
                }
                else -> error(path)
            }
        }, sessionStore = store)
        assertEquals("https://media.example/public.m4s", source.audio("BVtrack").url)
        assertEquals(listOf(expired, null), requestedCookies)
        assertEquals(SessionCookie(expired, active = false), saved)
    }

    @Test
    fun errorResponseKeepsPlatformCodeRatherThanParsingItsData() = runTest {
        val source = BilibiliSource(FixtureTransport { path, _, _ ->
            if (path == "/x/web-interface/nav") navigation else """{"code":-352,"data":[]} """
        })
        val failure = runCatching { source.search("夜曲", 1) }.exceptionOrNull()
        assertTrue(failure is IOException && failure.message.orEmpty().contains("-352"))
    }

    @Test
    fun videoPrefers720AvcAndRejectsInsecureVariants() = runTest {
        val source = BilibiliSource(FixtureTransport { path, _, _ ->
            when (path) {
                "/x/web-interface/view" -> """{"code":0,"data":{"aid":321,"cid":"456"}}"""
                "/x/web-interface/nav" -> navigation
                "/x/player/wbi/playurl" -> """{"code":0,"data":{"dash":{"video":[
                    {"height":720,"codecid":7,"base_url":"http://media.example/insecure"},
                    {"height":720,"codecid":12,"base_url":"https://media.example/hevc"},
                    {"height":1080,"codecid":7,"base_url":"https://media.example/1080"},
                    {"height":720,"codecs":"avc1.4d","bandwidth":700,"baseUrl":"https://media.example/720-avc"},
                    {"height":720,"codecid":7,"bandwidth":950,"base_url":"https://media.example/720-large"}
                ]}}}"""
                else -> error(path)
            }
        })
        assertEquals("https://media.example/720-avc", source.video("BVtrack").url)
    }

    private class FixtureTransport(
        val response: (String, String, Map<String, String>) -> String,
    ) : BilibiliTransport {
        val paths = mutableListOf<String>()
        override suspend fun get(address: String, headers: Map<String, String>): JSONObject {
            val uri = URI(address)
            paths.add(uri.path)
            return JSONObject(response(uri.path, uri.rawQuery.orEmpty(), headers))
        }
    }

    private companion object {
        const val image = "https://i.example/7cd084941338484aae1ad9425b84077c.png"
        const val sub = "https://i.example/4932caff0ff746eab6f01bf08b70ac45.png"
        const val navigation = """{"code":-101,"data":{"wbi_img":{"img_url":"$image","sub_url":"$sub"}}}"""
        const val video = """{"bvid":"BVtrack","title":"<em>夜曲</em> &amp; 现场","author":"UP","pic":"//i0.hdslb.com/cover.jpg","duration":"3:07"}"""
    }
}
