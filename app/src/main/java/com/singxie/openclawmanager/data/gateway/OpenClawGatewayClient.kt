package com.singxie.openclawmanager.data.gateway

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * OpenClaw Gateway WebSocket client.
 * - Waits for connect.challenge, then sends connect with device identity.
 * - device.id = SHA256(publicKey) hex; signature = Ed25519 over nonce:signedAt.
 */
class OpenClawGatewayClient(
    private val gatewayUrl: String,
    private val authToken: String? = null,
    private val deviceIdentity: DeviceIdentityManager.DeviceIdentity,
    private val identityManager: DeviceIdentityManager
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var connectRequestId: String? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, Channel<String>>()
    private var eventListener: ((String, Any?) -> Unit)? = null
    private var onConnectFailedListener: ((String, Map<String, Any?>?) -> Unit)? = null

    fun setEventListener(listener: (String, Any?) -> Unit) {
        eventListener = listener
    }

    /** Called when connect request fails; details may contain requestId for pairing. */
    fun setOnConnectFailedListener(listener: (String, Map<String, Any?>?) -> Unit) {
        onConnectFailedListener = listener
    }

    fun connect() {
        if (_connectionState.value is ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.WaitingChallenge
        ) return
        webSocket?.close(1000, null)
        _connectionState.value = ConnectionState.Connecting

        val origin = originForGatewayUrl(gatewayUrl)
        val request = Request.Builder()
            .url(gatewayUrl)
            .header("Origin", origin)
            .build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket open")
                _connectionState.value = ConnectionState.WaitingChallenge
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                pendingRequests.values.forEach { it.close() }
                pendingRequests.clear()
                _connectionState.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                pendingRequests.values.forEach { it.close() }
                pendingRequests.clear()
                _connectionState.value = ConnectionState.Error(t.message ?: "Connection failed")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        pendingRequests.values.forEach { it.close() }
        pendingRequests.clear()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun handleMessage(text: String) {
        try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = gson.fromJson(text, mapType) ?: return
            val type = map["type"] as? String ?: return

            when (type) {
                "event" -> {
                    val event = map["event"] as? String
                    val payload = map["payload"]
                    if (event == "connect.challenge") {
                        handleConnectChallenge(payload, text)
                    } else {
                        if (event != null && (event == "chat" || event.startsWith("chat.") ||
                                event == "agent" || event.startsWith("agent.") || event.startsWith("response."))) {
                            val keys = (payload as? Map<*, *>)?.keys?.joinToString(",") ?: "?"
                            Log.d(TAG, "event: $event keys=$keys")
                        }
                        eventListener?.invoke(event ?: "", payload)
                    }
                }
                "res" -> {
                    val id = map["id"]?.toString()
                    val ok = map["ok"] as? Boolean ?: false
                    if (id != null) {
                        if (id == connectRequestId) {
                            connectRequestId = null
                            if (ok) {
                                val payload = map["payload"]
                                val hello = parseHelloOk(payload)
                                _connectionState.value = ConnectionState.Connected(hello)
                            } else {
                                val err = map["error"] as? Map<*, *>
                                val msg = err?.get("message") as? String ?: "Connect failed"
                                val details = err?.get("details") as? Map<*, *>
                                val detailsStrKey = details?.mapKeys { it.key?.toString() ?: "" }?.mapValues { it.value }
                                val merged = mutableMapOf<String, Any?>()
                                detailsStrKey?.let { merged.putAll(it as Map<String, Any?>) }
                                listOf("requestId", "pairingRequestId", "id").forEach { key ->
                                    err?.get(key)?.let { merged.putIfAbsent(key, it) }
                                }
                                @Suppress("UNCHECKED_CAST")
                                onConnectFailedListener?.invoke(msg, merged)
                                _connectionState.value = ConnectionState.Error(msg)
                            }
                        } else {
                            pendingRequests[id]?.trySend(text)?.isSuccess
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMessage parse error", e)
        }
    }

    private fun parseHelloOk(payload: Any?): HelloOkPayload? {
        if (payload !is Map<*, *>) return null
        return try {
            gson.fromJson(gson.toJson(payload), HelloOkPayload::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun handleConnectChallenge(payload: Any?, rawJson: String) {
        val nonce: String
        val ts: Long
        try {
            if (payload is Map<*, *>) {
                nonce = (payload["nonce"] ?: "") as String
                ts = (payload["ts"] as? Number)?.toLong() ?: System.currentTimeMillis()
            } else {
                val challenge = gson.fromJson(rawJson, GatewayMessage::class.java)
                val p = challenge.payload
                if (p is ConnectChallengePayload) {
                    nonce = p.nonce
                    ts = p.ts
                } else return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse challenge failed", e)
            _connectionState.value = ConnectionState.Error("Invalid challenge")
            return
        }
        sendConnect(nonce, ts)
    }

    private fun sendConnect(nonce: String, signedAt: Long) {
        val signedAtMs = signedAt
        val payload = identityManager.buildDeviceAuthPayloadV2(
            deviceId = deviceIdentity.deviceId,
            clientId = ConnectClient().id,
            clientMode = ConnectClient().mode,
            role = "operator",
            scopes = listOf("operator.read", "operator.write", "operator.admin"),
            signedAtMs = signedAtMs,
            token = authToken,
            nonce = nonce
        )
        val signature = identityManager.signPayload(deviceIdentity.privateKey, payload)
        val device = ConnectDevice(
            id = deviceIdentity.deviceId,
            publicKey = deviceIdentity.publicKeyB64,
            signature = signature,
            signedAt = signedAt,
            nonce = nonce
        )
        val params = ConnectParams(
            client = ConnectClient(),
            auth = authToken?.let { ConnectAuth(token = it) },
            device = device
        )
        val id = "connect-${System.currentTimeMillis()}"
        connectRequestId = id
        @Suppress("UNCHECKED_CAST")
        val paramsMap = gson.fromJson(gson.toJson(params), object : TypeToken<Map<String, Any?>>() {}.type) as Map<String, Any?>
        val req = mapOf(
            "type" to "req",
            "id" to id,
            "method" to "connect",
            "params" to paramsMap
        )
        webSocket?.send(gson.toJson(req))
    }

    /**
     * Send a gateway request and wait for response (by id).
     * Returns JSON string of response payload or error.
     */
    suspend fun request(method: String, params: Map<String, Any?> = emptyMap()): Result<String> {
        val state = _connectionState.value
        if (state !is ConnectionState.Connected) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        val id = "req-${UUID.randomUUID()}"
        val channel = Channel<String>(1)
        pendingRequests[id] = channel
        val req = mapOf(
            "type" to "req",
            "id" to id,
            "method" to method,
            "params" to params
        )
        if (method == "chat.send") {
            Log.d(TAG, "request: chat.send sessionKey=${params["sessionKey"]} messageLen=${(params["message"] as? String)?.length}")
        }
        webSocket?.send(gson.toJson(req)) ?: run {
            channel.close()
            pendingRequests.remove(id)
            return Result.failure(IllegalStateException("Socket closed"))
        }
        return try {
            val response = channel.receive()
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = gson.fromJson(response, mapType)
            val ok = map["ok"] as? Boolean ?: false
            if (ok) {
                val payload = map["payload"]
                Result.success(gson.toJson(payload))
            } else {
                val err = map["error"] as? Map<*, *>
                val msg = err?.get("message") as? String ?: "Request failed"
                Result.failure(Exception(msg))
            }
        } catch (e: ClosedReceiveChannelException) {
            Result.failure(IllegalStateException("Connection closed", e))
        } finally {
            pendingRequests.remove(id)
            channel.close()
        }
    }

    companion object {
        private const val TAG = "OpenClawGateway"
    }

    /**
     * OpenClaw Gateway v2026.2.26+ may enforce Origin allowlist (gateway.controlUi.allowedOrigins)
     * even for native operator WebSocket connections. We set Origin explicitly.
     *
     * ws://host:port -> http://host:port
     * wss://host:port -> https://host:port
     */
    private fun originForGatewayUrl(url: String): String {
        return try {
            val uri = URI(url)
            val scheme = when (uri.scheme?.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                "http" -> "http"
                "https" -> "https"
                else -> "http"
            }
            val authority = uri.rawAuthority ?: run {
                val host = uri.host ?: url.substringAfter("://").substringBefore("/").substringBefore("?")
                host
            }
            "$scheme://$authority"
        } catch (_: Exception) {
            // Best-effort fallback; keep behavior deterministic.
            when {
                url.startsWith("wss://", ignoreCase = true) -> "https://" + url.removePrefix("wss://").substringBefore("/")
                url.startsWith("ws://", ignoreCase = true) -> "http://" + url.removePrefix("ws://").substringBefore("/")
                url.startsWith("https://", ignoreCase = true) -> "https://" + url.removePrefix("https://").substringBefore("/")
                url.startsWith("http://", ignoreCase = true) -> "http://" + url.removePrefix("http://").substringBefore("/")
                else -> "http://localhost"
            }
        }
    }
}
