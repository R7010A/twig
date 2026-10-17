package com.twig.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import com.twig.fs.network.CertificateTrust
import com.twig.fs.network.ServerTls
import java.lang.ref.WeakReference
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Device-local certificate pins, intentionally excluded from configuration backups. */
object ServerCertificateTrust : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private var foreground = WeakReference<Activity>(null)
    private var dialog: AlertDialog? = null
    private val promptLock = Any()

    fun forApp(ctx: android.content.Context): CertificateTrust {
        val app = ctx.applicationContext
        val owner = (ctx as? Activity)?.let { WeakReference(it) }
        val prefs = app.getSharedPreferences("twig_certificates", 0)
        return object : CertificateTrust {
            override fun remembered(authority: String): String? = prefs.getString(authority, null)

            override fun confirm(authority: String, certificate: X509Certificate, previous: String?): Boolean {
                // All callers are network workers. Never block the UI thread.
                if (Looper.myLooper() == Looper.getMainLooper()) return false
                synchronized(promptLock) {
                    val fingerprint = ServerTls.fingerprint(certificate)
                    if (remembered(authority) == fingerprint) return true
                    val done = CountDownLatch(1)
                    var accepted = false
                    val active = AtomicBoolean(true) // timed-out dialogs cannot save trust
                    var shown: AlertDialog? = null
                    main.post {
                        if (!active.get()) {
                            done.countDown()
                            return@post
                        }
                        val activity = foreground.get()
                        if (activity == null || activity.isFinishing || activity.isDestroyed ||
                            (owner != null && owner.get() !== activity)) {
                            done.countDown()
                            return@post
                        }
                        val message = activity.getString(
                            R.string.certificate_details, authority,
                            certificate.subjectX500Principal.name, certificate.issuerX500Principal.name,
                            java.text.DateFormat.getDateTimeInstance().format(certificate.notBefore),
                            java.text.DateFormat.getDateTimeInstance().format(certificate.notAfter), fingerprint,
                        ) + (remembered(authority)?.let {
                            "\n\n" + activity.getString(R.string.certificate_previous, it)
                        } ?: "")
                        shown = AlertDialog.Builder(activity)
                            .setTitle(if (remembered(authority) == null) R.string.certificate_untrusted else R.string.certificate_changed)
                            .setMessage(message)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(R.string.certificate_trust) { _, _ ->
                                if (active.compareAndSet(true, false)) {
                                    accepted = prefs.edit().putString(authority, fingerprint).commit()
                                }
                            }
                            .setOnDismissListener {
                                if (dialog === shown) dialog = null
                                done.countDown()
                            }
                            .show()
                        dialog = shown
                    }
                    return try {
                        done.await(2, TimeUnit.MINUTES) && accepted
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    } finally {
                        active.set(false)
                        main.post {
                            shown?.dismiss()
                        }
                    }
                }
            }
        }
    }

    override fun onActivityResumed(activity: Activity) { foreground = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) {
        if (foreground.get() === activity) {
            foreground.clear()
            dialog?.dismiss()
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
