package com.xtreeroot.mb.local

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(UnstableApi::class)
internal suspend fun embeddedLyrics(context: Context, file: File): String? = withTimeoutOrNull(4_000) {
    try {
        val media = MediaItem.fromUri(Uri.fromFile(file))
        val factory = ProgressiveMediaSource.Factory(FileDataSource.Factory())
        MetadataRetriever.Builder(context, media).setMediaSourceFactory(factory).build().use { reader ->
            val groups = suspendCancellableCoroutine<TrackGroupArray> { continuation ->
                val future = reader.retrieveTrackGroups()
                continuation.invokeOnCancellation { future.cancel(false) }
                future.addListener({ continuation.resumeWith(runCatching { future.get() }) }, MoreExecutors.directExecutor())
            }
            (0 until groups.length).asSequence().flatMap { groupIndex ->
                val group = groups[groupIndex]
                (0 until group.length).asSequence().mapNotNull { group.getFormat(it).metadata }
            }.flatMap { metadata -> (0 until metadata.length()).asSequence().map(metadata::get) }
                .mapNotNull { entry ->
                    when (entry) {
                        is TextInformationFrame -> when {
                            entry.id == "USLT" -> entry.values.firstOrNull()
                            entry.id == "TXXX" && (entry.description.equals("USLT", true) ||
                                entry.description.equals("LYRICS", true)) -> entry.values.firstOrNull()
                            else -> null
                        }
                        is BinaryFrame -> if (entry.id == "USLT" || entry.id == "ULT") decodeId3Lyrics(entry.data) else null
                        else -> null
                    }
                }.map(String::trim).firstOrNull { it.isNotEmpty() && it.encodeToByteArray().size <= maximumLyricBytes }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
