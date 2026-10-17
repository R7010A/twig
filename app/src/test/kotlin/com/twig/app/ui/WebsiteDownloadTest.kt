package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.core.CopyEngine
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteDownloadTest {
    /** Tiny loopback fixture; Android test compilation does not expose the JDK HTTP server module. */
    private class Headers : LinkedHashMap<String, String>() {
        fun add(name: String, value: String) { put(name, value) }
        fun getFirst(name: String): String? = entries.firstOrNull { it.key.equals(name, true) }?.value
    }
    private class HttpExchange(private val socket: java.net.Socket, val requestHeaders: Headers) {
        val responseHeaders = Headers()
        val responseBody: java.io.OutputStream get() = socket.getOutputStream()
        fun sendResponseHeaders(code: Int, size: Long) {
            val head = buildString {
                append("HTTP/1.1 $code Response\r\nConnection: close\r\n")
                if (size >= 0) append("Content-Length: $size\r\n")
                responseHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            responseBody.write(head.toByteArray(Charsets.ISO_8859_1))
            responseBody.flush()
        }
        fun close() { socket.close() }
    }
    private class HttpServer private constructor(address: InetSocketAddress) {
        private val socket = java.net.ServerSocket().apply { bind(address) }
        val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress
        private val routes = java.util.concurrent.ConcurrentHashMap<String, (HttpExchange) -> Unit>()
        fun createContext(path: String, handler: (HttpExchange) -> Unit) { routes[path] = handler }
        fun start() {
            Thread({
                while (!socket.isClosed) {
                    val peer = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread({
                        peer.use { connection ->
                            val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                            val headers = Headers()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                headers.add(line.substringBefore(':'), line.substringAfter(':').trim())
                            }
                            val exchange = HttpExchange(connection, headers)
                            runCatching { routes[path]?.invoke(exchange) ?: exchange.sendResponseHeaders(404, -1) }
                        }
                    }, "download-test-response").apply { isDaemon = true; start() }
                }
            }, "download-test-server").apply { isDaemon = true; start() }
        }
        fun stop(ignored: Int) { socket.close() }
        companion object { fun create(address: InetSocketAddress, ignored: Int) = HttpServer(address) }
    }

    @get:Rule val temporary = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private lateinit var folder: File
    private var previous: FileSystem? = null
    private val listener = object : CopyEngine.ProgressListener {}
    private val root: String get() = "http://127.0.0.1:${server.address.port}"
    private val site: Website get() = Website(id = "download", name = "Download", url = "$root/site")
    private val directory: XFile get() = XFile("file", folder.absolutePath, true)

    @Before fun setup() {
        folder = temporary.newFolder()
        previous = runCatching { FsRegistry.of("file") }.getOrNull()
        FsRegistry.register(LocalFileSystem())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun cleanup() {
        server.stop(0)
        FsRegistry.unregister("download-test")
        if (previous != null) FsRegistry.register(previous!!) else FsRegistry.unregister("file")
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    private fun reply(exchange: HttpExchange, value: String) {
        val bytes = value.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    private fun task(path: String, name: String = "file.txt", configured: Website = site) =
        WebsiteDownloadTask(configured, "$root$path", "Twig-Test/1", name, -1)
    private fun execute(task: WebsiteDownloadTask, dest: XFile = directory) =
        task.execute(app, dest, listener, { false }, { null }, { _, _ -> })

    @Test fun `download follows redirects with cookies but strips bookmark credentials at a different origin`() {
        val foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<Map<String, String?>>()
        val foreignHeaders = AtomicReference<Map<String, String?>>()
        val cookieWrites = mutableListOf<Pair<String, String>>()
        fun headers(e: HttpExchange) = listOf("Authorization", "Cookie", "User-Agent", "X-Secret")
            .associateWith { e.requestHeaders.getFirst(it) }
        foreign.createContext("/file") { e -> foreignHeaders.set(headers(e)); reply(e, "downloaded") }
        foreign.start()
        try {
            server.createContext("/start") { e ->
                seen.add(headers(e))
                e.responseHeaders.add("Set-Cookie", "session=renewed; Path=/")
                e.responseHeaders.add("Location", "/same")
                e.sendResponseHeaders(302, -1); e.close()
            }
            server.createContext("/same") { e ->
                seen.add(headers(e))
                e.responseHeaders.add("Location", "http://127.0.0.1:${foreign.address.port}/file")
                e.sendResponseHeaders(302, -1); e.close()
            }
            val configured = site.copy(basicAuthUsername = "alice", basicAuthPassword = "secret", headers = "X-Secret: bookmark")
            task("/start", configured = configured).execute(app, directory, listener, { false },
                { target -> if (target.startsWith(root)) "session=local" else "session=foreign" },
                { target, value -> cookieWrites.add(target to value) })
            assertEquals("downloaded", File(folder, "file.txt").readText())
            assertEquals(2, seen.size)
            seen.forEach {
                assertEquals("Basic YWxpY2U6c2VjcmV0", it["Authorization"])
                assertEquals("bookmark", it["X-Secret"])
                assertEquals("session=local", it["Cookie"])
                assertEquals("Twig-Test/1", it["User-Agent"])
            }
            assertNull(foreignHeaders.get()["Authorization"])
            assertNull(foreignHeaders.get()["X-Secret"])
            assertEquals("session=foreign", foreignHeaders.get()["Cookie"])
            assertEquals(listOf("$root/start" to "session=renewed; Path=/"), cookieWrites)
        } finally { foreign.stop(0) }
    }

    @Test fun `same names are numbered and progress reports actual bytes`() {
        server.createContext("/file") { reply(it, "new content") }
        File(folder, "file.txt").writeText("original")
        File(folder, "file (1).txt").writeText("older")
        var actualName = ""
        var total = 0L
        task("/file").execute(app, directory, object : CopyEngine.ProgressListener {
            override fun onFile(file: XFile) { actualName = file.name }
            override fun onBytes(copiedTotal: Long, totalBytes: Long) { total = copiedTotal }
        }, { false }, { null }, { _, _ -> })
        assertEquals("original", File(folder, "file.txt").readText())
        assertEquals("older", File(folder, "file (1).txt").readText())
        assertEquals("new content", File(folder, "file (2).txt").readText())
        assertEquals("file (2).txt", actualName)
        assertEquals(11L, total)
    }

    @Test fun `transfer sessions stream to the selected filesystem and report completion`() {
        server.createContext("/file") { reply(it, "remote destination") }
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = "download-test"
        })
        val destination = directory.copy(scheme = "download-test")
        val session = Transfers.Session(Transfers.Work.Download(task("/file"), destination),
            R.string.share_web_download, destination.path, R.drawable.ic_copy, CopyEngine.Plan(0, 1, 0))
        Transfers.execute(app, session) {}
        assertTrue(session.finished!!.isSuccess)
        assertEquals(0, session.filesLeft)
        assertEquals(18L, session.plan!!.bytes)
        assertEquals("remote destination", File(folder, "file.txt").readText())
        shadowOf(Looper.getMainLooper()).idle()
        Transfers.consumeFinished()
    }

    @Test fun `failed responses and short bodies leave existing files and remove partial downloads`() {
        server.createContext("/error") { e -> e.sendResponseHeaders(401, -1); e.close() }
        server.createContext("/short") { e ->
            e.sendResponseHeaders(200, 100)
            runCatching { e.responseBody.use { it.write(byteArrayOf(1, 2, 3)) } }
            e.close()
        }
        File(folder, "keep.txt").writeText("keep")
        assertThrows(Exception::class.java) { execute(task("/error", "keep.txt")) }
        assertThrows(Exception::class.java) { execute(task("/short")) }
        assertEquals(listOf("keep.txt"), folder.list()!!.toList())
        assertEquals("keep", File(folder, "keep.txt").readText())
    }

    @Test fun `cancel interrupts an in-flight request and removes the partial file`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/slow") { e ->
            e.sendResponseHeaders(200, 100)
            e.responseBody.write(ByteArray(5)); e.responseBody.flush()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            runCatching { e.responseBody.close() }; e.close()
        }
        val job = task("/slow")
        val result = AtomicReference<Throwable?>()
        val worker = Thread { runCatching { execute(job) }.onFailure { result.set(it) } }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertNotNull(result.get())
            assertTrue(folder.list()!!.isEmpty())
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun `unknown destination listings fail without overwriting and redirects are bounded`() {
        server.createContext("/file") { reply(it, "new") }
        server.createContext("/loop") { e -> e.responseHeaders.add("Location", "/loop"); e.sendResponseHeaders(302, -1); e.close() }
        File(folder, "file.txt").writeText("original")
        val underlying = LocalFileSystem()
        FsRegistry.register(object : FileSystem by underlying {
            override val scheme = "download-test"
            override fun list(dir: XFile): List<XFile> = throw java.io.IOException("Listing failed")
        })
        assertThrows(Exception::class.java) { execute(task("/file"), directory.copy(scheme = "download-test")) }
        assertEquals("original", File(folder, "file.txt").readText())
        assertThrows(Exception::class.java) { execute(task("/loop")) }
        assertEquals(listOf("file.txt"), folder.list()!!.toList())
    }

    @Test fun `default Downloads twig directory is created only when downloading`() {
        server.createContext("/file") { reply(it, "download") }
        val dest = WebsiteDownloadTask.defaultDirectory()
        assertEquals("twig", File(dest.path).name)
        assertEquals(android.os.Environment.DIRECTORY_DOWNLOADS, File(dest.path).parentFile!!.name)
        val name = "download-test-${System.nanoTime()}.txt"
        try {
            execute(task("/file", name), dest)
            assertEquals("download", File(dest.path, name).readText())
        } finally { File(dest.path, name).delete() }
    }

    @Test fun `download names decode extended Unicode filenames and reject traversal edits`() {
        assertEquals("你好+.zip", WebsiteDownloadTask.suggestedName("$root/file", "attachment; filename*=UTF-8''%E4%BD%A0%E5%A5%BD+.zip", "application/zip"))
        assertEquals("safe.txt", WebsiteDownloadTask.suggestedName("$root/file", "attachment; filename=\"../../safe.txt\"", "text/plain"))
        for (name in listOf("", " ", ".", "..", "../escape", "a/b", "a\\b", "a\u0000b")) assertFalse(name, WebsiteDownloadTask.validName(name))
    }

    @Test fun `WebView download opens confirmation with the dedicated directory and a Twig picker`() {
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, site.id)).setup()
        try {
            val activity = controller.get()
            val web = activity.findViewById<WebView>(R.id.web)
            shadowOf(web).downloadListener.onDownloadStart("$root/file", "UA", "attachment; filename=\"sample.zip\"", "application/zip", 123)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val name = dialog.window!!.decorView.findViewWithTag<EditText>(R.string.website_download_name)
            assertEquals("sample.zip", name.text.toString())
            val label = dialog.window!!.decorView.findViewWithTag<android.widget.TextView>(R.string.compress_save_to)
            assertTrue(label.text.contains(WebsiteDownloadTask.defaultDirectory().path))
            name.setText("../invalid")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertNotNull(name.error)
            name.setText("renamed.zip")
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            val intent = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(PickerActivity::class.java.name, intent.component!!.className)
            assertTrue(intent.getBooleanExtra("pick_dir", false))
            shadowOf(activity).receiveResult(intent, android.app.Activity.RESULT_OK, android.content.Intent()
                .putExtra(PickerActivity.EXTRA_PICKED_SCHEME, "file")
                .putExtra(PickerActivity.EXTRA_PICKED_PATH, folder.absolutePath)
                .putExtra(PickerActivity.EXTRA_PICKED_NAME, folder.name))
            shadowOf(Looper.getMainLooper()).idle()
            val returned = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals("renamed.zip", returned.window!!.decorView
                .findViewWithTag<EditText>(R.string.website_download_name).text.toString())
            assertTrue(returned.window!!.decorView.findViewWithTag<android.widget.TextView>(R.string.compress_save_to)
                .text.contains(folder.absolutePath))
        } finally { controller.pause().stop().destroy() }
    }
}
