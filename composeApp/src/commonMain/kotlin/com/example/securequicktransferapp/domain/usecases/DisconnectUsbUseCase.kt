package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository

class DisconnectUsbUseCase(private val repository: UsbRepository) {
    operator fun invoke() {
        repository.disconnect()
    }
}
