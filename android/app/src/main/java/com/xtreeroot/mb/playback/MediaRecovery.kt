package com.xtreeroot.mb.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.SourceResponseFailure
import com.xtreeroot.mb.music.isMediaAddressUnavailable
import com.xtreeroot.mb.music.isMediaConnectionFailure
import com.xtreeroot.mb.music.maxMediaBackupAddresses
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.internal.http2.ErrorCode
import okhttp3.internal.http2.StreamResetException

internal enum class MediaRecovery { Reopen, RefreshAddress }

internal class MediaFetchFailure(val recovery: MediaRecovery? = null, val status: Int? = null, val html: Boolean = false) :
    IOException(if (status == null) "Invalid media response" else "Invalid media response (HTTP $status)")

@OptIn(UnstableApi::class)
internal fun Throwable.playbackDiagnostic(code: Int): String {
    val name = PlaybackException.getErrorCodeName(code)
    val detail = mediaCauses(this).mapNotNull { failure ->
        when (failure) {
            is MediaFetchFailure -> failure.status?.let { if (failure.html) "HTTP $it / HTML" else "HTTP $it" }
            is HttpDataSource.InvalidResponseCodeException -> "HTTP ${failure.responseCode}"
            is StreamResetException -> "HTTP/2 ${failure.errorCode.name}"
            is SourceResponseFailure -> "${failure.kind.name} ${failure.code}"
            is UnknownHostException -> "DNS"
            is SSLException -> "TLS"
            is SocketTimeoutException -> "TIMEOUT"
            is ConnectException, is NoRouteToHostException -> "CONNECT"
            else -> null
        }
    }.firstOrNull()
    return if (detail == null) name else "$name / $detail"
}

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

    fun onError(error: PlaybackException, item: MediaItem?, usedPreload: Boolean = false, isSourceCurrent: () -> Boolean): Boolean {
        if (item == null || item !== player.currentMediaItem || !isSourceCurrent()) return false
        val action = if (usedPreload) MediaRecovery.RefreshAddress else mediaRecovery(error) ?: return false
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

private fun Throwable.isRecoverableMediaConnectionFailure(): Boolean = isMediaConnectionFailure() ||
    this is StreamResetException && (errorCode == ErrorCode.CANCEL || errorCode == ErrorCode.REFUSED_STREAM)

@OptIn(UnstableApi::class)
internal class MediaAddressDataSource(private val upstream: DataSource) : DataSource by upstream {
    var backupUrls: List<String> = emptyList()
    private var openedAddress: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        if (backupUrls.isEmpty()) return upstream.open(dataSpec)
        openedAddress?.let { return upstream.open(dataSpec.withUri(it)) }
        val addresses = listOf(dataSpec.uri) + backupUrls.take(maxMediaBackupAddresses).map(Uri::parse)
        for ((index, address) in addresses.withIndex()) {
            try {
                val length = upstream.open(dataSpec.withUri(address))
                openedAddress = address
                return length
            } catch (failure: IOException) {
                if (index == addresses.lastIndex || Thread.currentThread().isInterrupted || !canTryBackup(failure)) throw failure
                upstream.close()
            }
        }
        error("Media request has no address")
    }

    private fun canTryBackup(error: IOException): Boolean = mediaCauses(error).any { cause ->
        when (cause) {
            is MediaFetchFailure -> cause.html || cause.status?.isMediaAddressUnavailable() == true
            else -> cause.isRecoverableMediaConnectionFailure()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun mediaRecovery(error: Throwable): MediaRecovery? {
    val remote = mediaCauses(error).filterIsInstance<HttpDataSource.HttpDataSourceException>().firstOrNull() ?: return null
    val failure = mediaCauses(remote).filterIsInstance<MediaFetchFailure>().firstOrNull()
    if (failure != null) return failure.recovery
    return MediaRecovery.Reopen.takeIf {
        mediaCauses(remote).any { cause ->
            cause.isRecoverableMediaConnectionFailure() ||
                (cause is ProtocolException && remote.type == HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun guardedMediaHttp(client: OkHttpClient, guard: MediaRangeGuard = MediaRangeGuard()): OkHttpDataSource =
    OkHttpDataSource.Factory(guardedMediaClient(client, guard)).createDataSource()

internal fun guardedMediaClient(client: OkHttpClient, guard: MediaRangeGuard): OkHttpClient =
    client.newBuilder().retryOnConnectionFailure(false).apply { interceptors().add(0, guard) }.build()

@OptIn(UnstableApi::class)
internal fun ProgressiveMediaSource.Factory.recoverOutsideLoader() = setLoadErrorHandlingPolicy(
    object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
            val stop = mediaCauses(loadErrorInfo.exception).any { failure ->
                failure is HttpDataSource.HttpDataSourceException ||
                    failure is PlaybackIssueException && failure.issue != PlaybackIssue.Network && failure.issue != PlaybackIssue.Unknown
            }
            return if (stop) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)
        }
    },
)
