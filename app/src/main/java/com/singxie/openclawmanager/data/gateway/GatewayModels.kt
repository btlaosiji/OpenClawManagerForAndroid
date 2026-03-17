package com.singxie.openclawmanager.data.gateway

import com.google.gson.annotations.SerializedName

/**
 * OpenClaw Gateway Protocol (WebSocket, JSON).
 * See: https://docs.openclaw.ai/gateway/protocol
 *
 * Framing:
 * - Request:  { "type": "req", "id", "method", "params" }
 * - Response: { "type": "res", "id", "ok", "payload" | "error" }
 * - Event:    { "type": "event", "event", "payload", "seq?", "stateVersion?" }
 */

// ----- Events (Gateway → Client) -----

data class GatewayMessage(
    val type: String,
    val id: String? = null,
    val method: String? = null,
    val params: Map<String, Any?>? = null,
    val event: String? = null,
    val payload: Any? = null,
    val ok: Boolean? = null,
    val error: GatewayError? = null,
    val seq: Long? = null,
    @SerializedName("stateVersion") val stateVersion: Long? = null
)

data class GatewayError(
    val message: String? = null,
    val details: ErrorDetails? = null
)

data class ErrorDetails(
    val code: String? = null,
    val reason: String? = null,
    @SerializedName("canRetryWithDeviceToken") val canRetryWithDeviceToken: Boolean? = null,
    @SerializedName("recommendedNextStep") val recommendedNextStep: String? = null
)

// connect.challenge (Gateway → Client, before connect)
data class ConnectChallengePayload(
    val nonce: String,
    val ts: Long
)

// hello-ok (Gateway → Client, after successful connect)
data class HelloOkPayload(
    val type: String = "hello-ok",
    val protocol: Int,
    val policy: Policy? = null,
    val auth: HelloAuth? = null
)

data class Policy(
    @SerializedName("tickIntervalMs") val tickIntervalMs: Long? = null
)

data class HelloAuth(
    @SerializedName("deviceToken") val deviceToken: String? = null,
    val role: String? = null,
    val scopes: List<String>? = null
)

// ----- Connect request (Client → Gateway) -----

data class ConnectParams(
    @SerializedName("minProtocol") val minProtocol: Int = 3,
    @SerializedName("maxProtocol") val maxProtocol: Int = 3,
    val client: ConnectClient,
    val role: String = "operator",
    val scopes: List<String> = listOf("operator.read", "operator.write", "operator.admin"),
    val caps: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val permissions: Map<String, Any> = emptyMap(),
    val auth: ConnectAuth? = null,
    val locale: String = "en-US",
    @SerializedName("userAgent") val userAgent: String = "openclaw-android-manager/1.0",
    val device: ConnectDevice
)

data class ConnectClient(
    val id: String = "openclaw-control-ui",
    val version: String = "1.0.0",
    val platform: String = "android",
    val mode: String = "ui"
)

data class ConnectAuth(
    val token: String? = null
)

data class ConnectDevice(
    val id: String,
    @SerializedName("publicKey") val publicKey: String,
    val signature: String,
    @SerializedName("signedAt") val signedAt: Long,
    val nonce: String
)

// ----- Connection state (client-side) -----

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object WaitingChallenge : ConnectionState()
    data class Connected(val hello: HelloOkPayload?) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
