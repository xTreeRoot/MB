package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.SuggestionSource
import com.xtreeroot.mb.music.TrackPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchSuggestionsTest {
    @Test fun rapidEditsOnlyRequestTheSettledRawQueryAndNormalizeOptions() = runTest {
        val calls = mutableListOf<String>()
        val plugin = source { query -> calls += query; listOf("z", " ", "a", "z") }
        val first = launch { searchSuggestions(plugin, "earlier") }
        advanceTimeBy(140)
        first.cancel()
        val final = async { searchSuggestions(plugin, " 夜曲 ") }
        advanceTimeBy(249)
        assertTrue(calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(" 夜曲 "), calls)
        assertEquals(listOf("a", "z"), final.await())
    }

    @Test fun losingFocusOrSwitchingSourceCannotPublishLateOptions() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val oldSource = source {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            listOf("old suggestion")
        }
        val oldRequest = launch { published += searchSuggestions(oldSource, "old") }
        started.await()
        oldRequest.cancel()
        assertEquals(listOf("current suggestion"), searchSuggestions(source { listOf("current suggestion") }, "current"))
        release.complete(Unit)
        oldRequest.join()
        assertTrue(published.isEmpty())
    }

    @Test fun noQuerySkipsPluginAndFailureLeavesSearchAvailable() = runTest {
        var calls = 0
        val plugin = source { calls++; error("fixture failed") }
        assertEquals(emptyList<String>(), searchSuggestions(plugin, " \n"))
        assertEquals(0, calls)
        assertEquals(emptyList<String>(), searchSuggestions(plugin, "normal search"))
        assertEquals(1, calls)
    }

    private fun source(load: suspend (String) -> List<String>) = object : SuggestionSource {
        override val id = "fixture"
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun suggestions(query: String) = load(query)
    }
}
