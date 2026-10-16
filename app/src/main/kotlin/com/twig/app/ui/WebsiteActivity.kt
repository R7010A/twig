package com.twig.app.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.databinding.ActivityWebsiteBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Shares WebView's persistent website data across tree entries and launcher shortcuts. */
class WebsiteActivity : AppCompatActivity() {
    private lateinit var b: ActivityWebsiteBinding
    private lateinit var site: Website
    private var mobileUserAgent = ""
    private var upload: ValueCallback<Array<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        upload?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        upload = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebsiteBinding.inflate(layoutInflater)
        setContentView(b.root)
        val id = intent.data?.takeIf { it.scheme == "twig" && it.host == "website" }?.lastPathSegment
        val saved = WebsiteStore.all(this).firstOrNull { it.id == id }
        if (saved == null || WebsiteStore.normalizeUrl(saved.url) == null) {
            Toast.makeText(this, R.string.website_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        site = saved
        title = site.name
        b.toolbar.title = site.name
        b.toolbar.setNavigationOnClickListener { back() }
        b.toolbar.menu.add(R.string.action_refresh).setOnMenuItemClickListener { b.web.reload(); true }
        b.toolbar.menu.add(R.string.action_pin_shortcut).setOnMenuItemClickListener {
            WebsiteDialogs.pin(this, site); true
        }
        // Insets for independently hidden system bars, including the on-screen keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { view, insets ->
            if (site.hideStatusBar || site.hideNavigationBar) {
                val types = WindowInsetsCompat.Type.ime() or
                    (if (site.hideStatusBar) 0 else WindowInsetsCompat.Type.statusBars()) or
                    (if (site.hideNavigationBar) 0 else WindowInsetsCompat.Type.navigationBars())
                val bars = insets.getInsets(types)
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                view.setPadding(maxOf(bars.left, cutout.left),
                    if (site.hideStatusBar) 0 else maxOf(bars.top, cutout.top),
                    maxOf(bars.right, cutout.right), maxOf(bars.bottom, cutout.bottom))
            } else view.setPadding(0, 0, 0, 0)
            insets
        }
        b.web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = true
            displayZoomControls = false
        }
        mobileUserAgent = b.web.settings.userAgentString
        b.web.onPullProgress = { fraction ->
            b.pullIndicator.visibility = if (fraction > 0f) View.VISIBLE else View.GONE
            b.pullIndicator.alpha = fraction
            b.pullIndicator.translationY = 32 * resources.displayMetrics.density * fraction
        }
        b.web.onRefresh = { b.web.reload() }
        b.web.setOnLongClickListener {
            site.disableTextSelection && b.web.hitTestResult.type == WebView.HitTestResult.UNKNOWN_TYPE
        }
        applyWebsiteOptions()
        CookieManager.getInstance().setAcceptCookie(true)
        b.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme == "https" || scheme == "http") return false
                // Never grant a website access to app files or arbitrary intent extras.
                if (request.isForMainFrame && request.hasGesture() && scheme in setOf("mailto", "tel", "sms", "geo")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(this@WebsiteActivity, R.string.website_no_app, Toast.LENGTH_SHORT).show()
                    }
                }
                return true
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                applyPageOptions()
            }

            override fun onPageFinished(view: WebView, url: String) {
                applyPageOptions()
                b.toolbar.subtitle = Uri.parse(url).host
                persistCookies()
            }
        }
        b.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) {
                b.progress.progress = progress
                b.progress.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams): Boolean {
                upload?.onReceiveValue(null)
                upload = callback
                try {
                    picker.launch(params.createIntent())
                } catch (_: ActivityNotFoundException) {
                    upload?.onReceiveValue(null)
                    upload = null
                    Toast.makeText(this@WebsiteActivity, R.string.website_no_app, Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
        if (savedInstanceState?.getString("bookmark_url") != site.url ||
            b.web.restoreState(savedInstanceState) == null) b.web.loadUrl(site.url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Bringing an existing document task forward must keep its live WebView,
        // including navigation, scroll position and unsaved form contents.
        val id = intent.data?.takeIf { it.scheme == "twig" && it.host == "website" }?.lastPathSegment
        val saved = WebsiteStore.all(this).firstOrNull { it.id == id }
        if (!::site.isInitialized || saved == null || saved.id != site.id ||
            WebsiteStore.normalizeUrl(saved.url) == null) {
            recreate()
            return
        }
        if (saved == site) return
        val changedUrl = saved.url != site.url
        val changedDesktop = saved.desktopSite != site.desktopSite
        site = saved
        applyWebsiteOptions()
        when {
            changedUrl -> b.web.loadUrl(site.url)
            changedDesktop -> b.web.reload()
            else -> applyPageOptions()
        }
    }

    private fun back() {
        if (b.web.canGoBack()) b.web.goBack() else finish()
    }

    private fun applyWebsiteOptions() {
        title = site.name
        b.toolbar.title = site.name
        b.toolbar.visibility = if (site.fullscreen) View.GONE else View.VISIBLE
        b.web.pullToRefresh = site.pullToRefresh
        b.web.lockScrollAxis = site.lockScrollAxis
        b.web.settings.apply {
            val wanted = if (site.desktopSite) desktopUserAgent(mobileUserAgent) else mobileUserAgent
            if (userAgentString != wanted) userAgentString = wanted
            useWideViewPort = true
            loadWithOverviewMode = site.desktopSite
        }
        NavBarTint.surface(this)
        applySystemBars()
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun applySystemBars() {
        if (!::site.isInitialized) return
        WindowCompat.setDecorFitsSystemWindows(window, !site.hideStatusBar && !site.hideNavigationBar)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (site.hideStatusBar)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (site.hideStatusBar) hide(WindowInsetsCompat.Type.statusBars())
            else show(WindowInsetsCompat.Type.statusBars())
            if (site.hideNavigationBar) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /** Reapply on each document; CSS also covers content inserted later by a SPA. */
    private fun applyPageOptions() {
        b.web.evaluateJavascript("""
            (() => {
                const key = '__twig_selection_style';
                let style = document.getElementById(key);
                if (${site.disableTextSelection}) {
                    if (!style) {
                        style = document.createElement('style');
                        style.id = key;
                        (document.head || document.documentElement).appendChild(style);
                    }
                    style.textContent = 'html,body,body *{-webkit-user-select:none!important;user-select:none!important}' +
                        'input,textarea,[contenteditable],[contenteditable] *{-webkit-user-select:text!important;user-select:text!important}';
                } else if (style) style.remove();
                let viewport = document.querySelector('meta[name="viewport"]');
                if (${site.desktopSite}) {
                    if (!viewport) {
                        viewport = document.createElement('meta');
                        viewport.name = 'viewport';
                        viewport.__twigCreated = true;
                        (document.head || document.documentElement).appendChild(viewport);
                    }
                    if (viewport.__twigOriginal === undefined)
                        viewport.__twigOriginal = viewport.getAttribute('content');
                    viewport.setAttribute('content', 'width=1280');
                } else if (viewport && viewport.__twigOriginal !== undefined) {
                    if (viewport.__twigCreated) viewport.remove();
                    else if (viewport.__twigOriginal === null) viewport.removeAttribute('content');
                    else viewport.setAttribute('content', viewport.__twigOriginal);
                    delete viewport.__twigOriginal;
                }
            })();
        """.trimIndent(), null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    private fun persistCookies() {
        lifecycleScope.launch(Dispatchers.IO) { CookieManager.getInstance().flush() }
    }

    override fun onPause() {
        if (::b.isInitialized) {
            b.web.onPause()
            persistCookies()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) b.web.onResume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::site.isInitialized) outState.putString("bookmark_url", site.url)
        b.web.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        upload?.onReceiveValue(null)
        upload = null
        b.web.stopLoading()
        (b.web.parent as? android.view.ViewGroup)?.removeView(b.web)
        b.web.destroy()
        super.onDestroy()
    }

    companion object {
        /** Keep the installed engine version while asking servers for their desktop layout. */
        internal fun desktopUserAgent(mobile: String): String = mobile
            .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Version/4.0", "").replace(" Mobile", "")

        /** A document task per bookmark keeps desktop launches independent of the file browser. */
        fun intent(ctx: Context, id: String) = Intent(ctx, WebsiteActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.Builder().scheme("twig").authority("website").appendPath(id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
