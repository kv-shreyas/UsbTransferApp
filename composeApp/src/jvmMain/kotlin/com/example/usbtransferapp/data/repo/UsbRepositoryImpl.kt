package com.example.usbtransferapp.data.repo

import com.example.usbtransferapp.domain.repo.UsbRepository
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.data.usb.UsbConnection
import com.example.usbtransferapp.data.usb.UsbDeviceManager
import com.example.usbtransferapp.data.security.CryptoManager
import com.example.usbtransferapp.data.Packet
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        bufferHead = 0
        bufferTail = 0
        connection.clearInputBuffer()
        val handshakeSuccess = performHandshake()
        if (handshakeSuccess) {
            println("[UsbRepo] Handshake successful. Waiting for remote READY signal...")
            val readyPacket = readNextPacket()
            if (readyPacket != null && readyPacket.type == Packet.TYPE_ACK) {
                println("[UsbRepo] Remote READY signal received. Secure channel SYNCED.")
            } else {
                println("[UsbRepo] Warning: Did not receive expected READY signal, continuing anyway...")
            }
        }
        return handshakeSuccess
    }

    override fun disconnect() {
        println("[UsbRepo] Sending disconnect signal...")
        try {
            sendEncrypted(byteArrayOf(4.toByte())) // CMD_DISCONNECT
        } catch (e: Exception) {
            println("[UsbRepo] Failed to send disconnect signal: ${e.message}")
        }
        println("[UsbRepo] Disconnecting and closing connection...")
        connection.close()
    }

    override fun cancelTransfer() {
        println("[UsbRepo] Sending CANCEL signal to remote...")
        try {
            connection.bulkWrite(Packet.build(Packet.TYPE_CANCEL, ByteArray(0)))
            
            // Give Android a moment to process the cancel packet and abort its write loop,
            // then completely flush the Desktop's USB input buffer to discard any 
            // lingering file chunks that Android sent before it stopped.
            Thread.sleep(150)
            connection.clearInputBuffer()
            println("[UsbRepo] Cancel signal sent and lingering input buffer flushed.")
        } catch (e: Exception) {
            println("[UsbRepo] Failed to send CANCEL signal: ${e.message}")
        }
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
            val packet = readNextPacket() ?: run {
                println("[UsbRepo] Handshake Error: Read timeout or parse failed.")
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

    private var leftoverBuffer = ByteArray(1024 * 1024 * 5) // 5MB buffer
    private var bufferHead = 0
    private var bufferTail = 0

    private fun availableBytes() = bufferTail - bufferHead

    private fun compactBuffer() {
        if (bufferHead > 0) {
            val len = availableBytes()
            System.arraycopy(leftoverBuffer, bufferHead, leftoverBuffer, 0, len)
            bufferTail = len
            bufferHead = 0
        }
    }

    private fun readNextPacket(): Packet.PacketData? {
        while (availableBytes() < Packet.HEADER_SIZE) {
            compactBuffer()
            val raw = connection.bulkRead() ?: return null
            if (bufferTail + raw.size > leftoverBuffer.size) {
                val newBuffer = ByteArray(leftoverBuffer.size * 2)
                System.arraycopy(leftoverBuffer, bufferHead, newBuffer, 0, availableBytes())
                leftoverBuffer = newBuffer
                bufferTail = availableBytes()
                bufferHead = 0
            }
            System.arraycopy(raw, 0, leftoverBuffer, bufferTail, raw.size)
            bufferTail += raw.size
        }
        
        val bb = ByteBuffer.wrap(leftoverBuffer, bufferHead, availableBytes())
        val type = bb.get()
        val length = bb.int
        
        while (availableBytes() < Packet.HEADER_SIZE + length) {
            compactBuffer()
            val raw = connection.bulkRead() ?: return null
            if (bufferTail + raw.size > leftoverBuffer.size) {
                val newBuffer = ByteArray(leftoverBuffer.size * 2)
                System.arraycopy(leftoverBuffer, bufferHead, newBuffer, 0, availableBytes())
                leftoverBuffer = newBuffer
                bufferTail = availableBytes()
                bufferHead = 0
            }
            System.arraycopy(raw, 0, leftoverBuffer, bufferTail, raw.size)
            bufferTail += raw.size
        }
        
        val payload = ByteArray(length)
        System.arraycopy(leftoverBuffer, bufferHead + Packet.HEADER_SIZE, payload, 0, length)
        
        bufferHead += Packet.HEADER_SIZE + length
        
        return Packet.PacketData(type, length, payload)
    }

    private fun receiveEncrypted(): ByteArray? {
        val packet = readNextPacket() ?: run {
            println("[UsbRepo] Error: Failed to read incoming packet.")
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
        kotlinx.coroutines.coroutineScope {
            val rawPacketChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
            val decryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

            // Worker 1: USB Receive
            launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    while (isActive) {
                        val packet = readNextPacket() ?: break
                        if (packet.type != Packet.TYPE_DATA) break
                        rawPacketChannel.send(packet.payload)
                    }
                } finally {
                    rawPacketChannel.close()
                }
            }

            // Worker 2: Decrypt
            launch(kotlinx.coroutines.Dispatchers.Default) {
                try {
                    for (payload in rawPacketChannel) {
                        if (payload.size < 12) break
                        val iv = payload.copyOfRange(0, 12)
                        val ciphertext = payload.copyOfRange(12, payload.size)
                        val decrypted = try {
                            cryptoManager.decrypt(iv, ciphertext)
                        } catch (e: Exception) {
                            println("[UsbRepo] Error: Decryption failed - ${e.message}")
                            break
                        }
                        decryptedChannel.send(decrypted)
                    }
                } finally {
                    decryptedChannel.close()
                }
            }

            // Worker 3: Emit (Main Coroutine)
            for (chunk in decryptedChannel) {
                if (chunk.isEmpty()) break
                emit(chunk)
            }
        }
        println("[UsbRepo] Data stream ended.")
    }

    override fun sendFile(file: File, destinationPath: String, isDirectory: Boolean, remoteFileName: String): Flow<Int> = flow {
        println("[UsbRepo] --- UPLOAD START: $remoteFileName to $destinationPath ---")
        val fullRemotePath = if (destinationPath.endsWith("/")) destinationPath + remoteFileName else "$destinationPath/$remoteFileName"
        val fileNameBytes = fullRemotePath.toByteArray()
        val fileSize = file.length()
        
        val cmd = if (isDirectory) 5.toByte() else CMD_SEND
        val header = ByteBuffer.allocate(1 + 4 + fileNameBytes.size + 8)
            .put(cmd)
            .putInt(fileNameBytes.size)
            .put(fileNameBytes)
            .putLong(fileSize)
            .array()
        
        if (!sendEncrypted(header)) {
            println("[UsbRepo] Error: Failed to send file header.")
            return@flow
        }
        
        val fis = FileInputStream(file)
        var totalSent = 0L
        coroutineScope {
            fis.use { fis ->
                val chunkChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
                val encryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

                // Worker 1: Disk Read
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val buffer = ByteArray(256 * 1024)
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1 && isActive) {
                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            chunkChannel.send(chunk)
                        }
                    } finally {
                        chunkChannel.close()
                    }
                }

                // Worker 2: Encrypt
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        for (chunk in chunkChannel) {
                            val encrypted = cryptoManager.encrypt(chunk)
                            val securePayload = ByteBuffer.allocate(encrypted.iv.size + encrypted.ciphertext.size)
                                .put(encrypted.iv)
                                .put(encrypted.ciphertext)
                                .array()
                            encryptedChannel.send(securePayload)
                        }
                    } finally {
                        encryptedChannel.close()
                    }
                }

                // Worker 3: USB Write (Main Coroutine)
                for (securePayload in encryptedChannel) {
                    if (connection.bulkWrite(Packet.build(Packet.TYPE_DATA, securePayload))) {
                        val chunkSize = securePayload.size - 28 // Subtract IV (12) + Tag (16)
                        totalSent += chunkSize
                        emit(((totalSent * 100) / fileSize).toInt())
                    } else {
                        val errorMsg = "Failed to send chunk at $totalSent bytes."
                        println("[UsbRepo] Error: $errorMsg")
                        throw java.io.IOException(errorMsg)
                    }
                }
                connection.bulkWrite(Packet.build(Packet.TYPE_EOF, ByteArray(0)))
            }
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
        coroutineScope {
            try {
                val rawPacketChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
                val decryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

                // Worker 1: USB Receive
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val packet = readNextPacket() ?: break
                            if (packet.type != Packet.TYPE_DATA) break
                            rawPacketChannel.send(packet.payload)
                        }
                    } finally {
                        rawPacketChannel.close()
                    }
                }

                // Worker 2: Decrypt
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        for (payload in rawPacketChannel) {
                            if (payload.size < 12) break
                            val iv = payload.copyOfRange(0, 12)
                            val ciphertext = payload.copyOfRange(12, payload.size)
                            val decrypted = try {
                                cryptoManager.decrypt(iv, ciphertext)
                            } catch (e: Exception) {
                                println("[UsbRepo] Error: Decryption failed - ${e.message}")
                                break
                            }
                            decryptedChannel.send(decrypted)
                        }
                    } finally {
                        decryptedChannel.close()
                    }
                }

                // Worker 3: Disk Write (Main Coroutine)
                for (chunk in decryptedChannel) {
                    fos.write(chunk)
                    totalReceived += chunk.size
                    emit(((totalReceived * 100) / fileSize).toInt())
                    if (totalReceived >= fileSize) break
                }
            } finally {
                fos.close()
            }
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
        kotlinx.coroutines.coroutineScope {
            try {
                val rawPacketChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)
                val decryptedChannel = kotlinx.coroutines.channels.Channel<ByteArray>(2)

                // Worker 1: USB Receive
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val packet = readNextPacket() ?: break
                            if (packet.type != Packet.TYPE_DATA) break
                            rawPacketChannel.send(packet.payload)
                        }
                    } finally {
                        rawPacketChannel.close()
                    }
                }

                // Worker 2: Decrypt
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    try {
                        for (payload in rawPacketChannel) {
                            if (payload.size < 12) break
                            val iv = payload.copyOfRange(0, 12)
                            val ciphertext = payload.copyOfRange(12, payload.size)
                            val decrypted = try {
                                cryptoManager.decrypt(iv, ciphertext)
                            } catch (e: Exception) {
                                println("[UsbRepo] Error: Decryption failed - ${e.message}")
                                break
                            }
                            decryptedChannel.send(decrypted)
                        }
                    } finally {
                        decryptedChannel.close()
                    }
                }

                // Worker 3: Disk Write (Main Coroutine)
                for (chunk in decryptedChannel) {
                    fos.write(chunk)
                    totalReceived += chunk.size
                    emit(((totalReceived * 100) / fileSize).toInt())
                    if (totalReceived >= fileSize) break
                }
            } finally {
                fos.close()
            }
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
            val err = "Failed to send list request."
            println("[UsbRepo] Error: $err")
            throw java.io.IOException(err)
        }
        
        val countData = receiveEncrypted() ?: run {
            val err = "Failed to receive directory count."
            println("[UsbRepo] Error: $err")
            throw java.io.IOException(err)
        }
        val count = ByteBuffer.wrap(countData).getInt()
        
        val result = mutableListOf<RemoteFile>()
        println("[UsbRepo] Discovered $count items.")
        for (i in 0 until count) {
            val itemData = receiveEncrypted() ?: throw java.io.IOException("Connection lost while reading directory items")
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
