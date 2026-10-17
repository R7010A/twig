package com.twig.app.ui

import android.app.Activity
import android.os.Build
import android.webkit.WebView
import java.util.WeakHashMap

/** The app request is session-only; Chromium ignores it on eng/userdebug Android builds. */
internal object WebsiteRemoteDebug {
    private val owners = WeakHashMap<Activity, Unit>()
    private var requested = false
    val forcedBySystem: Boolean get() = Build.TYPE == "eng" || Build.TYPE == "userdebug"
    val enabled: Boolean get() = forcedBySystem || requested

    fun start(owner: Activity) {
        if (owners.isEmpty()) setEnabled(false)
        owners[owner] = Unit
    }

    fun stop(owner: Activity) {
        owners.remove(owner)
        if (owners.isEmpty()) setEnabled(false)
    }

    fun setEnabled(value: Boolean) {
        WebView.setWebContentsDebuggingEnabled(value)
        requested = value
    }
}
