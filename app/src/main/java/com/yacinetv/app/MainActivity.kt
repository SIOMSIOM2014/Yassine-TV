package com.yacinetv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var web: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousSystemUi = 0
    private val network = Executors.newSingleThreadExecutor()

    inner class PlaylistBridge {
        @JavascriptInterface
        fun loadPlaylist() {
            val prefs = getSharedPreferences("playlist_cache", MODE_PRIVATE)
            val cached = prefs.getString("m3u", null)
            if (!cached.isNullOrBlank()) sendPlaylist(cached, null, true)
            network.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL("https://iptv-org.github.io/iptv/index.country.m3u").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 18000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "YACINE-TV/1.0 Android")
                        setRequestProperty("Accept", "text/plain,*/*")
                        useCaches = true
                    }
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (text.isBlank()) throw IllegalStateException("القائمة فارغة")
                    prefs.edit().putString("m3u", text).apply()
                    sendPlaylist(text, null, false)
                } catch (e: Exception) {
                    if (cached.isNullOrBlank()) sendPlaylist(null, e.message ?: "Network error", false)
                } finally {
                    connection?.disconnect()
                }
            }
        }
    }

    private fun sendPlaylist(data: String?, error: String?, cached: Boolean) {
        runOnUiThread {
            val js = "window.nativePlaylistResult && window.nativePlaylistResult(${JSONObject.quote(data ?: "")},${JSONObject.quote(error ?: "")},$cached);"
            web?.evaluateJavascript(js, null)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(9, 15, 29)
        window.navigationBarColor = Color.rgb(9, 15, 29)
        createWebView()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        view.addJavascriptInterface(PlaylistBridge(), "AndroidBridge")
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                fullscreenView = view
                fullscreenCallback = callback
                previousOrientation = requestedOrientation
                previousSystemUi = window.decorView.systemUiVisibility
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
                val decor = window.decorView as FrameLayout
                decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
            override fun onHideCustomView() { hideFullscreen() }
        }
        view.webViewClient = WebViewClient()
        setContentView(view)
        web = view
        view.loadUrl("file:///android_asset/index.html")
    }

    private fun hideFullscreen() {
        val custom = fullscreenView ?: return
        (window.decorView as? FrameLayout)?.removeView(custom)
        fullscreenView = null
        fullscreenCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = previousSystemUi
        requestedOrientation = previousOrientation
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullscreenView != null) { fullscreenCallback?.onCustomViewHidden(); hideFullscreen(); return }
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (fullscreenView != null) { fullscreenCallback?.onCustomViewHidden(); hideFullscreen(); return true }
            web?.let { if (it.canGoBack()) { it.goBack(); return true } }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (fullscreenView != null) hideFullscreen()
        web?.apply { stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
        web = null
        network.shutdownNow()
        super.onDestroy()
    }
}
