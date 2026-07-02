package com.vcam.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * ConnectServer — TCP server that lets the "conect vcam" Windows app
 * control zoom / scale / pan / rotation / mirror in real-time over USB (ADB forward).
 *
 * Usage on PC:
 *   1. adb forward tcp:7979 tcp:7979
 *   2. Connect with host=localhost port=7979 token=(shown in app)
 *
 * Protocol: newline-delimited JSON
 *   → {"cmd":"auth","token":"XXXXXX"}
 *   ← {"status":"ok"}
 *   → {"cmd":"zoom","value":2.5}
 *   → {"cmd":"scale","value":0.8}
 *   → {"cmd":"pan","x":100,"y":50}
 *   → {"cmd":"pan_reset"}
 *   → {"cmd":"rotate","degrees":90}
 *   → {"cmd":"mirror","enabled":true}
 *   → {"cmd":"ping"}
 *   → {"cmd":"info"}   ← returns current state
 */
class ConnectServer(
    private val context: Context,
    private val onCommand: (cmd: String, params: JSONObject) -> Unit
) {

    companion object {
        private const val TAG        = "ConnectServer"
        const val PORT               = 7979
        private const val PREFS_NAME = "vcam_connect"
        private const val KEY_TOKEN  = "connect_token"
        private const val KEY_ENABLED = "connect_enabled"

        fun getToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var t = prefs.getString(KEY_TOKEN, null)
            if (t.isNullOrEmpty()) {
                t = (100000..999999).random().toString()
                prefs.edit().putString(KEY_TOKEN, t).apply()
            }
            return t
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            try {
                val ss = ServerSocket(PORT).also { it.reuseAddress = true; serverSocket = it }
                Log.d(TAG, "ConnectServer listening on port $PORT")
                while (running && isActive) {
                    try {
                        val client = ss.accept()
                        launch { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) Log.w(TAG, "Accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { scope.cancel() } catch (_: Exception) {}
        Log.d(TAG, "ConnectServer stopped")
    }

    private fun handleClient(socket: Socket) {
        val token = getToken(context)
        var authenticated = false
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream().writer(Charsets.UTF_8), true)

            fun reply(obj: JSONObject) = writer.println(obj.toString())
            fun ok()   = reply(JSONObject().put("status", "ok"))
            fun err(m: String) = reply(JSONObject().put("status", "error").put("message", m))

            while (running && !socket.isClosed) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                try {
                    val json = JSONObject(trimmed)
                    when (val cmd = json.optString("cmd", "")) {

                        "ping" -> reply(
                            JSONObject().put("status", "ok")
                                .put("auth", authenticated)
                                .put("port", PORT)
                        )

                        "auth" -> {
                            val clientToken = json.optString("token", "")
                            authenticated = clientToken == token
                            if (authenticated) reply(JSONObject().put("status", "ok").put("message", "authenticated"))
                            else err("invalid token")
                        }

                        else -> {
                            if (!authenticated) { err("not authenticated"); continue }
                            onCommand(cmd, json)
                            ok()
                        }
                    }
                } catch (e: Exception) {
                    try {
                        writer.println(JSONObject().put("status", "error").put("message", e.message ?: "bad request"))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
