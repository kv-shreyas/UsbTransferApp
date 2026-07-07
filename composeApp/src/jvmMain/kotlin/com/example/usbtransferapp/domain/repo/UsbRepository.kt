package com.example.usbtransferapp.domain.repo

import com.example.usbtransferapp.domain.model.RemoteFile
import kotlinx.coroutines.flow.Flow
import java.io.File

interface UsbRepository {
    fun connect(): Boolean
    fun disconnect()
    fun receiveStream(): Flow<ByteArray>
    fun sendFile(file: File, destinationPath: String, isDirectory: Boolean = false, remoteFileName: String = file.name): Flow<Int>
    fun fetchFile(remotePath: String, localFile: File): Flow<Int>
    fun fetchDirectory(remotePath: String, localFile: File): Flow<Int>
    suspend fun listDirectory(path: String): List<RemoteFile>
    fun cancelTransfer()
}
