package com.tvsmartconnect

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Kết nối WebSocket tới máy chủ relay với vai trò "tv", tự kết nối lại khi rớt mạng.
 * Mọi callback của [Listener] đều chạy trên main thread.
 */
class RelayClient(
    private var baseUrl: String,
    private val code: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnectionChanged(connected: Boolean)
        fun onCommand(cmd: JSONObject)
    }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Máy chủ Render gói miễn phí có thể mất ~1 phút để "thức dậy".
        .readTimeout(70, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var started = false
    private var retry = 0
    var connected = false
        private set

    private val reconnect = Runnable { open() }

    fun connect() {
        if (started) return
        started = true
        retry = 0
        open()
    }

    fun disconnect() {
        started = false
        main.removeCallbacks(reconnect)
        socket?.close(1000, null)
        socket = null
        setConnected(false)
    }

    fun setBaseUrl(url: String) {
        disconnect()
        baseUrl = url
        connect()
    }

    fun send(msg: JSONObject) {
        socket?.send(msg.toString())
    }

    private fun open() {
        if (!started) return
        val wsUrl = baseUrl.trimEnd('/')
            .replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/ws?role=tv&code=$code"
        val request = try {
            Request.Builder().url(wsUrl).build()
        } catch (e: IllegalArgumentException) {
            scheduleReconnect()
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (socket !== webSocket) return@post
                    retry = 0
                    setConnected(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return
                }
                main.post { if (socket === webSocket) listener.onCommand(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(webSocket: WebSocket) {
        main.post {
            if (socket !== webSocket) return@post
            socket = null
            setConnected(false)
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!started) return
        val delay = minOf(15_000L, 1_000L shl minOf(retry, 4))
        retry++
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        listener.onConnectionChanged(value)
    }
}
