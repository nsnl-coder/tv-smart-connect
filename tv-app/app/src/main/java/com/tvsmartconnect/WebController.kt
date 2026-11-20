package com.tvsmartconnect

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * Điều khiển WebView xem web phim: chèn script tvsc.js vào mọi frame để điều khiển <video>,
 * chặn popup / chuyển trang quảng cáo, và mô phỏng chạm-cuộn tại vị trí con trỏ.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebController(
    private val activity: Activity,
    private val webView: WebView,
    private val fullscreenContainer: FrameLayout,
    private val events: Events,
) {
    interface Events {
        fun onWebStatusChanged()
        fun onNotice(message: String, url: String? = null)
    }

    /** Một frame (trang chính hoặc iframe) đã chạy tvsc.js. */
    private class Frame(val proxy: JavaScriptReplyProxy, val isMain: Boolean, val origin: String) {
        var lastSeen = 0L
        var has = false
        var playing = false
        var waiting = false
        var ended = false
        var pos = 0.0
        var dur = 0.0
        var live = false
        var area = 0.0

        val score get() = (if (playing) 1e9 else 0.0) + (if (dur > 0) 1e8 else 0.0) + area
    }

    data class VideoStatus(
        val state: String,
        val position: Double,
        val duration: Double,
        val live: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val frames = LinkedHashMap<JavaScriptReplyProxy, Frame>()
    private val script = activity.assets.open("tvsc.js").bufferedReader().use { it.readText() }
    private val listenerSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var siteDomain: String? = null
    private var autoplayUntil = 0L

    var fullscreenOn = false
        private set

    val pageTitle: String? get() = webView.title?.takeIf { it.isNotBlank() && it != "about:blank" }
    val pageUrl: String? get() = webView.url?.takeIf { it != "about:blank" }

    private val autoplayTicker = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > autoplayUntil) return
            if (activeFrame()?.playing != true) broadcast(JSONObject().put("type", "autoplay"))
            main.postDelayed(this, 2000)
        }
    }

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        if (listenerSupported) {
            WebViewCompat.addWebMessageListener(webView, "tvscBridge", setOf("*")) { _, message, origin, isMainFrame, proxy ->
                onFrameMessage(message.data ?: return@addWebMessageListener, origin, isMainFrame, proxy)
            }
        }
        if (docStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
        }
    }

    // ---------- Mở / đóng trang ----------

    fun open(url: String) {
        siteDomain = registrableDomain(Uri.parse(url).host)
        exitCustomView()
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
        webView.requestFocus()
        startAutoplay()
    }

    fun close() {
        autoplayUntil = 0
        exitCustomView()
        fullscreenOn = false
        frames.clear()
        siteDomain = null
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.visibility = View.GONE
    }

    fun onPause() = webView.onPause()
    fun onResume() = webView.onResume()

    /** Nút Back: thoát toàn màn hình → lùi trang. Trả về false nếu không còn gì để lùi. */
    fun back(): Boolean {
        when {
            customView != null -> exitCustomView()
            fullscreenOn -> setFullscreen(false)
            webView.canGoBack() -> webView.goBack()
            else -> return false
        }
        return true
    }

    fun forward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() = webView.reload()

    // ---------- Video ----------

    fun videoCommand(cmd: JSONObject) {
        val active = activeFrame()
        if (active != null) send(active, cmd) else broadcast(cmd)
        if (!listenerSupported) evalHandle(cmd)
    }

    fun toggleFullscreen() {
        if (customView != null) exitCustomView() else setFullscreen(!fullscreenOn)
    }

    fun setFullscreen(on: Boolean) {
        val active = activeFrame()
        if (on && active == null) {
            events.onNotice("Chưa tìm thấy video trên trang — hãy bấm phát video trước")
            return
        }
        fullscreenOn = on
        if (active == null) {
            broadcast(JSONObject().put("type", "fs").put("on", false))
            broadcast(JSONObject().put("type", "fsFrame").put("on", false))
        } else {
            send(active, JSONObject().put("type", "fs").put("on", on))
            if (!active.isMain) {
                val msg = JSONObject().put("type", "fsFrame").put("on", on).put("origin", active.origin)
                frames.values.filter { it !== active }.forEach { send(it, msg) }
            }
        }
        if (!listenerSupported) evalHandle(JSONObject().put("type", "fs").put("on", on))
        events.onWebStatusChanged()
    }

    fun videoStatus(): VideoStatus? {
        val f = activeFrame() ?: return null
        val state = when {
            f.ended -> "ended"
            f.playing -> "playing"
            f.waiting -> "buffering"
            else -> "paused"
        }
        return VideoStatus(state, f.pos, f.dur, f.live)
    }

    private fun startAutoplay() {
        autoplayUntil = SystemClock.uptimeMillis() + 25_000
        main.removeCallbacks(autoplayTicker)
        main.postDelayed(autoplayTicker, 2500)
    }

    private fun activeFrame(): Frame? {
        val now = SystemClock.uptimeMillis()
        return frames.values.filter { it.has && now - it.lastSeen < 3500 }.maxByOrNull { it.score }
    }

    private fun onFrameMessage(data: String, origin: Uri, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        val msg = try {
            JSONObject(data)
        } catch (e: Exception) {
            return
        }
        val frame = frames.getOrPut(proxy) { Frame(proxy, isMainFrame, origin.toString()) }
        frame.lastSeen = SystemClock.uptimeMillis()
        when (msg.optString("type")) {
            "hello" -> if (SystemClock.uptimeMillis() < autoplayUntil) send(frame, JSONObject().put("type", "autoplay"))
            "v" -> {
                frame.has = msg.optBoolean("has")
                frame.playing = msg.optBoolean("playing")
                frame.waiting = msg.optBoolean("waiting")
                frame.ended = msg.optBoolean("ended")
                frame.pos = msg.optDouble("pos", 0.0)
                frame.dur = msg.optDouble("dur", 0.0)
                frame.live = msg.optBoolean("live")
                frame.area = msg.optDouble("area", 0.0)
                // Phim vừa bắt đầu chạy sau khi mở từ điện thoại → tự phóng to toàn màn hình.
                if (frame.playing && SystemClock.uptimeMillis() < autoplayUntil) {
                    autoplayUntil = 0
                    if (!fullscreenOn && customView == null) setFullscreen(true)
                }
                events.onWebStatusChanged()
            }
        }
    }

    private fun send(frame: Frame, msg: JSONObject) {
        try {
            frame.proxy.postMessage(msg.toString())
        } catch (e: Exception) {
            frames.remove(frame.proxy)
        }
    }

    private fun broadcast(msg: JSONObject) = frames.values.toList().forEach { send(it, msg) }

    private fun evalHandle(cmd: JSONObject) {
        webView.evaluateJavascript("window.__tvscHandle && window.__tvscHandle($cmd)", null)
    }

    // ---------- Chuột / bàn phím ảo ----------

    private val target: View get() = customView ?: webView

    fun click(x: Float, y: Float) {
        webView.requestFocus()
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    fun hover(x: Float, y: Float) {
        val ev = mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Cuộn theo kiểu kéo ngón tay: dy < 0 (kéo lên) → trang cuộn xuống. */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        val factor = scrollFactor()
        val ev = mouseEvent(MotionEvent.ACTION_SCROLL, x, y, v = dy / factor, h = -dx / factor)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    fun key(keyCode: Int) {
        webView.requestFocus()
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    fun text(text: String) {
        val cmd = JSONObject().put("type", "text").put("text", text)
        if (listenerSupported) broadcast(cmd) else evalHandle(cmd)
    }

    private fun scrollFactor(): Float =
        if (Build.VERSION.SDK_INT >= 26) ViewConfiguration.get(activity).scaledVerticalScrollFactor
        else 64 * activity.resources.displayMetrics.density

    private fun mouseEvent(action: Int, x: Float, y: Float, v: Float = 0f, h: Float = 0f): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, v)
            setAxisValue(MotionEvent.AXIS_HSCROLL, h)
        })
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
    }

    // ---------- Toàn màn hình thật (nút fullscreen của trình phát) ----------

    private fun exitCustomView() {
        val view = customView ?: return
        customCallback?.onCustomViewHidden()
        hideCustomView(view)
    }

    private fun hideCustomView(view: View) {
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customCallback = null
        webView.requestFocus()
        events.onWebStatusChanged()
    }

    // ---------- WebViewClient: chặn quảng cáo / chuyển trang ----------

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                events.onNotice("Đã chặn trang web mở ứng dụng ngoài")
                return true
            }
            val domain = registrableDomain(url.host)
            if (siteDomain == null || domain == siteDomain) return false
            // Từ trang tìm kiếm bấm sang kết quả: cho phép và coi trang mới là trang chính.
            if (request.hasGesture() && siteDomain in SEARCH_DOMAINS) {
                siteDomain = domain
                return false
            }
            events.onNotice("Đã chặn chuyển sang trang lạ: ${url.host}", url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host ?: return null
            if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            frames.clear()
            fullscreenOn = false
            events.onWebStatusChanged()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!docStartSupported) view.evaluateJavascript(script, null)
            events.onWebStatusChanged()
        }
    }

    // ---------- WebChromeClient: popup, fullscreen, hộp thoại ----------

    private val chrome = object : WebChromeClient() {
        // Popup (window.open / target=_blank): bắt lấy URL rồi quyết định có mở hay không.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val temp = WebView(activity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    if (registrableDomain(url.host) == siteDomain) {
                        webView.loadUrl(url.toString())
                    } else {
                        events.onNotice("Đã chặn cửa sổ quảng cáo: ${url.host ?: ""}", url.toString().takeIf { url.scheme?.startsWith("http") == true })
                    }
                    v.destroy()
                    return true
                }
            }
            main.postDelayed({ temp.destroy() }, 10_000)
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView?.let { hideCustomView(it) }
            customView = view
            customCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreenContainer.visibility = View.VISIBLE
            events.onWebStatusChanged()
        }

        override fun onHideCustomView() {
            customView?.let { hideCustomView(it) }
        }

        // Ảnh nền đen thay cho biểu tượng play xám mặc định của WebView.
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // Trang phim hay spam alert/confirm quảng cáo → tự đóng.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            result.cancel(); return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm(); return true
        }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onReceivedTitle(view: WebView, title: String?) = events.onWebStatusChanged()
    }

    // Đặt sau khai báo client/chrome vì Kotlin khởi tạo theo thứ tự trong file.
    init {
        webView.webViewClient = client
        webView.webChromeClient = chrome
    }

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private val SEARCH_DOMAINS = setOf("google.com", "google.com.vn", "bing.com", "duckduckgo.com", "coccoc.com", "yahoo.com")

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com",
            "onclickads.net", "onclkds.com", "adcash.com", "hilltopads.net", "trafficjunky.net", "clickadu.com",
            "ad-maven.com", "admaven.com", "mgid.com", "taboola.com", "outbrain.com", "yllix.com", "a-ads.com",
            "bidvertiser.com", "revcontent.com", "popmyads.com", "galaksion.com", "monetag.com", "highperformanceformat.com",
        )

        /** Tên miền gốc gần đúng: phim.example.com.vn → example.com.vn. */
        fun registrableDomain(host: String?): String? {
            val h = host?.lowercase()?.removePrefix("www.") ?: return null
            val parts = h.split('.')
            if (parts.size <= 2) return h
            val n = parts.size
            val secondLevel = parts[n - 2] in setOf("com", "net", "org", "edu", "gov", "co", "ac", "info", "biz")
            return if (parts[n - 1].length == 2 && secondLevel) parts.takeLast(3).joinToString(".") else parts.takeLast(2).joinToString(".")
        }
    }
}
