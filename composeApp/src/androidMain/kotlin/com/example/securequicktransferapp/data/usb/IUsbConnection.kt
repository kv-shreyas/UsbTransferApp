package com.example.securequicktransferapp.data.usb

interface IUsbConnection {
    fun send(data: ByteArray): Int
    fun receive(maxSize: Int = 4096): ByteArray?
    fun receive(buffer: ByteArray): Int
    fun receiveExact(size: Int): ByteArray?
    fun clearBuffer()
    fun disconnect()
    fun isConnected(): Boolean = true
}

