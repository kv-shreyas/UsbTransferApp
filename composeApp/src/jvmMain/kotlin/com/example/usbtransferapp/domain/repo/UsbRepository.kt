package com.example.usbtransferapp.domain.repo

import com.example.usbtransferapp.domain.model.RemoteFile
import kotlinx.coroutines.flow.Flow
import java.io.File

interface UsbRepository {
    fun connect(): Boolean
    fun receiveStream(): Flow<ByteArray>
    fun sendFile(file: File): Flow<Int>
    fun fetchFile(remotePath: String, localFile: File): Flow<Int>
    suspend fun listDirectory(path: String): List<RemoteFile>
}
