package com.twig.app.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumeIconRefreshTest {
    @Test fun `resume refreshes default app icons without rebinding rows or thumbnails`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = true)
        adapter.submitList(listOf("picture.png", "file.unknown").map {
            PaneViewModel.FileNode(XFile("file", "/sdcard/$it", false), 1, false, false)
        })
        val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
        val holder = adapter.createViewHolder(parent, adapter.getItemViewType(1))
        adapter.bindViewHolder(holder, 1)
        val icon = holder.itemView.findViewById<ImageView>(R.id.icon)
        val name = holder.itemView.findViewById<TextView>(R.id.name)
        icon.layoutParams = icon.layoutParams.apply { height = 71 }
        name.text = "existing row"
        val changed = mutableListOf<Int>()
        val payloads = mutableListOf<Any>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { fail("resume must not invalidate the whole list") }
            override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                assertNotNull(payload)
                changed += start
                payloads += payload!!
            }
        })
        adapter.refreshDefaultAppIcons()
        assertEquals("thumbnail rows must not be rebound", listOf(1), changed)
        adapter.onBindViewHolder(holder, 1, payloads)
        assertEquals(71, icon.layoutParams.height)
        assertEquals("existing row", name.text.toString())
    }
}
