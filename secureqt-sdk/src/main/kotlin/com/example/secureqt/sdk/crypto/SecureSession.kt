package com.example.secureqt.sdk.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the full lifecycle of a secure communication session:
 * key pair generation → public key exchange → shared secret derivation → encrypt/decrypt.
 *
 * This is the primary entry point for consumers who need end-to-end encrypted communication.
 * It composes [IKeyExchange] and [ICryptoEngine] following the Dependency Inversion principle.
 *
 * Performance notes:
 * - Caches the SecretKeySpec after key derivation (avoids per-call reconstruction).
 * - Reuses a single SecureRandom (thread-safe).
 * - encrypt()/decrypt() operate directly on the cached key for hot-path performance.
 */
class SecureSession(
    private val keyExchange: IKeyExchange = EcdhKeyExchange(),
    private val cryptoEngine: ICryptoEngine = AesGcmEngine()
) {
    companion object {
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private val secureRandom = SecureRandom()
    }

    private var derivedKey: ByteArray? = null

    // Cached SecretKeySpec — avoids recreating on every encrypt/decrypt.
    @Volatile
    private var cachedKeySpec: SecretKeySpec? = null

    init {
        keyExchange.generateKeyPair()
    }

    /** Returns the local public key bytes to send to the remote peer. */
    fun getPublicKey(): ByteArray = keyExchange.getPublicKeyBytes()

    /**
     * Derives the shared symmetric key from the remote peer's public key.
     * After this call, [encrypt] and [decrypt] become available.
     */
    fun deriveSharedSecret(remotePublicKeyBytes: ByteArray) {
        derivedKey = keyExchange.deriveSharedSecret(remotePublicKeyBytes)
        cachedKeySpec = SecretKeySpec(derivedKey, "AES")
    }

    /**
     * Encrypts [data] using the cached derived key.
     * Uses the cached SecretKeySpec directly to avoid per-call overhead.
     * @throws IllegalStateException if [deriveSharedSecret] has not been called.
     */
    fun encrypt(data: ByteArray): ICryptoEngine.EncryptedPayload {
        val keySpec = cachedKeySpec
            ?: throw IllegalStateException("Shared secret not derived. Call deriveSharedSecret() first.")
        val iv = ByteArray(IV_SIZE).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(data)
        return ICryptoEngine.EncryptedPayload(iv, ciphertext)
    }

    /**
     * Decrypts [ciphertext] using the provided [iv] and the cached derived key.
     * @throws IllegalStateException if [deriveSharedSecret] has not been called.
     * @throws javax.crypto.AEADBadTagException if the data has been tampered with.
     */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val keySpec = cachedKeySpec
            ?: throw IllegalStateException("Shared secret not derived. Call deriveSharedSecret() first.")
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Returns true if the shared secret has been derived and the session is ready for encryption. */
    fun isReady(): Boolean = derivedKey != null

    /** Resets the session: clears the derived key and generates a fresh key pair. */
    fun reset() {
        derivedKey = null
        cachedKeySpec = null
        keyExchange.generateKeyPair()
    }
}
