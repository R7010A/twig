package com.twig.app.ui

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore

object WebsiteDialogs {
    fun edit(ctx: Context, site: Website? = null) {
        val padding = (20 * ctx.resources.displayMetrics.density).toInt()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val name = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_name)
            setSingleLine()
            setText(site?.name.orEmpty())
        }
        val url = EditText(ctx).apply {
            hint = ctx.getString(R.string.website_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(site?.url.orEmpty())
        }
        form.addView(name)
        form.addView(url)
        fun option(label: Int, checked: Boolean) = CheckBox(ctx).apply {
            tag = label // Identify each option within the scrollable form.
            setText(label)
            isChecked = checked
            form.addView(this)
        }
        val fullscreen = option(R.string.website_fullscreen, site?.fullscreen ?: false)
        val statusBar = option(R.string.website_hide_status_bar, site?.hideStatusBar ?: false)
        val navigationBar = option(R.string.website_hide_navigation_bar, site?.hideNavigationBar ?: false)
        val pullToRefresh = option(R.string.website_pull_to_refresh, site?.pullToRefresh ?: false)
        val selection = option(R.string.website_disable_text_selection, site?.disableTextSelection ?: false)
        val scrollAxis = option(R.string.website_lock_scroll_axis, site?.lockScrollAxis ?: false)
        val desktop = option(R.string.website_desktop_site, site?.desktopSite ?: false)
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (site == null) R.string.website_add else R.string.website_edit)
            .setView(ScrollView(ctx).apply { addView(form) })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = WebsiteStore.normalizeUrl(url.text.toString())
                if (address == null) {
                    url.error = ctx.getString(R.string.website_invalid_url)
                    return@setOnClickListener
                }
                val label = name.text.toString().trim().ifEmpty { android.net.Uri.parse(address).host.orEmpty() }
                val saved = (site ?: Website(name = label, url = address)).copy(
                    name = label, url = address, fullscreen = fullscreen.isChecked,
                    hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                    pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                    lockScrollAxis = scrollAxis.isChecked, desktopSite = desktop.isChecked,
                )
                WebsiteStore.save(ctx, saved)
                ShortcutManagerCompat.updateShortcuts(ctx, listOf(shortcut(ctx, saved)))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    fun menu(ctx: Context, site: Website) {
        val actions = arrayOf(ctx.getString(R.string.website_edit),
            ctx.getString(R.string.action_pin_shortcut), ctx.getString(R.string.action_delete))
        AlertDialog.Builder(ctx).setTitle(site.name).setItems(actions) { _, index ->
            when (index) {
                0 -> edit(ctx, site)
                1 -> pin(ctx, site)
                2 -> AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.website_delete_confirm, site.name))
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .setPositiveButton(R.string.dialog_ok) { _, _ ->
                        WebsiteStore.remove(ctx, site.id)
                        ShortcutManagerCompat.disableShortcuts(ctx, listOf("website_${site.id}"),
                            ctx.getString(R.string.website_missing))
                    }.show()
            }
        }.show()
    }

    fun shortcut(ctx: Context, site: Website): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, "website_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(ShortcutIcons.of(ctx, R.drawable.ic_website))
            .setIntent(WebsiteActivity.intent(ctx, site.id))
            .build()

    fun pin(ctx: Context, site: Website) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            Toast.makeText(ctx, R.string.pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        ShortcutManagerCompat.requestPinShortcut(ctx, shortcut(ctx, site), null)
    }
}
