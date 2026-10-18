package com.twig.app.ui

import android.app.Application
import android.webkit.WebView
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
class WebsiteBackgroundTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    @Test fun `background execution is opt in and closing always destroys the WebView`() {
        for (enabled in listOf(false, true)) {
            val site = Website(name = "Music", url = "https://example.com", allowBackgroundRunning = enabled)
            WebsiteStore.save(app, site)
            val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
                WebsiteActivity.intent(app, site.id)).setup()
            val web = controller.get().findViewById<WebView>(R.id.web)
            val engine = shadowOf(web)
            controller.pause().stop()
            assertEquals(!enabled, engine.wasOnPauseCalled())
            assertFalse(engine.wasDestroyCalled())
            controller.start().resume().visible()
            assertTrue(engine.wasOnResumeCalled())
            assertEquals(0, engine.reloadInvocations)
            controller.pause().stop().destroy()
            assertTrue(engine.wasDestroyCalled())
            WebsiteStore.remove(app, site.id)
        }
    }

    @Test fun `disabling background execution in a live website applies on the next pause without reloading`() {
        val site = Website(name = "Music", url = "https://example.com", allowBackgroundRunning = true)
        WebsiteStore.save(app, site)
        val intent = WebsiteActivity.intent(app, site.id)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        val web = controller.get().findViewById<WebView>(R.id.web)
        web.loadUrl("https://example.com/player")
        controller.pause().stop()
        assertFalse(shadowOf(web).wasOnPauseCalled())
        controller.start().resume().visible()
        WebsiteStore.save(app, site.copy(allowBackgroundRunning = false))
        controller.newIntent(intent)
        assertEquals("https://example.com/player", shadowOf(web).lastLoadedUrl)
        assertEquals(0, shadowOf(web).reloadInvocations)
        controller.pause().stop()
        assertTrue(shadowOf(web).wasOnPauseCalled())
        controller.destroy()
    }
}
