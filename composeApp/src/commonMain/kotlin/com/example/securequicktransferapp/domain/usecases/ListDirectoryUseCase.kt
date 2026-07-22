package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.domain.model.RemoteFile

class ListDirectoryUseCase(private val repo: UsbRepository) {
    suspend operator fun invoke(path: String): List<RemoteFile> = repo.listDirectory(path)
}
