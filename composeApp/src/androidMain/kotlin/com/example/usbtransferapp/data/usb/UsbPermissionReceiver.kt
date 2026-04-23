package com.example.usbtransferapp.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UsbPermissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_USB_PERMISSION) return

        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        val accessory = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)

        val granted = intent.getBooleanExtra(
            UsbManager.EXTRA_PERMISSION_GRANTED, false
        )

        if (!granted) return

        CoroutineScope(Dispatchers.IO).launch {
            if (device != null) {
                UsbPermissionBus.emit(UsbPermissionEvent.DeviceGranted(device))
            } else if (accessory != null) {
                UsbPermissionBus.emit(UsbPermissionEvent.AccessoryGranted(accessory))
            }
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
    }
}

