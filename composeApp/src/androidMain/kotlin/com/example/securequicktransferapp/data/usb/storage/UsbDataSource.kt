package com.example.securequicktransferapp.data.usb.storage

import com.example.securequicktransferapp.data.usb.connection.IUsbConnection

import android.util.Log
import com.example.secureqt.sdk.SecureQtSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UsbDataSource"

@Singleton
class UsbDataSource @Inject constructor(
    private val manager: IUsbConnection
) {

    private val session = SecureQtSdk.createSession()
    private val rxBuffer = SecureQtSdk.createBuffer()

    private val sendMutex = Mutex()
    private val pendingRawPackets = ArrayDeque<Pair<Byte, ByteArray>>()

    fun pushBackRawPacket(packet: Pair<Byte, ByteArray>) {
        synchronized(pendingRawPackets) {
            pendingRawPackets.addFirst(packet)
        }
    }

    private val readBuffer = ByteArray(256 * 1024)

    private suspend fun receivePacket(): Pair<Byte, ByteArray>? = withContext(Dispatchers.IO) {
        while (!rxBuffer.has(SecureQtSdk.Codec.HEADER_SIZE)) {
            val bytesRead = manager.receive(readBuffer)
            if (bytesRead <= 0) {
                if (!manager.isConnected() || !currentCoroutineContext().isActive) {
                    Log.e(TAG, "receivePacket: USB stream disconnected or coroutine cancelled")
                    return@withContext null
                }
                if (bytesRead == 0) kotlinx.coroutines.yield() else delay(5)
                continue
            }
            rxBuffer.append(readBuffer, 0, bytesRead)
        }

        val header = rxBuffer.take(SecureQtSdk.Codec.HEADER_SIZE)
        val type = SecureQtSdk.Codec.decodeType(header)
        val length = SecureQtSdk.Codec.decodePayloadLength(header)

        if (length < 0 || length > 1024 * 1024 * 10) { // 10MB limit
            Log.e(TAG, "receivePacket: Invalid packet length: $length")
            return@withContext null
        }

        while (!rxBuffer.has(length)) {
            val bytesRead = manager.receive(readBuffer)
            if (bytesRead <= 0) {
                if (!manager.isConnected() || !currentCoroutineContext().isActive) {
                    Log.e(TAG, "receivePacket: USB stream disconnected or coroutine cancelled during payload read")
                    return@withContext null
                }
                if (bytesRead == 0) kotlinx.coroutines.yield() else delay(5)
                continue
            }
            rxBuffer.append(readBuffer, 0, bytesRead)
        }

        val payload = rxBuffer.take(length)
        Pair(type, payload)
    }

    suspend fun performHandshake(): Boolean = withContext(Dispatchers.IO) {
        synchronized(pendingRawPackets) {
            pendingRawPackets.clear()
        }
        rxBuffer.clear()
        try {
            manager.clearBuffer()
            Log.d(TAG, "Handshake: Flushed stale hardware USB bulk FIFO")
        } catch (_: Exception) {}
        Log.i(TAG, "Handshake: STARTING")
        session.reset()

        // 1. Wait for Desktop Public Key
        Log.d(TAG, "Handshake: Step 1 - Waiting for Desktop Public Key...")
        var packet: Pair<Byte, ByteArray>? = null
        for (i in 0 until 240) {
            packet = receivePacket()
            if (packet == null) {
                delay(250)
                continue
            }
            if (packet.first == SecureQtSdk.Packets.TYPE_PUBLIC_KEY) {
                break
            }
            Log.w(TAG, "Handshake: Ignoring stale packet type: ${packet.first}")
        }
        
        if (packet?.first != SecureQtSdk.Packets.TYPE_PUBLIC_KEY) {
            Log.e(TAG, "Handshake: Expected TYPE_PUBLIC_KEY (0x01), got ${packet?.first}")
            return@withContext false
        }
        
        Log.d(TAG, "Handshake: Desktop Public Key received (${packet.second.size} bytes)")
        
        // 2. Send our Public Key
        Log.d(TAG, "Handshake: Step 2 - Sending Android Public Key...")
        val pubBytes = session.getPublicKey()
        val sendResult = manager.send(SecureQtSdk.Codec.encode(SecureQtSdk.Packets.TYPE_PUBLIC_KEY, pubBytes))
        if (sendResult <= 0) {
            Log.e(TAG, "Handshake: Failed to send Android Public Key")
            return@withContext false
        }

        // 3. Derive Secret
        Log.d(TAG, "Handshake: Step 3 - Deriving shared secret...")
        try {
            session.deriveSharedSecret(packet.second)
        } catch (e: Exception) {
            Log.e(TAG, "Handshake: Failed to derive secret", e)
            return@withContext false
        }
        
        Log.i(TAG, "Handshake: SUCCESS. Secure channel established.")
        true
    }

    suspend fun performHandshakeAsInitiator(): Boolean = withContext(Dispatchers.IO) {
        synchronized(pendingRawPackets) {
            pendingRawPackets.clear()
        }
        rxBuffer.clear()
        try {
            manager.clearBuffer()
            Log.d(TAG, "HandshakeAsInitiator: Flushed stale hardware USB bulk FIFO")
        } catch (_: Exception) {}
        Log.i(TAG, "HandshakeAsInitiator: STARTING (Host Role)")
        session.reset()

        // 1. Send our Public Key FIRST
        Log.d(TAG, "HandshakeAsInitiator: Step 1 - Sending Initiator Public Key...")
        val pubBytes = session.getPublicKey()
        val sendResult = manager.send(SecureQtSdk.Codec.encode(SecureQtSdk.Packets.TYPE_PUBLIC_KEY, pubBytes))
        if (sendResult <= 0) {
            Log.e(TAG, "HandshakeAsInitiator: Failed to send Initiator Public Key")
            return@withContext false
        }

        // 2. Wait for Remote Responder Public Key
        Log.d(TAG, "HandshakeAsInitiator: Step 2 - Waiting for Responder Public Key...")
        var packet: Pair<Byte, ByteArray>? = null
        for (i in 0 until 240) {
            packet = receivePacket()
            if (packet == null) {
                delay(250)
                continue
            }
            if (packet.first == SecureQtSdk.Packets.TYPE_PUBLIC_KEY) {
                break
            }
            Log.w(TAG, "HandshakeAsInitiator: Ignoring stale packet type: ${packet.first}")
        }

        if (packet?.first != SecureQtSdk.Packets.TYPE_PUBLIC_KEY) {
            Log.e(TAG, "HandshakeAsInitiator: Expected TYPE_PUBLIC_KEY (0x01), got ${packet?.first}")
            return@withContext false
        }

        Log.d(TAG, "HandshakeAsInitiator: Responder Public Key received (${packet.second.size} bytes)")

        // 3. Derive Secret
        Log.d(TAG, "HandshakeAsInitiator: Step 3 - Deriving shared secret...")
        try {
            session.deriveSharedSecret(packet.second)
        } catch (e: Exception) {
            Log.e(TAG, "HandshakeAsInitiator: Failed to derive secret", e)
            return@withContext false
        }

        Log.i(TAG, "HandshakeAsInitiator: SUCCESS. Secure channel established as Initiator.")
        true
    }

    suspend fun encryptData(data: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (!session.isReady()) return@withContext null
        val encrypted = session.encrypt(data)
        java.nio.ByteBuffer.allocate(encrypted.iv.size + encrypted.ciphertext.size)
            .put(encrypted.iv).put(encrypted.ciphertext).array()
    }

    suspend fun encryptAndWrapData(data: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (!session.isReady()) return@withContext null
        val encrypted = session.encrypt(data)
        SecureQtSdk.Codec.encodeDataPacket(encrypted.iv, encrypted.ciphertext)
    }

    suspend fun sendRawPacket(type: Byte, payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            manager.send(SecureQtSdk.Codec.encode(type, payload)) > 0
        }
    }

    suspend fun sendPrebuiltPacket(packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            manager.send(packet) > 0
        }
    }

    suspend fun receiveRawPacket(): Pair<Byte, ByteArray>? = withContext(Dispatchers.IO) {
        synchronized(pendingRawPackets) {
            if (pendingRawPackets.isNotEmpty()) {
                return@withContext pendingRawPackets.removeFirst()
            }
        }
        receivePacket()
    }

    suspend fun decryptData(payload: ByteArray): ByteArray? = withContext(Dispatchers.Default) {
        if (!session.isReady()) return@withContext null
        if (payload.size < 12) return@withContext null
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        try {
            session.decrypt(iv, ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            null
        }
    }

    suspend fun sendSecure(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val payload = encryptData(data) ?: return@withContext false
        sendRawPacket(SecureQtSdk.Packets.TYPE_DATA, payload)
    }

    class TransferCancelledException : Exception("Transfer cancelled by remote")

    suspend fun receiveSecure(): ByteArray? = withContext(Dispatchers.IO) {
        val packet = receiveRawPacket() ?: return@withContext null
        
        if (packet.first == SecureQtSdk.Commands.CMD_CANCEL_TRANSFER) {
            throw TransferCancelledException()
        }
        
        // Use TYPE_EOF for PING compatibility from older logic if needed
        if (packet.first == SecureQtSdk.Packets.TYPE_EOF) {
            return@withContext receiveSecure()
        }
        
        if (packet.first != SecureQtSdk.Packets.TYPE_DATA) {
            Log.w(TAG, "receiveSecure: Received non-DATA packet type (${packet.first}) during secure stream. Ignoring and waiting for next packet...")
            return@withContext receiveSecure()
        }

        decryptData(packet.second)
    }

    fun disconnect() {
        session.reset()
        synchronized(pendingRawPackets) {
            pendingRawPackets.clear()
        }
        rxBuffer.clear()
        manager.disconnect()
    }
}
