package com.twig.app.ui

import android.app.Application
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.ServerCertificateTrust
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.fs.network.ServerTls
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteCertificateTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<WebsiteActivity>
    private lateinit var web: WebView
    private val certificate: X509Certificate get() = javaClass.getResourceAsStream("/tls/server.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    @Before fun setup() {
        app.registerActivityLifecycleCallbacks(ServerCertificateTrust)
        app.getSharedPreferences("twig_certificates", 0).edit().clear().commit()
        val site = Website(name = "NAS", url = "https://nas:8443")
        WebsiteStore.save(app, site)
        controller = Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, site.id)).setup()
        web = controller.get().findViewById(R.id.web)
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        app.unregisterActivityLifecycleCallbacks(ServerCertificateTrust)
    }

    private fun failTls(url: String = "https://nas:8443/image.png", cert: SslCertificate = SslCertificate(certificate)): SslErrorHandler {
        val handler = ReflectionHelpers.callConstructor(SslErrorHandler::class.java)
        web.webViewClient.onReceivedSslError(web, handler, SslError(SslError.SSL_UNTRUSTED, cert, url))
        return handler
    }

    private fun dialog(): AlertDialog {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ShadowDialog.getLatestDialog()?.isShowing != true && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        return (ShadowDialog.getLatestDialog() as AlertDialog).also { assertTrue(it.isShowing) }
    }

    private fun awaitResult(handler: SslErrorHandler) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!shadowOf(handler).wasProceedCalled() && !shadowOf(handler).wasCancelCalled() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }

    @Test fun approvalResumesRequestAndReusesBackendTrustWithoutPrompt() {
        val handler = failTls()
        assertFalse(shadowOf(handler).wasProceedCalled())
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitResult(handler)
        assertTrue(shadowOf(handler).wasProceedCalled())
        assertFalse(shadowOf(handler).wasCancelCalled())
        assertEquals(ServerTls.fingerprint(certificate), ServerCertificateTrust.forApp(app).remembered("nas:8443"))
        val again = failTls()
        assertTrue(shadowOf(again).wasProceedCalled())
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test fun cancellationRejectsAndDoesNotRemember() {
        val handler = failTls()
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        awaitResult(handler)
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
        assertNull(ServerCertificateTrust.forApp(app).remembered("nas:8443"))
    }

    @Test fun changedCertificateRequiresNewApprovalAndPreservesOldTrustOnCancel() {
        app.getSharedPreferences("twig_certificates", 0).edit().putString("nas:8443", "old-fingerprint").commit()
        val handler = failTls()
        val dialog = dialog()
        assertEquals(controller.get().getString(R.string.certificate_changed),
            dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        awaitResult(handler)
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertEquals("old-fingerprint", ServerCertificateTrust.forApp(app).remembered("nas:8443"))
    }

    @Test fun subresourceUsesItsOwnHostAndPortRatherThanBookmarkAddress() {
        app.getSharedPreferences("twig_certificates", 0).edit()
            .putString("nas:8443", ServerTls.fingerprint(certificate)).commit()
        val handler = failTls("https://cdn:9443/image.png")
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitResult(handler)
        assertTrue(shadowOf(handler).wasProceedCalled())
        assertEquals(ServerTls.fingerprint(certificate), ServerCertificateTrust.forApp(app).remembered("cdn:9443"))
    }

    @Test fun leavingPageCancelsPendingRequest() {
        val handler = failTls()
        dialog()
        controller.pause()
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
        assertNull(ServerCertificateTrust.forApp(app).remembered("nas:8443"))
        controller.resume()
    }

    @Test fun missingEncodedCertificateIsRejected() {
        val handler = failTls(cert = SslCertificate("subject", "issuer", java.util.Date(), java.util.Date()))
        assertTrue(shadowOf(handler).wasCancelCalled())
        assertFalse(shadowOf(handler).wasProceedCalled())
    }
}
