package com.twig.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.RemoteCmd
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class LauncherShortcutTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `launcher discovers both action factories`() {
        val entries = ctx.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage(ctx.packageName), 0,
        ).map { it.activityInfo }
        assertEquals(setOf(CreateShortcutActivity::class.java.name,
            CreateCommandShortcutActivity::class.java.name), entries.map { it.name }.toSet())
        assertTrue(entries.all { it.exported })
    }

    @Suppress("DEPRECATION")
    @Test
    fun `factory returns a directly launchable action without pinning an icon`() {
        val act = Robolectric.buildActivity(CreateShortcutActivity::class.java).get()
        val file = XFile("file", "/storage/emulated/0/note.txt", false)
        val target = OpenShortcutActivity.intent(act, file, OpenShortcutActivity.MODE_TEXT)
        act.shortcutResult(ShortcutInfoCompat.Builder(act, "test_file")
            .setShortLabel("Note")
            .setIcon(ShortcutIcons.of(act, R.drawable.ic_shortcut_terminal))
            .setIntent(target).build())
        val shadow = shadowOf(act)
        assertEquals(Activity.RESULT_OK, shadow.resultCode)
        val result = shadow.resultIntent
        assertEquals("Note", result.getStringExtra(Intent.EXTRA_SHORTCUT_NAME))
        // A gesture editor forwards this result again. Only string-backed metadata
        // may travel with it; a bitmap can become an ashmem file descriptor on device.
        assertEquals(setOf(Intent.EXTRA_SHORTCUT_NAME, Intent.EXTRA_SHORTCUT_INTENT,
            Intent.EXTRA_SHORTCUT_ICON_RESOURCE), result.extras!!.keySet())
        val icon = result.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)!!
        assertEquals(ctx.packageName, icon.packageName)
        assertEquals(ctx.resources.getResourceName(R.drawable.ic_shortcut_launcher), icon.resourceName)
        val relay = result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
        assertEquals(LauncherShortcutActivity::class.java.name, relay.component!!.className)
        // The old launcher path runs as another app; the actual viewer stays private.
        assertTrue(ctx.packageManager.getActivityInfo(relay.component!!, 0).exported)
        val resolved = LauncherShortcutActivity.load(ctx, relay)!!
        assertEquals(target.toUri(0), resolved.toUri(0))
        assertTrue(act.isFinishing)
    }

    @Test
    fun `local command shortcut opens a local terminal without server lookup`() {
        val cmd = RemoteCmd("", "/sdcard", "id; pwd", true, label = "Local", target = RemoteCmd.TARGET_LOCAL)
        val act = Robolectric.buildActivity(RunCommandActivity::class.java, cmd.launchIntent(ctx)).create().get()
        val terminal = shadowOf(act).nextStartedActivity
        assertNotNull(terminal)
        assertEquals(TerminalActivity::class.java.name, terminal.component!!.className)
        assertEquals(LOCAL_SCHEME, terminal.getStringExtra("scheme"))
        assertEquals("id; pwd", terminal.getStringExtra("cmd"))
        assertEquals("/sdcard", terminal.getStringExtra("dir"))
        assertEquals(com.twig.app.Privileged.OFF, terminal.getIntExtra("priv", -1))
    }

    @Test
    fun `forged raw targets and unknown ids cannot open private activities`() {
        val raw = Intent(ctx, OpenShortcutActivity::class.java)
        val forged = Intent(ctx, LauncherShortcutActivity::class.java)
            .putExtra("target", raw.toUri(0))
            .putExtra("scheme", "sftp_private")
            .putExtra("shortcut_id", "guessed")
        assertNull(LauncherShortcutActivity.load(ctx, forged))
        assertNull(LauncherShortcutActivity.load(ctx, Intent()))
    }

    @Test
    fun `relay opens saved action and ignores injected target extras`() {
        val target = WebsiteActivity.intent(ctx, "saved-site")
        val relay = LauncherShortcutActivity.save(ctx, target)
        relay.putExtra("target", Intent(ctx, RunCommandActivity::class.java).toUri(0))
        val controller = Robolectric.buildActivity(LauncherShortcutActivity::class.java, relay).create()
        assertEquals(target.toUri(0), shadowOf(controller.get()).nextStartedActivity.toUri(0))
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun `command result preserves approved command through opaque ids`() {
        val command = RemoteCmd("server", "/srv/project", "printf hello", true, label = "Hello")
        val descriptor = RemoteCmd.shortcut(ctx, command)
        val relay = LauncherShortcutActivity.save(ctx, descriptor.intent)
        assertFalse(relay.toUri(0).contains(command.command))
        val resolved = LauncherShortcutActivity.load(ctx, relay)!!
        assertEquals(command, RemoteCmd.fromShortcut(ctx, resolved))
        assertNull(RemoteCmd.fromShortcut(ctx, Intent().putExtra("cmd_command", "injected")))
    }
}
