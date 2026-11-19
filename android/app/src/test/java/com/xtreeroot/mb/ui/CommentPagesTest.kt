package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.CommentPage
import com.xtreeroot.mb.music.InteractionSource
import com.xtreeroot.mb.music.TrackComment
import com.xtreeroot.mb.music.TrackPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentPagesTest {
    @Test fun appendDeduplicatesAndBothFailuresRetryWithoutClearingRows() = runTest {
        val cursors = mutableListOf<Int?>()
        val model = CommentPages(source { track, cursor ->
            assertEquals("track", track)
            cursors += cursor
            when (cursors.size) {
                1 -> CommentPage(listOf(comment("first")), 3, 12)
                2 -> error("append failed")
                3 -> CommentPage(listOf(comment("first"), comment("second"), comment("second")), 3, 12)
                4 -> error("refresh failed")
                else -> CommentPage(listOf(comment("replacement")), 1, null)
            }
        }, "track")

        model.refresh()
        model.loadMore()
        assertEquals(listOf("first"), model.ids())
        assertTrue(model.state.value.appending)
        assertEquals("append failed", model.state.value.error)
        model.retry()
        assertEquals(listOf("first", "second"), model.ids())
        assertNull(model.state.value.page.nextCursor)

        model.refresh()
        assertEquals(listOf("first", "second"), model.ids())
        assertFalse(model.state.value.appending)
        assertEquals("refresh failed", model.state.value.error)
        model.retry()
        assertEquals(listOf("replacement"), model.ids())
        assertEquals(listOf(null, 12, 12, null, null), cursors)
        assertEquals(1, model.state.value.page.total)
        assertNull(model.state.value.error)
    }

    @Test fun postingAndLikingAreIdempotentForTheSameComment() = runTest {
        val model = CommentPages(source { _, _ -> CommentPage(listOf(comment("existing")), 7, null) }, "track")
        model.refresh()
        model.recordPosted(comment("existing"))
        model.recordPosted(comment("posted"))
        model.recordPosted(comment("posted"))
        assertEquals(listOf("posted", "existing"), model.ids())
        assertEquals(8, model.state.value.page.total)

        model.updateLike("posted", true)
        model.updateLike("posted", true)
        assertEquals(comment("posted").copy(likes = 1, liked = true), model.state.value.page.comments.first())
        model.updateLike("posted", false)
        model.updateLike("posted", false)
        assertEquals(comment("posted"), model.state.value.page.comments.first())
    }

    private fun CommentPages.ids() = state.value.page.comments.map(TrackComment::id)

    private fun comment(id: String) = TrackComment(id, "Author", null, id, 0)

    private fun source(fetch: suspend (String, Int?) -> CommentPage) = object : InteractionSource {
        override val id = "test"
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun isLiked(trackId: String) = false
        override suspend fun like(trackId: String, enabled: Boolean) = Unit
        override suspend fun comments(trackId: String, cursor: Int?) = fetch(trackId, cursor)
        override suspend fun postComment(trackId: String, text: String) = error("Not used")
    }
}
