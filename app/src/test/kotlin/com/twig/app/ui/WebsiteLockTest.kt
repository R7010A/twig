package com.twig.app.ui

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Secrets
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [WebsiteCustomizationTest.AuthHandlerShadow::class])
class WebsiteLockTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var site: Website
    private var page: ActivityController<WebsiteActivity>? = null
    private val activity get() = page!!.get()
    private val web get() = activity.findViewById<WebView>(R.id.web)
    private val cover get() = activity.window.decorView.findViewWithTag<View>("website_lock_cover")

    private object MemoryWrapper : Secrets.Wrapper {
        private var key: ByteArray? = null
        override fun available() = true
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "memory" }
        override fun unwrap(blob: String) = key?.copyOf()
    }

    @Before fun setup() {
        Secrets.wrapper = MemoryWrapper
        Secrets.reset(app)
        WebsiteStore.preferences(app).edit().clear().commit()
        site = Website(name = "Private", url = "https://example.com", allowBackgroundRunning = true,
            basicAuthUsername = "alice", basicAuthPassword = "private-password")
        WebsiteStore.save(app, site)
        Secrets.enableMasterPassword(app, "master-password".toCharArray())
        CookieManager.getInstance().setCookie(site.url, "session=existing-login")
        ShadowDialog.reset()
    }

    @After fun cleanup() {
        page?.pause()?.stop()?.destroy()
        Secrets.reset(app)
        Secrets.wrapper = Secrets.AndroidKeystore
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    private fun open() {
        page = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, site.id)).setup()
    }

    private fun unlock() {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        fun input(view: View): EditText? {
            if (view is EditText) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) input(view.getChildAt(i))?.let { return it }
            return null
        }
        input(dialog.window!!.decorView)!!.setText("master-password")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while ((Secrets.locked(app) || cover != null) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertFalse(Secrets.locked(app))
        assertNull(cover)
    }

    @Test fun `locked shortcut loads nothing until unlock then authenticates with decrypted Basic credentials`() {
        Secrets.lock()
        open()
        assertNull(shadowOf(web).lastLoadedUrl)
        assertNotNull(cover)
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(WebsiteBackground.entries().isEmpty())
        page!!.newIntent(WebsiteActivity.intent(app, site.id))
        assertEquals(1, ShadowDialog.getShownDialogs().count { it.isShowing })
        unlock()
        assertEquals(site.url, shadowOf(web).lastLoadedUrl)
        assertTrue(WebsiteBackground.entries().any { it.id == site.id })
        val handler = ReflectionHelpers.callConstructor(HttpAuthHandler::class.java)
        web.webViewClient.onReceivedHttpAuthRequest(web, handler, "example.com", "Private")
        assertEquals("alice" to "private-password",
            Shadow.extract<WebsiteCustomizationTest.AuthHandlerShadow>(handler).credentials)
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }

    @Test fun `recents unlock keeps the same authenticated page and background session without reloading`() {
        open()
        val original = web
        web.loadUrl("https://example.com/player")
        page!!.pause().stop()
        Secrets.lock()
        page!!.start().resume().visible()
        assertNotNull(cover)
        assertFalse(shadowOf(web).wasOnPauseCalled())
        assertTrue(WebsiteBackground.entries().any { it.id == site.id })
        unlock()
        assertSame(original, web)
        assertEquals("https://example.com/player", shadowOf(web).lastLoadedUrl)
        assertEquals(0, shadowOf(web).reloadInvocations)
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }

    @Test fun `reused shortcut defers edited Basic credentials until unlock`() {
        open()
        web.loadUrl("https://example.com/private")
        WebsiteStore.save(app, site.copy(basicAuthPassword = "new-password"))
        page!!.pause().stop()
        Secrets.lock()
        page!!.newIntent(WebsiteActivity.intent(app, site.id)).start().resume().visible()
        assertNotNull(cover)
        assertEquals("https://example.com/private", shadowOf(web).lastLoadedUrl)
        unlock()
        val handler = ReflectionHelpers.callConstructor(HttpAuthHandler::class.java)
        web.webViewClient.onReceivedHttpAuthRequest(web, handler, "example.com", "Private")
        assertEquals("alice" to "new-password",
            Shadow.extract<WebsiteCustomizationTest.AuthHandlerShadow>(handler).credentials)
    }

    @Test fun `cancelling unlock closes a cold website without loading or deleting its login cookie`() {
        Secrets.lock()
        open()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(web).lastLoadedUrl)
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertEquals("session=existing-login", CookieManager.getInstance().getCookie(site.url))
    }
}
