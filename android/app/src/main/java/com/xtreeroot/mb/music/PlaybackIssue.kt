package com.xtreeroot.mb.music

import java.io.IOException

internal enum class PlaybackIssue { Vip, SourceUnavailable, SignIn, Unavailable, Network, Unknown }

enum class TrackUnavailableReason(val code: String, internal val issue: PlaybackIssue) {
    Vip("vip", PlaybackIssue.Vip),
    NoSource("noSource", PlaybackIssue.SourceUnavailable),
    SignIn("signIn", PlaybackIssue.SignIn),
    Unavailable("unavailable", PlaybackIssue.Unavailable);

    internal companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code }
    }
}

internal class PlaybackIssueException(val issue: PlaybackIssue, message: String) : IOException(message)

internal class SourceResponseFailure(val kind: Kind, val code: Int, message: String) : IOException(message) {
    enum class Kind { HTTP, API }
}
