package com.example.securequicktransferapp.domain.usecases

import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.domain.model.RemoteFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileOutputStream

class UsbUseCases(private val repo: UsbRepository) {

    fun connectUsb(): Boolean = repo.connect()

    fun disconnectUsb() = repo.disconnect()

    fun sendFile(
        file: File, 
        destinationPath: String, 
        isDirectory: Boolean = false, 
        remoteFileName: String = file.name
    ): Flow<Int> {
        return repo.sendFile(file, destinationPath, isDirectory, remoteFileName)
    }

    fun fetchFile(remoteFileName: String, localFile: File): Flow<Int> {
        return repo.fetchFile(remoteFileName, localFile)
    }

    fun fetchDirectory(remotePath: String, localFile: File): Flow<Int> {
        return repo.fetchDirectory(remotePath, localFile)
    }

    suspend fun listDirectory(path: String): List<RemoteFile> {
        return repo.listDirectory(path)
    }

    fun cancelTransfer() = repo.cancelTransfer()

    suspend fun createFolder(remotePath: String): Boolean {
        return repo.createFolder(remotePath)
    }

    suspend fun deleteFile(remotePath: String): Boolean {
        return repo.deleteFile(remotePath)
    }

    suspend fun renameFile(remotePath: String, newName: String): Boolean {
        return repo.renameFile(remotePath, newName)
    }

    fun receiveFile(output: File): Flow<Long> = flow {
        val fos = FileOutputStream(output)
        var total = 0L
        try {
            repo.receiveStream().collect { chunk ->
                fos.write(chunk)
                total += chunk.size
                emit(total)
            }
        } finally {
            fos.close()
        }
    }
}
