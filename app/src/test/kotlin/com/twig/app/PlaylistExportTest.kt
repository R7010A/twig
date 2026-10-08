package com.twig.app

import com.twig.core.XFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExportTest {
    private fun entry(dir: XFile, file: XFile): String {
        val track = PlaylistTrack("local", file.path)
        return PlaylistExport.render(dir, listOf(track to file)).lines()[2]
    }

    @Test fun `local paths are relative including parent traversal and unicode`() {
        val dir = XFile("file", "/Music/lists", true)
        assertEquals("../专辑/一 首.flac", entry(dir, XFile("file", "/Music/专辑/一 首.flac", false)))
        assertEquals("track.mp3", entry(dir, XFile("file", "/Music/lists/track.mp3", false)))
        assertEquals("album/a.mp3", entry(XFile("file", "/", true), XFile("file", "/album/a.mp3", false)))
        assertEquals("../else/a.mp3", entry(XFile("file", "/Music/", true), XFile("file", "/else/a.mp3", false)))
    }

    @Test fun `same connection is relative but another server retains its source`() {
        val dir = XFile("smb123", "/lists", true)
        assertEquals("../album/a.mp3", entry(dir, XFile("smb123", "/album/a.mp3", false)))
        assertEquals("smb456:///album/a.mp3", entry(dir, XFile("smb456", "/album/a.mp3", false)))
        assertEquals("file:///Music/a.mp3", entry(dir, XFile("file", "/Music/a.mp3", false)))
    }

    @Test fun `opaque content uris are preserved rather than sliced`() {
        val path = "content://provider/tree/primary%3AMusic/document/primary%3AMusic%2Fa.mp3"
        assertEquals(path, entry(XFile("saf", "content://provider/tree/primary%3AMusic", true), XFile("saf", path, false)))
    }

    @Test fun `hash filenames cannot turn into comments`() {
        assertEquals("./#song.mp3", entry(XFile("file", "/Music", true), XFile("file", "/Music/#song.mp3", false)))
    }

    @Test fun `export keeps order duplicates and safe metadata`() {
        val track = PlaylistTrack("local", "/Music/a.mp3", title = "Title\nline", artist = "Artist\rname", durationMs = 123456)
        val file = XFile("file", track.path, false)
        assertEquals("#EXTM3U\n#EXTINF:123,Artist name - Title line\na.mp3\n#EXTINF:123,Artist name - Title line\na.mp3\n",
            PlaylistExport.render(XFile("file", "/Music", true), listOf(track to file, track to file)))
        assertEquals("#EXTM3U\n", PlaylistExport.render(XFile("file", "/Music", true), emptyList()))
    }

    @Test fun `playlist names cannot create subdirectories`() {
        assertEquals("My_list.m3u8", PlaylistExport.fileName(" My/list "))
        assertEquals("playlist.m3u8", PlaylistExport.fileName(".."))
        assertTrue(PlaylistExport.fileName("收藏").endsWith(".m3u8"))
    }
}
