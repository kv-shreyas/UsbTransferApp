package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import java.io.File

class SendFileUseCase(private val repo: UsbRepository) {
    operator fun invoke(file: File): Flow<Int> = repo.sendFile(file)
}
