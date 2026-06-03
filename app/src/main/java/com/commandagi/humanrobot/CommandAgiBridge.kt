package com.commandagi.humanrobot

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Registers THIS phone as a robot on CommandAGI and bridges it: streams the camera up and surfaces
 * the control actions the platform sends down (which a human then performs). This is the producer
 * side of the API — the phone *is* the robot.
 *
 * @param apiKey  a CommandAGI API key (operator scope)
 * @param baseUrl API base, default https://api.commandagi.com
 * @param onAction called on the WS thread with each control action (e.g. "move", {"speed":0.8})
 * @param onStatus called with human-readable connection status + the watch URL
 */
class CommandAgiBridge(
    private val apiKey: String,
    private val baseUrl: String = "https://api.commandagi.com",
    private val onAction: (action: String, payload: JSONObject) -> Unit,
    private val onStatus: (status: String, sessionUrl: String?) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val json = "application/json".toMediaType()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var deviceId: String = ""
    @Volatile var connected: Boolean = false; private set

    private fun post(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { res: Response ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw RuntimeException("POST $path -> ${res.code}: $text")
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    /** Register the robot and open the realtime channel. Call from a background thread. */
    fun start() {
        onStatus("Registering robot…", null)
        val sessionId = post("/machines", JSONObject().put("title", "phone-robot")).getString("sessionId")
        val dev = post(
            "/sessions/$sessionId/connect-device",
            JSONObject().put("kind", "robot").put("name", "phone-robot"),
        )
        deviceId = dev.getString("deviceId")
        val controlUrl = dev.getString("controlUrl")
        val token = dev.getString("token")
        val webBase = baseUrl.replace("https://api-dev.", "https://dev.").replace("https://api.", "https://")
        val sessionUrl = "$webBase/machine/$sessionId"

        val wsUrl = (controlUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")) +
            (if (controlUrl.contains("?")) "&" else "?") + "runtime=1&role=agent&token=$token"
        ws = http.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    webSocket.send(JSONObject().put("type", "status").put("status", "live").toString())
                    webSocket.send(
                        JSONObject().put("type", "channels").put(
                            "channels",
                            org.json.JSONArray().put(
                                JSONObject().put("channelId", "cam-head").put("label", "Camera").put("kind", "camera"),
                            ),
                        ).toString(),
                    )
                    onStatus("Live — you're a robot", sessionUrl)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val m = try { JSONObject(text) } catch (_: Exception) { return }
                    if (m.optString("type") == "control") {
                        onAction(m.optString("action"), m.optJSONObject("payload") ?: JSONObject())
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    onStatus("Disconnected: ${t.message}", null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                }
            },
        )
    }

    /** Publish one camera frame (JPEG bytes) on the cam-head channel. */
    fun sendFrame(jpeg: ByteArray) {
        val socket = ws ?: return
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        socket.send(JSONObject().put("type", "frame").put("channelId", "cam-head").put("url", url).toString())
    }

    fun stop() {
        connected = false
        ws?.close(1000, "bye")
        ws = null
    }
}
