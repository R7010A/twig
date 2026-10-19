package com.twig.app.ui

import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.twig.app.AndroidDataAccess.AppFolder
import com.twig.app.R
import com.twig.core.XFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDataFolderDialogTest {
    class Host : AppCompatActivity() {
        override fun onCreate(state: android.os.Bundle?) {
            setTheme(R.style.Theme_Twig)
            super.onCreate(state)
        }
    }

    @Test fun `filter matches labels and packages and selects the displayed app without probing by default`() = runTest {
        val host = Robolectric.buildActivity(Host::class.java).setup()
        val folders = listOf(
            AppFolder(XFile("file", "/missing/com.example.alpha", true), "First App"),
            AppFolder(XFile("file", "/missing/com.example.beta", true), "Second App"),
            AppFolder(XFile("file", "/missing/com.example.system", true), "System App", system = true),
        )
        var selected: XFile? = null
        val dialog = AppDataFolderDialog.show(host.get(), this, folders) { selected = it }
        try {
            val list = dialog.findViewById<ListView>(R.id.list)!!
            val filter = dialog.findViewById<EditText>(R.id.filter)!!
            assertFalse(dialog.findViewById<CheckBox>(R.id.existing)!!.isChecked)
            val hideSystem = dialog.findViewById<CheckBox>(R.id.hide_system)!!
            assertTrue(hideSystem.isChecked)
            assertEquals(2, list.count)
            hideSystem.isChecked = false
            assertEquals(3, list.count)
            filter.setText("system")
            assertEquals(1, list.count)
            hideSystem.isChecked = true
            assertEquals(0, list.count)
            filter.setText("FIRST")
            assertEquals(1, list.count)
            filter.setText("beta")
            assertEquals(1, list.count)
            list.performItemClick(list.adapter.getView(0, null, list), 0, 0)
            assertEquals(folders[1].file, selected)
            assertFalse(dialog.isShowing)
        } finally {
            dialog.dismiss()
            host.pause().stop().destroy()
        }
    }
}
