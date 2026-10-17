package com.twig.app.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Backup
import com.twig.app.secure.Secrets
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONArray
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
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [WebsiteCustomizationTest.AuthHandlerShadow::class])
class WebsiteCustomizationTest {
    @Implements(HttpAuthHandler::class)
    class AuthHandlerShadow {
        var suitable = true
        var credentials: Pair<String, String>? = null
        var cancelled = false
        @Implementation fun useHttpAuthUsernamePassword() = suitable
        @Implementation fun proceed(username: String, password: String) { credentials = username to password }
        @Implementation fun cancel() { cancelled = true }
    }

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val site = Website(id = "custom", name = "Custom", url = "https://twig.test/site")
    private val wrapper = object : Secrets.Wrapper {
        var key: ByteArray? = null
        var unavailable = false
        override fun available() = true
        override fun wrap(dek: ByteArray): String { key = dek.copyOf(); return "test-key" }
        override fun unwrap(blob: String): ByteArray? {
            if (unavailable) throw java.security.KeyStoreException("Temporarily unavailable")
            return key?.copyOf()
        }
    }

    @Before fun clear() {
        Secrets.wrapper = wrapper
        Secrets.reset(app)
        WebsiteStore.preferences(app).edit().clear().commit()
    }

    @After fun restore() {
        Secrets.wrapper = Secrets.AndroidKeystore
    }

    private inner class Page(val saved: Website) : AutoCloseable {
        val intent = WebsiteActivity.intent(app, saved.id)
        val controller = run {
            WebsiteStore.save(app, saved)
            Robolectric.buildActivity(WebsiteActivity::class.java, intent).setup()
        }
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val shadow = shadowOf(web)
        fun edit(updated: Website) {
            WebsiteStore.save(app, updated)
            controller.newIntent(intent)
        }
        override fun close() { controller.pause().stop().destroy() }
    }

    private fun request(url: String, main: Boolean = true, method: String = "GET", redirect: Boolean = false) =
        object : WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = main
            override fun isRedirect() = redirect
            override fun hasGesture() = true
            override fun getMethod() = method
            override fun getRequestHeaders() = emptyMap<String, String>()
        }

    @Test fun `native HTTP auth supplies saved credentials only on the bookmark site and stops rejected retries`() {
        val configured = site.copy(basicAuthUsername = "alice", basicAuthPassword = "p:a ss")
        Page(configured).use { page ->
            fun challenge(host: String = "twig.test", suitable: Boolean = true): AuthHandlerShadow {
                val handler = ReflectionHelpers.callConstructor(HttpAuthHandler::class.java)
                val state = Shadow.extract<AuthHandlerShadow>(handler).apply { this.suitable = suitable }
                page.web.webViewClient!!.onReceivedHttpAuthRequest(page.web, handler, host, "Private")
                return state
            }
            assertEquals("alice" to "p:a ss", challenge().credentials)
            assertTrue(challenge(suitable = false).cancelled)
            assertTrue(challenge("other.test").cancelled)
            for (url in listOf("https://other.test", "http://twig.test", "https://twig.test:8443")) {
                page.web.loadUrl(url)
                assertTrue(challenge().cancelled)
            }
            page.web.loadUrl(site.url)
            page.edit(configured.copy(basicAuthUsername = "bob", basicAuthPassword = "new"))
            assertEquals("bob" to "new", challenge().credentials)
            page.edit(site)
            assertTrue(challenge().cancelled)
        }
        assertFalse(WebsiteCustomization.validBasicAuth("bad:name", "pass"))
        assertFalse(WebsiteCustomization.validBasicAuth("name", "bad\npass"))
    }

    @Test fun `basic auth is visible below URL with a masked password and roundtrips outside advanced`() {
        Page(site).use { page ->
            WebsiteDialogs.edit(page.activity, site)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val root = dialog.window!!.decorView
            val user = root.findViewWithTag<EditText>(R.string.website_basic_auth_username)
            val password = root.findViewWithTag<EditText>(R.string.website_basic_auth_password)
            assertTrue(user.isShown)
            assertTrue(password.isShown)
            assertTrue(password.transformationMethod is android.text.method.PasswordTransformationMethod)
            assertEquals(View.GONE, root.findViewWithTag<View>("website_advanced_fields").visibility)
            user.setText("alice")
            password.setText(" pass:word ")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            val saved = site.copy(basicAuthUsername = "alice", basicAuthPassword = " pass:word ")
            assertEquals(saved, WebsiteStore.all(app).single())
            WebsiteDialogs.edit(page.activity, saved)
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(" pass:word ", reopened.window!!.decorView
                .findViewWithTag<EditText>(R.string.website_basic_auth_password).text.toString())
            reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(saved, WebsiteStore.all(app).single())
        }
    }

    @Test fun `headers accept values containing colons but reject ambiguous or managed fields`() {
        assertEquals(mapOf("Authorization" to "Bearer abc", "X-Link" to "https://twig.test:8443/path"),
            WebsiteCustomization.parseHeaders("Authorization: Bearer abc\r\nX-Link: https://twig.test:8443/path\n"))
        for (invalid in listOf("No colon", ": value", "Bad Name: value", "X-A: 1\nx-a: 2",
            "X-A: ok\u0000bad", "X-A: first\rX-B: second", "User-Agent: fake", "Host: other.test",
            "Content-Length: 42", "Transfer-Encoding: chunked", "enc1:ciphertext")) {
            assertNull(invalid, WebsiteCustomization.parseHeaders(invalid))
        }
        assertFalse(WebsiteCustomization.validUserAgent("Agent\r\nX-Injected: yes"))
        assertTrue(WebsiteCustomization.validUserAgent("Custom browser/1.0"))
    }

    @Test fun `customizations distinguish host scheme and port and export exact browser fixtures`() {
        val configured = site.copy(headers = "X-Token: local")
        assertEquals(mapOf("X-Token" to "local"), WebsiteCustomization.headersFor(configured, "https://TWIG.test:443/path"))
        for (url in listOf("https://www.twig.test", "http://twig.test", "https://twig.test:8443", "https://other.test")) {
            assertTrue(url, WebsiteCustomization.headersFor(configured, url).isEmpty())
        }
        val fixture = site.copy(style = "body { color: rgb(12, 34, 56); } #ad { display: none; }",
            script = "window.twigRuns = (window.twigRuns || 0) + 1; document.querySelector('#label').textContent = 'updated';")
        // tools/test_website_customization.py executes these exact production-generated snippets.
        val data = JSONObject().put("style", WebsiteCustomization.styleScript(fixture))
            .put("updatedStyle", WebsiteCustomization.styleScript(fixture.copy(style = "body { color: rgb(90, 80, 70); }")))
            .put("clearStyle", WebsiteCustomization.styleScript(fixture.copy(style = "")))
            .put("literalStyle", WebsiteCustomization.styleScript(fixture.copy(style = "body::after { content: '\"\\\\ </style> \u2028\u2029'; }")))
            .put("hiddenOnly", WebsiteCustomization.styleScript(site.copy(hiddenElements = "#ad\ninvalid[\nbody { color: red; }\n#later\n#space\\ ")))
            .put("keepCssClearHidden", WebsiteCustomization.styleScript(site.copy(style = "body { color: rgb(12, 34, 56); }")))
            .put("script", WebsiteCustomization.pageScript(fixture))
            .put("throwingScript", WebsiteCustomization.pageScript(fixture.copy(script = "throw new Error('expected test error');")))
            .put("invalidScript", WebsiteCustomization.pageScript(fixture.copy(script = "let = ;")))
        File("build/reports/website-customization/scripts.json").apply {
            parentFile.mkdirs()
            writeText(data.toString())
        }
    }

    @Test fun `headers apply to initial and same-origin GET navigation but not forms or frames`() {
        Page(site.copy(headers = "Authorization: Bearer test\nX-Name: custom")).use { page ->
            assertEquals(mapOf("Authorization" to "Bearer test", "X-Name" to "custom"), page.shadow.lastAdditionalHttpHeaders)
            val client = requireNotNull(page.web.webViewClient)
            assertTrue(client.shouldOverrideUrlLoading(page.web, request("https://twig.test/next")))
            assertEquals("https://twig.test/next", page.shadow.lastLoadedUrl)
            assertEquals("Bearer test", page.shadow.lastAdditionalHttpHeaders["Authorization"])
            for (req in listOf(request("https://other.test"), request("https://twig.test/frame", main = false),
                request("https://twig.test/form", method = "POST"), request("https://twig.test/redirect", redirect = true))) {
                assertFalse(client.shouldOverrideUrlLoading(page.web, req))
                assertEquals("https://twig.test/next", page.shadow.lastLoadedUrl)
            }
            assertTrue(client.shouldOverrideUrlLoading(page.web, request("https://other.test/redirect", redirect = true)))
            assertEquals("https://other.test/redirect", page.shadow.lastLoadedUrl)
            assertTrue(page.shadow.lastAdditionalHttpHeaders.isNullOrEmpty())
        }
    }

    @Test fun `custom UA overrides desktop UA and clearing it restores the chosen default`() {
        Page(site.copy(desktopSite = true, userAgent = "My browser/1.0")).use { page ->
            assertEquals("My browser/1.0", page.web.settings.userAgentString)
            page.edit(site.copy(desktopSite = true))
            assertTrue(page.web.settings.userAgentString.contains("X11; Linux x86_64"))
            assertEquals(1, page.shadow.reloadInvocations)
            page.edit(site.copy(userAgent = "Second browser/2.0"))
            assertEquals("Second browser/2.0", page.web.settings.userAgentString)
        }
    }

    @Test fun `CSS updates and clears live while script changes reload the current page`() {
        val configured = site.copy(style = "body { color: red; }")
        Page(configured).use { page ->
            val client = requireNotNull(page.web.webViewClient)
            client.onPageFinished(page.web, configured.url)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("__twig_user_style_custom"))
            page.edit(configured.copy(style = "body { color: blue; }"))
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("color: blue"))
            assertEquals(0, page.shadow.reloadInvocations)
            page.edit(site)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("if (!css)"))
            assertEquals(0, page.shadow.reloadInvocations)
            page.edit(site.copy(script = "window.custom = true;"))
            assertEquals(1, page.shadow.reloadInvocations)
            page.web.loadUrl("https://other.test/")
            client.onPageFinished(page.web, "https://other.test/")
            assertFalse(page.shadow.lastEvaluatedJavascript.contains("window.custom = true"))
        }
    }

    @Test fun `hidden selectors apply without custom CSS and removing them restores live without reload`() {
        val configured = site.copy(hiddenElements = "#ad\n.banner")
        Page(configured).use { page ->
            page.web.webViewClient!!.onPageFinished(page.web, site.url)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("__twig_hidden_elements_"))
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("#ad"))
            page.edit(site)
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("if (!selectors.length)"))
            assertTrue(page.shadow.lastEvaluatedJavascript.contains("const selectors = []"))
            assertEquals(0, page.shadow.reloadInvocations)
        }
        assertEquals(listOf("#space\\ "), WebsiteCustomization.hiddenSelectors("#space\\ "))
    }

    @Test fun `header edits reload with new headers while unchanged reopen preserves the live page`() {
        val configured = site.copy(headers = "X-Token: first")
        Page(configured).use { page ->
            page.web.loadUrl("https://twig.test/draft")
            page.edit(configured.copy(headers = "X-Token: second"))
            assertEquals("https://twig.test/draft", page.shadow.lastLoadedUrl)
            assertEquals(mapOf("X-Token" to "second"), page.shadow.lastAdditionalHttpHeaders)
            page.web.scrollTo(0, 333)
            page.controller.newIntent(page.intent)
            assertEquals(333, page.web.scrollY)
            assertEquals(0, page.shadow.reloadInvocations)
        }
    }

    @Test fun `editor validates headers and saves customizations and hidden selectors together`() {
        Page(site).use { page ->
            WebsiteDialogs.edit(page.activity, site)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val advanced = dialog.window!!.decorView.findViewWithTag<View>("website_advanced_fields")
            val advancedHeader = dialog.window!!.decorView.findViewWithTag<View>(R.string.website_advanced)
            assertEquals(View.GONE, advanced.visibility)
            advancedHeader.performClick()
            assertEquals(View.VISIBLE, advanced.visibility)
            fun field(key: Int) = dialog.window!!.decorView.findViewWithTag<EditText>(key)
            field(R.string.website_user_agent).setText("Custom/2.0")
            field(R.string.website_script).setText("window.changed = true;")
            field(R.string.website_style).setText("#ad { display: none; }")
            field(R.string.website_headers).setText("Broken header")
            advancedHeader.performClick()
            assertEquals(View.GONE, advanced.visibility)
            assertEquals("Custom/2.0", field(R.string.website_user_agent).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertEquals(View.VISIBLE, advanced.visibility)
            assertNotNull(field(R.string.website_headers).error)
            assertEquals(site, WebsiteStore.all(app).single())
            field(R.string.website_headers).setText("X-Auth: secret")
            field(R.string.website_hidden_elements).setText("#ad\n.banner")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            assertEquals(site.copy(userAgent = "Custom/2.0", script = "window.changed = true;",
                style = "#ad { display: none; }", headers = "X-Auth: secret", hiddenElements = "#ad\n.banner"), WebsiteStore.all(app).single())
            val saved = WebsiteStore.all(app).single()
            WebsiteDialogs.edit(page.activity, saved)
            shadowOf(Looper.getMainLooper()).idle()
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            assertEquals(View.GONE, reopened.window!!.decorView.findViewWithTag<View>("website_advanced_fields").visibility)
            reopened.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(saved, WebsiteStore.all(app).single())
        }
    }

    @Test fun `headers are encrypted on disk and backups reencrypt them for another device`() {
        val configured = site.copy(headers = "Authorization: Bearer unique-test-secret", script = "window.ok = true;",
            style = "body { color: green; }", userAgent = "Custom/1.0",
            basicAuthUsername = "private-user", basicAuthPassword = "private-pass", hiddenElements = "#ad\n.banner")
        WebsiteStore.save(app, configured)
        val stored = WebsiteStore.preferences(app).getString("list", "")!!
        assertFalse(stored.contains("unique-test-secret"))
        assertFalse(stored.contains("private-user"))
        assertFalse(stored.contains("private-pass"))
        val cipher = JSONArray(stored).getJSONObject(0).getString("headers")
        assertTrue(cipher.startsWith(Secrets.PREFIX))
        val output = ByteArrayOutputStream()
        Backup.export(app, output, null)
        assertTrue(output.toString("UTF-8").contains("unique-test-secret"))
        Secrets.reset(app)
        wrapper.key = null
        WebsiteStore.preferences(app).edit().clear().commit()
        Backup.import(app, output.toByteArray(), null)
        assertEquals(configured, WebsiteStore.all(app).single())
        val newCipher = JSONArray(WebsiteStore.preferences(app).getString("list", "")).getJSONObject(0).getString("headers")
        assertNotEquals(cipher, newCipher)
        assertFalse(newCipher.contains("unique-test-secret"))
    }

    @Test fun `temporarily unavailable keys preserve encrypted headers and never send ciphertext`() {
        val configured = site.copy(headers = "X-Token: private-token", basicAuthUsername = "private-user", basicAuthPassword = "private-pass")
        WebsiteStore.save(app, configured)
        val cipher = JSONArray(WebsiteStore.preferences(app).getString("list", "")).getJSONObject(0).getString("headers")
        Secrets.lock()
        wrapper.unavailable = true
        val locked = WebsiteStore.all(app).single()
        assertEquals(cipher, locked.headers)
        assertTrue(locked.basicAuthPassword.startsWith(Secrets.PREFIX))
        assertNull(WebsiteCustomization.basicAuthFor(locked, locked.url, "twig.test"))
        assertTrue(WebsiteCustomization.headersFor(locked, locked.url).isEmpty())
        WebsiteStore.save(app, locked.copy(name = "Renamed"))
        val before = WebsiteStore.preferences(app).getString("list", "")!!
        assertEquals(cipher, JSONArray(before).getJSONObject(0).getString("headers"))
        assertThrows(Secrets.KeyUnavailable::class.java) { WebsiteStore.save(app, configured.copy(headers = "X-Token: replacement")) }
        assertEquals(before, WebsiteStore.preferences(app).getString("list", ""))
        assertThrows(Secrets.KeyUnavailable::class.java) { WebsiteStore.exportJson(app) }
        wrapper.unavailable = false
        assertEquals(configured.copy(name = "Renamed"), WebsiteStore.all(app).single())
        assertThrows(Exception::class.java) { WebsiteStore.importJson(app, "{broken") }
        assertEquals(before, WebsiteStore.preferences(app).getString("list", ""))
    }
}
