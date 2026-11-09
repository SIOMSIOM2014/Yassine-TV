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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private var web: WebView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousSystemUi = 0
    private val network = Executors.newSingleThreadExecutor()
    private val loading = AtomicBoolean(false)
    private val cacheFile by lazy { File(filesDir, "playlist_cache.m3u") }

    inner class PlaylistBridge {
        @JavascriptInterface
        fun loadPlaylist() {
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                try { sendPlaylist(cacheFile.readText(Charsets.UTF_8), null, true) } catch (_: Exception) {}
            }
            if (!loading.compareAndSet(false, true)) return
            network.execute {
                try {
                    val sources = listOf(
                        "https://iptv-org.github.io/iptv/index.country.m3u",
                        "https://iptv-org.github.io/iptv/regions/arab.m3u",
                        "https://iptv-org.github.io/iptv/languages/ara.m3u",
                        "https://iptv-org.github.io/iptv/countries/ma.m3u",
                        "https://iptv-org.github.io/iptv/countries/dz.m3u",
                        "https://iptv-org.github.io/iptv/countries/tn.m3u"
                    )
                    val merged = StringBuilder("#EXTM3U\\n")
                    val seen = HashSet<String>()
                    var success = 0
                    for (source in sources) {
                        var connection: HttpURLConnection? = null
                        try {
                            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 10000
                                readTimeout = 15000
                                instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 YACINE-TV")
                                setRequestProperty("Accept", "application/x-mpegURL,text/plain,*/*")
                            }
                            if (connection.responseCode !in 200..299) continue
                            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            if (!text.contains("#EXTM3U") || !text.contains("#EXTINF")) continue
                            var pending = ""
                            for (line in text.lineSequence()) {
                                val trimmed = line.trim()
                                if (trimmed.startsWith("#EXTINF")) pending = trimmed
                                else if (trimmed.startsWith("http") && pending.isNotEmpty()) {
                                    if (seen.add(trimmed)) {
                                        merged.append(pending).append('\\n').append(trimmed).append('\\n')
                                    }
                                    pending = ""
                                }
                            }
                            success++
                        } catch (_: Exception) {
                            // Continue to the next independent public playlist.
                        } finally {
                            connection?.disconnect()
                        }
                    }
                    if (success == 0 || seen.isEmpty()) {
                        if (!cacheFile.exists() || cacheFile.length() == 0L)
                            sendPlaylist(null, "لم نتمكن من الوصول إلى مصادر القنوات. تحقق من اتصال الإنترنت ثم أعد المحاولة.", false)
                    } else {
                        val result = merged.toString()
                        cacheFile.writeText(result, Charsets.UTF_8)
                        sendPlaylist(result, null, false)
                    }
                } catch (e: Exception) {
                    if (!cacheFile.exists() || cacheFile.length() == 0L)
                        sendPlaylist(null, e.message ?: "تعذر الاتصال بمصادر القنوات", false)
                } finally {
                    loading.set(false)
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
