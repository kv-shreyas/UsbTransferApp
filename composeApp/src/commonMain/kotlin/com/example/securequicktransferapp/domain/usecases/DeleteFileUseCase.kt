package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository

class DeleteFileUseCase(private val repository: UsbRepository) {
    suspend operator fun invoke(remotePath: String): Boolean {
        return repository.deleteFile(remotePath)
    }
}
