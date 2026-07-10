package com.example.usbtransferapp.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

class UsbManagerWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbLogger: com.example.usbtransferapp.data.logging.UsbLogger
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
        usbLogger.i("UsbManagerWrapper", "Requesting permission for device ${device.productName} (${device.deviceId})")
        val intent = Intent(context, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION_USB_PERMISSION
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            device.deviceId,
            intent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
    }

    fun requestPermission(accessory: UsbAccessory) {
        usbLogger.i("UsbManagerWrapper", "Requesting permission for accessory ${accessory.model}")
        val intent = Intent(context, UsbPermissionReceiver::class.java).apply {
            action = UsbPermissionReceiver.ACTION_USB_PERMISSION
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )
        usbManager.requestPermission(accessory, pendingIntent)
    }

    fun openDevice(device: UsbDevice): UsbDeviceConnection? {
        return usbManager.openDevice(device)
    }

    fun openAccessory(accessory: UsbAccessory): ParcelFileDescriptor? {
        return usbManager.openAccessory(accessory)
    }

    fun observeCableState(): Flow<Boolean> = callbackFlow {
        trySend(isUsbCablePhysicallyConnected())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                usbLogger.d("UsbManagerWrapper", "Cable state broadcast received: $action")
                val isConnected = isUsbCablePhysicallyConnected(intent)
                trySend(isConnected)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction("android.hardware.usb.action.USB_STATE")
            addAction("android.hardware.usb.action.USB_PORT_CHANGED")
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            usbLogger.e("UsbManagerWrapper", "Failed to register broadcast receiver: ${e.message}")
        }

        val job = launch {
            while (isActive) {
                try {
                    val connected = isUsbCablePhysicallyConnected()
                    trySend(connected)
                } catch (e: Exception) {
                    // Suppress excessive backup loop errors unless critical
                }
                kotlinx.coroutines.delay(500)
            }
        }

        awaitClose {
            job.cancel()
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                usbLogger.e("UsbManagerWrapper", "Error unregistering receiver: ${e.message}")
            }
        }
    }.distinctUntilChanged()

    fun isUsbCablePhysicallyConnected(intent: Intent? = null): Boolean {
        // If we just received an explicit detached/unplugged broadcast, check immediately
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED ||
            intent?.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED ||
            intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
            val hasRemainingDevices = getDevices().isNotEmpty()
            val hasRemainingAccessories = !getAccessories().isNullOrEmpty()
            if (!hasRemainingDevices && !hasRemainingAccessories && !isUsbPowerConnected()) {
                return false
            }
        }

        // Check active USB devices or accessories via UsbManager
        val hasDevices = getDevices().isNotEmpty()
        val hasAccessories = !getAccessories().isNullOrEmpty()
        if (hasDevices || hasAccessories) return true

        // Fallback: Check if USB data power is connected via BatteryManager.
        // This detects the cable on the peripheral side BEFORE the remote host
        // switches us to AOA Accessory mode (when no UsbDevice/UsbAccessory is visible yet).
        return isUsbPowerConnected()
    }

    /**
     * Checks if a USB data cable is providing power, even when no USB device or accessory
     * is enumerated yet. This is useful for detecting cable presence on the peripheral side
     * of the connection before AOA mode switching occurs.
     *
     * Note: BATTERY_PLUGGED_USB specifically means USB data port power, NOT AC charger
     * (which would be BATTERY_PLUGGED_AC).
     */
    private fun isUsbPowerConnected(): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val pluggedType = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            pluggedType == BatteryManager.BATTERY_PLUGGED_USB
        } catch (e: Exception) {
            false
        }
    }
}