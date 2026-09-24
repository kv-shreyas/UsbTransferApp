package com.example.securequicktransferapp.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.usecases.UsbUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SmartNavDashboardViewModel @Inject constructor(
    private val usbUseCases: UsbUseCases,
    val usbLogger: UsbLogger
) : ViewModel() {

    companion object {
        private const val TAG = "SmartNavDashboardVM"
    }

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun prepareLocalSmartNavStaging(stagingDir: File, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            _statusMessage.value = "Generating local SmartNav staging folders..."
            usbLogger.i(TAG, "prepareLocalSmartNavStaging: Initializing SmartNavRoot hierarchy under ${stagingDir.absolutePath}...")
            try {
                val foldersAndFiles = Constants.SmartnavRoot.DEFAULT_FOLDERS_AND_FILES
                for ((folder, filePair) in foldersAndFiles) {
                    val (fileName, content) = filePair
                    val dir = File(stagingDir, folder)
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    if (!file.exists()) {
                        file.writeText(content)
                    }
                }
                usbLogger.i(TAG, "prepareLocalSmartNavStaging: Done.")
                _statusMessage.value = "Staging directory structure created successfully."
                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to create staging folders", e)
                _statusMessage.value = "Error generating staging: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun sendTextAsRemoteFile(
        context: Context,
        fileName: String,
        content: String,
        targetFolder: String,
        onComplete: (() -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            val tempFile = File(context.cacheDir, fileName)
            try {
                tempFile.writeText(content)
                usbLogger.i(TAG, "Sending text file $fileName to $targetFolder...")
                usbUseCases.sendFile(tempFile, targetFolder).collect { progress ->
                    _statusMessage.value = "Sending $fileName: $progress%"
                }
                usbLogger.i(TAG, "Successfully sent text file $fileName to $targetFolder")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to send text file $fileName", e)
                _statusMessage.value = "Error sending $fileName: ${e.message}"
            } finally {
                if (tempFile.exists()) tempFile.delete()
                _isProcessing.value = false
            }
        }
    }

    fun sendMultipleFiles(
        files: List<File>,
        targetFolder: String,
        onComplete: (() -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            usbLogger.i(TAG, "sendMultipleFiles: sending ${files.size} items to $targetFolder")
            try {
                for (file in files) {
                    if (file.isDirectory) {
                        sendDirectoryRecursive(file, targetFolder)
                    } else {
                        usbUseCases.sendFile(file, targetFolder).collect { progress ->
                            _statusMessage.value = "Sending ${file.name}: $progress%"
                        }
                    }
                }
                usbLogger.i(TAG, "Finished sending files to $targetFolder")
                withContext(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error sending multiple files", e)
                _statusMessage.value = "Error sending files: ${e.message}"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun sendDirectoryRecursive(dir: File, destinationPath: String) {
        val remoteDirPath = "$destinationPath/${dir.name}"
        usbUseCases.createFolder(remoteDirPath)
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                sendDirectoryRecursive(child, remoteDirPath)
            } else {
                usbUseCases.sendFile(child, remoteDirPath).collect { progress ->
                    _statusMessage.value = "Sending ${child.name}: $progress%"
                }
            }
        }
    }
}
