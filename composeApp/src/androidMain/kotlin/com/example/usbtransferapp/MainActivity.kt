package com.example.usbtransferapp

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.usbtransferapp.presentation.ui.TransferScreen
import com.example.usbtransferapp.presentation.viewmodel.UsbTransferViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlin.getValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
/*    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App()
        }
    }*/

    private val viewModel: UsbTransferViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        
        checkStoragePermissions()

        setContent {
            TransferScreen(viewModel)
        }
    }

    private fun checkStoragePermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:${packageName}")
                startActivity(intent)
            }
        } else {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_client_mode", false) == true) {
            Log.d("MainActivity", "Opened from USB Client Service notification")
            viewModel.usbLogger.i("MainActivity", "Opened directly from USB Client Service notification")
            viewModel.detectAndConnect()
            return
        }

        when (intent?.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.d("MainActivity", "USB attached via intent - auto-connecting")
                viewModel.usbLogger.i("MainActivity", "USB attached via intent (${intent.action}) - auto-connecting")
                viewModel.detectAndConnect()
            }
            UsbManager.ACTION_USB_ACCESSORY_DETACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.d("MainActivity", "USB detached via intent")
                viewModel.usbLogger.i("MainActivity", "USB detached via intent (${intent.action})")
                viewModel.disconnect()
            }
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}