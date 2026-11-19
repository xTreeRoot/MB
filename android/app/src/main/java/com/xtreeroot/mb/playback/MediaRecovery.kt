package com.xtreeroot.mb.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal enum class MediaRecovery { Reopen, RefreshAddress }

internal class MediaFetchFailure(val recovery: MediaRecovery? = null, status: Int? = null) :
    IOException(if (status == null) "Invalid media response" else "Invalid media response (HTTP $status)")

internal class MediaRecoveryBudget {
    private var reopened = 0
    private var refreshed = false

    fun delayFor(action: MediaRecovery): Long? = when (action) {
        MediaRecovery.RefreshAddress -> if (refreshed) null else 150L.also { refreshed = true }
        MediaRecovery.Reopen -> if (reopened == 2) null else (600L shl reopened++)
    }
}

@OptIn(UnstableApi::class)
internal class AudioRecovery(private val scope: CoroutineScope, private val player: Player,
                             private val invalidateAddress: (MediaItem?) -> Unit) {
    private var budget = MediaRecoveryBudget()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        budget = MediaRecoveryBudget()
    }

    fun onError(error: PlaybackException, item: MediaItem?, isSourceCurrent: () -> Boolean): Boolean {
        if (item == null || item !== player.currentMediaItem || !isSourceCurrent()) return false
        val action = mediaRecovery(error) ?: return false
        val wait = budget.delayFor(action) ?: return false
        job?.cancel()
        job = scope.launch {
            delay(wait)
            if (player.playerError === error && player.currentMediaItem === item && isSourceCurrent()) {
                if (action == MediaRecovery.RefreshAddress) invalidateAddress(item)
                player.prepare()
            }
        }
        return true
    }
}

@OptIn(UnstableApi::class)
private fun mediaCauses(error: Throwable) = generateSequence(error) { it.cause }.take(16)

@OptIn(UnstableApi::class)
internal fun mediaRecovery(error: Throwable): MediaRecovery? {
    val remote = mediaCauses(error).filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull() ?: return null
    val failure = mediaCauses(remote).filterIsInstance<MediaFetchFailure>().firstOrNull()
    if (failure != null) return failure.recovery
    return MediaRecovery.Reopen.takeIf {
        mediaCauses(remote).any { cause ->
            cause is SocketException || cause is SocketTimeoutException || cause is UnknownHostException || cause is EOFException ||
                (cause is ProtocolException && remote.type == HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun guardedMediaHttp(client: OkHttpClient): OkHttpDataSource = OkHttpDataSource.Factory(
    client.newBuilder().retryOnConnectionFailure(false).apply { interceptors().add(0, MediaRangeGuard()) }.build(),
).createDataSource()

@OptIn(UnstableApi::class)
internal fun ProgressiveMediaSource.Factory.recoverOutsideLoader() = setLoadErrorHandlingPolicy(
    object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long =
            if (mediaCauses(loadErrorInfo.exception).any { it is HttpDataSource.HttpDataSourceException }) C.TIME_UNSET
            else super.getRetryDelayMsFor(loadErrorInfo)
    },
)
