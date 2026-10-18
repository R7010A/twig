package com.twig.app.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
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
class WebsiteBackgroundServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    private fun site(name: String, enabled: Boolean = true) = Website(name = name,
        url = "https://example.com/private?token=not-for-notifications", allowBackgroundRunning = enabled)

    private fun page(site: Website) = run {
        WebsiteStore.save(app, site)
        Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, site.id)).setup()
    }

    @Test fun `opt in starts a website foreground service that survives stop and ends after the last page closes`() {
        val page = page(site("Music"))
        assertEquals(WebsiteBackgroundService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        val notification = shadowOf(service).lastForegroundNotification
        assertEquals(WebsiteBackgroundService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Music", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.toString().contains("not-for-notifications"))
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(WebsiteActivity.intent(app, WebsiteBackground.entries().single().id).data, open.data)
        assertNull(open.extras)
        page.pause().stop()
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertNotNull(manager.getNotificationChannel(WebsiteBackgroundService.CHANNEL))
        page.destroy()
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(manager.getNotificationChannel("music"))
        controller.destroy()
        assertTrue(WebsiteBackground.entries().isEmpty())
    }

    @Test fun `multiple pages share one notification and disabling one leaves the other alive`() {
        val first = site("First")
        val second = site("Second")
        val a = page(first)
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        val b = page(second)
        assertEquals(2, WebsiteBackground.entries().size)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        WebsiteStore.save(app, first.copy(allowBackgroundRunning = false))
        a.newIntent(WebsiteActivity.intent(app, first.id))
        assertEquals(listOf(second.id), WebsiteBackground.entries().map { it.id })
        assertFalse(shadowOf(service).isStoppedBySelf)
        a.pause().stop().destroy()
        assertEquals(1, WebsiteBackground.entries().size)
        service.onStartCommand(Intent(app, WebsiteBackgroundService::class.java).setAction(WebsiteBackgroundService.ACTION_CLOSE), 0, 2)
        assertTrue(b.get().isFinishing)
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        b.pause().stop().destroy()
        controller.destroy()
    }

    @Test fun `swiping one website task away closes only its session`() {
        val first = site("First")
        val second = site("Second")
        val a = page(first)
        val b = page(second)
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        service.onTaskRemoved(WebsiteActivity.intent(app, first.id))
        assertTrue(a.get().isFinishing)
        assertFalse(b.get().isFinishing)
        assertEquals(listOf(second.id), WebsiteBackground.entries().map { it.id })
        assertFalse(shadowOf(service).isStoppedBySelf)
        a.pause().stop().destroy()
        b.pause().stop().destroy()
        controller.destroy()
    }

    @Test fun `disabled and stopped pages do not start a service and orphaned starts do not revive pages`() {
        val saved = site("Disabled", enabled = false)
        val page = page(saved)
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertNull(shadowOf(app).nextStartedService)
        page.pause().stop()
        WebsiteBackground.update(page.get(), saved.copy(allowBackgroundRunning = true))
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertNull(shadowOf(app).nextStartedService)
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        page.destroy()
        controller.destroy()
    }

    @Test fun `editing or removing a bookmark while its page is stopped releases its session immediately`() {
        val saved = site("Music")
        val page = page(saved)
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        page.pause().stop()
        WebsiteStore.save(app, saved.copy(allowBackgroundRunning = false))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(page.get().findViewById<android.webkit.WebView>(com.twig.app.R.id.web)).wasOnPauseCalled())
        controller.destroy()
        WebsiteStore.save(app, saved)
        // Resuming a task from recents does not require onNewIntent to see a newly enabled option.
        page.start().resume().visible()
        assertEquals(listOf(saved.id), WebsiteBackground.entries().map { it.id })
        val fresh = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        fresh.get().onStartCommand(Intent(), 0, 1)
        WebsiteStore.remove(app, saved.id)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(WebsiteBackground.entries().isEmpty())
        assertTrue(shadowOf(fresh.get()).isStoppedBySelf)
        page.pause().stop().destroy()
        fresh.destroy()
    }

    @Test fun `opening a page during service shutdown starts a fresh session and old cleanup cannot detach it`() {
        val first = page(site("First"))
        val old = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        old.get().onStartCommand(Intent(), 0, 1)
        first.pause().stop().destroy()
        assertNull(WebsiteBackground.service)
        val second = page(site("Second"))
        val fresh = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        fresh.get().onStartCommand(Intent(), 0, 1)
        old.destroy()
        assertSame(fresh.get(), WebsiteBackground.service)
        assertFalse(shadowOf(fresh.get()).isStoppedBySelf)
        second.pause().stop().destroy()
        assertTrue(shadowOf(fresh.get()).isStoppedBySelf)
        fresh.destroy()
    }

    @Test @Config(sdk = [24, 32]) fun `general background service also works before Android 14`() {
        val page = page(site("Old Android"))
        val controller = Robolectric.buildService(WebsiteBackgroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        page.pause().stop().destroy()
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
}
