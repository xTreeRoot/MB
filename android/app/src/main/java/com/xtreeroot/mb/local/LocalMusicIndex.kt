package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

internal data class LocalCollection(val id: String, val title: String, val artist: String, val tracks: List<Track>) {
    val artwork: Track get() = tracks.firstOrNull { it.artworkUrl != null } ?: tracks.first()
}

internal class LocalMusicIndex(imports: List<LocalTrack>, downloads: List<SavedAudio>, locale: Locale) {
    private val nameOrder = Collator.getInstance(locale)
    private val tags = imports.associateBy { it.track.id }
    private val songOrder = compareBy<Track, String>(nameOrder, Track::title).thenBy(Track::id)
    private val albumOrder = compareBy<Track>({ tags[it.id]?.disc ?: 0 }, { tags[it.id]?.number ?: 0 }).then(songOrder)
    private val collectionOrder = compareBy<LocalCollection, String>(nameOrder, LocalCollection::title).thenBy(LocalCollection::id)
    private val additions = imports.map { it.track to it.addedAt } + downloads.map { it.track to it.used }
    val songs = additions.map { it.first }.distinctBy(Track::id).sortedWith(songOrder)
    val recentAdditions = additions.sortedWith(compareByDescending<Pair<Track, Long>> { it.second }.thenBy { it.first.id })
        .asSequence().map { it.first }.distinctBy(Track::id).take(8).toList()
    val albums = imports.filter { it.album.isNotBlank() }.groupBy { item ->
        val owner = item.albumArtist.ifBlank { item.folderId ?: item.track.artist }
        "${item.album.lowercase()}\u0000${owner.lowercase()}"
    }.map { (id, items) ->
        val tracks = items.map(LocalTrack::track).sortedWith(albumOrder)
        val first = tags.getValue(tracks.first().id)
        val artist = first.albumArtist.ifBlank { first.track.artist.takeIf { title -> tracks.all { it.artist.equals(title, true) } }.orEmpty() }
        LocalCollection(id, first.album, artist, tracks)
    }.sortedWith(collectionOrder)
    val artists = buildMap<String, Pair<String, MutableMap<String, Track>>> {
        fun include(credits: String, tracks: List<Track>) {
            credits.split(artistSeparators).map(String::trim).filter(String::isNotEmpty).forEach { name ->
                val entry = getOrPut(name.lowercase()) { name to linkedMapOf() }
                tracks.forEach { entry.second.putIfAbsent(it.id, it) }
            }
        }
        songs.forEach { include(it.artist, listOf(it)) }
        albums.forEach { album -> include(tags.getValue(album.tracks.first().id).albumArtist, album.tracks) }
    }.map { (id, entry) ->
        LocalCollection(id, entry.first, "", entry.second.values.sortedWith(
            compareBy<Track, String>(nameOrder) { tags[it.id]?.album.orEmpty() }.then(albumOrder)))
    }.sortedWith(collectionOrder)

    fun filter(tracks: List<Track>, query: String): List<Track> = tracks.filter { track ->
        val tag = tags[track.id]
        matchesLocalMusic(listOfNotNull(track.title, track.artist, tag?.album, tag?.albumArtist).joinToString("\n"), query)
    }
}

private val artistSeparators = Regex(" / |;|、| feat\\. | ft\\. ", RegexOption.IGNORE_CASE)
private val combiningMarks = Regex("\\p{M}+")
private val searchWhitespace = Regex("\\s+")

internal fun matchesLocalMusic(text: String, query: String): Boolean {
    if (query.isBlank()) return true
    fun folded(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(combiningMarks, "").lowercase()
    val candidate = folded(text)
    return folded(query).trim().split(searchWhitespace).all(candidate::contains)
}
