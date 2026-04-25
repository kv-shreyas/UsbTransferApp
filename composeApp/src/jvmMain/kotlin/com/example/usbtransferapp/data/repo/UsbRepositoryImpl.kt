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
    
    // Commands: 0: LIST, 1: SEND, 2: FETCH, 3: FETCH_DIR
    private val CMD_LIST = 0.toByte()
    private val CMD_SEND = 1.toByte()
    private val CMD_FETCH = 2.toByte()
    private val CMD_FETCH_DIR = 3.toByte()

    override fun connect(): Boolean {
        println("[UsbRepo] Attempting to find Android device...")
        val device = deviceManager.findAndroidDevice() ?: run {
            println("[UsbRepo] Error: No device found on USB bus.")
            return false
        }
        
        try {
            println("[UsbRepo] Attempting to open device: $device")
            if (!connection.open(device)) {
                println("[UsbRepo] Initial open failed, attempting switch to AOA mode...")
                if (connection.switchToAoa(device)) {
                    println("[UsbRepo] AOA switch triggered. Waiting for re-enumeration...")
                    
                    var accessory: org.usb4java.Device? = null
                    for (i in 0 until 10) {
                        Thread.sleep(1000) // 1s wait is safer for re-enumeration
                        accessory = deviceManager.findAndroidDevice(requireAccessory = true)
                        if (accessory != null) {
                            println("[UsbRepo] Accessory found after ${ (i+1) * 1000 }ms")
                            break
                        }
                    }
                    if (accessory == null) {
                        println("[UsbRepo] Error: Accessory not found after timeout.")
                        return false
                    }

                    try {
                        Thread.sleep(500) // Extra time for OS to settle
                        if (!connection.open(accessory)) {
                            println("[UsbRepo] Error: Failed to open device in accessory mode.")
                            return false
                        }
                    } finally {
                        deviceManager.releaseDevice(accessory)
                    }
                } else {
                    println("[UsbRepo] Error: AOA switch rejected by device.")
                    return false
                }
            }
        } finally {
            deviceManager.releaseDevice(device)
        }
        
        println("[UsbRepo] USB Pipe established. Performing handshake...")
        val handshakeSuccess = performHandshake()
        if (handshakeSuccess) {
            println("[UsbRepo] Handshake successful. Waiting for remote READY signal...")
            connection.clearInputBuffer()
            val readyPacket = receiveEncrypted()
            if (readyPacket != null && readyPacket.isNotEmpty() && readyPacket[0] == Packet.TYPE_ACK) {
                println("[UsbRepo] Remote READY signal received. Secure channel SYNCED.")
            } else {
                println("[UsbRepo] Warning: Did not receive expected READY signal, continuing anyway...")
            }
        }
        return handshakeSuccess
    }

    override fun disconnect() {
        println("[UsbRepo] Disconnecting and closing connection...")
        connection.close()
    }

    private fun performHandshake(): Boolean {
        return try {
            val myPublicKey = cryptoManager.getPublicKey()
            println("[UsbRepo] Handshake Step 1: Sending local public key (${myPublicKey.size} bytes)...")
            if (!connection.bulkWrite(Packet.build(Packet.TYPE_PUBLIC_KEY, myPublicKey))) {
                println("[UsbRepo] Handshake Error: Write failed.")
                return false
            }
            
            println("[UsbRepo] Handshake Step 2: Waiting for remote public key...")
            val response = connection.bulkRead() ?: run {
                println("[UsbRepo] Handshake Error: Read timeout.")
                return false
            }
            
            val packet = Packet.parse(response) ?: run {
                println("[UsbRepo] Handshake Error: Failed to parse packet (Size: ${response.size} bytes).")
                return false
            }
            
            if (packet.type != Packet.TYPE_PUBLIC_KEY) {
                println("[UsbRepo] Handshake Error: Expected TYPE_PUBLIC_KEY, got ${packet.type}")
                return false
            }
            
            cryptoManager.deriveSharedSecret(packet.payload)
            println("[UsbRepo] Handshake Step 3: Shared secret derived. Secure channel READY.")
            true
        } catch (e: Exception) {
            println("[UsbRepo] Handshake Exception: ${e.message}")
            false
        }
    }

    private fun sendEncrypted(payload: ByteArray): Boolean {
        val encrypted = cryptoManager.encrypt(payload)
        val securePayload = ByteBuffer.allocate(encrypted.iv.size + encrypted.ciphertext.size)
            .put(encrypted.iv)
            .put(encrypted.ciphertext)
            .array()
        
        val success = connection.bulkWrite(Packet.build(Packet.TYPE_DATA, securePayload))
        if (!success) println("[UsbRepo] Error: Failed to write encrypted packet (Size: ${payload.size} bytes)")
        return success
    }

    private fun receiveEncrypted(): ByteArray? {
        val raw = connection.bulkRead() ?: run {
            return null
        }
        val packet = Packet.parse(raw) ?: run {
            println("[UsbRepo] Error: Failed to parse incoming packet.")
            return null
        }
        if (packet.type != Packet.TYPE_DATA) {
            println("[UsbRepo] Warning: Received non-data packet during transfer: ${packet.type}")
            return null
        }
        val payload = packet.payload
        if (payload.size < 12) {
            println("[UsbRepo] Error: Secure payload too small for IV.")
            return null
        }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        return try {
            cryptoManager.decrypt(iv, ciphertext)
        } catch (e: Exception) {
            println("[UsbRepo] Error: Decryption failed - ${e.message}")
            null
        }
    }

    override fun receiveStream(): Flow<ByteArray> = flow {
        println("[UsbRepo] Starting data stream reception...")
        while (true) {
            val data = receiveEncrypted() ?: break
            if (data.isEmpty()) break
            emit(data)
        }
        println("[UsbRepo] Data stream ended.")
    }

    override fun sendFile(file: File): Flow<Int> = flow {
        println("[UsbRepo] --- UPLOAD START: ${file.name} ---")
        val fileNameBytes = file.name.toByteArray()
        val fileSize = file.length()
        
        val header = ByteBuffer.allocate(1 + 4 + fileNameBytes.size + 8)
            .put(CMD_SEND)
            .putInt(fileNameBytes.size)
            .put(fileNameBytes)
            .putLong(fileSize)
            .array()
        
        if (!sendEncrypted(header)) {
            println("[UsbRepo] Error: Failed to send file header.")
            return@flow
        }
        
        val fis = FileInputStream(file)
        val buffer = ByteArray(16 * 1024)
        var totalSent = 0L
        try {
            while (totalSent < fileSize) {
                val read = fis.read(buffer)
                if (read == -1) break
                val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                if (sendEncrypted(chunk)) {
                    totalSent += read
                    emit(((totalSent * 100) / fileSize).toInt())
                } else {
                    println("[UsbRepo] Error: Failed to send chunk at $totalSent bytes.")
                    break
                }
            }
        } finally {
            fis.close()
        }
        println("[UsbRepo] --- UPLOAD COMPLETE: $totalSent bytes sent ---")
    }

    override fun fetchFile(remotePath: String, localFile: File): Flow<Int> = flow {
        println("[UsbRepo] --- DOWNLOAD START: $remotePath ---")
        val pathBytes = remotePath.toByteArray()
        
        val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
            .put(CMD_FETCH)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
        
        if (!sendEncrypted(header)) {
            println("[UsbRepo] Error: Failed to send fetch request.")
            return@flow
        }
        
        val sizeResponse = receiveEncrypted() ?: run {
            println("[UsbRepo] Error: Failed to receive file size response.")
            return@flow
        }
        val fileSize = ByteBuffer.wrap(sizeResponse).getLong()
        if (fileSize == 0L) {
            println("[UsbRepo] Warning: Remote file not found or empty.")
            return@flow
        }
        println("[UsbRepo] Downloading $fileSize bytes to ${localFile.name}")

        val fos = FileOutputStream(localFile)
        var totalReceived = 0L
        try {
            while (totalReceived < fileSize) {
                val chunk = receiveEncrypted() ?: run {
                    println("[UsbRepo] Error: Connection lost during download.")
                    break
                }
                fos.write(chunk)
                totalReceived += chunk.size
                emit(((totalReceived * 100) / fileSize).toInt())
            }
        } finally {
            fos.close()
        }
        println("[UsbRepo] --- DOWNLOAD COMPLETE: Saved to ${localFile.absolutePath} ---")
    }

    override fun fetchDirectory(remotePath: String, localFile: File): Flow<Int> = flow {
        println("[UsbRepo] --- DIR DOWNLOAD START: $remotePath ---")
        val pathBytes = remotePath.toByteArray()
        
        val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
            .put(CMD_FETCH_DIR)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
        
        if (!sendEncrypted(header)) {
            println("[UsbRepo] Error: Failed to send fetch dir request.")
            return@flow
        }
        
        val sizeResponse = receiveEncrypted() ?: run {
            println("[UsbRepo] Error: Failed to receive zip size response.")
            return@flow
        }
        val fileSize = ByteBuffer.wrap(sizeResponse).getLong()
        if (fileSize == 0L) {
            println("[UsbRepo] Warning: Remote directory not found or empty.")
            return@flow
        }
        println("[UsbRepo] Downloading ZIP of $fileSize bytes to ${localFile.name}")

        val fos = FileOutputStream(localFile)
        var totalReceived = 0L
        try {
            while (totalReceived < fileSize) {
                val chunk = receiveEncrypted() ?: run {
                    println("[UsbRepo] Error: Connection lost during dir download.")
                    break
                }
                fos.write(chunk)
                totalReceived += chunk.size
                emit(((totalReceived * 100) / fileSize).toInt())
            }
        } finally {
            fos.close()
        }
        println("[UsbRepo] --- DIR DOWNLOAD COMPLETE: Saved to ${localFile.absolutePath} ---")
    }

    override suspend fun listDirectory(path: String): List<RemoteFile> {
        println("[UsbRepo] Listing Directory: $path")
        val pathBytes = path.toByteArray()
        
        val header = ByteBuffer.allocate(1 + 4 + pathBytes.size)
            .put(CMD_LIST)
            .putInt(pathBytes.size)
            .put(pathBytes)
            .array()
        
        if (!sendEncrypted(header)) {
            println("[UsbRepo] Error: Failed to send list request.")
            return emptyList()
        }
        
        val countData = receiveEncrypted() ?: run {
            println("[UsbRepo] Error: Failed to receive directory count.")
            return emptyList()
        }
        val count = ByteBuffer.wrap(countData).getInt()
        
        val result = mutableListOf<RemoteFile>()
        println("[UsbRepo] Discovered $count items.")
        for (i in 0 until count) {
            val itemData = receiveEncrypted() ?: break
            val buffer = ByteBuffer.wrap(itemData)
            val isDir = buffer.get() == 1.toByte()
            val size = buffer.getLong()
            val nameLen = buffer.getInt()
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val name = String(nameBytes)
            println("[UsbRepo] [$i] ${if (isDir) "[DIR] " else "[FILE]"} $name ($size bytes)")
            result.add(RemoteFile(name, isDir, size, if (path.endsWith("/")) "$path$name" else "$path/$name"))
        }
        return result
    }
}
