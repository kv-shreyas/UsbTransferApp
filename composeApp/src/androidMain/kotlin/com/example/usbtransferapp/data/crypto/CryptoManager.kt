package com.example.usbtransferapp.data.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class CryptoManager @Inject constructor() {

    private val AES_MODE = "AES/GCM/NoPadding"

    fun generateAESKey(sharedSecret: ByteArray): SecretKey {
        val hash = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return SecretKeySpec(hash, 0, 32, "AES")
    }

    fun encrypt(data: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_MODE)
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }

        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(data)

        return Pair(iv, encrypted)
    }

    fun decrypt(iv: ByteArray, encrypted: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }
}
