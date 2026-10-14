package com.twig.fs.smb

import com.twig.core.FsException
import org.junit.Assert.*
import org.junit.Test
import jcifs.smb.SmbFile
import jcifs.context.SingletonContext

class Smb1FileSystemTest {
    @Test fun `URLs preserve literal filenames and mount prefixes without embedding credentials`() {
        val fs = Smb1FileSystem(SmbConfig("localhost:11445", "Public/Photos", "user", "secret"))
        try {
            assertEquals("smb://localhost:11445/Public/Photos/", fs.urlFor("/", true))
            val url = fs.urlFor("/中文 a #%.txt", false)
            SmbFile(url, SingletonContext.getInstance()).use {
                assertEquals("中文 a #%.txt", it.name)
                assertEquals("\\Photos\\中文 a #%.txt", it.locator.uncPath)
            }
            assertFalse(fs.urlFor("/a", false).contains("secret"))
        } finally { fs.disconnect() }
    }
    @Test fun `navigation stubs do not fabricate file metadata`() {
        val fs = Smb1FileSystem(SmbConfig("localhost", "Public"))
        try { assertTrue(fs.resolve("/somewhere").isDir); assertEquals(0L, fs.resolve("/somewhere").size) }
        finally { fs.disconnect() }
    }
    @Test fun `parent traversal is rejected before touching a server`() {
        val fs = Smb1FileSystem(SmbConfig("localhost", "Public"))
        try {
            assertThrows(FsException::class.java) { fs.urlFor("/../outside", false) }
            assertThrows(FsException::class.java) { fs.urlFor("/one\\outside", false) }
        } finally { fs.disconnect() }
    }
}
