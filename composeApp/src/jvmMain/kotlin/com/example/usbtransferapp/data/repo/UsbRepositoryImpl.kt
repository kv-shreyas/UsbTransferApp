package com.example.usbtransferapp.data.repo

import com.example.usbtransferapp.domain.repo.UsbRepository
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.data.usb.UsbConnection
import com.example.usbtransferapp.data.usb.UsbDeviceManager
import com.example.usbtransferapp.data.security.CryptoManager
import com.example.usbtransferapp.data.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class UsbRepositoryImpl(
    private val deviceManager: UsbDeviceManager,
    private val connection: UsbConnection
) : UsbRepository {

    private val cryptoManager = CryptoManager()
    
    // Commands: 0: LIST, 1: SEND, 2: FETCH
    private val CMD_LIST = 0.toByte()
    private val CMD_SEND = 1.toByte()
    private val CMD_FETCH = 2.toByte()

    override fun connect(): Boolean {
        val device = deviceManager.findAndroidDevice() ?: return false
        
        if (!connection.open(device)) {
            if (connection.switchToAoa(device)) {
                Thread.sleep(1500) // Increased delay for re-enumeration
                val accessory = deviceManager.findAndroidDevice() ?: return false
                if (!connection.open(accessory)) return false
            } else {
                return false
            }
        }
        
        return performHandshake()
    }

    private fun performHandshake(): Boolean {
        val myPublicKey = cryptoManager.getPublicKey()
        if (!connection.bulkWrite(Packet.build(Packet.TYPE_PUBLIC_KEY, myPublicKey))) return false
        
        val response = connection.bulkRead() ?: return false
        val packet = Packet.parse(response) ?: return false
        if (packet.type != Packet.TYPE_PUBLIC_KEY) return false
        
        cryptoManager.deriveSharedSecret(packet.payload)
        return true
    }

    private fun sendEncrypted(payload: ByteArray): Boolean {
        val encrypted = cryptoManager.encrypt(payload)
        val securePayload = ByteBuffer.allocate(encrypted.iv.size + encrypted.ciphertext.size)
            .put(encrypted.iv)
            .put(encrypted.ciphertext)
            .array()
        return connection.bulkWrite(Packet.build(Packet.TYPE_DATA, securePayload))
    }

    private fun receiveEncrypted(): ByteArray? {
        val raw = connection.bulkRead() ?: return null
        val packet = Packet.parse(raw) ?: return null
        if (packet.type != Packet.TYPE_DATA) return null
        val payload = packet.payload
        if (payload.size < 12) return null
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        return cryptoManager.decrypt(iv, ciphertext)
    }

    override fun receiveStream(): Flow<ByteArray> = flow {
        while (true) {
            val data = receiveEncrypted() ?: break
            if (data.isEmpty()) break
            emit(data)
        }
    }

    override fun sendFile(file: File): Flow<Int> = flow {
        val fileNameBytes = file.name.toByteArray()
        val fileSize = file.length()
        
        // Command 1 (SEND)
        val header = ByteBuffer.allocate(1 + 4 + fileNameBytes.size + 8)
            .put(CMD_SEND)
            .putInt(fileNameBytes.size)
            .put(fileNameBytes)
            .putLong(fileSize)
            .array()
        
        if (!sendEncrypted(header)) return@flow
        
        val fis = FileInputStream(file)
        val buffer = ByteArray(16 * 1024)
        var totalSent = 0L
        while (totalSent < fileSize) {
            val read = fis.read(buffer)
            if (read == -1) break
            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
            if (sendEncrypted(chunk)) {
                totalSent += read
                emit(((totalSent * 100) / fileSize).toInt())
            } else break
        }
        fis.close()
    }

    override fun fetchFile(remotePath: String, localFile: File): Flow<Int> = flow {
        val pathBytes = remotePath.toByteArray()
        
        // Command 2 (FETCH)
        val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
            .put(CMD_FETCH)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
        
        if (!sendEncrypted(header)) return@flow
        
        val sizeResponse = receiveEncrypted() ?: return@flow
        val fileSize = ByteBuffer.wrap(sizeResponse).getLong()
        if (fileSize == 0L) return@flow

        val fos = FileOutputStream(localFile)
        var totalReceived = 0L
        while (totalReceived < fileSize) {
            val chunk = receiveEncrypted() ?: break
            fos.write(chunk)
            totalReceived += chunk.size
            emit(((totalReceived * 100) / fileSize).toInt())
        }
        fos.close()
    }

    override suspend fun listDirectory(path: String): List<RemoteFile> {
        val pathBytes = path.toByteArray()
        
        // Command 0 (LIST)
        val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
            .put(CMD_LIST)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
        
        if (!sendEncrypted(header)) return emptyList()
        
        val countData = receiveEncrypted() ?: return emptyList()
        val count = ByteBuffer.wrap(countData).getInt()
        
        val result = mutableListOf<RemoteFile>()
        for (i in 0 until count) {
            val itemData = receiveEncrypted() ?: break
            val buffer = ByteBuffer.wrap(itemData)
            val isDir = buffer.get() == 1.toByte()
            val size = buffer.getLong()
            val nameLen = buffer.getInt()
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes)
            result.add(RemoteFile(name, isDir, size, if (path.endsWith("/")) "$path$name" else "$path/$name"))
        }
        return result
    }
}
