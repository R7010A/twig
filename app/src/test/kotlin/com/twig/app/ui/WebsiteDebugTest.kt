package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.os.Build
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Secrets
import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWebView
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [WebsiteDebugTest.DebugWebViewShadow::class])
class WebsiteDebugTest {
    @Implements(WebView::class)
    class DebugWebViewShadow : ShadowWebView() {
        companion object {
            var remote = false
            @JvmStatic @Implementation(methodName = "setWebContentsDebuggingEnabled") fun recordDebugging(enabled: Boolean) { remote = enabled }
        }
    }
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val originalBuildType = Build.TYPE
    private val site = Website(id = "debug", name = "Debug", url = "https://twig.test/page")
    private val wrapper = object : Secrets.Wrapper {
        var dek: ByteArray? = null
        override fun available() = true
        override fun wrap(dek: ByteArray): String { this.dek = dek.copyOf(); return "test" }
        override fun unwrap(blob: String) = dek?.copyOf()
    }

    @Before fun prepare() {
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", "user")
        Secrets.wrapper = wrapper
        Secrets.reset(app)
        WebsiteStore.preferences(app).edit().clear().commit()
        WebsiteRemoteDebug.setEnabled(false)
    }
    @After fun cleanup() {
        ReflectionHelpers.setStaticField(Build::class.java, "TYPE", originalBuildType)
        WebsiteRemoteDebug.setEnabled(false)
        Secrets.wrapper = Secrets.AndroidKeystore
    }
    private inner class Page(configured: Website = site) : AutoCloseable {
        val controller = run {
            WebsiteStore.save(app, configured)
            Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, configured.id)).setup()
        }
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val shadow = shadowOf(web)
        val panel = activity.findViewById<View>(R.id.debug_host)
        fun menu(label: Int) {
            val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu as MenuBuilder
            val item = (0 until menu.size()).map { menu.getItem(it) }.single { it.title == activity.getString(label) }
            assertTrue(menu.performItemAction(item, 0))
        }
        fun click(label: Int) { assertTrue(panel.findViewWithTag<View>(label).performClick()) }
        fun input(label: Int) = panel.findViewWithTag<EditText>(label)
        fun logs() = panel.findViewWithTag<TextView>("website_console_log").text.toString()
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun `console receives page logs with source locations and keeps a bounded selectable buffer`() {
        Page().use { page ->
            assertEquals(View.GONE, page.panel.visibility)
            page.web.webChromeClient!!.onConsoleMessage(ConsoleMessage("first error", "app.js", 42, ConsoleMessage.MessageLevel.ERROR))
            page.menu(R.string.website_debug)
            assertTrue(page.logs().contains("ERROR app.js:42\nfirst error"))
            assertTrue(page.panel.findViewWithTag<TextView>("website_console_log").isTextSelectable)
            for (i in 0..240) page.web.webChromeClient!!.onConsoleMessage(
                ConsoleMessage("message $i " + "x".repeat(1000), "page.js", i, ConsoleMessage.MessageLevel.LOG))
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertTrue(page.logs().contains("message 240"))
            assertFalse(page.logs().contains("first error"))
            assertTrue(page.logs().length < 67000)
            page.click(R.string.history_clear)
            assertEquals(page.activity.getString(R.string.website_debug_console_empty), page.logs())
            page.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.GONE, page.panel.visibility)
            assertFalse(page.activity.isFinishing)
        }
    }

    @Test fun `manual JS runs in the current page without persisting and returns decoded results`() {
        Page().use { page ->
            page.menu(R.string.website_debug)
            page.click(R.string.website_script)
            val code = "window.debugCount = (window.debugCount || 0) + 1; document.querySelector('#label').textContent = 'changed'; ({ count: window.debugCount })"
            page.input(R.string.website_debug_js_input).setText(code)
            page.click(R.string.website_debug_run)
            assertEquals(code + "\n//# sourceURL=twig-console.js", page.shadow.lastEvaluatedJavascript)
            page.shadow.lastEvaluatedJavascriptCallback.onReceiveValue("{\"count\":1}")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            assertTrue(page.logs().contains("< {\"count\":1}"))
            assertEquals(site, WebsiteStore.all(app).single())
            assertEquals(0, page.shadow.reloadInvocations)
            val fixture = JSONObject().put("manual", page.shadow.lastEvaluatedJavascript)
                .put("preview", WebsiteCustomization.styleScript(site.copy(style = "body { color: rgb(12, 34, 56); }")))
                .put("restore", WebsiteCustomization.styleScript(site))
            File("build/reports/website-debug/scripts.json").apply { parentFile.mkdirs(); writeText(fixture.toString()) }
        }
    }

    @Test fun `CSS preview survives panel close and page callbacks but restore and navigation discard it`() {
        Page(site.copy(style = "body { color: blue; }")).use { page ->
            page.menu(R.string.website_debug)
            page.click(R.string.website_style)
            page.input(R.string.website_debug_css_input).setText("body { color: green; }")
            page.click(R.string.website_debug_preview)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: green"))
            assertEquals("body { color: blue; }", WebsiteStore.all(app).single().style)
            page.click(R.string.dialog_close)
            page.web.webViewClient!!.onPageCommitVisible(page.web, site.url)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: green"))
            page.menu(R.string.website_debug)
            page.click(R.string.website_debug_restore)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: blue"))
            page.input(R.string.website_debug_css_input).setText("")
            page.click(R.string.website_debug_preview)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("const css = \"\""))
            page.web.webViewClient!!.onPageStarted(page.web, site.url, null)
            page.web.webViewClient!!.onPageCommitVisible(page.web, site.url)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: blue"))
        }
    }

    @Test fun `saving CSS merges the latest record and preserves credentials and other page options`() {
        Page().use { page ->
            page.menu(R.string.website_debug)
            page.click(R.string.website_style)
            page.input(R.string.website_debug_css_input).setText("body { color: green; }")
            val latest = site.copy(name = "Renamed", userAgent = "New/1", basicAuthUsername = "private", basicAuthPassword = "secret", headers = "X-Token: saved")
            WebsiteStore.save(app, latest)
            page.click(R.string.viewer_save)
            assertEquals(latest.copy(style = "body { color: green; }"), WebsiteStore.all(app).single())
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: green"))
            page.web.webViewClient!!.onPageStarted(page.web, site.url, null)
            page.web.webViewClient!!.onPageCommitVisible(page.web, site.url)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: green"))
        }
    }

    @Test fun `saving a script reloads but manual executions do not alter its saved version`() {
        Page().use { page ->
            page.menu(R.string.website_debug)
            page.click(R.string.website_script)
            page.input(R.string.website_debug_js_input).setText("window.savedScript = true;")
            page.click(R.string.website_debug_save_reload)
            assertEquals("window.savedScript = true;", WebsiteStore.all(app).single().script)
            assertEquals(1, page.shadow.reloadInvocations)
            page.input(R.string.website_debug_js_input).setText("window.temporary = true;")
            page.click(R.string.website_debug_run)
            assertEquals("window.savedScript = true;", WebsiteStore.all(app).single().script)
        }
    }

    @Test fun `CSS preview does not inject bookmark styles into another origin`() {
        Page().use { page ->
            page.web.loadUrl("https://other.test")
            page.menu(R.string.website_debug)
            page.click(R.string.website_style)
            page.input(R.string.website_debug_css_input).setText("body { display: none; }")
            val before = page.shadow.lastEvaluatedJavascript
            page.click(R.string.website_debug_preview)
            assertEquals(before, page.shadow.lastEvaluatedJavascript)
            assertEquals(site, WebsiteStore.all(app).single())
        }
    }

    @Test fun `userdebug and eng show a system-forced status instead of a misleading toggle`() {
        for (type in listOf("userdebug", "eng")) {
            ReflectionHelpers.setStaticField(Build::class.java, "TYPE", type)
            Page().use { page ->
                assertTrue(WebsiteRemoteDebug.forcedBySystem)
                assertTrue(WebsiteRemoteDebug.enabled)
                val menu = page.activity.findViewById<Toolbar>(R.id.toolbar).menu
                val item = (0 until menu.size()).map { menu.getItem(it) }
                    .single { it.title == page.activity.getString(R.string.website_debug_pc_forced) }
                assertFalse(item.isCheckable)
                assertFalse(item.isChecked)
                page.menu(R.string.website_debug_pc_forced)
                assertTrue(WebsiteRemoteDebug.enabled)
                assertFalse(item.isCheckable)
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertTrue(dialog.findViewById<TextView>(android.R.id.message)!!.text.contains(type))
                dialog.dismiss()
                page.controller.pause().stop()
                assertTrue(WebsiteRemoteDebug.enabled)
            }
        }
    }

    @Test fun `computer debugging is off by default and turns off after the last website screen stops`() {
        Page().use { first ->
            assertFalse(WebsiteRemoteDebug.enabled)
            assertFalse(DebugWebViewShadow.remote)
            first.menu(R.string.website_debug_pc)
            assertTrue(WebsiteRemoteDebug.enabled)
            assertTrue(DebugWebViewShadow.remote)
            Page(site.copy(id = "second")).use { second ->
                assertTrue(WebsiteRemoteDebug.enabled)
                first.controller.pause().stop()
                assertTrue(WebsiteRemoteDebug.enabled)
                second.controller.pause().stop()
                assertFalse(WebsiteRemoteDebug.enabled)
                assertFalse(DebugWebViewShadow.remote)
                first.controller.start().resume()
                assertFalse(WebsiteRemoteDebug.enabled)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
