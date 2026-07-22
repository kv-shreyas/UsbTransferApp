package com.example.securequicktransferapp.data.security

import java.security.*
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoManager {
    private var sharedSecret: ByteArray? = null
    private val keyPair: KeyPair

    init {
        val keyGen = KeyPairGenerator.getInstance("EC")
        keyGen.initialize(256)
        keyPair = keyGen.generateKeyPair()
    }

    fun getPublicKey(): ByteArray = keyPair.public.encoded

    fun deriveSharedSecret(remotePublicKeyBytes: ByteArray) {
        val kf = KeyFactory.getInstance("EC")
        val remotePublicKey = kf.generatePublic(X509EncodedKeySpec(remotePublicKeyBytes))
        
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(remotePublicKey, true)
        
        // Use SHA-256 to derive a fixed-length key from the shared secret
        val secret = ka.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        sharedSecret = md.digest(secret)
    }

    fun encrypt(data: ByteArray): EncryptedPacket {
        val secretKey = SecretKeySpec(sharedSecret!!, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        val ciphertext = cipher.doFinal(data)
        
        return EncryptedPacket(iv, ciphertext)
    }

    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val secretKey = SecretKeySpec(sharedSecret!!, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertext)
    }

    fun isReady() = sharedSecret != null
}

data class EncryptedPacket(val iv: ByteArray, val ciphertext: ByteArray)
