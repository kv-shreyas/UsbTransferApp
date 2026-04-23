package com.example.usbtransferapp.data.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class UsbManagerWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun getDevices(): Collection<UsbDevice> = usbManager.deviceList.values

    fun getAccessories(): Array<UsbAccessory>? = usbManager.accessoryList

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun hasPermission(accessory: UsbAccessory): Boolean {
        return usbManager.hasPermission(accessory)
    }

    fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            context,
            device.deviceId, // unique per device
            Intent(UsbPermissionReceiver.ACTION_USB_PERMISSION),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )

        usbManager.requestPermission(device, intent)
    }

    fun requestPermission(accessory: UsbAccessory) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(UsbPermissionReceiver.ACTION_USB_PERMISSION),
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )
        usbManager.requestPermission(accessory, intent)
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return usbManager.openDevice(device)
    }

    fun openAccessory(accessory: UsbAccessory): ParcelFileDescriptor? {
        return usbManager.openAccessory(accessory)
    }
}