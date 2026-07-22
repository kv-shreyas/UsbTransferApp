package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import java.io.File

class FetchFileUseCase(private val repo: UsbRepository) {
    operator fun invoke(remoteFileName: String, localFile: File): Flow<Int> = repo.fetchFile(remoteFileName, localFile)
}
