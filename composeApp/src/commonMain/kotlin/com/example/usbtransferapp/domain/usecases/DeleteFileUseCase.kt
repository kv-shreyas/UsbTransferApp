package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository

class DeleteFileUseCase(private val repository: UsbRepository) {
    suspend operator fun invoke(remotePath: String): Boolean {
        return repository.deleteFile(remotePath)
    }
}
