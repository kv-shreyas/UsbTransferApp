package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.data.crypto.CryptoManager
import javax.crypto.SecretKey
import javax.inject.Inject

class DecryptDataUseCase @Inject constructor(
    private val crypto: CryptoManager
) {
    operator fun invoke(iv: ByteArray, data: ByteArray, key: SecretKey)
        = crypto.decrypt(iv, data, key)
}