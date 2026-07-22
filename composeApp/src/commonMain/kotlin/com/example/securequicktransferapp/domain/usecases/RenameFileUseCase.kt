package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository

class RenameFileUseCase(private val repository: UsbRepository) {
    suspend operator fun invoke(remotePath: String, newName: String): Boolean {
        return repository.renameFile(remotePath, newName)
    }
}
