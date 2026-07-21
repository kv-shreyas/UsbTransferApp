package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository
class CreateFolderUseCase(
    private val repository: UsbRepository
) {
    suspend operator fun invoke(remotePath: String): Boolean {
        return repository.createFolder(remotePath)
    }
}
