package com.jarvis.assistant

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class PcControlClient(
    private val onStatus: (String) -> Unit = {}
) {
    private val client = OkHttpClient()
    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null

    fun connect(url: String, token: String, onReady: () -> Unit = {}) {
        disconnect()
        val request = Request.Builder().url(url.trim()).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                send("pair", token = token)
                postStatus("PC LINK  •  CONNECTED")
                main.postDelayed(onReady, 250L)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val message = when (type) {
                        "status" -> json.optString("message", "PC LINK  •  ONLINE")
                        "hub" -> "DEVICE HUB  •  " + json.optInt("clients", 1) + " CONNECTED"
                        "error" -> "PC LINK  •  " + json.optString("message", "ERROR")
                        else -> json.optString("message", "PC LINK  •  EVENT")
                    }
                    postStatus(message)
                } catch (_: Exception) {
                    postStatus(text.take(80))
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                postStatus("PC LINK  •  DISCONNECTED")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                postStatus("PC LINK  •  CONNECTION FAILED")
            }
        })
    }

    fun send(action: String, value: String = "", token: String = ""): Boolean {
        val json = JSONObject()
            .put("action", action)
            .put("value", value)
        if (token.isNotBlank()) json.put("token", token)
        return socket?.send(json.toString()) == true
    }

    fun disconnect() {
        socket?.close(1000, "user")
        socket = null
    }

    private fun postStatus(value: String) {
        main.post { onStatus(value) }
    }
}
