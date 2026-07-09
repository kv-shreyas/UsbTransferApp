package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository

class DisconnectUsbUseCase(private val repository: UsbRepository) {
    operator fun invoke() {
        repository.disconnect()
    }
}
