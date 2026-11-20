package com.tvsmartconnect

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity(), RelayClient.Listener, WebController.Events {

    private lateinit var web: WebController
    private lateinit var webView: WebView
    private lateinit var idleView: View
    private lateinit var cursor: CursorView
    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var serverText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var relay: RelayClient
    private lateinit var code: String

    private var browsing = false
    private var lastStatusAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        idleView = findViewById(R.id.idleView)
        cursor = findViewById(R.id.cursorView)
        statusText = findViewById(R.id.statusText)
        messageText = findViewById(R.id.messageText)
        serverText = findViewById(R.id.serverText)
        qrImage = findViewById(R.id.qrImage)

        code = Prefs.code(this)
        findViewById<TextView>(R.id.codeText).text = code

        web = WebController(this, webView, findViewById<FrameLayout>(R.id.fullscreenContainer), this)
        relay = RelayClient(Prefs.serverUrl(this), code, this)
        renderServer()
        onConnectionChanged(false)
    }

    override fun onStart() {
        super.onStart()
        web.onResume()
        relay.connect()
    }

    override fun onStop() {
        super.onStop()
        web.onPause()
        relay.disconnect()
    }

    // ---------- Lệnh từ điện thoại ----------

    override fun onCommand(cmd: JSONObject) {
        when (cmd.optString("type")) {
            "play" -> openPage(cmd.optString("url"))
            "pause", "resume", "toggle", "seek", "seekBy" -> if (browsing) web.videoCommand(cmd)
            "fullscreen" -> if (browsing) web.toggleFullscreen()
            "back" -> if (browsing && !web.back()) goHome()
            "reload" -> if (browsing) web.reload()
            "stop", "home" -> goHome()
            "move" -> moveCursor(cmd.optDouble("dx", 0.0).toFloat(), cmd.optDouble("dy", 0.0).toFloat())
            "click" -> if (browsing) {
                cursor.flashClick()
                web.click(cursor.cursorX, cursor.cursorY)
            }
            "scroll" -> if (browsing) {
                cursor.wake()
                web.scroll(cursor.cursorX, cursor.cursorY, cmd.optDouble("dx", 0.0).toFloat(), cmd.optDouble("dy", 0.0).toFloat())
            }
            "volume" -> {
                val dir = if (cmd.optString("dir") == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
            }
        }
        sendStatus(force = true)
    }

    override fun onConnectionChanged(connected: Boolean) {
        statusText.text = if (connected) "● Đã kết nối — sẵn sàng" else "○ Đang kết nối tới máy chủ…"
        statusText.setTextColor(if (connected) 0xFF2ECC71.toInt() else 0xFF8B92A1.toInt())
        if (connected) sendStatus(force = true)
    }

    private fun openPage(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        browsing = true
        idleView.visibility = View.GONE
        messageText.visibility = View.GONE
        cursor.visibility = View.VISIBLE
        cursor.wake()
        web.open(url)
    }

    private fun goHome() {
        browsing = false
        web.close()
        cursor.visibility = View.GONE
        idleView.visibility = View.VISIBLE
    }

    /** Di con trỏ; khi chạm mép màn hình thì cuộn trang phần còn dư. */
    private fun moveCursor(dx: Float, dy: Float) {
        if (!browsing) return
        // Bàn di trên điện thoại nhỏ hơn TV nhiều → nhân hệ số theo bề rộng màn hình.
        val scale = cursor.width / 640f
        val (ox, oy) = cursor.moveBy(dx * scale, dy * scale)
        web.hover(cursor.cursorX, cursor.cursorY)
        if (ox != 0f || oy != 0f) web.scroll(cursor.cursorX, cursor.cursorY, -ox, -oy)
        cursor.invalidate()
    }

    // ---------- Gửi trạng thái về điện thoại ----------

    override fun onWebStatusChanged() = sendStatus()

    override fun onNotice(message: String, url: String?) {
        relay.send(JSONObject().put("type", "notice").put("message", message).put("url", url ?: JSONObject.NULL))
    }

    private fun sendStatus(force: Boolean = false) {
        if (!relay.connected) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatusAt < 400) return
        lastStatusAt = now

        val msg = JSONObject().put("type", "status").put("mode", if (browsing) "web" else "idle")
        if (browsing) {
            msg.put("title", web.pageTitle ?: JSONObject.NULL)
                .put("url", web.pageUrl ?: JSONObject.NULL)
                .put("fullscreen", web.fullscreenOn)
            val v = web.videoStatus()
            if (v != null) {
                msg.put("state", v.state).put("position", v.position).put("duration", v.duration).put("live", v.live)
            } else {
                msg.put("state", "novideo")
            }
        } else {
            msg.put("state", "idle")
        }
        relay.send(msg)
    }

    // ---------- Giao diện màn hình chờ ----------

    private fun renderServer() {
        val server = Prefs.serverUrl(this).trimEnd('/')
        serverText.text = "hoặc mở ${server.substringAfter("://")} trên iPhone và nhập mã"
        qrImage.setImageBitmap(Qr.bitmap("$server/?c=$code", 480))
    }

    private fun askServerUrl() {
        val input = EditText(this).apply {
            setText(Prefs.serverUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Địa chỉ máy chủ")
            .setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Prefs.setServerUrl(this, url)
                    renderServer()
                    relay.setBaseUrl(url)
                } else {
                    Toast.makeText(this, "Địa chỉ phải bắt đầu bằng http:// hoặc https://", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ---------- Remote Fire TV ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!browsing) {
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_DOWN) askServerUrl()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        // Nhấn giữ thì con trỏ chạy nhanh dần.
        val step = (12 + minOf(event.repeatCount * 4, 40)).toFloat()
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveCursorPx(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveCursorPx(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) moveCursorPx(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) moveCursorPx(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (down && event.repeatCount == 0) {
                cursor.flashClick()
                web.click(cursor.cursorX, cursor.cursorY)
            }
            KeyEvent.KEYCODE_BACK -> if (down && event.repeatCount == 0 && !web.back()) goHome()
            KeyEvent.KEYCODE_MENU -> if (down) web.toggleFullscreen()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (down) web.videoCommand(JSONObject().put("type", "toggle"))
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) web.videoCommand(JSONObject().put("type", "seekBy").put("delta", -10))
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) web.videoCommand(JSONObject().put("type", "seekBy").put("delta", 10))
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun moveCursorPx(dx: Float, dy: Float) {
        val d = resources.displayMetrics.density
        val (ox, oy) = cursor.moveBy(dx * d, dy * d)
        web.hover(cursor.cursorX, cursor.cursorY)
        if (ox != 0f || oy != 0f) web.scroll(cursor.cursorX, cursor.cursorY, -ox * 3, -oy * 3)
    }
}
