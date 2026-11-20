package com.xtreeroot.mb.playback

import androidx.media3.common.PlaybackException
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.music.PlaybackIssueException
import com.xtreeroot.mb.music.SourceResponseFailure
import com.xtreeroot.mb.music.Track
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPlaybackIssuesTest {
    @Test fun retryClearsOnlyTheSuccessfulSourceAndTrack() {
        val first = Track("first", "same", "Track A", "Artist")
        val second = Track("second", "same", "Track B", "Artist")
        val statuses = TrackPlaybackIssues()
        statuses.failed(first, PlaybackIssue.Network)
        statuses.failed(second, PlaybackIssue.SourceUnavailable)
        assertEquals(PlaybackIssue.Network, statuses.values.value[first.playbackKey]?.issue)
        assertEquals(PlaybackIssue.SourceUnavailable, statuses.values.value[second.playbackKey]?.issue)
        statuses.playing(first.copy(title = "Updated title"))
        assertNull(statuses.values.value[first.playbackKey])
        assertEquals(PlaybackIssue.SourceUnavailable, statuses.values.value[second.playbackKey]?.issue)
    }

    @Test fun trustsStructuredReasonsAndNetworkCodesButNotExceptionText() {
        val unknown = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        assertEquals(PlaybackIssue.Unknown, classifyPlaybackIssue(IOException("VIP 网络异常"), unknown))
        assertEquals(PlaybackIssue.Unknown, classifyPlaybackIssue(IOException("HTTP 403 VIP"),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(PlaybackIssue.Vip, classifyPlaybackIssue(IOException(
            PlaybackIssueException(PlaybackIssue.Vip, "Account tier required")), unknown))
        assertEquals(PlaybackIssue.Unavailable, classifyPlaybackIssue(IOException(
            PlaybackIssueException(PlaybackIssue.Unavailable, "Temporarily unavailable")), unknown))
        assertEquals(PlaybackIssue.SourceUnavailable, classifyPlaybackIssue(IOException(
            PlaybackIssueException(PlaybackIssue.SourceUnavailable, "No track")), unknown))
        assertEquals(PlaybackIssue.SignIn, classifyPlaybackIssue(IOException(
            PlaybackIssueException(PlaybackIssue.SignIn, "Session required")), unknown))
        assertEquals(PlaybackIssue.Network, classifyPlaybackIssue(IOException(UnknownHostException()), unknown))
        assertEquals(PlaybackIssue.Network, classifyPlaybackIssue(IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test fun playbackDiagnosticsExposeOnlyStructuredCodes() {
        val code = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val failure = IOException("private header", MediaFetchFailure(status = 403))
        assertEquals("ERROR_CODE_IO_UNSPECIFIED / HTTP 403", failure.playbackDiagnostic(code))
        assertEquals("ERROR_CODE_IO_UNSPECIFIED", IOException("HTTP 403").playbackDiagnostic(code))
        assertEquals("ERROR_CODE_IO_UNSPECIFIED / API -412",
            IOException(SourceResponseFailure(SourceResponseFailure.Kind.API, -412, "private response")).playbackDiagnostic(code))
        assertEquals("ERROR_CODE_IO_UNSPECIFIED / HTTP 429",
            IOException(SourceResponseFailure(SourceResponseFailure.Kind.HTTP, 429, "private response")).playbackDiagnostic(code))
    }

    @Test fun playbackDiagnosticsDistinguishNetworkFailuresWithoutCopyingPrivateDetails() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        val details = "signed URL, proxy credentials, or a private hostname"
        val causes = listOf(UnknownHostException(details) to "DNS", SSLHandshakeException(details) to "TLS",
            ConnectException(details) to "CONNECT", SocketTimeoutException(details) to "TIMEOUT")
        causes.forEach { (cause, label) ->
            assertEquals("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED / $label", IOException(details, cause).playbackDiagnostic(code))
        }
    }
}
