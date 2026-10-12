package com.twig.app.ui

import com.twig.app.PlaylistTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTrackSortTest {
    @Test fun discBeforeTrackIncludingRepeatedTrackNumbers() {
        val tracks = listOf(
            PlaylistTrack("local", "/album/02.flac", album = "Album", trackNumber = 2, discNumber = 1),
            PlaylistTrack("local", "/album/04.flac", album = "Album", trackNumber = 4, discNumber = 2),
            PlaylistTrack("local", "/album/01.flac", album = "Album", trackNumber = 1, discNumber = 1),
            PlaylistTrack("local", "/album/03.flac", album = "Album", trackNumber = 3, discNumber = 1),
            PlaylistTrack("local", "/album/05.flac", album = "Album", trackNumber = 1, discNumber = 2),
        )
        for (mode in listOf(3, 7)) {
            assertEquals(listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 4),
                MusicTrackSort.sorted(tracks, mode).map { it.discNumber to it.trackNumber })
        }
    }

    @Test fun cdFoldersSupplyMissingDiscTagsInNumericOrder() {
        val tracks = listOf(
            PlaylistTrack("local", "/album/CD10/01.flac", album = "Album", trackNumber = 1, discNumber = -1),
            PlaylistTrack("local", "/album/CD2/01.flac", album = "Album", trackNumber = 1, discNumber = -1),
            PlaylistTrack("local", "/album/CD1/02.flac", album = "Album", trackNumber = 2, discNumber = -1),
            PlaylistTrack("local", "/album/CD1/01.flac", album = "Album", trackNumber = 1, discNumber = -1),
        )
        assertEquals(listOf("/album/CD1/01.flac", "/album/CD1/02.flac", "/album/CD2/01.flac", "/album/CD10/01.flac"),
            MusicTrackSort.sorted(tracks, 7).map { it.path })
    }

    @Test fun parsesNumberAndTotalTags() {
        assertEquals(2, MusicTrackSort.tagNumber("02/03"))
        assertEquals(0, MusicTrackSort.tagNumber(null))
        assertEquals(0, MusicTrackSort.tagNumber("bad"))
    }

    @Test fun japaneseEditionTrackStaysAtItsTrackNumber() {
        val dir = "/Music/Coldplay - A Head Full of Dreams (Japanese Edition)"
        val tracks = listOf(5, 3, 1, 8, 2, 4).map { number ->
            PlaylistTrack(
                kind = "local",
                path = "$dir/%02d - song.flac".format(number),
                artist = "Coldplay",
                album = if (number == 5) "A Head Full Of Dreams"
                    else "A Head Full of Dreams (Japanese Edition)",
                trackNumber = number,
            )
        }
        for (mode in listOf(3, 7)) {
            assertEquals(listOf(1, 2, 3, 4, 5, 8),
                MusicTrackSort.sorted(tracks, mode).map { it.trackNumber })
        }
    }

    @Test fun differentEditionFoldersStaySeparate() {
        val tracks = listOf(
            PlaylistTrack("local", "/standard/02.flac", album = "A Head Full of Dreams", trackNumber = 2),
            PlaylistTrack("local", "/japanese/01.flac", album = "A Head Full of Dreams (Japanese Edition)", trackNumber = 1),
            PlaylistTrack("local", "/standard/01.flac", album = "A Head Full of Dreams", trackNumber = 1),
        )
        assertEquals(listOf("/japanese/01.flac", "/standard/01.flac", "/standard/02.flac"),
            MusicTrackSort.sorted(tracks, 7).map { it.path })
    }
}
