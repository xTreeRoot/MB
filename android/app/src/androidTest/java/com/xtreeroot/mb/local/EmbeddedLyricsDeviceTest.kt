package com.xtreeroot.mb.local

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedLyricsDeviceTest {
    @Test fun extractsTimedLyricsFromMp4AndId3UsingTheStagedFile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (asset in listOf("itunes.m4a.b64", "id3.mp3.b64")) {
            val staged = File.createTempFile("lyrics-test-", ".part", context.cacheDir)
            try {
                val encoded = instrumentation.context.assets.open("lyrics/$asset").use { it.readBytes() }
                staged.writeBytes(Base64.decode(encoded, Base64.DEFAULT))
                val line = parseLrc(checkNotNull(embeddedLyrics(context, staged)) { "Missing lyrics in $asset" }).single()
                assertEquals(asset, "Embedded line", line.text)
                assertEquals(asset, 100L, line.startMs)
            } finally { staged.delete() }
        }
    }
}
