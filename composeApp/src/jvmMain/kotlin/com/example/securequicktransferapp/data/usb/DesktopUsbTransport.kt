package com.example.securequicktransferapp.data.usb

import com.example.secureqt.sdk.transport.IUsbTransport

class DesktopUsbTransport(private val connection: UsbConnection) : IUsbTransport {
    override suspend fun read(buffer: ByteArray): Int {
        val data = connection.bulkRead() ?: return -1
        val len = minOf(data.size, buffer.size)
        System.arraycopy(data, 0, buffer, 0, len)
        return len
    }

    override suspend fun write(buffer: ByteArray): Boolean {
        return connection.bulkWrite(buffer)
    }

    override fun isConnected(): Boolean {
        return connection.isOpen()
    }

    override fun clearBuffer() {
        connection.clearInputBuffer()
    }
}
