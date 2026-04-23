package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository
import com.example.usbtransferapp.domain.model.RemoteFile

class ListDirectoryUseCase(private val repo: UsbRepository) {
    suspend operator fun invoke(path: String): List<RemoteFile> = repo.listDirectory(path)
}
