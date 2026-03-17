package com.singxie.openclawmanager.data.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages persistent Ed25519 device identity for OpenClaw Gateway connect.
 * device.id = fingerprint (SHA256 of public key, hex); publicKey = base64(raw 32 bytes).
 */
class DeviceIdentityManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateIdentity(): DeviceIdentity {
        val privateKeyB64 = prefs.getString(KEY_PRIVATE_KEY, null)
        val publicKeyB64 = prefs.getString(KEY_PUBLIC_KEY, null)
        return if (privateKeyB64 != null && publicKeyB64 != null) {
            val privateKeyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
            val publicKeyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
            if (privateKeyBytes.size != Ed25519PrivateKeyParameters.KEY_SIZE ||
                publicKeyBytes.size != Ed25519PublicKeyParameters.KEY_SIZE
            ) {
                Log.w(TAG, "Stored key size wrong, regenerating")
                createAndSaveIdentity()
            } else {
                val privateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
                val deviceId = fingerprint(publicKeyBytes)
                DeviceIdentity(deviceId = deviceId, publicKeyB64 = publicKeyB64, privateKey = privateKey)
            }
        } else {
            createAndSaveIdentity()
        }
    }

    private fun createAndSaveIdentity(): DeviceIdentity {
        val keyPair = generateKeyPair()
        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters
        val publicKeyB64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        val deviceId = fingerprint(publicKey.encoded)
        prefs.edit()
            .putString(KEY_PRIVATE_KEY, Base64.encodeToString(privateKey.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC_KEY, publicKeyB64)
            .apply()
        return DeviceIdentity(deviceId = deviceId, publicKeyB64 = publicKeyB64, privateKey = privateKey)
    }

    private fun generateKeyPair(): AsymmetricCipherKeyPair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        return gen.generateKeyPair()
    }

    /**
     * Fingerprint = SHA-256(publicKeyBytes) as hex. Gateway expects device.id to match this.
     */
    private fun fingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyBytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Build v2 device auth payload string (OpenClaw gateway device-auth.ts).
     * Payload = "v2|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce"
     */
    fun buildDeviceAuthPayloadV2(
        deviceId: String,
        clientId: String,
        clientMode: String,
        role: String,
        scopes: List<String>,
        signedAtMs: Long,
        token: String?,
        nonce: String
    ): String {
        val scopesStr = scopes.joinToString(",")
        val tokenStr = token ?: ""
        return listOf(
            "v2",
            deviceId,
            clientId,
            clientMode,
            role,
            scopesStr,
            signedAtMs.toString(),
            tokenStr,
            nonce
        ).joinToString("|")
    }

    /**
     * Sign the v2 payload string (UTF-8 bytes) with Ed25519; return Base64 signature.
     */
    fun signPayload(privateKey: Ed25519PrivateKeyParameters, payload: String): String {
        val message = payload.toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        val signature = signer.generateSignature()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    data class DeviceIdentity(
        val deviceId: String,
        val publicKeyB64: String,
        val privateKey: Ed25519PrivateKeyParameters
    )

    companion object {
        private const val TAG = "DeviceIdentityManager"
        private const val PREFS_NAME = "openclaw_device_identity"
        private const val KEY_PRIVATE_KEY = "ed25519_private_key"
        private const val KEY_PUBLIC_KEY = "ed25519_public_key"
    }
}
