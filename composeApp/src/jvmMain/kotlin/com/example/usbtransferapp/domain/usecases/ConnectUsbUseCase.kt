package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository

class ConnectUsbUseCase(private val repo: UsbRepository) {
    operator fun invoke(): Boolean = repo.connect()
}
