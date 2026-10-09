package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.app.ui.MusicEngine
import com.twig.app.ui.PaneViewModel
import com.twig.core.FsRegistry
import com.twig.core.FileSystem
import com.twig.core.XFile
import com.twig.fs.archive.ZipFileSystem
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.restic.ResticFileSystem
import com.twig.fs.restic.ResticRepo
import com.twig.fs.restic.Zstd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemporaryPlaylistTest {
    private lateinit var app: Application
    private val dispatcher = StandardTestDispatcher()
    private val track = PlaylistTrack("temporary", "/snapshot/music/song.flac",
        size = 123L, lastModified = 456L, displayName = "歌曲.flac", sessionScheme = "restic-test")

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("twig_playlists", 0).edit().clear().commit()
        PlaylistStore.clearTemporary()
        Dispatchers.setMain(dispatcher)
    }

    @After fun cleanup() {
        MusicEngine.shutdown()
        FsRegistry.unregister("restic-test")
        Dispatchers.resetMain()
    }

    @Test fun `temporary queue metadata and progress stay in memory without replacing durable now`() {
        val durable = PlaylistStore.setNow(app, "Local", listOf(PlaylistTrack("local", "/music/local.mp3")))
        val saved = PlaylistStore.create(app, "Saved")
        PlaylistStore.fav(app)
        val prefs = app.getSharedPreferences("twig_playlists", 0)
        val before = prefs.getString("list", null)
        val queue = PlaylistStore.setNow(app, "Backup", listOf(track))
        assertEquals(Playlist.TEMP, queue.id)
        assertNull(PlaylistStore.rename(app, queue.id, "Save backup"))
        assertFalse(PlaylistStore.toggleFav(app, track))
        PlaylistStore.addTracks(app, saved.id, listOf(track))
        PlaylistStore.create(app, "Invalid persistent queue", listOf(track))
        PlaylistStore.updateTrackMeta(app, queue.id, track.copy(title = "Title"))
        PlaylistStore.saveResume(app, queue.id, track.id, 3000L)
        assertEquals("Title", PlaylistStore.get(app, queue.id)!!.tracks.single().title)
        assertEquals(3000L, PlaylistStore.get(app, queue.id)!!.lastPosMs)
        assertEquals(before, prefs.getString("list", null))
        assertEquals(durable, PlaylistStore.get(app, Playlist.NOW))
        MusicEngine.shutdown()
        assertNull(PlaylistStore.get(app, queue.id))
        assertEquals(durable, PlaylistStore.get(app, Playlist.NOW))
    }

    @Test fun `temporary paths cannot be serialized or exported`() {
        assertThrows(IllegalStateException::class.java) { track.toJson() }
        assertThrows(IllegalStateException::class.java) { Playlist(Playlist.TEMP, "Backup", listOf(track)).toJson() }
        assertThrows(IllegalStateException::class.java) {
            PlaylistExport.render(XFile("file", "/music", true), listOf(track to XFile(track.sessionScheme, track.path, false)))
        }
    }

    @Test fun `exit cancels a queue still resolving and prevents late playback`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val conn = SavedConnection(type = "smb", host = "queue-cancellation-test", user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = scheme
            override fun list(dir: XFile): List<XFile> {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return listOf(XFile(scheme, "/music/song.mp3", false, size = 10L))
            }
        })
        try {
            val queue = Playlist(Playlist.NOW, "Pending", listOf(PlaylistTrack("conn", "/music/song.mp3", connLabel = conn.label())))
            MusicEngine.play(app, queue, 0)
            assertTrue(MusicEngine.loadingQueue)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            MusicEngine.shutdown()
            release.countDown()
            // Drain the resolver before the main looper: a stale load must never create a player.
            val executor = MusicEngine::class.java.getDeclaredField("io").apply { isAccessible = true }
                .get(MusicEngine) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(MusicEngine.loadingQueue)
            assertFalse(MusicEngine.hasQueue())
            assertEquals("", MusicEngine.queueId)
        } finally {
            release.countDown()
            FsRegistry.unregister(scheme)
        }
    }

    @Test fun `mounted restic keeps listing metadata and requires the live filesystem`() {
        val fixture = listOf(File("../fs-restic/src/test/resources/repo"), File("fs-restic/src/test/resources/repo"))
            .first { it.isDirectory }.canonicalFile
        val repo = ResticRepo.open(LocalFileSystem(), XFile("file", fixture.path, true), "test123",
            Zstd { _, _ -> error("Resolving a live track must not read snapshot data") })
        FsRegistry.register(ResticFileSystem(repo, "restic-test"))
        val file = XFile(track.sessionScheme, track.path, false, size = track.size,
            lastModified = track.lastModified, displayName = track.displayName)
        assertEquals(track, PlaylistTrack.fromMounted(file))
        val restored = MusicEngine.resolveFile(app, track)!!
        assertEquals(file.path, restored.path)
        assertEquals(file.name, restored.name)
        assertEquals(file.size, restored.size)
        assertEquals(file.lastModified, restored.lastModified)
        FsRegistry.unregister(track.sessionScheme)
        assertNull(MusicEngine.resolveFile(app, track))
    }

    @Test fun `archive audio siblings form a temporary queue and can be read through their mounted source`() = runTest(dispatcher) {
        val archive = File.createTempFile("queue", ".zip", app.cacheDir)
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                for (name in listOf("01.mp3", "02.flac", "notes.txt", "other/03.mp3")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(name.toByteArray())
                    zip.closeEntry()
                }
            }
            val vm = PaneViewModel(app).apply { io = dispatcher }
            vm.mountExternal(XFile("file", archive.path, false))
            advanceUntilIdle()
            val tapped = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .first { it.file.name == "02.flac" }.file
            val (siblings, index) = vm.audioSiblings(tapped)
            assertEquals(listOf("01.mp3", "02.flac"), siblings.map { it.name })
            assertEquals(1, index)
            val tracks = siblings.map { vm.trackFrom(it)!! }
            assertTrue(tracks.all { it.temporary })
            assertTrue(PlaylistStore.setNow(app, "Archive", tracks).temporary)
            val resolved = MusicEngine.resolveFile(app, tracks[index])!!
            assertEquals("02.flac", FsRegistry.of(resolved).openInput(resolved).use { it.readBytes().decodeToString() })
            assertEquals(ZipFileSystem.SCHEME, resolved.scheme)
        } finally { archive.delete() }
    }
}
