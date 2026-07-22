package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import java.io.File

class SendFileUseCase(private val repo: UsbRepository) {
    operator fun invoke(file: File, destinationPath: String, isDirectory: Boolean = false, remoteFileName: String = file.name): Flow<Int> {
        return repo.sendFile(file, destinationPath, isDirectory, remoteFileName)
    }
}
