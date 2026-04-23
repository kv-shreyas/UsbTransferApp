package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream

class ReceiveFileUseCase(
    private val repo: UsbRepository
) {

    operator fun invoke(output: File): Flow<Long> = flow {

        val fos = FileOutputStream(output)

        var total = 0L

        repo.receiveStream().collect { chunk ->
            fos.write(chunk)
            total += chunk.size
            emit(total)
        }

        fos.close()
    }
}
