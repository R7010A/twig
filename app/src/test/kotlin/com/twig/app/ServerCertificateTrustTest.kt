package com.twig.app

import android.app.Application
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.twig.fs.network.ServerTls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ServerCertificateTrustTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val certificate: X509Certificate get() = javaClass.getResourceAsStream("/tls/server.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }

    private fun ask(): FutureTask<Boolean> {
        val task = FutureTask {
            ServerCertificateTrust.forApp(app).confirm("nas:443", certificate, null)
        }
        Thread(task).start()
        // Drain until the dialog is shown (or the worker finishes without a foreground Activity).
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!task.isDone && ShadowDialog.getLatestDialog()?.isShowing != true && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        return task
    }

    @Test fun approvalPersistsAcrossBridgeInstances() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        ServerCertificateTrust.onActivityResumed(activity.get())
        val task = ask()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(task.get(5, TimeUnit.SECONDS))
        assertEquals(ServerTls.fingerprint(certificate), ServerCertificateTrust.forApp(app).remembered("nas:443"))
        ServerCertificateTrust.onActivityPaused(activity.get())
        activity.pause().stop().destroy()
    }

    @Test fun leavingForegroundCancelsPromptWithoutSaving() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        ServerCertificateTrust.onActivityResumed(activity.get())
        val task = ask()
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
        ServerCertificateTrust.onActivityPaused(activity.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertNull(ServerCertificateTrust.forApp(app).remembered("nas:443"))
        activity.pause().stop().destroy()
    }

    @Test fun noForegroundActivityRejectsWithoutSaving() {
        val task = ask()
        assertFalse(task.get(5, TimeUnit.SECONDS))
        assertNull(ServerCertificateTrust.forApp(app).remembered("nas:443"))
    }
}
