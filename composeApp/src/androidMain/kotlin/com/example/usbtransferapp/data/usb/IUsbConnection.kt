package com.example.usbtransferapp.data.usb

interface IUsbConnection {
    fun send(data: ByteArray): Int
    fun receive(maxSize: Int = 4096): ByteArray?
    fun receiveExact(size: Int): ByteArray?
}
