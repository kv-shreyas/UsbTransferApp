package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository

class ConnectUsbUseCase(private val repo: UsbRepository) {
    operator fun invoke(): Boolean = repo.connect()
}
