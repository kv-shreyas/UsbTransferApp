package com.example.securequicktransferapp.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.securequicktransferapp.data.logging.UsbLogger
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.usecases.UsbUseCases
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class RemoteFileManagerViewModel @Inject constructor(
    private val usbUseCases: UsbUseCases,
    val usbLogger: UsbLogger
) : ViewModel() {

    companion object {
        private const val TAG = "RemoteFileManagerVM"
    }

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles: StateFlow<List<RemoteFile>> = _remoteFiles

    private val _currentRemotePath = MutableStateFlow("/sdcard")
    val currentRemotePath: StateFlow<String> = _currentRemotePath

    private val _isRemoteLoading = MutableStateFlow(false)
    val isRemoteLoading: StateFlow<Boolean> = _isRemoteLoading

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    private val directoryCache = ConcurrentHashMap<String, Pair<Long, List<RemoteFile>>>()
    private var fetchRemoteJob: Job? = null

    init {
        fetchRemoteFiles("/sdcard")
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun fetchRemoteFiles(path: String, forceRefresh: Boolean = false) {
        val normalizedPath = if (path == "/" || path.isEmpty() || path == "/sdcard") "/sdcard" else path.trimEnd('/')
        if (!forceRefresh) {
            val cached = directoryCache[normalizedPath]
            if (cached != null && System.currentTimeMillis() - cached.first < 30_000L) {
                usbLogger.d(TAG, "fetchRemoteFiles: Serving '$normalizedPath' from cache (${cached.second.size} items)")
                _currentRemotePath.value = normalizedPath
                _remoteFiles.value = cached.second
                _isRemoteLoading.value = false
                return
            }
        }

        usbLogger.i(TAG, "fetchRemoteFiles: Requesting remote directory listing for '$normalizedPath'...")
        fetchRemoteJob?.cancel()
        fetchRemoteJob = viewModelScope.launch {
            _currentRemotePath.value = normalizedPath
            _isRemoteLoading.value = true
            _remoteFiles.value = emptyList()
            try {
                val list = usbUseCases.listDirectory(normalizedPath)
                val sorted = list.sortedWith(compareBy<RemoteFile> { !it.isDirectory }.thenBy { it.name.lowercase() })
                directoryCache[normalizedPath] = Pair(System.currentTimeMillis(), sorted)
                usbLogger.i(TAG, "fetchRemoteFiles: Received ${sorted.size} items for '$normalizedPath'")
                _remoteFiles.value = sorted
            } catch (e: Exception) {
                usbLogger.e(TAG, "fetchRemoteFiles: Error fetching listing for '$normalizedPath'", e)
                _statusMessage.value = "Error fetching folder: ${e.message}"
            } finally {
                _isRemoteLoading.value = false
            }
        }
    }

    fun navigateUp() {
        val current = _currentRemotePath.value
        if (current == "/" || current == "/sdcard") return
        val parent = if (current.count { it == '/' } == 1) "/" else current.substringBeforeLast("/")
        val nextPath = if (parent.isEmpty()) "/" else parent
        fetchRemoteFiles(nextPath)
    }

    fun createFolder(folderName: String) {
        if (folderName.isBlank()) return
        val currentPath = _currentRemotePath.value
        viewModelScope.launch {
            _isRemoteLoading.value = true
            val remotePath = "$currentPath/$folderName".replace("//", "/")
            try {
                val success = usbUseCases.createFolder(remotePath)
                if (success) {
                    usbLogger.i(TAG, "Successfully created remote folder: $folderName")
                    _statusMessage.value = "Folder created: $folderName"
                    fetchRemoteFiles(currentPath, forceRefresh = true)
                } else {
                    usbLogger.e(TAG, "Failed to create remote folder: $folderName")
                    _statusMessage.value = "Failed to create folder: $folderName"
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error creating remote folder: ${e.message}")
                _statusMessage.value = "Error creating folder: ${e.message}"
            } finally {
                _isRemoteLoading.value = false
            }
        }
    }

    fun deleteRemoteFile(remotePath: String) {
        viewModelScope.launch {
            try {
                if (usbUseCases.deleteFile(remotePath)) {
                    _statusMessage.value = "Deleted item"
                    directoryCache.remove(_currentRemotePath.value)
                    fetchRemoteFiles(_currentRemotePath.value, forceRefresh = true)
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Delete error: ${e.message}")
                _statusMessage.value = "Error deleting: ${e.message}"
            }
        }
    }

    fun renameRemoteFile(oldPath: String, newName: String) {
        val currentPath = _currentRemotePath.value
        val parentPath = oldPath.substringBeforeLast('/')
        val newPath = "$parentPath/$newName"
        viewModelScope.launch {
            try {
                if (usbUseCases.renameFile(oldPath, newPath)) {
                    _statusMessage.value = "Renamed to $newName"
                    directoryCache.remove(currentPath)
                    fetchRemoteFiles(currentPath, forceRefresh = true)
                }
            } catch (e: Exception) {
                usbLogger.e(TAG, "Rename error: ${e.message}")
                _statusMessage.value = "Error renaming: ${e.message}"
            }
        }
    }

    fun sendTextAsRemoteFile(context: Context, fileName: String, content: String, targetFolder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, fileName)
            try {
                tempFile.writeText(content)
                usbUseCases.sendFile(tempFile, targetFolder).collect { progress ->
                    _statusMessage.value = "Uploading $fileName: $progress%"
                }
                _statusMessage.value = "Created and uploaded $fileName"
                directoryCache.remove(targetFolder)
                fetchRemoteFiles(targetFolder, forceRefresh = true)
            } catch (e: Exception) {
                usbLogger.e(TAG, "Failed to send text file $fileName", e)
                _statusMessage.value = "Error creating text file: ${e.message}"
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    fun sendRemoteUris(context: Context, uris: List<Uri>, targetFolder: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isRemoteLoading.value = true
            try {
                for ((index, uri) in uris.withIndex()) {
                    val fileName = getFileNameFromUri(context, uri) ?: "upload_${System.currentTimeMillis()}"
                    val tempFile = File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    _statusMessage.value = "Uploading ${index + 1}/${uris.size}: $fileName"
                    usbUseCases.sendFile(tempFile, targetFolder).collect { progress ->
                        _statusMessage.value = "Uploading $fileName ($progress%)"
                    }
                    if (tempFile.exists()) tempFile.delete()
                }
                _statusMessage.value = "Uploaded ${uris.size} files"
                directoryCache.remove(targetFolder)
                fetchRemoteFiles(targetFolder, forceRefresh = true)
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error uploading URIs", e)
                _statusMessage.value = "Error uploading: ${e.message}"
            } finally {
                _isRemoteLoading.value = false
            }
        }
    }

    fun fetchRemoteFile(remotePath: String, localSaveDir: File, isDirectory: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val localFile = File(localSaveDir, remotePath.substringAfterLast('/'))
            try {
                _statusMessage.value = "Downloading ${localFile.name}..."
                if (isDirectory) {
                    usbUseCases.fetchDirectory(remotePath, localFile).collect { progress ->
                        _statusMessage.value = "Downloading ${localFile.name} ($progress%)"
                    }
                } else {
                    usbUseCases.fetchFile(remotePath, localFile).collect { progress ->
                        _statusMessage.value = "Downloading ${localFile.name} ($progress%)"
                    }
                }
                _statusMessage.value = "Downloaded ${localFile.name}"
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error fetching remote file $remotePath", e)
                _statusMessage.value = "Error downloading: ${e.message}"
            }
        }
    }

    fun fetchRemoteFilesBatch(files: List<RemoteFile>, localSaveDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for ((index, file) in files.withIndex()) {
                    val localFile = File(localSaveDir, file.name)
                    _statusMessage.value = "Downloading ${index + 1}/${files.size}: ${file.name}"
                    if (file.isDirectory) {
                        usbUseCases.fetchDirectory(file.path, localFile).collect {}
                    } else {
                        usbUseCases.fetchFile(file.path, localFile).collect {}
                    }
                }
                _statusMessage.value = "Batch download complete (${files.size} items)"
            } catch (e: Exception) {
                usbLogger.e(TAG, "Error in batch download", e)
                _statusMessage.value = "Error in batch download: ${e.message}"
            }
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) result = cursor.getString(nameIndex)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }
}
