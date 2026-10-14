package com.twig.fs.smb

import com.twig.core.FsException
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import jcifs.smb.SmbAuthException

/** Opt-in local Samba fixture with server min/max protocol = NT1. */
class Smb1LiveTest {
    private fun client(share: String = "Public"): Smb1FileSystem {
        val host = System.getenv("TWIG_SMB1_TEST_HOST")
        assumeTrue("Set TWIG_SMB1_TEST_HOST to an SMB1-only test server", !host.isNullOrBlank())
        return Smb1FileSystem(SmbConfig(host!!, share,
            user = System.getenv("TWIG_SMB1_TEST_USER") ?: "guest",
            password = System.getenv("TWIG_SMB1_TEST_PASSWORD") ?: ""), "test-smb1").also { it.connect() }
    }
    @Test fun `named logins cannot silently become a guest session`() {
        val host = System.getenv("TWIG_SMB1_TEST_HOST")
        assumeTrue(!host.isNullOrBlank())
        val fs = Smb1FileSystem(SmbConfig(host!!, "Public", "twig-nonexistent-user", "wrong-password"))
        try { assertThrows(SmbAuthException::class.java) { fs.connect() } }
        finally { fs.disconnect() }
    }
    @Test fun `read write stat append seek rename and recursive deletion work against SMB1 only Samba`() {
        val fs = client()
        try {
            val dir = fs.mkdir(fs.root(), "twig-${System.nanoTime()}")
            try {
                val names = listOf("ordinary.txt", "space #%.txt", "中文.txt")
                for (name in names) {
                    val file = fs.createFile(dir, name)
                    fs.openOutput(file).use { it.write("hello".toByteArray()) }
                    assertEquals("hello", fs.openInput(file).bufferedReader().use { it.readText() })
                    assertEquals(5L, fs.stat(file.path)?.size)
                }
                assertEquals(names.toSet(), fs.list(dir).map { it.name }.toSet())
                val a = fs.createFile(dir, names.first())
                fs.openOutput(a, append = true).use { it.write(" world".toByteArray()) }
                fs.openRandom(a).use {
                    val bytes = ByteArray(5)
                    assertEquals(5, it.readAt(6, bytes, 0, bytes.size))
                    assertEquals("world", String(bytes))
                    assertEquals(11L, it.length())
                }
                assertThrows(FsException::class.java) { fs.rename(a, names[1]) }
                val renamed = fs.rename(a, "renamed.txt")
                assertFalse(fs.exists(a))
                assertTrue(fs.exists(renamed))
                assertNull(fs.stat(dir.path + "/not-here"))
            } finally { fs.delete(dir) }
            assertFalse(fs.exists(dir))
        } finally { fs.disconnect() }
    }
    @Test fun `server share enumeration and subdirectory mounts use the same logical paths`() {
        val server = client("")
        try { assertTrue(server.list(server.root()).any { it.name == "Public" }) }
        finally { server.disconnect() }
        val parent = client()
        try {
            val dir = parent.mkdir(parent.root(), "nested-${System.nanoTime()}")
            try {
                val file = parent.createFile(dir, "inside.txt")
                parent.openOutput(file).use { it.write(42) }
                val nested = client("Public" + dir.path)
                try {
                    assertEquals(listOf("/inside.txt"), nested.list(nested.root()).map { it.path })
                    assertEquals(1L, nested.stat("/inside.txt")?.size)
                } finally { nested.disconnect() }
            } finally { parent.delete(dir) }
        } finally { parent.disconnect() }
    }
}
