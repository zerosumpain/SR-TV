package com.strangeramblings.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * A full-screen WebView on the sr. TV front end. Every screen is a web page;
 * this shell only adds what a browser on a TV cannot: a launcher entry, the
 * Back key, keep-awake, persisted cookies and self-update.
 */
class MainActivity : Activity() {

    companion object {
        const val START_URL = "https://strangeramblings.com/games/tv"
        const val OFFLINE_URL = "file:///android_asset/offline.html"
        private const val SITE_HOST = "strangeramblings.com"

        fun isSiteUrl(uri: Uri?): Boolean {
            if (uri == null || uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return host == SITE_HOST || host.endsWith(".$SITE_HOST")
        }
    }

    private lateinit var webView: WebView
    private lateinit var updater: Updater
    private var clearHistoryOnNextLoad = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.rgb(0x14, 0x16, 0x1a))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(webView)
        enterImmersive()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString SRTV/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.addJavascriptInterface(Bridge(), "SRTV")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url
                if (url.toString().startsWith(OFFLINE_URL)) return false
                // Stay on the site; anything else is simply not opened.
                return !isSiteUrl(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadUrl(OFFLINE_URL)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (clearHistoryOnNextLoad && url != null && !url.startsWith(OFFLINE_URL)) {
                    clearHistoryOnNextLoad = false
                    view.clearHistory()
                }
            }
        }

        if (savedInstanceState == null) webView.loadUrl(START_URL) else webView.restoreState(savedInstanceState)
        webView.requestFocus()

        updater = Updater(this)
        updater.checkOnLaunch()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersive()
        updater.onResume()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        updater.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersive()
            if (!webView.hasFocus()) webView.requestFocus()
        }
    }

    /** Back goes to the page first (window.srTvBack), then history, then exits. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) handleBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBack() {
        val current = webView.url ?: ""
        if (current.startsWith(OFFLINE_URL)) {
            finish()
            return
        }
        webView.evaluateJavascript(
            "(function(){try{return !!(window.srTvBack && window.srTvBack());}catch(e){return false;}})()"
        ) { result ->
            if (result == "true") return@evaluateJavascript
            if (webView.canGoBack()) webView.goBack() else finish()
        }
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun refocusWebView() {
        webView.requestFocus()
    }

    /** Exposed to pages as window.SRTV. Keep it tiny: every frame can see it. */
    private inner class Bridge {
        @JavascriptInterface
        fun version(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun retry() {
            runOnUiThread {
                clearHistoryOnNextLoad = true
                webView.loadUrl(START_URL)
            }
        }
    }
}
