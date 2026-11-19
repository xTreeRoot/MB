package com.xtreeroot.mb.music

import org.json.JSONObject

internal fun Track.toMetadataJson(): JSONObject = JSONObject().apply {
    put("source", sourceId)
    put("track", sourceTrackId)
    put("title", title)
    put("artist", artist)
    put("artwork", artworkUrl)
    put("duration", durationMs)
    put("page", pageUrl)
    put("author", artistId)
    if (unavailable) put("unavailable", true)
    unavailableReason?.let { put("unavailableReason", it.code) }
    val publicHeaders = artworkHeaders.filterKeys(::publicArtworkHeader)
    if (publicHeaders.isNotEmpty()) put("artworkHeaders", JSONObject(publicHeaders))
}

internal fun JSONObject.toTrackMetadata(): Track {
    val headers = optJSONObject("artworkHeaders")
    return Track(getString("source"), getString("track"), getString("title"), optString("artist"),
        optional("artwork"), optLong("duration").coerceAtLeast(0), optional("page"), optional("author"),
        headers?.keys()?.asSequence()?.filter(::publicArtworkHeader)?.associateWith { headers.getString(it) }.orEmpty(),
        unavailable = optBoolean("unavailable"), unavailableReason = TrackUnavailableReason.fromCode(optString("unavailableReason")))
}

internal fun publicArtworkHeader(name: String) = name.equals("Referer", true) || name.equals("User-Agent", true)

private fun JSONObject.optional(key: String): String? = optString(key).takeIf(String::isNotEmpty)
