package com.example.securequicktransferapp.data.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.example.securequicktransferapp.data.UsbUiState
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.presentation.viewmodel.UsbTransferViewModel.TransferProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientServiceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aoaManager: AoaConnectionManager,
    private val delegatingConnection: DelegatingUsbConnection,
    private val dataSource: UsbDataSource,
    private val commandProcessor: UsbCommandProcessor,
    private val usbLogger: UsbLogger
) {
    companion object {
        private const val TAG = "ClientServiceController"
        const val ACTION_START_CLIENT = "com.example.securequicktransferapp.action.START_CLIENT"
        const val ACTION_STOP_CLIENT = "com.example.securequicktransferapp.action.STOP_CLIENT"
    }

    val isServiceRunning = MutableStateFlow(false)
    val clientUiState = MutableStateFlow<UsbUiState>(UsbUiState.Idle)
    val clientTransferProgress = MutableStateFlow(TransferProgress())

    private var clientJob: Job? = null
    private var currentReceiveFileName = ""
    private var currentReceiveFileSize = 0L
    private var receiveStartTime = 0L

    fun startService(accessory: UsbAccessory? = null) {
        try {
            usbLogger.i(TAG, "startService requested for accessory: ${accessory?.model}")
            val intent = Intent(context, UsbClientForegroundService::class.java).apply {
                action = ACTION_START_CLIENT
                if (accessory != null) {
                    putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            usbLogger.e(TAG, "Failed to start UsbClientForegroundService", e)
        }
    }

    fun stopService() {
        try {
            usbLogger.i(TAG, "stopService requested. Stopping loop and service...")
            clientJob?.cancel()
            clientJob = null
            delegatingConnection.disconnect()
            aoaManager.disconnect()
            isServiceRunning.value = false
            clientUiState.value = UsbUiState.NoDevice
            clientTransferProgress.value = TransferProgress()

            val intent = Intent(context, UsbClientForegroundService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            usbLogger.e(TAG, "Failed to stop UsbClientForegroundService", e)
        }
    }

    suspend fun runClientLoop(accessory: UsbAccessory, onStatusUpdate: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (clientJob?.isActive == true && aoaManager.isConnected()) {
            usbLogger.i(TAG, "runClientLoop: Already running and connected to ${accessory.model}. Ignoring duplicate runClientLoop request.")
            return@withContext
        }
        clientJob?.cancel()
        clientJob = launch {
            try {
                usbLogger.i(TAG, "runClientLoop: Connecting to accessory ${accessory.model}...")
                clientUiState.value = UsbUiState.Connecting
                onStatusUpdate("Connecting to Host (${accessory.model ?: "USB Host"})...")

                var connected = false
                for (attempt in 1..30) {
                    if (!isActive) break
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    if (usbManager.hasPermission(accessory)) {
                        if (aoaManager.connect(accessory)) {
                            connected = true
                            usbLogger.i(TAG, "runClientLoop: Successfully connected to accessory on attempt $attempt")
                            break
                        } else {
                            usbLogger.d(TAG, "runClientLoop: aoaManager.connect attempt $attempt failed (Host may still be opening pipe). Retrying in 500ms...")
                        }
                    } else {
                        usbLogger.d(TAG, "runClientLoop: Waiting for accessory permission (attempt $attempt)...")
                    }
                    kotlinx.coroutines.delay(500)
                }

                if (!connected) {
                    usbLogger.e(TAG, "runClientLoop: aoaManager.connect(accessory) failed after 15s of retries.")
                    clientUiState.value = UsbUiState.Error("Failed to open USB Accessory connection.")
                    onStatusUpdate("Connection Failed")
                    return@launch
                }

                delegatingConnection.setDelegate(aoaManager)

                while (isActive && aoaManager.isConnected()) {
                    clientUiState.value = UsbUiState.Transferring
                    onStatusUpdate("Handshaking with Host...")

                    if (dataSource.performHandshake()) {
                        usbLogger.i(TAG, "runClientLoop: Handshake SUCCESS. Secure channel established.")
                        clientUiState.value = UsbUiState.Success("Secure Channel Established")
                        onStatusUpdate("Connected as Client: Listening for commands")

                        delegatingConnection.clearBuffer()
                        commandProcessor.startListening(
                            onReceiveStarted = { fileName, fileSize ->
                                currentReceiveFileName = fileName
                                currentReceiveFileSize = fileSize
                                receiveStartTime = System.currentTimeMillis()
                                val formattedSize = if (fileSize > 0) formatSize(fileSize) else "Unknown"
                                clientUiState.value = UsbUiState.Receiving(fileName, 0f)
                                clientTransferProgress.value = TransferProgress(
                                    isVisible = true,
                                    isComplete = false,
                                    filename = fileName,
                                    statusMessage = "Receiving: $fileName",
                                    percentage = 0,
                                    total = formattedSize,
                                    transferred = "0 B",
                                    speed = "Receiving...",
                                    eta = "Calculating...",
                                    elapsed = "0s",
                                    currentFileIndex = 1,
                                    totalFiles = 1
                                )
                                onStatusUpdate("Receiving file: $fileName ($formattedSize)")
                            },
                            onReceiveProgress = { progress ->
                                val current = clientUiState.value
                                val currentName = if (current is UsbUiState.Receiving) current.fileName else currentReceiveFileName.ifEmpty { "File" }
                                clientUiState.value = UsbUiState.Receiving(currentName, progress)
                                val elapsedSec = (System.currentTimeMillis() - receiveStartTime) / 1000L
                                val transferredBytes = (currentReceiveFileSize * progress).toLong()
                                val speedSec = if (elapsedSec > 0) formatSize(transferredBytes / elapsedSec) + "/s" else "Calculating..."
                                val etaStr = if (elapsedSec > 0 && progress > 0f) {
                                    val remainingBytes = currentReceiveFileSize - transferredBytes
                                    val speedBps = transferredBytes / elapsedSec
                                    if (speedBps > 0) formatTime(remainingBytes / speedBps) else "Calculating..."
                                } else "Calculating..."

                                clientTransferProgress.value = clientTransferProgress.value.copy(
                                    percentage = (progress * 100).toInt(),
                                    statusMessage = "Receiving $currentReceiveFileName (${(progress * 100).toInt()}%)",
                                    transferred = formatSize(transferredBytes),
                                    speed = speedSec,
                                    eta = etaStr,
                                    elapsed = formatTime(elapsedSec)
                                )
                            },
                            onReceiveFinished = {
                                val elapsedSec = (System.currentTimeMillis() - receiveStartTime) / 1000L
                                val finalSizeStr = if (currentReceiveFileSize > 0) formatSize(currentReceiveFileSize) else "Done"
                                val finalSpeedStr = if (elapsedSec > 0 && currentReceiveFileSize > 0) "${formatSize(currentReceiveFileSize / elapsedSec)}/s" else "Done"
                                clientUiState.value = UsbUiState.Success("File Transfer Complete ($currentReceiveFileName)")
                                clientTransferProgress.value = TransferProgress(
                                    isVisible = true,
                                    filename = currentReceiveFileName,
                                    statusMessage = "Receive Complete - Successfully received $currentReceiveFileName",
                                    percentage = 100,
                                    isComplete = true,
                                    total = finalSizeStr,
                                    transferred = finalSizeStr,
                                    speed = finalSpeedStr,
                                    eta = "Done",
                                    elapsed = formatTime(elapsedSec),
                                    currentFileIndex = 1,
                                    totalFiles = 1
                                )
                                onStatusUpdate("Connected as Client: Ready ($currentReceiveFileName received)")
                            },
                            onReceiveCancelled = {
                                clientUiState.value = UsbUiState.Success("Transfer Cancelled")
                                clientTransferProgress.value = clientTransferProgress.value.copy(
                                    isComplete = true,
                                    statusMessage = "Transfer cancelled ❌"
                                )
                                onStatusUpdate("Connected as Client: Ready")
                            },
                            onReceiveError = { errorMsg ->
                                usbLogger.e(TAG, "runClientLoop error: $errorMsg")
                                clientUiState.value = UsbUiState.Error(errorMsg)
                                clientTransferProgress.value = clientTransferProgress.value.copy(
                                    isComplete = true,
                                    statusMessage = "Error: $errorMsg"
                                )
                                onStatusUpdate("Error: $errorMsg")
                            },
                            onDisconnectReceived = {
                                usbLogger.i(TAG, "Disconnected command received from remote Host. Entering instant standby...")
                                clientUiState.value = UsbUiState.Success("Standby: Ready for instant connection...")
                                onStatusUpdate("Standby: Ready for instant connection...")
                            }
                        )
                    } else {
                        if (!aoaManager.isConnected()) {
                            usbLogger.i(TAG, "runClientLoop: Accessory physically disconnected.")
                            break
                        }
                        usbLogger.w(TAG, "runClientLoop: Waiting for Host handshake Step 1...")
                        clientUiState.value = UsbUiState.Success("Standby: Ready for instant connection...")
                        onStatusUpdate("Standby: Ready for instant connection...")
                        kotlinx.coroutines.delay(200)
                    }
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "runClientLoop exception: ${e.message}", e)
                clientUiState.value = UsbUiState.Error(e.message ?: "Client error")
                onStatusUpdate("Error: ${e.message}")
            } finally {
                usbLogger.i(TAG, "runClientLoop finished or cancelled.")
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    private fun formatTime(seconds: Long): String {
        return if (seconds >= 60) {
            val min = seconds / 60
            val sec = seconds % 60
            "${min}m ${sec}s"
        } else {
            "${seconds}s"
        }
    }
}
