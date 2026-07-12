package com.example.usbtransferapp.data.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.example.usbtransferapp.MainActivity
import com.example.usbtransferapp.R
import com.example.usbtransferapp.data.logging.UsbLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class UsbClientForegroundService : Service() {

    @Inject
    lateinit var controller: ClientServiceController

    @Inject
    lateinit var usbLogger: UsbLogger

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "UsbClientForegroundSvc"
        private const val CHANNEL_ID = "usb_client_channel"
        private const val CHANNEL_NAME = "USB Client Service"
        private const val NOTIFICATION_ID = 2026
    }

    override fun onCreate() {
        super.onCreate()
        usbLogger.i(TAG, "onCreate: Initializing UsbClientForegroundService...")
        createNotificationChannel()
        val notification = buildNotification("USB Client Active", "Listening for USB Host connections...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } catch (e: Exception) {
                usbLogger.w(TAG, "onCreate: startForeground with FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE failed or not allowed ($e). Trying fallback...")
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    usbLogger.e(TAG, "onCreate: fallback startForeground blocked ($e2). Service running without FGS notification.")
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                usbLogger.e(TAG, "onCreate: startForeground failed ($e)")
            }
        }
        controller.isServiceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        usbLogger.i(TAG, "onStartCommand: action=$action")

        if (action == ClientServiceController.ACTION_STOP_CLIENT) {
            usbLogger.i(TAG, "Received ACTION_STOP_CLIENT. Stopping service...")
            controller.stopService()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        var accessory = if (intent != null) {
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else null

        if (accessory == null) {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            accessory = usbManager.accessoryList?.firstOrNull()
        }

        if (accessory != null) {
            usbLogger.i(TAG, "Starting client loop for accessory: ${accessory.model} (${accessory.manufacturer})")
            updateNotification("USB Client Connected", "Host: ${accessory.model ?: "USB Host"} - Listening...")
            serviceScope.launch {
                controller.runClientLoop(accessory) { statusMsg ->
                    updateNotification("USB Client Mode", statusMsg)
                }
            }
        } else {
            usbLogger.w(TAG, "No UsbAccessory attached right now. Service running in standby mode...")
            updateNotification("USB Client Active", "Waiting for USB Host cable connection...")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        usbLogger.i(TAG, "onDestroy: Shutting down UsbClientForegroundService.")
        serviceScope.cancel()
        controller.isServiceRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs background USB data transfer when Android acts as Client/Accessory"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        // Tapping on top of the notification opens MainActivity (TransferScreen)
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_client_mode", true)
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Disconnect Action button on notification
        val disconnectIntent = Intent(this, UsbClientForegroundService::class.java).apply {
            action = ClientServiceController.ACTION_STOP_CLIENT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openAppPendingIntent)
//            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(title, content))
    }
}
