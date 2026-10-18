package com.twig.app.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import java.util.WeakHashMap

/** Main-thread session ownership. Only explicitly opted-in, live website activities hold the service. */
internal object WebsiteBackground {
    data class Entry(val id: String, val name: String)
    private val owners = WeakHashMap<WebsiteActivity, Entry>()
    var service: WebsiteBackgroundService? = null
        private set

    fun entries(): List<Entry> = owners.values.toList()

    fun update(owner: WebsiteActivity, site: Website) {
        if (!site.allowBackgroundRunning || owner.isFinishing || owner.isDestroyed) {
            release(owner)
            return
        }
        // Never initiate a new foreground service from a stopped page or a saved preference alone.
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        owners[owner] = Entry(site.id, site.name)
        val active = service
        if (active != null) active.refresh() else try {
            ContextCompat.startForegroundService(owner, Intent(owner, WebsiteBackgroundService::class.java))
        } catch (e: RuntimeException) {
            owners.remove(owner)
            Log.w("WebsiteBackground", "Could not start website background service", e)
            Toast.makeText(owner, R.string.website_background_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun release(owner: WebsiteActivity) {
        if (owners.remove(owner) != null) service?.refresh()
    }

    fun refreshSaved(context: Context) {
        val saved = WebsiteStore.all(context).associateBy { it.id }
        for (page in owners.keys.toList()) {
            val entry = saved[owners[page]?.id]
            if (entry?.allowBackgroundRunning != true) {
                owners.remove(page)
                page.backgroundRunningDisabled()
            } else owners[page] = Entry(entry.id, entry.name)
        }
        service?.refresh()
    }

    fun attach(value: WebsiteBackgroundService) { service = value }
    fun detach(value: WebsiteBackgroundService) { if (service === value) service = null }

    fun close(id: String? = null) {
        val pages = owners.keys.toList().filter { id == null || owners[it]?.id == id }
        pages.forEach { owners.remove(it) }
        pages.forEach { it.finish() }
        service?.refresh()
    }

    fun failed(value: WebsiteBackgroundService) {
        detach(value)
        owners.clear()
    }
}

/** Keeps opted-in website sessions out of Android's cached-app freezer; WebView still owns page execution. */
class WebsiteBackgroundService : Service() {
    private lateinit var notifications: NotificationManager
    private var foreground = false
    private val settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "list") WebsiteBackground.refreshSaved(this)
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.website_background_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        WebsiteBackground.attach(this)
        WebsiteStore.preferences(this).registerOnSharedPreferenceChangeListener(settingsListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (WebsiteBackground.entries().isNotEmpty()) WebsiteBackground.attach(this)
        if (intent?.action == ACTION_CLOSE) WebsiteBackground.close() else refresh()
        // A process restart cannot restore live WebViews, authenticated pages, or their execution state.
        return START_NOT_STICKY
    }

    internal fun refresh() {
        val entries = WebsiteBackground.entries()
        if (entries.isEmpty()) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            WebsiteBackground.detach(this)
            stopSelf()
            return
        }
        try {
            val notification = notification(entries)
            if (!foreground) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                foreground = true
            } else notifications.notify(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            Log.w("WebsiteBackground", "Could not promote website background service", e)
            WebsiteBackground.failed(this)
            Toast.makeText(this, R.string.website_background_failed, Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
    }

    private fun notification(entries: List<WebsiteBackground.Entry>): Notification {
        val site = entries.first()
        val open = PendingIntent.getActivity(this, 0, WebsiteActivity.intent(this, site.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, WebsiteBackgroundService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.website_background_channel))
            .setContentText(entries.joinToString(", ") { it.name.take(80) }.take(240))
            .setSubText(getString(R.string.website_allow_background_running))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.website_background_close), close).build())
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val uri = rootIntent?.data
        if (rootIntent?.component?.className == WebsiteActivity::class.java.name &&
            uri?.scheme == "twig" && uri.host == "website") uri.lastPathSegment?.let { WebsiteBackground.close(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        WebsiteStore.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsListener)
        WebsiteBackground.detach(this)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Keep the existing channel ID so its user-selected notification settings survive the rename.
        internal const val CHANNEL = "website_playback"
        internal const val NOTIFICATION_ID = 0x7108
        internal const val ACTION_CLOSE = "com.twig.app.website.CLOSE_BACKGROUND"
    }
}
