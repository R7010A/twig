package com.twig.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Privileged
import com.twig.app.R
import com.twig.app.RemoteCmd
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog
import rikka.shizuku.Shizuku

@Implements(Shizuku::class, isInAndroidSdk = false)
class CommandShizukuShadow {
    companion object {
        @JvmField var uid = 0
        @JvmField var running = true
        @Implementation @JvmStatic fun pingBinder() = running
        @Implementation @JvmStatic fun isPreV11() = false
        @Implementation @JvmStatic fun checkSelfPermission() = PackageManager.PERMISSION_GRANTED
        @Implementation @JvmStatic fun getUid() = uid
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [CommandShizukuShadow::class])
class ShizukuCommandShortcutTest {
    @Before
    fun setup() {
        CommandShizukuShadow.uid = 0
        CommandShizukuShadow.running = true
        org.robolectric.util.ReflectionHelpers.setStaticField(Privileged::class.java, "active", Privileged.SHIZUKU)
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @After
    fun clearMode() {
        org.robolectric.util.ReflectionHelpers.setStaticField(Privileged::class.java, "active", Privileged.OFF)
    }

    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()

    @Test
    fun `available Shizuku is opt-in and unchecked local actions remain unprivileged`() {
        for (checked in listOf(false, true)) {
            val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
            val act = controller.get()
            val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
            val node = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
                .first { it.file.scheme == "file" && it.file.path != "/" }
            pane.createNodeShortcut(node, command = true)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val children = views(dialog.window!!.decorView)
            val box = children.filterIsInstance<CheckBox>().first { it.text.toString().contains("Shizuku") }
            assertEquals(View.VISIBLE, box.visibility)
            assertFalse(box.isChecked)
            box.isChecked = checked
            children.filterIsInstance<EditText>()[1].setText("id -u; pwd")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            @Suppress("DEPRECATION")
            val relay = shadowOf(act).resultIntent.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)!!
            val cmd = RemoteCmd.fromShortcut(act, LauncherShortcutActivity.load(act, relay)!!)!!
            assertEquals(if (checked) RemoteCmd.TARGET_SHIZUKU_ROOT else RemoteCmd.TARGET_LOCAL, cmd.target)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `root directory does not implicitly enable privilege even when Shizuku is root`() {
        val controller = Robolectric.buildActivity(CreateCommandShortcutActivity::class.java).setup()
        val act = controller.get()
        val pane = act.supportFragmentManager.findFragmentByTag("picker_pane") as PaneFragment
        val node = pane.viewModel.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
            .first { it.file.scheme == "file" && it.file.path == "/" }
        act.onPickNode(node)
        assertTrue(act.findViewById<View>(R.id.btn_confirm).isEnabled)
        act.findViewById<View>(R.id.btn_confirm).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertFalse(views(dialog.window!!.decorView).filterIsInstance<CheckBox>()
            .first { it.text.toString().contains("Shizuku") }.isChecked)
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun `saved Shizuku identity opens its own terminal without changing global privilege mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val before = Privileged.active
        val cmd = RemoteCmd("", "/sdcard", "id -u", true, label = "Shizuku", target = RemoteCmd.TARGET_SHIZUKU_ROOT)
        val act = Robolectric.buildActivity(RunCommandActivity::class.java, cmd.launchIntent(app)).create().get()
        val terminal = shadowOf(act).nextStartedActivity
        assertEquals(TerminalActivity::class.java.name, terminal.component!!.className)
        assertEquals(Privileged.SHIZUKU, terminal.getIntExtra("priv", -1))
        assertEquals(RemoteCmd.TARGET_SHIZUKU_ROOT, terminal.getStringExtra("shizuku_target"))
        assertEquals("id -u", terminal.getStringExtra("cmd"))
        assertEquals(before, Privileged.active)
    }

    @Test
    fun `privileged command provider follows selected mode rather than Shizuku availability`() {
        assertEquals(RemoteCmd.TARGET_ROOT, Privileged.commandTarget(Privileged.ROOT, serviceUid = 0, hasSu = true))
        assertEquals(RemoteCmd.TARGET_SHIZUKU_ROOT, Privileged.commandTarget(Privileged.SHIZUKU, serviceUid = 0, hasSu = true))
        assertEquals(RemoteCmd.TARGET_SHIZUKU, Privileged.commandTarget(Privileged.SHIZUKU, serviceUid = 2000, hasSu = true))
        assertNull(Privileged.commandTarget(Privileged.OFF, serviceUid = 0, hasSu = true))
        assertNull(Privileged.commandTarget(Privileged.ROOT, serviceUid = 0, hasSu = false))
    }

    @Test
    fun `background Shizuku root command rejects changed uid before launching user code`() {
        CommandShizukuShadow.uid = 2000
        val cmd = RemoteCmd("", "", "printf SHOULD_NOT_RUN", false, label = "Test", target = RemoteCmd.TARGET_SHIZUKU_ROOT)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            com.twig.app.LocalCommands.execute(cmd, shell = "/bin/sh")
        }
    }

    @Test
    fun `changed or stopped Shizuku does not run saved root command as shell or local`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cmd = RemoteCmd("", "/", "id -u", true, label = "Root", target = RemoteCmd.TARGET_SHIZUKU_ROOT)
        for (uid in listOf(2000, -1)) {
            CommandShizukuShadow.uid = uid
            CommandShizukuShadow.running = uid != -1
            val act = Robolectric.buildActivity(RunCommandActivity::class.java, cmd.launchIntent(app)).create().get()
            assertNull(shadowOf(act).nextStartedActivity)
            assertTrue(act.isFinishing)
        }
    }
}
