package com.example.secureqt.sdk.crypto

/**
 * Interface for asymmetric key exchange operations.
 * Default implementation: [EcdhKeyExchange] (ECDH over P-256 + SHA-256 derivation).
 *
 * Lifecycle: [generateKeyPair] → [getPublicKeyBytes] (send to remote) →
 *            [deriveSharedSecret] (with remote's public key) → ready for encryption.
 */
interface IKeyExchange {

    /**
     * Generates a fresh asymmetric key pair.
     * Must be called before [getPublicKeyBytes] or [deriveSharedSecret].
     */
    fun generateKeyPair()

    /**
     * Returns the local public key in X.509 encoded format.
     * This is the value to send to the remote peer during the handshake.
     *
     * @throws IllegalStateException if [generateKeyPair] has not been called.
     */
    fun getPublicKeyBytes(): ByteArray

    /**
     * Derives a shared secret from the local private key and the remote's public key.
     * The returned bytes are the final symmetric key (e.g., SHA-256 hashed for AES-256).
     *
     * @param remotePublicKeyBytes Remote peer's public key in X.509 encoded format.
     * @return Raw symmetric key bytes suitable for use with [ICryptoEngine].
     * @throws IllegalStateException if [generateKeyPair] has not been called.
     */
    fun deriveSharedSecret(remotePublicKeyBytes: ByteArray): ByteArray
}
