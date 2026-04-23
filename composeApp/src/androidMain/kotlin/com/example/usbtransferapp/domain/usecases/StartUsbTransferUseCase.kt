package com.example.usbtransferapp.domain.usecases

import com.example.usbtransferapp.data.usb.UsbDataSource
import javax.inject.Inject

class StartUsbTransferUseCase @Inject constructor(
    private val usb: UsbDataSource
) {

    suspend fun execute(message: String): Boolean {
        if (!usb.performHandshake()) return false
        return usb.sendSecure(message.toByteArray())
    }
}