package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import java.io.File

class FetchDirectoryUseCase(private val repository: UsbRepository) {
    operator fun invoke(remotePath: String, localFile: File): Flow<Int> {
        return repository.fetchDirectory(remotePath, localFile)
    }
}
