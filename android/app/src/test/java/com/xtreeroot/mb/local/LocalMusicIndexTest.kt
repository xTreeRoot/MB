package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicIndexTest {
    @Test fun keepsAlbumsWithTheSameTitleSeparateAndOrdersEachByDiscAndTrack() {
        val third = imported("Id3", "Beyond", "B", "LIVE", "Compilers", "a", disc = 2)
        val first = imported("Id1", "Zénith", "A", "Live", "compilers", "b", number = 1)
        val second = imported("Id2", "Another", "B", "Live", "Compilers", "a", number = 2)
        val otherAlbum = imported("Id4", "Split", "A", "Live", "Different Artist", "b")
        val index = LocalMusicIndex(listOf(third, second, first, otherAlbum), emptyList(), Locale.ENGLISH)
        assertEquals(2, index.albums.size)
        assertEquals(listOf(first, second, third).map(LocalTrack::track), index.albums.single { it.artist.equals("Compilers", true) }.tracks)
        assertEquals(listOf(otherAlbum.track), index.albums.single { it.artist == "Different Artist" }.tracks)
    }

    @Test fun usesFolderWhenThereIsNoAlbumArtistAndFallsBackToTheTrackArtistForLooseFiles() {
        val first = imported("Id1", "First", "A", "Live", folder = "fixture-one", number = 1)
        val second = imported("Id2", "Second", "B", "Live", folder = "fixture-one", number = 2)
        val separate = imported("Id3", "Third", "A", "Live", folder = "fixture-two")
        val loose = imported("Id4", "Fourth", "A", "Live")
        val index = LocalMusicIndex(listOf(first, second, separate, loose), emptyList(), Locale.ENGLISH)
        assertEquals(setOf(listOf(first.track, second.track), listOf(separate.track), listOf(loose.track)), index.albums.map { it.tracks }.toSet())
    }

    @Test fun includesAlbumAndTrackCreditsWithoutSplittingCommasOrDuplicatingSongs() {
        val first = imported("Id1", "Zénith", "AC, DC feat. Guest / ac, dc", "Live", "AC, DC; Ensemble")
        val second = imported("Id2", "Finale", "Guest", "LIVE", "AC, DC; Ensemble")
        val downloaded = Track("test", "pod", "Another Track", "Guest、Feature")
        val index = LocalMusicIndex(listOf(first, second), listOf(downloaded), Locale.ENGLISH)
        assertEquals(setOf(first.track, second.track), index.artists.single { it.title == "AC, DC" }.tracks.toSet())
        assertEquals(2, index.artists.single { it.title == "AC, DC" }.tracks.size)
        assertEquals(setOf(first.track, second.track), index.artists.single { it.title == "Ensemble" }.tracks.toSet())
        assertEquals(listOf(downloaded), index.artists.single { it.title == "Feature" }.tracks)
        assertEquals(listOf(first.track), index.filter(index.songs, "guest   zenith live"))
        assertEquals(listOf(downloaded), index.filter(index.songs, "feature another"))
        assertEquals(1, index.albums.size)
    }

    private fun imported(id: String, title: String, artist: String, album: String, albumArtist: String = "",
                         folder: String? = null, disc: Int = 1, number: Int = 1) =
        LocalTrack(Track(localSourceId, id, title, artist), album, albumArtist, "", disc, number, folder)
}
