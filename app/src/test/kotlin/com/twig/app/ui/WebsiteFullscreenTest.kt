package com.twig.app.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
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
class WebsiteFullscreenTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() {
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    private inner class Page(val site: Website = Website(name = "Video", url = "https://example.com")) : AutoCloseable {
        val controller = run {
            WebsiteStore.save(app, site)
            Robolectric.buildActivity(WebsiteActivity::class.java,
                WebsiteActivity.intent(app, site.id)).setup()
        }
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val root = activity.findViewById<Toolbar>(R.id.toolbar).parent as View
        val client = requireNotNull(web.webChromeClient)
        fun show(): Request = Request(View(activity)).also { request ->
            client.onShowCustomView(request.view, WebChromeClient.CustomViewCallback { request.hidden++ })
        }
        override fun close() {
            controller.pause().stop().destroy()
        }
    }
    private class Request(val view: View, var hidden: Int = 0)

    @Test fun `fullscreen attaches the engine view and exits back to the same page`() {
        Page().use { page ->
            page.web.loadUrl("https://example.com/watch/current")
            page.web.scrollTo(0, 200)
            val request = page.show()
            val layer = request.view.parent as ViewGroup
            assertSame(page.activity.window.decorView, layer.parent)
            assertEquals(Color.BLACK, (layer.background as ColorDrawable).color)
            assertTrue(layer.keepScreenOn)
            assertEquals(View.GONE, page.root.visibility)
            page.activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(1, request.hidden)
            assertNull(request.view.parent)
            assertNull(layer.parent)
            assertFalse(layer.keepScreenOn)
            assertEquals(View.VISIBLE, page.root.visibility)
            assertFalse(page.activity.isFinishing)
            assertSame(page.web, page.activity.findViewById(R.id.web))
            assertEquals("https://example.com/watch/current", shadowOf(page.web).lastLoadedUrl)
            assertEquals(0, shadowOf(page.web).reloadInvocations)
            assertEquals(200, page.web.scrollY)
            page.client.onHideCustomView()
            assertEquals("Repeated engine callbacks must be harmless", 1, request.hidden)
        }
    }

    @Test
    @Config(sdk = [28])
    fun `video fullscreen overrides bars temporarily and restores each bookmark setting`() {
        for (hideStatus in listOf(false, true)) for (hideNavigation in listOf(false, true)) {
            Page(Website(name = "Video", url = "https://example.com", fullscreen = hideStatus,
                hideStatusBar = hideStatus, hideNavigationBar = hideNavigation)).use { page ->
                val request = page.show()
                var flags = page.activity.window.decorView.systemUiVisibility
                assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
                assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
                page.client.onHideCustomView()
                flags = page.activity.window.decorView.systemUiVisibility
                assertEquals(hideStatus, flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
                assertEquals(hideNavigation, flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
                assertEquals(if (hideStatus) View.GONE else View.VISIBLE,
                    page.activity.findViewById<Toolbar>(R.id.toolbar).visibility)
                assertEquals(1, request.hidden)
                assertEquals(page.site, WebsiteStore.all(app).first { it.id == page.site.id })
            }
        }
    }

    @Test fun `duplicate fullscreen requests are rejected without replacing the active video`() {
        Page().use { page ->
            val first = page.show()
            val layer = first.view.parent
            val duplicate = page.show()
            assertEquals(0, first.hidden)
            assertEquals(1, duplicate.hidden)
            assertNull(duplicate.view.parent)
            assertSame(layer, first.view.parent)
            page.client.onHideCustomView()
            assertEquals(1, first.hidden)
            assertEquals(1, duplicate.hidden)
        }
    }

    @Test fun `destroying the activity releases the fullscreen view and callback exactly once`() {
        val page = Page()
        val request = page.show()
        val layer = request.view.parent as View
        page.close()
        assertEquals(1, request.hidden)
        assertNull(request.view.parent)
        assertNull(layer.parent)
        assertFalse(layer.keepScreenOn)
    }

    @Test fun `rotating the device keeps the webpage and active fullscreen view`() {
        Page().use { page ->
            val request = page.show()
            val layer = request.view.parent
            val config = Configuration(page.activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 800
                screenHeightDp = 400
            }
            page.controller.configurationChange(config)
            assertSame(page.activity, page.controller.get())
            assertSame(page.web, page.controller.get().findViewById(R.id.web))
            assertSame(layer, request.view.parent)
            assertEquals(0, request.hidden)
            assertEquals(View.GONE, page.root.visibility)
        }
    }

    @Test fun `changing the bookmarked URL leaves fullscreen before navigating`() {
        Page().use { page ->
            val request = page.show()
            WebsiteStore.save(app, page.site.copy(url = "https://example.org/new"))
            page.controller.newIntent(WebsiteActivity.intent(app, page.site.id))
            assertEquals(1, request.hidden)
            assertNull(request.view.parent)
            assertEquals(View.VISIBLE, page.root.visibility)
            assertEquals("https://example.org/new", shadowOf(page.web).lastLoadedUrl)
        }
    }
}
