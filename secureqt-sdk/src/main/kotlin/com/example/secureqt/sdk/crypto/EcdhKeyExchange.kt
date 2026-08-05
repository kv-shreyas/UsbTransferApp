package com.example.secureqt.sdk.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * ECDH (Elliptic-Curve Diffie-Hellman) key exchange over the P-256 curve.
 *
 * The raw ECDH shared secret is passed through SHA-256 to produce a
 * uniform 256-bit symmetric key suitable for AES-256-GCM.
 */
class EcdhKeyExchange : IKeyExchange {

    private var keyPair: KeyPair? = null

    override fun generateKeyPair() {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(256)
        keyPair = keyGen.generateKeyPair()
    }

    override fun getPublicKeyBytes(): ByteArray {
        return keyPair?.public?.encoded
            ?: throw IllegalStateException("Key pair not generated. Call generateKeyPair() first.")
    }

    override fun deriveSharedSecret(remotePublicKeyBytes: ByteArray): ByteArray {
        val kp = keyPair
            ?: throw IllegalStateException("Key pair not generated. Call generateKeyPair() first.")

        val kf = KeyFactory.getInstance("EC")
        val remotePublicKey = kf.generatePublic(X509EncodedKeySpec(remotePublicKeyBytes))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(remotePublicKey, true)

        val rawSecret = ka.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(rawSecret)
    }
}
