package com.twig.app.ui

import android.app.Application
import android.net.Uri
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Backup
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteFeatureTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    @Test fun `accept only web addresses and default bare hosts to https`() {
        assertEquals("https://example.com/path?q=a#b", WebsiteStore.normalizeUrl(" example.com/path?q=a#b "))
        assertEquals("http://192.168.1.2:8080/", WebsiteStore.normalizeUrl("http://192.168.1.2:8080/"))
        for (url in listOf("", "file:///data/data/com.twig.app/test", "content://private/data",
            "javascript:alert(1)", "intent://example.com", "https://", "https://a b.com",
            "https://example.com:99999", "https://user:pass@example.com")) {
            assertNull(url, WebsiteStore.normalizeUrl(url))
        }
    }

    @Test fun `editing preserves order and identity even for duplicate URLs`() {
        val first = Website(name = "First", url = "https://example.com")
        val second = Website(name = "Second", url = first.url)
        WebsiteStore.save(app, first)
        WebsiteStore.save(app, second)
        val edited = first.copy(name = "Edited", url = "https://example.org", fullscreen = true)
        WebsiteStore.save(app, edited)
        assertEquals(listOf(edited, second), WebsiteStore.all(app))
        WebsiteStore.remove(app, first.id)
        assertEquals(listOf(second), WebsiteStore.all(app))
    }

    @Test fun `shortcut reads updated bookmark including fullscreen instead of stale intent extras`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val shortcut = WebsiteDialogs.shortcut(app, site)
        val edited = site.copy(url = "https://example.org", fullscreen = true)
        WebsiteStore.save(app, edited)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, shortcut.intent).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        assertEquals(edited.url, shadowOf(web).lastLoadedUrl)
        assertEquals(View.GONE, activity.findViewById<Toolbar>(R.id.toolbar).visibility)
        assertTrue(web.settings.javaScriptEnabled)
        assertTrue(web.settings.domStorageEnabled)
        assertTrue(CookieManager.getInstance().acceptCookie())
        assertFalse(web.settings.allowFileAccess)
        assertFalse(web.settings.allowContentAccess)
        controller.pause().stop().destroy()
    }

    @Test fun `ordinary bookmark has a toolbar and deleting a bookmark makes its shortcut exit`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        assertEquals(View.VISIBLE, controller.get().findViewById<Toolbar>(R.id.toolbar).visibility)
        controller.pause().stop().destroy()
        WebsiteStore.remove(app, site.id)
        val missing = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        assertTrue(missing.get().isFinishing)
        missing.pause().stop().destroy()
    }

    @Test fun `reopening a live website keeps the current page and WebView instance`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val currentPage = "https://example.com/account/draft"
        web.loadUrl(currentPage)
        web.scrollTo(0, 480)
        // The tree and desktop shortcut address the same document task.
        for (intent in listOf(WebsiteActivity.intent(app, site.id), WebsiteDialogs.shortcut(app, site).intent)) {
            controller.newIntent(intent)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(activity.isDestroyed)
            assertSame(activity, controller.get())
            assertSame(web, controller.get().findViewById<WebView>(R.id.web))
            assertEquals(currentPage, shadowOf(web).lastLoadedUrl)
            assertEquals(480, web.scrollY)
        }
        controller.pause().stop().destroy()
    }

    @Test fun `old records preserve display options and enable toolbar double tap by default`() {
        WebsiteStore.preferences(app).edit().putString("list", """[
            {"id":"old","name":"Old","url":"https://example.com","fullscreen":true},
            {"id":"normal","name":"Normal","url":"https://example.org"}
        ]""").commit()
        val sites = WebsiteStore.all(app)
        assertFalse(sites[0].allowBackgroundRunning)
        assertTrue(sites[0].doubleTapToolbar)
        assertTrue(sites[0].fullscreen)
        assertTrue(sites[0].hideStatusBar)
        assertTrue(sites[0].hideNavigationBar)
        assertFalse(sites[0].pullToRefresh)
        assertFalse(sites[0].disableTextSelection)
        assertFalse(sites[0].lockScrollAxis)
        assertFalse(sites[0].desktopSite)
        assertEquals(Website("normal", "Normal", "https://example.org"), sites[1])
    }

    @Test fun `editing display and gesture options preserves the live page and supports undo`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebsiteWebView>(R.id.web)
        web.loadUrl("https://example.com/draft")
        web.scrollTo(0, 400)
        val enabled = site.copy(fullscreen = true, hideStatusBar = true, hideNavigationBar = false,
            pullToRefresh = true, disableTextSelection = true, lockScrollAxis = true)
        for (saved in listOf(enabled, site)) {
            WebsiteStore.save(app, saved)
            controller.newIntent(intent)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame(web, activity.findViewById(R.id.web))
            assertFalse(activity.isDestroyed)
            assertEquals("https://example.com/draft", shadowOf(web).lastLoadedUrl)
            assertEquals(0, shadowOf(web).reloadInvocations)
            assertEquals(400, web.scrollY)
            assertEquals(saved.pullToRefresh, web.pullToRefresh)
            assertEquals(saved.lockScrollAxis, web.lockScrollAxis)
            assertEquals(if (saved.fullscreen) View.GONE else View.VISIBLE,
                activity.findViewById<Toolbar>(R.id.toolbar).visibility)
            assertNull("Text selection settings must not consume page long presses",
                shadowOf(web).onLongClickListener)
        }
        controller.pause().stop().destroy()
    }

    @Test fun `refresh requires a positive response from the actual page probe`() {
        val site = Website(name = "Example", url = "https://example.com", pullToRefresh = true)
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        // Robolectric's native WebView delegate does not implement scrolling; keep
        // that boundary deterministic while exercising the real asset and JS callback.
        val web = object : WebsiteWebView(controller.get()) {
            override fun canScrollVertically(direction: Int) = false
            override fun onTouchEvent(event: android.view.MotionEvent) = true
        }.apply {
            pullToRefresh = true
            onRefresh = { reload() }
            // The mock WebView provider also skips setFrame during layout.
            right = 1000
            bottom = 1600
        }
        fun touch(action: Int, y: Float) {
            val event = android.view.MotionEvent.obtain(0, 100, action, 100f, y, 0)
            web.dispatchTouchEvent(event)
            event.recycle()
        }
        assertTrue(web.pullToRefresh)
        assertEquals(1000, web.width)
        assertEquals(1600, web.height)
        for (reply in listOf("false", "null", "true")) {
            touch(android.view.MotionEvent.ACTION_DOWN, 100f)
            val callback = shadowOf(web).lastEvaluatedJavascriptCallback
            assertNotNull("Touch must query the page scroll containers", callback)
            callback.onReceiveValue(reply)
            touch(android.view.MotionEvent.ACTION_MOVE, 700f)
            touch(android.view.MotionEvent.ACTION_UP, 700f)
            assertEquals(if (reply == "true") 1 else 0, shadowOf(web).reloadInvocations)
        }
        web.destroy()
        controller.pause().stop().destroy()
    }

    @Test fun `live bookmark updates cannot bypass URL validation`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        val web = controller.get().findViewById<WebView>(R.id.web)
        val invalid = "javascript:alert(document.cookie)"
        WebsiteStore.save(app, site.copy(url = invalid))
        controller.newIntent(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotEquals(invalid, shadowOf(web).lastLoadedUrl)
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun `desktop user agent preserves the installed engine version and toggle reloads current page`() {
        val mobile = "Mozilla/5.0 (Linux; Android 14; Phone; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.0.0 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
            WebsiteActivity.desktopUserAgent(mobile))
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val mobileAgent = web.settings.userAgentString
        web.loadUrl("https://example.com/current")
        for ((index, desktop) in listOf(true, false).withIndex()) {
            WebsiteStore.save(app, site.copy(desktopSite = desktop))
            controller.newIntent(intent)
            assertSame(web, activity.findViewById(R.id.web))
            assertEquals(index + 1, shadowOf(web).reloadInvocations)
            assertEquals("https://example.com/current", shadowOf(web).lastLoadedUrl)
            assertEquals(desktop, web.settings.loadWithOverviewMode)
            assertEquals(if (desktop) WebsiteActivity.desktopUserAgent(mobileAgent) else mobileAgent,
                web.settings.userAgentString)
        }
        controller.pause().stop().destroy()
    }

    @Test
    @Config(sdk = [28])
    fun `status navigation and toolbar visibility are independent`() {
        for (hideStatus in listOf(false, true)) for (hideNavigation in listOf(false, true)) {
            val site = Website(name = "Example", url = "https://example.com", fullscreen = false,
                hideStatusBar = hideStatus, hideNavigationBar = hideNavigation)
            WebsiteStore.save(app, site)
            val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
                WebsiteActivity.intent(app, site.id)).setup()
            val activity = controller.get()
            val flags = activity.window.decorView.systemUiVisibility
            assertEquals(hideStatus, flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
            assertEquals(hideNavigation, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
            assertEquals(View.VISIBLE, activity.findViewById<Toolbar>(R.id.toolbar).visibility)
            controller.pause().stop().destroy()
        }
    }

    @Test fun `edit dialog round trips each option independently`() {
        val site = Website(name = "Example", url = "https://example.com", fullscreen = true,
            hideStatusBar = false, hideNavigationBar = true, pullToRefresh = false,
            disableTextSelection = true, lockScrollAxis = false, desktopSite = true)
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        WebsiteDialogs.edit(controller.get(), site)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val options = listOf(R.string.website_open_fullscreen to site.openFullscreen,
            R.string.website_fullscreen to site.fullscreen,
            R.string.website_double_tap_toolbar to site.doubleTapToolbar,
            R.string.website_allow_background_running to site.allowBackgroundRunning,
            R.string.website_hide_status_bar to site.hideStatusBar,
            R.string.website_hide_navigation_bar to site.hideNavigationBar,
            R.string.website_pull_to_refresh to site.pullToRefresh,
            R.string.website_disable_text_selection to site.disableTextSelection,
            R.string.website_lock_scroll_axis to site.lockScrollAxis,
            R.string.website_desktop_site to site.desktopSite)
        for ((key, value) in options) {
            val checkbox = dialog.window!!.decorView.findViewWithTag<android.widget.CheckBox>(key)
            assertEquals(value, checkbox.isChecked)
            checkbox.isChecked = !value
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(site.copy(fullscreen = false, hideStatusBar = true, hideNavigationBar = false,
            pullToRefresh = true, disableTextSelection = false, lockScrollAxis = true, desktopSite = false,
            openFullscreen = true, doubleTapToolbar = false, allowBackgroundRunning = true),
            WebsiteStore.all(app).single())
        controller.pause().stop().destroy()
    }

    private fun request(value: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun `web navigation stays inside the viewer but cannot open local data`() {
        val site = Website(name = "Example", url = "https://example.com")
        WebsiteStore.save(app, site)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, site.id)).setup()
        val web = controller.get().findViewById<WebView>(R.id.web)
        assertFalse(web.webViewClient.shouldOverrideUrlLoading(web, request("https://example.org/login")))
        for (url in listOf("file:///data/data/com.twig.app/test", "content://com.twig.app.stream/test",
            "intent://example.com/#Intent;scheme=https;end", "javascript:alert(1)")) {
            assertTrue(url, web.webViewClient.shouldOverrideUrlLoading(web, request(url)))
        }
        controller.pause().stop().destroy()
    }

    @Test fun `configuration backup restores bookmarks and fullscreen preferences`() {
        val site = Website(name = "Example", url = "https://example.com", fullscreen = true,
            hideStatusBar = false, hideNavigationBar = true, pullToRefresh = true,
            disableTextSelection = true, lockScrollAxis = true, desktopSite = true, openFullscreen = true,
            orientation = com.twig.app.WebsiteOrientation.LANDSCAPE)
        WebsiteStore.save(app, site)
        val out = ByteArrayOutputStream()
        Backup.export(app, out, null)
        clear()
        Backup.import(app, out.toByteArray(), null)
        assertEquals(listOf(site), WebsiteStore.all(app))
    }

    @Test fun `tree renders website entries as actions with unique keys and an add row`() {
        val first = Website(name = "First", url = "https://example.com")
        val second = Website(name = "Second", url = first.url)
        WebsiteStore.save(app, first)
        WebsiteStore.save(app, second)
        val vm = PaneViewModel(app)
        vm.refreshTree()
        val group = vm.state.value.rows.filterIsInstance<PaneViewModel.GroupNode>().first { it.id == "websites" }
        vm.toggle(group)
        val rows = vm.state.value.rows
        assertEquals(listOf(first, second), rows.filterIsInstance<PaneViewModel.WebsiteNode>().map { it.site })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows.any { it is PaneViewModel.ActionNode && it.id == "add_website" })
        assertNull(vm.state.value.currentDir)
    }
}
