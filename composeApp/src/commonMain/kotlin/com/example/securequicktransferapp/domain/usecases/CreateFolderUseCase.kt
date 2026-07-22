package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
class CreateFolderUseCase(
    private val repository: UsbRepository
) {
    suspend operator fun invoke(remotePath: String): Boolean {
        return repository.createFolder(remotePath)
    }
}
