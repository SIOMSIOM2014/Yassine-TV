package com.yacinetv.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture

class MainActivity : Activity() {
    private var web: WebView? = null
    private var bannerAd: AdView? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var previousSystemUi = 0
    private val network = Executors.newFixedThreadPool(6)
    private val loading = AtomicBoolean(false)
    private val cacheFile by lazy { File(filesDir, "playlist_cache.m3u") }

    inner class PlaylistBridge {
        @JavascriptInterface
        fun loadPlaylist() {
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                try { sendPlaylist(cacheFile.readText(Charsets.UTF_8), null, true) } catch (_: Exception) {}
            }
            if (!loading.compareAndSet(false, true)) return
            val sources = listOf(
                "https://iptv-org.github.io/iptv/index.country.m3u",
                "https://iptv-org.github.io/iptv/regions/arab.m3u",
                "https://iptv-org.github.io/iptv/languages/ara.m3u",
                "https://iptv-org.github.io/iptv/countries/ma.m3u",
                "https://iptv-org.github.io/iptv/countries/dz.m3u",
                "https://iptv-org.github.io/iptv/countries/tn.m3u"
            )
            val merged = StringBuilder("#EXTM3U\n")
            val seen = HashSet<String>()
            val completed = AtomicInteger(0)
            val validSources = AtomicInteger(0)

            // Download independent playlists in parallel: one reachable source is enough
            // to populate the channel list while slower sources continue in the background.
            for (source in sources) {
                CompletableFuture.supplyAsync({
                    downloadPlaylist(source)
                }, network).whenComplete { playlist, failure ->
                    if (failure == null && !playlist.isNullOrBlank()) {
                        val entries = playlist.lineSequence().map { it.trim() }.toList()
                        if (entries.any { it.startsWith("#EXTINF") }) {
                            synchronized(merged) {
                                var pending = ""
                                var added = 0
                                for (line in entries) {
                                    if (line.startsWith("#EXTINF")) pending = line
                                    else if (line.startsWith("http") && pending.isNotEmpty()) {
                                        if (seen.add(line)) {
                                            merged.append(pending).append('\\n').append(line).append('\\n')
                                            added++
                                        }
                                        pending = ""
                                    }
                                }
                                if (added > 0) {
                                    validSources.incrementAndGet()
                                    val snapshot = merged.toString()
                                    try { cacheFile.writeText(snapshot, Charsets.UTF_8) } catch (_: Exception) {}
                                    sendPlaylist(snapshot, null, false)
                                }
                            }
                        }
                    }
                    if (completed.incrementAndGet() == sources.size) {
                        if (validSources.get() == 0 && (!cacheFile.exists() || cacheFile.length() == 0L)) {
                            sendPlaylist(null, "تعذر الوصول إلى مصادر القنوات. تحقق من الشبكة أو جرّب لاحقاً.", false)
                        }
                        loading.set(false)
                    }
                }
            }
        }
    }

    private fun downloadPlaylist(source: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 9000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 YACINE-TV")
                setRequestProperty("Accept", "application/x-mpegURL,text/plain,*/*")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .takeIf { it.contains("#EXTM3U") && it.contains("#EXTINF") }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendPlaylist(data: String?, error: String?, cached: Boolean) {
        runOnUiThread {
            val js = "window.nativePlaylistResult && window.nativePlaylistResult(${JSONObject.quote(data ?: "")},${JSONObject.quote(error ?: "")},$cached);"
            web?.evaluateJavascript(js, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(9, 15, 29)
        window.navigationBarColor = Color.rgb(9, 15, 29)
        createWebView()
    }

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

        web = view
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(page)
        view.loadUrl("file:///android_asset/index.html")

        // Official Google sample banner ID: test ads only; replace only after policy/consent setup.
        val adView = AdView(this).apply {
            adUnitId = "ca-app-pub-3940256099942544/9214589741"
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            val widthDp = (metrics.widthPixels / metrics.density).toInt().coerceAtLeast(320)
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this@MainActivity, widthDp))
        }
        bannerAd = adView
        page.addView(adView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MobileAds.initialize(this) {
            runOnUiThread { adView.loadAd(AdRequest.Builder().build()) }
        }
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

    override fun onResume() {
        super.onResume()
        bannerAd?.resume()
    }

    override fun onPause() {
        bannerAd?.pause()
        super.onPause()
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
        bannerAd?.destroy()
        bannerAd = null
        web?.apply { stopLoading(); loadUrl("about:blank"); clearHistory(); removeAllViews(); destroy() }
        web = null
        network.shutdownNow()
        super.onDestroy()
    }
}
