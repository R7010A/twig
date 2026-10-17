package com.twig.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.Rect
import android.util.Log
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.content.ContextCompat
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.WebsiteOrientation
import com.twig.app.secure.Secrets

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
        val openFullscreen = option(R.string.website_open_fullscreen, site?.openFullscreen ?: false)
        val fullscreen = option(R.string.website_fullscreen, site?.fullscreen ?: false)
        val statusBar = option(R.string.website_hide_status_bar, site?.hideStatusBar ?: false)
        val navigationBar = option(R.string.website_hide_navigation_bar, site?.hideNavigationBar ?: false)
        val pullToRefresh = option(R.string.website_pull_to_refresh, site?.pullToRefresh ?: false)
        val selection = option(R.string.website_disable_text_selection, site?.disableTextSelection ?: false)
        val scrollAxis = option(R.string.website_lock_scroll_axis, site?.lockScrollAxis ?: false)
        val desktop = option(R.string.website_desktop_site, site?.desktopSite ?: false)
        form.addView(TextView(ctx).apply { setText(R.string.website_orientation) })
        val orientation = RadioGroup(ctx).apply {
            tag = R.string.website_orientation
            val labels = listOf(
                WebsiteOrientation.SYSTEM to R.string.website_orientation_system,
                WebsiteOrientation.FREE to R.string.website_orientation_free,
                WebsiteOrientation.LOCKED to R.string.website_orientation_locked,
                WebsiteOrientation.LANDSCAPE to R.string.website_orientation_landscape,
                WebsiteOrientation.PORTRAIT to R.string.website_orientation_portrait,
            )
            for ((mode, label) in labels) addView(RadioButton(ctx).apply {
                id = View.generateViewId()
                tag = mode
                setText(label)
            })
            val selected = site?.orientation ?: WebsiteOrientation.SYSTEM
            check(findViewWithTag<RadioButton>(selected).id)
        }
        form.addView(orientation)
        val advanced = LinearLayout(ctx).apply {
            this.orientation = LinearLayout.VERTICAL
            tag = "website_advanced_fields"
            visibility = View.GONE
        }
        val advancedHeader = TextView(ctx).apply {
            tag = R.string.website_advanced
            setText(R.string.website_advanced)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
            compoundDrawablePadding = (8 * ctx.resources.displayMetrics.density).toInt()
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
            val background = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
            setBackgroundResource(background.resourceId)
            isFocusable = true
        }
        fun expandAdvanced(show: Boolean) {
            if (!show && advanced.hasFocus()) {
                ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(advanced.windowToken, 0)
                advanced.findFocus()?.clearFocus()
            }
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advancedHeader.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                if (show) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right, 0)
        }
        advancedHeader.setOnClickListener { expandAdvanced(advanced.visibility != View.VISIBLE) }
        form.addView(advancedHeader, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * ctx.resources.displayMetrics.density).toInt()))
        form.addView(advanced)
        fun field(label: Int, hint: Int, value: String, multiline: Boolean = false) = EditText(ctx).apply {
            tag = label
            advanced.addView(TextView(ctx).apply { setText(label) })
            this.hint = ctx.getString(hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            if (multiline) {
                minLines = 3
                maxLines = 7
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                typeface = Typeface.MONOSPACE
                textSize = 13f
            } else setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(value)
            advanced.addView(this)
        }
        val userAgent = field(R.string.website_user_agent, R.string.website_user_agent_hint, site?.userAgent.orEmpty())
        val headers = field(R.string.website_headers, R.string.website_headers_hint, site?.headers.orEmpty(), true)
        val script = field(R.string.website_script, R.string.website_script_hint, site?.script.orEmpty(), true)
        val style = field(R.string.website_style, R.string.website_style_hint, site?.style.orEmpty(), true)
        advanced.addView(TextView(ctx).apply { setText(R.string.website_custom_scope) })
        fun advancedError(field: EditText, message: Int) {
            expandAdvanced(true)
            field.error = ctx.getString(message)
            field.requestFocus()
            field.post { field.requestRectangleOnScreen(Rect(0, 0, field.width, field.height), true) }
        }
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
                val agent = userAgent.text.toString().trim()
                if (!WebsiteCustomization.validUserAgent(agent)) {
                    advancedError(userAgent, R.string.website_user_agent_invalid)
                    return@setOnClickListener
                }
                if (WebsiteCustomization.parseHeaders(headers.text.toString()) == null) {
                    advancedError(headers, R.string.website_headers_invalid)
                    return@setOnClickListener
                }
                val label = name.text.toString().trim().ifEmpty { android.net.Uri.parse(address).host.orEmpty() }
                val saved = (site ?: Website(name = label, url = address)).copy(
                    name = label, url = address, fullscreen = fullscreen.isChecked,
                    openFullscreen = openFullscreen.isChecked,
                    hideStatusBar = statusBar.isChecked, hideNavigationBar = navigationBar.isChecked,
                    pullToRefresh = pullToRefresh.isChecked, disableTextSelection = selection.isChecked,
                    lockScrollAxis = scrollAxis.isChecked, desktopSite = desktop.isChecked,
                    orientation = orientation.findViewById<RadioButton>(orientation.checkedRadioButtonId).tag as WebsiteOrientation,
                    userAgent = agent, headers = headers.text.toString(),
                    script = script.text.toString(), style = style.text.toString(),
                )
                try {
                    WebsiteStore.save(ctx, saved)
                } catch (_: Secrets.KeyUnavailable) {
                    advancedError(headers, R.string.backup_locked)
                    return@setOnClickListener
                }
                if (site != null && site.url != saved.url) WebsiteIcons.forget(ctx, site)
                updateShortcut(ctx, saved)
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
                        WebsiteIcons.forget(ctx, site)
                        ShortcutManagerCompat.disableShortcuts(ctx, listOf("website_${site.id}"),
                            ctx.getString(R.string.website_missing))
                    }.show()
            }
        }.show()
    }

    fun shortcut(ctx: Context, site: Website, icon: Bitmap? = WebsiteIcons.cached(site)): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(ctx, "website_${site.id}")
            .setShortLabel(site.name)
            .setLongLabel(site.name)
            .setIcon(if (icon != null) ShortcutIcons.of(ctx, icon) else ShortcutIcons.of(ctx, R.drawable.ic_website))
            .setIntent(WebsiteActivity.intent(ctx, site.id))
            .build()

    /** Use the cache on a fresh process too, so editing a label keeps its real icon. */
    fun updateShortcut(ctx: Context, site: Website) {
        val app = ctx.applicationContext
        WebsiteIcons.load(app, site) { icon ->
            val saved = WebsiteStore.all(app).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            try {
                ShortcutManagerCompat.updateShortcuts(app, listOf(shortcut(app, saved, icon)))
            } catch (e: Exception) {
                Log.w("WebsiteIcons", "Could not update website shortcut", e)
            }
        }
    }

    fun pin(ctx: Context, site: Website) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
            Toast.makeText(ctx, R.string.pin_shortcut_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        WebsiteIcons.load(ctx, site) { icon ->
            val saved = WebsiteStore.all(ctx).firstOrNull { it.id == site.id && it.url == site.url }
                ?: return@load
            ShortcutManagerCompat.requestPinShortcut(ctx, shortcut(ctx, saved, icon), null)
        }
    }
}
