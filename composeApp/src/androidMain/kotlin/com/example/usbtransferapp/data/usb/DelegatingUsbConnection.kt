package com.example.usbtransferapp.data.usb

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DelegatingUsbConnection @Inject constructor() : IUsbConnection {
    private var delegate: IUsbConnection? = null

    fun setDelegate(newDelegate: IUsbConnection) {
        delegate = newDelegate
    }

    override fun send(data: ByteArray): Int {
        return delegate?.send(data) ?: -1
    }

    override fun receive(maxSize: Int): ByteArray? {
        return delegate?.receive(maxSize)
    }

    override fun receive(buffer: ByteArray): Int {
        return delegate?.receive(buffer) ?: -1
    }

    override fun receiveExact(size: Int): ByteArray? {
        return delegate?.receiveExact(size)
    }

    override fun clearBuffer() {
        delegate?.clearBuffer()
    }

    override fun disconnect() {
        delegate?.disconnect()
        delegate = null
    }
}
