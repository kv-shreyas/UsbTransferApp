package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository

class CancelTransferUseCase(private val repo: UsbRepository) {
    operator fun invoke() = repo.cancelTransfer()
}
