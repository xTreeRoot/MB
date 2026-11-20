package com.xtreeroot.mb.music

import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal const val maxMediaBackupAddresses = 2

internal fun Int.isMediaAddressUnavailable(): Boolean = when (this) {
    401, 403, 404, 410, 429, in 500..599 -> true
    else -> false
}

internal fun Throwable.isMediaConnectionFailure(): Boolean =
    this is SocketException || this is SocketTimeoutException || this is UnknownHostException || this is EOFException
