package com.example.secureqt.sdk.crypto

/**
 * Interface for symmetric encryption/decryption operations.
 * Default implementation: [AesGcmEngine] (AES-256-GCM).
 *
 * Implementors can swap in alternative algorithms (e.g., ChaCha20-Poly1305)
 * without changing any consumer code.
 */
interface ICryptoEngine {

    /**
     * Result of an encryption operation, containing the IV and ciphertext.
     * For AES-GCM, the ciphertext includes the authentication tag appended by the cipher.
     */
    data class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray)

    /**
     * Encrypts [data] using the provided symmetric [key].
     * Generates a fresh random IV internally for each call.
     *
     * @param data Plaintext bytes to encrypt.
     * @param key  Raw symmetric key bytes (e.g., 32 bytes for AES-256).
     * @return     [EncryptedPayload] containing the IV and ciphertext.
     */
    fun encrypt(data: ByteArray, key: ByteArray): EncryptedPayload

    /**
     * Decrypts [ciphertext] using the provided [iv] and symmetric [key].
     *
     * @param iv         Initialization vector used during encryption.
     * @param ciphertext Encrypted bytes (including auth tag for GCM).
     * @param key        Raw symmetric key bytes.
     * @return           Decrypted plaintext bytes.
     * @throws javax.crypto.AEADBadTagException if the authentication tag is invalid.
     */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, key: ByteArray): ByteArray
}
