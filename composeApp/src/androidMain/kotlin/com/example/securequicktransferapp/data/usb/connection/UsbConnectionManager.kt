package com.example.securequicktransferapp.data.usb.connection

import android.hardware.usb.UsbAccessory
import android.util.Log
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createAndroidAccessoryTransport
import com.example.secureqt.sdk.transport.IUsbTransport
import com.example.secureqt.sdk.transport.android.AndroidAoaTransport
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android USB Connection Manager: Manages physical USB connection lifecycle
 * and implements [IUsbTransport] by delegating to [AndroidAoaTransport].
 */
@Singleton
class UsbConnectionManager @Inject constructor(
    private val wrapper: UsbManagerWrapper,
    private val usbLogger: com.example.securequicktransferapp.data.logging.UsbLogger
) : IUsbTransport {

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

    override suspend fun read(buffer: ByteArray): Int {
        return transport?.read(buffer) ?: -1
    }

    override suspend fun write(buffer: ByteArray): Boolean {
        return transport?.write(buffer) ?: false
    }

    override fun isConnected(): Boolean {
        return transport?.isConnected() == true
    }

    override fun clearBuffer() {
        transport?.clearBuffer()
    }

    fun disconnect() {
        try {
            transport?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing connection", e)
        } finally {
            transport = null
        }
    }
}
