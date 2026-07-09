package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository

class RenameFileUseCase(private val repository: UsbRepository) {
    suspend operator fun invoke(remotePath: String, newName: String): Boolean {
        return repository.renameFile(remotePath, newName)
    }
}
