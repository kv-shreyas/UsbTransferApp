package com.example.secureqt.sdk.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption implementation.
 *
 * - IV: 12 bytes, randomly generated per encryption call.
 * - Auth Tag: 128-bit, appended to the ciphertext by the JCE provider.
 * - Key: 256-bit (32 bytes) derived from the ECDH shared secret.
 * 
 * Performance notes:
 * - Reuses a single SecureRandom instance (thread-safe) to avoid init overhead.
 * - Callers should cache the SecretKeySpec when encrypting multiple chunks with the same key.
 */
class AesGcmEngine : ICryptoEngine {

    companion object {
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        
        // SecureRandom is thread-safe — reuse to avoid per-call init overhead
        private val secureRandom = SecureRandom()
    }

    override fun encrypt(data: ByteArray, key: ByteArray): ICryptoEngine.EncryptedPayload {
        val secretKey = SecretKeySpec(key, "AES")
        val iv = ByteArray(IV_SIZE).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(data)
        return ICryptoEngine.EncryptedPayload(iv, ciphertext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, key: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
