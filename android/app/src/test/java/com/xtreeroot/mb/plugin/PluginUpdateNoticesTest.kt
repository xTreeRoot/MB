package com.xtreeroot.mb.plugin

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginUpdateNoticesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun reusesPersistedNoticesUntilTheNextDayAndDropsReplacedOrRemovedRevisions() = runBlocking {
        val cache = folder.root.resolve("updates.json")
        val installed = plugin("player")
        var clock = 10_000L
        var calls = 0
        var tag = "v2.0"
        val latest: suspend (String) -> String = { calls++; tag }
        val first = PluginUpdateNotices(cache, latest) { clock }
        assertEquals(mapOf(installed.revision to "v2.0"), first.check(listOf(installed)))
        val restarted = PluginUpdateNotices(cache, latest) { clock }
        clock += 86_399_999L
        assertEquals(mapOf(installed.revision to "v2.0"), restarted.check(listOf(installed.copy(enabled = false))))
        assertTrue(restarted.check(listOf(installed.copy(revision = UUID.randomUUID(), repository = "other/player"))).isEmpty())
        assertTrue(restarted.check(emptyList()).isEmpty())
        assertEquals(1, calls)
        clock++
        tag = "v3.0"
        assertEquals(mapOf(installed.revision to "v3.0"), restarted.check(listOf(installed)))
        assertEquals(2, calls)
    }

    @Test fun checksDisabledRepositoriesAndCachesEmptyResultsWithoutContactingZipOnlyPlugins() = runBlocking {
        val cache = folder.root.resolve("updates.json")
        val missing = plugin("missing")
        val broken = plugin("broken")
        val unchanged = plugin("unchanged")
        val zip = plugin("zip").copy(repository = null)
        val checked = mutableListOf<String>()
        val notices = PluginUpdateNotices(cache, { repository ->
            checked += repository
            when (repository) {
                missing.repository -> null
                broken.repository -> error("fixture request failed")
                else -> "v1.0"
            }
        }) { 10_000L }
        assertTrue(notices.check(listOf(zip)).isEmpty())
        val installed = listOf(missing.copy(enabled = false), broken, unchanged, zip)
        repeat(2) { assertTrue(notices.check(installed).isEmpty()) }
        assertEquals(listOf(missing.repository, broken.repository, unchanged.repository), checked)
    }

    @Test fun concurrentForegroundChecksShareOneNetworkScan() = runBlocking {
        withTimeout(5_000) {
            val installed = plugin("player")
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val notices = PluginUpdateNotices(folder.root.resolve("updates.json"), {
                calls++
                started.complete(Unit)
                release.await()
                "v2.0"
            }) { 10_000L }
            val first = async { notices.check(listOf(installed)) }
            started.await()
            val second = async { notices.check(listOf(installed)) }
            release.complete(Unit)
            assertEquals(first.await(), second.await())
            assertEquals(1, calls)
        }
    }

    private fun plugin(name: String): InstalledPlugin = InstalledPlugin(
        PluginManifest.parse(JSONObject().put("id", name).put("version", "1.0")),
        UUID.randomUUID(), folder.root, folder.root, "example/$name", true,
    )
}
