package com.example.securequicktransferapp.data.usb.connection

import com.example.securequicktransferapp.data.usb.broadcast.UsbPermissionReceiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

class UsbManagerWrapper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val usbLogger: com.example.securequicktransferapp.data.logging.UsbLogger
) {

    val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var isPermissionReceiverRegistered = false
    private val permissionReceiver = UsbPermissionReceiver()

    @Synchronized
    private fun registerPermissionReceiverIfNeeded() {
        if (!isPermissionReceiverRegistered) {
            try {
                val filter = IntentFilter(UsbPermissionReceiver.ACTION_USB_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    ContextCompat.registerReceiver(
                        context,
                        permissionReceiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }
                isPermissionReceiverRegistered = true
                usbLogger.d("UsbManagerWrapper", "Runtime UsbPermissionReceiver registered successfully")
            } catch (e: Exception) {
                usbLogger.e("UsbManagerWrapper", "Failed to register runtime UsbPermissionReceiver: ${e.message}")
            }
        }
    }

    fun getAccessories(): Array<UsbAccessory>? = usbManager.accessoryList

    fun hasPermission(accessory: UsbAccessory): Boolean {
        return usbManager.hasPermission(accessory)
    }

    fun requestPermission(accessory: UsbAccessory) {
        registerPermissionReceiverIfNeeded()
        usbLogger.i("UsbManagerWrapper", "Requesting permission for accessory ${accessory.model}")
        val intent = Intent(UsbPermissionReceiver.ACTION_USB_PERMISSION).apply {
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
        // 1. If we just received an explicit broadcast, check action-specific flags immediately
        if (intent != null) {
            when (intent.action) {
                "android.hardware.usb.action.USB_STATE" -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    if (!connected) return false
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    val hasRemainingAccessories = !getAccessories().isNullOrEmpty()
                    if (!hasRemainingAccessories) {
                        return false
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    return false
                }
            }
        }

        // 2. Check active accessories via UsbManager
        val hasAccessories = !getAccessories().isNullOrEmpty()
        if (hasAccessories) return true

        // 3. Check sticky USB_STATE broadcast (instantaneous kernel USB gadget state)
        val usbStateIntent = try {
            context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        } catch (_: Exception) { null }
        if (usbStateIntent != null) {
            val isConnected = usbStateIntent.getBooleanExtra("connected", false)
            if (!isConnected) return false
            return true
        }

        // 4. Fallback: Check if USB data power is connected via BatteryManager.
        return isUsbPowerConnected()
    }

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
