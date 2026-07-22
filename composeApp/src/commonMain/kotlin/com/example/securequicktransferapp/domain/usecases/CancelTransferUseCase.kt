package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository

class CancelTransferUseCase(private val repo: UsbRepository) {
    operator fun invoke() = repo.cancelTransfer()
}
