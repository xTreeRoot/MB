package com.xtreeroot.mb.playback

import com.xtreeroot.mb.local.localSourceId
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import org.json.JSONArray
import org.json.JSONObject

internal data class QueueSnapshot(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffle: Boolean,
    val shuffleOrder: List<Int>? = null,
    val station: PlaybackStation? = null,
) {
    fun forPortableStorage(): QueueSnapshot = copy(tracks = tracks.map { track ->
        track.copy(artworkUrl = track.artworkUrl?.takeIf { track.sourceId != localSourceId && it.startsWith("https://", true) })
    })

    fun encode(): String = JSONObject().apply {
        put("tracks", JSONArray(tracks.map(Track::toMetadataJson)))
        put("index", index)
        put("position", positionMs)
        put("repeat", repeatMode)
        put("shuffle", shuffle)
        shuffleOrder?.let { put("shuffleOrder", JSONArray(it)) }
        station?.let { put("station", it.encode()) }
    }.toString()

    companion object {
        fun decode(value: String): QueueSnapshot? = runCatching {
            val root = JSONObject(value)
            val items = root.getJSONArray("tracks")
            val tracks = (0 until items.length()).map { items.getJSONObject(it).toTrackMetadata() }
            if (tracks.isEmpty()) return null
            val index = root.optInt("index").coerceIn(tracks.indices)
            val order = root.optJSONArray("shuffleOrder")?.let { entries ->
                (0 until entries.length()).map { entries.optInt(it, -1) }
            }?.takeIf { it.size == tracks.size && it.toSet() == tracks.indices.toSet() }
            val station = root.optJSONObject("station")?.let(PlaybackStation::decode)?.takeIf { it.sourceId == tracks[index].sourceId }
            QueueSnapshot(tracks, index, root.optLong("position").coerceAtLeast(0),
                root.optInt("repeat").coerceIn(0, 2), root.optBoolean("shuffle"), order, station)
        }.getOrNull()
    }
}
