package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPagesTest {
    @Test fun searchScopesKeepIndependentTermsResultsAndRawCursors() = runTest {
        val cursors = mutableListOf<Int>()
        val music = SearchPages(this, 1) { _, query, cursor -> TrackPage(listOf(track(query)), false) to cursor + 1 }
        val videos = SearchPages(this, 0) { _, query, cursor ->
            cursors += cursor
            when (cursor) {
                0 -> TrackPage(emptyList(), true) to 2
                2 -> TrackPage(listOf(track(query), track(query)), true) to 5
                else -> TrackPage(listOf(track(query)), false) to 6
            }
        }
        music.submit("fixture", "song")
        videos.submit("fixture", "clip")
        advanceUntilIdle()
        videos.loadNext()
        advanceUntilIdle()
        videos.loadNext()
        advanceUntilIdle()
        assertEquals(listOf(0, 2, 5), cursors)
        assertEquals(listOf("song"), music.state.value.value.tracks.map(Track::title))
        assertEquals(listOf("clip"), videos.state.value.value.tracks.map(Track::title))
        assertFalse(videos.state.value.value.hasMore)
    }

    @Test fun cancelledOldRequestCannotReplaceNewSearch() = runTest {
        val release = CompletableDeferred<Unit>()
        val pages = SearchPages(this, 0) { _, query, cursor ->
            if (query == "old") withContext(NonCancellable) { release.await() }
            TrackPage(listOf(track(query)), false) to cursor + 1
        }
        pages.submit("fixture", "old")
        runCurrent()
        pages.submit("fixture", "new")
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("new", pages.state.value.value.tracks.single().title)
    }

    private fun track(title: String) = Track("fixture", title, title, "Artist")
}
