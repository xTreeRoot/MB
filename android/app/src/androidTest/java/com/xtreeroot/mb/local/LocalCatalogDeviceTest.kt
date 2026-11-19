package com.xtreeroot.mb.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.music.Track
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCatalogDeviceTest {
    @Test fun migratesV1AndDoesNotOverwriteAnExplicitUnlikeWhenRestoringOldActivity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "local-library-test-${UUID.randomUUID()}.db"
        val id = "a".repeat(64)
        val imported = Track(localSourceId, id, "Legacy Import", "")
        val downloaded = imported.copy(sourceId = "other")
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { database ->
                database.execSQL("""CREATE TABLE audio (
                    id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, duration INTEGER NOT NULL,
                    album TEXT NOT NULL, album_artist TEXT NOT NULL, year TEXT NOT NULL, disc INTEGER NOT NULL,
                    track_number INTEGER NOT NULL, artwork INTEGER NOT NULL, lyrics TEXT NOT NULL DEFAULT '',
                    added INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0
                )""")
                database.execSQL("INSERT INTO audio VALUES (?, ?, '', 0, '', '', '', 0, 0, 0, '', 100, 1, 200)",
                    arrayOf(id, imported.title))
                database.version = 1
            }
            LocalCatalog(context, databaseName).use { catalog ->
                val legacy = LocalTrackActivity(imported.id, 100, 200)
                assertEquals(legacy, catalog.activity().single())
                assertEquals(imported, catalog.list().single().track)
                catalog.toggleFavorite(imported.id)
                catalog.toggleFavorite(downloaded.id)
                catalog.recordPlay(downloaded.id, 500)
                catalog.recordPlay(downloaded.id, 400)
                catalog.restoreActivity(listOf(legacy))
                val activity = catalog.activity().associateBy(LocalTrackActivity::trackId)
                assertEquals(legacy.copy(likedAt = 0), activity[imported.id])
                assertEquals(500L, activity.getValue(downloaded.id).playedAt)
                assertTrue(activity.getValue(downloaded.id).likedAt > 0)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }
}
