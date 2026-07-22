package com.example.securequicktransferapp.data.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.securequicktransferapp.data.preferences.UsbPreferencesManager
import com.example.securequicktransferapp.data.UsbRole
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class UsbAttachmentReceiver : BroadcastReceiver() {

    @Inject
    lateinit var controller: ClientServiceController

    @Inject
    lateinit var preferencesManager: UsbPreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("UsbAttachmentReceiver", "onReceive: action=$action")

        when (action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                val accessory = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                Log.i("UsbAttachmentReceiver", "USB Accessory attached (${accessory?.model}). Auto-starting UsbClientForegroundService...")

                preferencesManager.usbRole = UsbRole.Client(
                    connectedHostName = accessory?.model ?: "USB Host"
                )

                val serviceIntent = Intent(context, UsbClientForegroundService::class.java).apply {
                    this.action = ClientServiceController.ACTION_START_CLIENT
                    if (accessory != null) {
                        putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
                    }
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e("UsbAttachmentReceiver", "Failed to start foreground service on attach", e)
                }
            }
            UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                Log.i("UsbAttachmentReceiver", "USB Accessory detached. Stopping UsbClientForegroundService...")
                controller.stopService()
            }
        }
    }
}
