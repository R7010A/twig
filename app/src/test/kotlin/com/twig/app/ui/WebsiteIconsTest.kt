package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Website
import com.twig.app.WebsiteStore
import java.io.File
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebsiteIconsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val executor = WebsiteIcons.io
    private val site = Website(name = "Example", url = "https://example.com")

    @Before fun prepare() {
        WebsiteIcons.io = Executor { it.run() }
        WebsiteIcons.clearMemory()
        File(app.cacheDir, "website-icons").deleteRecursively()
        WebsiteStore.preferences(app).edit().clear().commit()
        WebsiteStore.save(app, site)
    }

    @After fun clean() {
        shadowOf(Looper.getMainLooper()).idle()
        WebsiteIcons.clearMemory()
        WebsiteIcons.io = executor
    }

    private fun icon(color: Int, width: Int = 32, height: Int = 32) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun receive(color: Int) {
        WebsiteIcons.received(app, site, site.url, icon(color))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `icons refresh bound rows and survive a cold cache`() {
        val left = ImageView(app)
        val right = ImageView(app)
        WebsiteIcons.bind(left, site)
        WebsiteIcons.bind(right, site)
        receive(Color.RED)
        receive(Color.BLUE)
        for (view in listOf(left, right)) {
            assertEquals(Color.BLUE, (view.drawable as BitmapDrawable).bitmap.getPixel(16, 16))
        }
        WebsiteIcons.clearMemory()
        var restored: Bitmap? = null
        WebsiteIcons.load(app, site) { restored = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Color.BLUE, requireNotNull(restored).getPixel(16, 16))
        assertEquals(Color.BLUE, requireNotNull(WebsiteDialogs.shortcut(app, site).icon?.bitmap).getPixel(16, 16))
    }

    @Test fun `recycled rows do not receive old icons`() {
        val view = ImageView(app)
        WebsiteIcons.bind(view, site)
        view.tag = null
        view.setImageBitmap(icon(Color.GREEN))
        receive(Color.RED)
        assertEquals(Color.GREEN, (view.drawable as BitmapDrawable).bitmap.getPixel(16, 16))
    }

    @Test fun `navigation to unrelated sites and deleted bookmarks cannot replace icons`() {
        WebsiteIcons.received(app, site, "https://other.example", icon(Color.RED))
        assertNull(WebsiteIcons.cached(site))
        WebsiteStore.remove(app, site.id)
        receive(Color.RED)
        assertNull(WebsiteIcons.cached(site))
        assertTrue(WebsiteIcons.matchesPage(site.copy(url = "http://example.com"), "https://www.example.com/path"))
        assertFalse(WebsiteIcons.matchesPage(site, "https://example.com:8443"))
    }

    @Test fun `large rectangular icons fit within a bounded square without cropping`() {
        WebsiteIcons.received(app, site, site.url, icon(Color.RED, 512, 256))
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = requireNotNull(WebsiteIcons.cached(site))
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(128, 0))
        assertEquals(Color.RED, bitmap.getPixel(128, 128))
    }
}
