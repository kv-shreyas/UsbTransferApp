package com.example.securequicktransferapp.data.usb.connection

import android.hardware.usb.UsbAccessory
import android.util.Log
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createAndroidAccessoryTransport
import com.example.secureqt.sdk.transport.android.AndroidAoaTransport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbConnectionManager @Inject constructor(
    private val wrapper: UsbManagerWrapper,
    private val usbLogger: com.example.securequicktransferapp.data.logging.UsbLogger
) : IUsbConnection {

    companion object {
        private const val TAG = "UsbConnManager"
    }

    /** Active multiplatform transport provided by secureqt-sdk */
    var transport: AndroidAoaTransport? = null
        private set

    fun connect(accessory: UsbAccessory): Boolean {
        if (isConnected()) {
            usbLogger.d(TAG, "AOA already connected to accessory: $accessory")
            return true
        }
        usbLogger.d(TAG, "Connecting to accessory: $accessory")

        val aoaTransport = SecureQtSdk.createAndroidAccessoryTransport(wrapper.usbManager, accessory)
        if (aoaTransport != null) {
            transport = aoaTransport
            usbLogger.i(TAG, "AOA Connection established via SecureQtSdk")
            return true
        }

        usbLogger.e(TAG, "Failed to open accessory via AndroidAoaTransport")
        return false
    }

    override fun send(data: ByteArray): Int {
        val t = transport ?: return -1
        return if (t.writeSync(data)) data.size else -1
    }

    override fun receive(maxSize: Int): ByteArray? {
        val chunk = minOf(AndroidAoaTransport.MAX_USBFS_BURST_SIZE, maxSize)
        val buffer = ByteArray(chunk)
        val len = receive(buffer)
        return if (len > 0) buffer.copyOf(len) else null
    }

    override fun receive(buffer: ByteArray): Int {
        val t = transport ?: return -1
        return t.readSync(buffer)
    }

    override fun receiveExact(size: Int): ByteArray? {
        val t = transport ?: return null
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val remaining = size - offset
            val len = t.readSync(buffer, offset, remaining)
            if (len <= 0) {
                Log.e(TAG, "Failed to receive exact bytes at offset $offset: len=$len")
                return null
            }
            offset += len
        }
        return buffer
    }

    override fun clearBuffer() {
        transport?.clearBuffer()
    }

    override fun disconnect() {
        try {
            transport?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        } finally {
            transport = null
        }
    }

    override fun isConnected(): Boolean {
        return transport?.isConnected() == true
    }
}
