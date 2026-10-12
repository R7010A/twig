package com.twig.app.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.twig.app.AppsFileSystem
import com.twig.core.FsRegistry
import com.twig.core.FsException
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppsRestoreTest {
    @Test
    fun `restores an app as an archive and safely collapses installed apps`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pkg = "com.example.repro"
        val apk = File(app.cacheDir, "repro.apk").apply {
            ZipOutputStream(outputStream()).use {
                it.putNextEntry(ZipEntry("AndroidManifest.xml"))
                it.write("fixture".toByteArray())
                it.closeEntry()
            }
        }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = pkg
            versionName = "1"
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                sourceDir = apk.path
                flags = 0
                nonLocalizedLabel = "Repro"
            }
        })
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val vm = PaneViewModel(app).apply { io = dispatcher }
                val path = "/user/$pkg"
                val fs = FsRegistry.of(AppsFileSystem.SCHEME)
                val entry = fs.resolve(path)
                assertFalse(entry.isDir)
                assertThrows(FsException::class.java) { fs.list(entry) }
                assertThrows(FsException::class.java) { fs.list(entry.copy(isDir = true)) }
                assertThrows(FsException::class.java) { fs.list(XFile("apps", "/unknown", isDir = true)) }
                val original = PaneViewModel(app).apply { io = dispatcher }
                original.bootstrap(listOf("apps\t/", "apps\t/user"))
                advanceUntilIdle()
                val appNode = original.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .single { it.file.scheme == "apps" && it.file.path == path }
                original.openAsArchive(appNode)
                advanceUntilIdle()
                val saved = original.expandedDescriptors()
                assertTrue(saved.contains("apps\t$path"))
                vm.bootstrap(saved)
                advanceUntilIdle()
                val restoredApp = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .single { it.file.scheme == "apps" && it.file.path == path }
                assertFalse(restoredApp.file.isDir)
                assertTrue(restoredApp.expanded)
                assertTrue(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .any { it.file.scheme == "zip" && it.file.name == "AndroidManifest.xml" })
                val installed = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .single { it.file.scheme == "apps" && it.file.path == "/user" }
                assertTrue(installed.expanded)
                vm.toggle(installed)
                assertFalse(vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .single { it.file.scheme == "apps" && it.file.path == "/user" }.expanded)
                vm.toggle(installed)
                advanceUntilIdle()
                val reopened = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                    .single { it.file.scheme == "apps" && it.file.path == "/user" }
                assertTrue(reopened.expanded)
                vm.toggle(reopened)
            }
        } finally {
            Dispatchers.resetMain()
            apk.delete()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cache(vm: PaneViewModel) = PaneViewModel::class.java.getDeclaredField("children")
        .apply { isAccessible = true }.get(vm) as MutableMap<String, List<XFile>>

    @Suppress("UNCHECKED_CAST")
    private fun expanded(vm: PaneViewModel) = PaneViewModel::class.java.getDeclaredField("expanded")
        .apply { isAccessible = true }.get(vm) as MutableSet<String>

    @Suppress("UNCHECKED_CAST")
    private fun descendants(vm: PaneViewModel, key: String) = PaneViewModel::class.java
        .getDeclaredMethod("descendantFilesByKey", String::class.java)
        .apply { isAccessible = true }.invoke(vm, key) as List<XFile>

    @Test
    fun `cancellation terminates on a cyclic cached listing`() {
        val vm = PaneViewModel(ApplicationProvider.getApplicationContext())
        val a = XFile("apps", "/user/a", isDir = false)
        val b = XFile("apps", "/user/b", isDir = false)
        val ak = PaneViewModel.fileKey(a)
        val bk = PaneViewModel.fileKey(b)
        cache(vm)[ak] = listOf(b)
        cache(vm)[bk] = listOf(a)
        expanded(vm).addAll(listOf(ak, bk))
        assertEquals(listOf(b, a), descendants(vm, ak))
    }

    @Test
    fun `cancellation traverses deep caches without recursion`() {
        val vm = PaneViewModel(ApplicationProvider.getApplicationContext())
        val files = (0..10000).map { XFile("file", "/$it", isDir = true) }
        files.zipWithNext().forEach { (a, b) ->
            cache(vm)[PaneViewModel.fileKey(a)] = listOf(b)
            expanded(vm).add(PaneViewModel.fileKey(b))
        }
        assertEquals(files.drop(1), descendants(vm, PaneViewModel.fileKey(files.first())))
    }

    @Test
    fun `cancellation follows the external mount row keys`() {
        val vm = PaneViewModel(ApplicationProvider.getApplicationContext())
        val dir = XFile("zip", "/inner", isDir = true)
        val file = XFile("zip", "/inner/file.txt", isDir = false)
        val rootKey = "x:f:file:/archive.zip"
        val dirKey = "x:" + PaneViewModel.fileKey(dir)
        cache(vm)[rootKey] = listOf(dir)
        cache(vm)[dirKey] = listOf(file)
        expanded(vm).add(dirKey)
        assertEquals(listOf(dir, file), descendants(vm, rootKey))
    }
}
