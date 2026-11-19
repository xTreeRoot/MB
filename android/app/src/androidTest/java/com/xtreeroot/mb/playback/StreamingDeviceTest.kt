package com.xtreeroot.mb.playback

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.StationPlaybackSource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.TrackPage
import com.xtreeroot.mb.music.VideoSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class StreamingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun exoplayerReadsAReusedPrefixAndContinuesIntoTheTail() = runBlocking {
        val wave = wave(40)
        val requests = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request.header("Range").orEmpty()
            media(request, wave)
        }.build()
        val resource = MediaResource("https://fixture.invalid/next.wav", emptyMap())
        val prepared = checkNotNull(primeAudio(client, resource))
        assertEquals(listOf("bytes=0-${audioPrefixBytes - 1}"), requests.toList())
        val guard = MediaRangeGuard().apply { offer(prepared.prefix) }
        val player = withContext(Dispatchers.Main) {
            player(client, DataSource.Factory { guardedMediaHttp(client, guard) }).apply {
                setMediaItem(MediaItem.fromUri(resource.url))
                prepare()
                play()
            }
        }
        try {
            eventually { player.isPlaying && player.currentPosition > 100 }
            withContext(Dispatchers.Main) {
                assertEquals(40_000L, player.duration)
                assertNull(player.playerError)
                player.seekTo(30_000)
            }
            eventually { player.isPlaying && player.currentPosition > 30_100 }
            assertEquals("bytes=$audioPrefixBytes-", requests[1])
            assertTrue(requests.drop(2).all { it.startsWith("bytes=") })
        } finally { withContext(Dispatchers.Main) { player.release() } }
    }

    @Test fun expiresAtNonzeroOffsetAndRestartsAtLatestPositionWithoutStartingPausedAudio() = runBlocking {
        val wave = wave(120)
        val revision = AtomicInteger(1)
        val rejectedOffset = AtomicInteger(-1)
        val renewedOffset = AtomicInteger(-1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val start = offset(request)
            when {
                start > 0 && request.url.encodedPath == "/v1" -> {
                    rejectedOffset.set(start)
                    response(request, 403, "<Error><Code>ExpiredToken</Code></Error>".toByteArray()).build()
                }
                else -> {
                    if (start > 0 && request.url.encodedPath == "/v2") renewedOffset.set(start)
                    media(request, wave)
                }
            }
        }.build()
        val factory = ResolvingDataSource.Factory({ guardedMediaHttp(client) }) { spec ->
            spec.withUri(Uri.parse("https://fixture.invalid/v${revision.get()}"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client, factory).apply {
                val recovery = AudioRecovery(scope, this) { revision.incrementAndGet() }
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        recovery.onError(error, currentMediaItem) { true }
                        seekTo(92_000)
                    }
                })
                setMediaItem(MediaItem.fromUri("https://fixture.invalid/track"))
                prepare()
            }
        }
        try {
            eventually { audio.playbackState == Player.STATE_READY }
            withContext(Dispatchers.Main) { audio.seekTo(84_000) }
            eventually { audio.playbackState == Player.STATE_READY && renewedOffset.get() > 0 }
            withContext(Dispatchers.Main) {
                assertEquals(2, revision.get())
                assertTrue(rejectedOffset.get() > 0)
                assertTrue(renewedOffset.get() > rejectedOffset.get())
                assertEquals(92_000L, audio.currentPosition)
                assertFalse(audio.playWhenReady)
                assertFalse(audio.isPlaying)
            }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun videoWaitsForAudioAndFirstFrameAndHonorsPauseAfterTwoSeeks() = runBlocking {
        val mp4 = instrumentation.context.assets.open("video.mp4").use { it.readBytes() }
        val wave = wave(12)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            media(request, if (request.url.encodedPath == "/video") mp4 else wave)
        }.build()
        val address = CompletableDeferred<MediaResource>()
        val track = Track("fixture", "track", "Streaming fixture", "", durationMs = 12_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String) = address.await()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build())
                prepare()
                play()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { AudioQuality.Standard })
        }
        val textures = SurfaceTexture(false)
        val output = Surface(textures)
        try {
            eventually { audio.isPlaying }
            val ticket = withContext(Dispatchers.Main) {
                val session = CaptionSessionPlayer(audio, coordinator) {}
                val show = checkNotNull(coordinator.show(track))
                assertTrue(session.playWhenReady)
                assertFalse(audio.isPlaying)
                session.seekTo(820)
                session.seekTo(310)
                session.pause()
                assertEquals(false, coordinator.heldIntent)
                address.complete(MediaResource("https://fixture.invalid/video", emptyMap(), "mp4"))
                show
            }
            eventually { coordinator.frames.value?.player != null }
            withContext(Dispatchers.Main) {
                val video = checkNotNull(coordinator.frames.value?.player)
                assertTrue(coordinator.frames.value!!.loading)
                assertEquals(false, coordinator.heldIntent)
                video.setVideoSurface(output)
            }
            eventually { coordinator.frames.value?.loading == false }
            withContext(Dispatchers.Main) {
                val video = checkNotNull(coordinator.frames.value?.player)
                assertNull(coordinator.heldIntent)
                assertEquals(310L, video.currentPosition)
                assertEquals(310L, audio.currentPosition)
                assertFalse(audio.playWhenReady)
                assertFalse(video.playWhenReady)
                ticket.close()
                assertNull(coordinator.frames.value)
                assertFalse(audio.isPlaying)
            }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
            output.release()
            textures.release()
        }
    }

    @Test fun failedVideoAddressDoesNotStopPlayingAudioOrReopenVideoOnSeek() = runBlocking {
        val wave = wave(20)
        val client = OkHttpClient.Builder().addInterceptor { chain -> media(chain.request(), wave) }.build()
        val releaseAddress = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val track = Track("fixture", "video-failed-address", "Fixture", "", durationMs = 20_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String): MediaResource {
                calls.incrementAndGet()
                releaseAddress.await()
                throw IOException("Fixture address failure")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build())
                prepare()
                play()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { AudioQuality.Standard })
        }
        try {
            eventually { audio.isPlaying }
            withContext(Dispatchers.Main) { checkNotNull(coordinator.show(track)) }
            eventually { calls.get() == 1 }
            val heldPosition = withContext(Dispatchers.Main) {
                assertFalse(audio.isPlaying)
                audio.currentPosition.also { releaseAddress.complete(Unit) }
            }
            eventually { coordinator.frames.value?.error != null }
            withContext(Dispatchers.Main) { assertNull(coordinator.heldIntent) }
            eventually { audio.isPlaying && audio.currentPosition > heldPosition + 100 }
            withContext(Dispatchers.Main) { audio.seekTo(4000) }
            eventually { audio.isPlaying && audio.currentPosition > 4100 }
            withContext(Dispatchers.Main) {
                assertEquals(track.id, audio.currentMediaItem?.mediaId)
                assertEquals(1, calls.get())
                assertTrue(coordinator.frames.value?.error != null)
                coordinator.retry()
            }
            eventually { calls.get() == 2 && coordinator.frames.value?.error != null && audio.isPlaying }
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun terminalVideoReadFailureKeepsPausedAudioAndAllowsPlayWithoutClosingVideo() = runBlocking {
        val wave = wave(20)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == "/video") response(request, 404, ByteArray(0)).build() else media(request, wave)
        }.build()
        val track = Track("fixture", "video-read-failure", "Fixture", "", durationMs = 20_000)
        val source = object : VideoSource {
            override val id = track.sourceId
            override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
            override suspend fun video(trackId: String) = MediaResource("https://fixture.invalid/video", emptyMap(), "mp4")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) {
            player(client).apply {
                setMediaItem(track.mediaItem().buildUpon().setUri("https://fixture.invalid/audio").build(), 1500)
                prepare()
            }
        }
        val coordinator = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { source }, { AudioQuality.Standard })
        }
        try {
            eventually { audio.playbackState == Player.STATE_READY }
            val session = withContext(Dispatchers.Main) {
                CaptionSessionPlayer(audio, coordinator) {}.also { checkNotNull(coordinator.show(track)) }
            }
            eventually { coordinator.frames.value?.error?.contains("HTTP 404") == true }
            withContext(Dispatchers.Main) {
                assertNull(coordinator.heldIntent)
                assertFalse(audio.playWhenReady)
                assertFalse(audio.isPlaying)
                assertEquals(1500L, audio.currentPosition)
                session.play()
            }
            eventually { audio.isPlaying && audio.currentPosition > 1600 }
            assertTrue(coordinator.frames.value?.error?.contains("HTTP 404") == true)
        } finally {
            withContext(Dispatchers.Main) { coordinator.close(); scope.cancel(); audio.release() }
        }
    }

    @Test fun stationAutomaticallyContinuesAfterTheTailEnds() = runBlocking {
        val first = Track("radio", "first", "First", "", durationMs = 1000)
        val next = first.copy(sourceTrackId = "second", title = "Second")
        val batch = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        try {
            withContext(Dispatchers.Main) {
                val source = stationProvider { id ->
                    assertEquals("daily", id)
                    if (calls.incrementAndGet() > 1) awaitCancellation()
                    batch.await()
                }
                val radio = StationPlayback(audio, scope, { source }, {})
                audio.setMediaItem(first.mediaItem())
                radio.set(PlaybackStation(first.sourceId, "daily", "Daily"), prefetch = true)
                audio.prepare()
                audio.play()
            }
            eventually { calls.get() == 1 && audio.playbackState == Player.STATE_ENDED }
            batch.complete(listOf(next))
            eventually { audio.currentMediaItem?.mediaId == next.id && audio.isPlaying }
            withContext(Dispatchers.Main) {
                assertEquals(2, audio.mediaItemCount)
                audio.pause()
            }
        } finally {
            withContext(Dispatchers.Main) { scope.cancel(); audio.release() }
        }
    }

    @Test fun stationStopsAfterThreeEmptyBatchesAndRejectsALateAppendAfterQueueReplacement() = runBlocking {
        val first = Track("radio", "first", "First", "")
        val other = first.copy(sourceTrackId = "regular", title = "Regular selection")
        val late = CompletableDeferred<List<Track>>()
        val calls = AtomicInteger()
        val client = stationFixture()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val audio = withContext(Dispatchers.Main) { player(client, stationMedia(client)) }
        val source = stationProvider { if (calls.incrementAndGet() <= 3) emptyList() else withContext(NonCancellable) { late.await() } }
        var status = StationStatus()
        val radio = withContext(Dispatchers.Main) {
            StationPlayback(audio, scope, { source }, { status = it }).apply {
                audio.setMediaItem(first.mediaItem())
                set(PlaybackStation(first.sourceId, "daily", "Daily"), prefetch = true)
            }
        }
        val video = withContext(Dispatchers.Main) {
            VideoPlayback(instrumentation.targetContext, client, audio, scope, { null }, { AudioQuality.Standard })
        }
        try {
            eventually { status.failure == StationFailure.Empty }
            withContext(Dispatchers.Main) { assertEquals(3, calls.get()); assertTrue(radio.next()) }
            eventually { calls.get() == 4 }
            withContext(Dispatchers.Main) {
                CaptionSessionPlayer(audio, video, radio) {}.setMediaItems(listOf(other.mediaItem()), 0, 0)
                assertNull(status.station)
            }
            late.complete(listOf(first))
            withContext(Dispatchers.Main) {
                delay(80)
                assertEquals(1, audio.mediaItemCount)
                assertEquals(other.id, audio.currentMediaItem?.mediaId)
            }
        } finally {
            late.complete(emptyList())
            withContext(Dispatchers.Main) { video.close(); scope.cancel(); audio.release() }
        }
    }

    private fun stationProvider(batch: suspend (String) -> List<Track>) = object : StationPlaybackSource {
        override val id = "radio"
        override suspend fun search(query: String, page: Int) = TrackPage(emptyList(), false)
        override suspend fun stationNext(id: String) = batch(id)
    }

    private fun stationFixture(): OkHttpClient {
        val initial = wave(1)
        val remaining = wave(12)
        return OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            media(request, if (request.url.encodedPath == "/first") initial else remaining)
        }.build()
    }

    private fun stationMedia(client: OkHttpClient) = ResolvingDataSource.Factory({ guardedMediaHttp(client) }) { spec ->
        spec.withUri(Uri.parse("https://fixture.invalid/${spec.uri.lastPathSegment}"))
    }

    private fun player(client: OkHttpClient, factory: DataSource.Factory = DataSource.Factory { guardedMediaHttp(client) }) =
        ExoPlayer.Builder(instrumentation.targetContext)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(300, 1000, 100, 100).build())
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(factory).recoverOutsideLoader()).build()

    private suspend fun eventually(condition: () -> Boolean) = withContext(Dispatchers.Main) {
        withTimeout(8_000) { while (!condition()) delay(20) }
    }

    private fun offset(request: Request) = request.header("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0

    private fun response(request: Request, status: Int, bytes: ByteArray) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(bytes.toResponseBody())

    private fun media(request: Request, bytes: ByteArray): Response {
        val range = request.header("Range") ?: return response(request, 200, bytes).header("ETag", "\"fixture\"").build()
        val start = offset(request)
        if (start >= bytes.size) return response(request, 416, byteArrayOf()).header("Content-Range", "bytes */${bytes.size}").build()
        val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(bytes.lastIndex) ?: bytes.lastIndex
        return response(request, 206, bytes.copyOfRange(start, end + 1)).header("ETag", "\"fixture\"")
            .header("Content-Range", "bytes $start-$end/${bytes.size}").build()
    }
}

internal fun wave(seconds: Int): ByteArray {
    val rate = 8_000
    val length = seconds * rate * 2
    return ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(length + 36).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(length).array()
}
