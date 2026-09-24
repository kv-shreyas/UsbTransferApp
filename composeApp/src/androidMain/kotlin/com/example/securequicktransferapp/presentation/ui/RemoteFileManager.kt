package com.example.securequicktransferapp.presentation.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.presentation.theme.AppTheme
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.viewmodel.RemoteFileManagerViewModel
import java.io.File

@Composable
fun RemoteFileManager(
    viewModel: RemoteFileManagerViewModel = hiltViewModel(),
    onNavigateDir: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val remoteFiles by viewModel.remoteFiles.collectAsState()
    val currentRemotePath by viewModel.currentRemotePath.collectAsState()
    val isRemoteLoading by viewModel.isRemoteLoading.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val defaultDownloadDir = remember {
        File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "SecureQuickTransfer"
        ).apply { mkdirs() }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.sendRemoteUris(context, uris, currentRemotePath)
        }
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    var showCreateTextFileDialog by remember { mutableStateOf(false) }
    var newTextFileName by remember { mutableStateOf("") }
    var newTextFileContent by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val parent = when {
                        currentRemotePath == "/sdcard" || currentRemotePath == "/" || currentRemotePath.isEmpty() -> "/sdcard"
                        else -> currentRemotePath.substringBeforeLast('/', "/sdcard").ifEmpty { "/sdcard" }
                    }
                    viewModel.fetchRemoteFiles(parent)
                    onNavigateDir?.invoke(parent)
                },
                enabled = currentRemotePath != "/sdcard" && currentRemotePath != "/" && currentRemotePath.isNotEmpty()
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Remote: $currentRemotePath",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { showCreateFolderDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Create Folder")
            }
            IconButton(onClick = { showCreateTextFileDialog = true }) {
                Icon(Icons.Default.EditNote, contentDescription = "Create File")
            }
            IconButton(onClick = { viewModel.fetchRemoteFiles(currentRemotePath, forceRefresh = true) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (statusMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(8.dp),
                color = AppTheme.colors.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = statusMessage ?: "",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.clearStatusMessage() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (showCreateFolderDialog) {
            AlertDialog(
                onDismissRequest = { showCreateFolderDialog = false },
                title = { Text("Create Folder") },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Folder Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.createFolder(newFolderName)
                        }
                        newFolderName = ""
                        showCreateFolderDialog = false
                    }) {
                        Text("Create")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newFolderName = ""
                        showCreateFolderDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showCreateTextFileDialog) {
            AlertDialog(
                onDismissRequest = { showCreateTextFileDialog = false },
                title = { Text("Create File") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newTextFileName,
                            onValueChange = { newTextFileName = it },
                            label = { Text("File Name with extension (e.g. note.txt, data.json)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = newTextFileContent,
                            onValueChange = { newTextFileContent = it },
                            label = { Text("Content") },
                            modifier = Modifier.fillMaxWidth().height(150.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newTextFileName.isNotBlank()) {
                            viewModel.sendTextAsRemoteFile(context, newTextFileName, newTextFileContent, currentRemotePath)
                        }
                        newTextFileName = ""
                        newTextFileContent = ""
                        showCreateTextFileDialog = false
                    }) {
                        Text("Save & Upload")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        newTextFileName = ""
                        newTextFileContent = ""
                        showCreateTextFileDialog = false
                    }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Action Bar for uploading files to current directory
        AndroidActionBar(
            currentPath = currentRemotePath,
            onSendFile = {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        )

        Spacer(Modifier.height(8.dp))

        if (isRemoteLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AppTheme.colors.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading folder contents...", color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 14.sp)
                }
            }
        } else {
            AndroidFileList(
                files = remoteFiles,
                modifier = Modifier.weight(1f),
                onFolderClick = { dir ->
                    viewModel.fetchRemoteFiles(dir.path)
                    onNavigateDir?.invoke(dir.path)
                },
                onFilesFetch = { files ->
                    if (files.size == 1) {
                        viewModel.fetchRemoteFile(files.first().path, defaultDownloadDir, files.first().isDirectory)
                    } else {
                        viewModel.fetchRemoteFilesBatch(files, defaultDownloadDir)
                    }
                },
                onFileDelete = { file -> viewModel.deleteRemoteFile(file.path) },
                onFileRename = { file, newName -> viewModel.renameRemoteFile(file.path, newName) }
            )
        }
    }
}

@Composable
fun AndroidActionBar(currentPath: String, onSendFile: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AppTheme.colors.primaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CloudUpload, null, tint = AppTheme.colors.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload to Android Client", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    "Destination: $currentPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSendFile,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Upload", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AndroidFileList(
    files: List<RemoteFile>,
    modifier: Modifier = Modifier,
    onFolderClick: (RemoteFile) -> Unit,
    onFilesFetch: (List<RemoteFile>) -> Unit,
    onFileDelete: (RemoteFile) -> Unit,
    onFileRename: (RemoteFile, String) -> Unit
) {
    var selectedFiles by remember { mutableStateOf(setOf<RemoteFile>()) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        color = AppTheme.colors.surface
    ) {
        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("Directory is empty", color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(AppTheme.colors.primaryContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedFiles.size} items selected",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.onPrimaryContainer,
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                onFilesFetch(selectedFiles.toList())
                                selectedFiles = emptySet()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Fetch Selected", fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { selectedFiles = emptySet() }) {
                            Text("Clear", fontSize = 12.sp, color = AppTheme.colors.onPrimaryContainer)
                        }
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(files) { file ->
                        AndroidFileRow(
                            file = file,
                            isSelected = selectedFiles.contains(file),
                            onSelectionChange = { selected ->
                                if (selected) selectedFiles += file else selectedFiles -= file
                            },
                            onFolderClick = onFolderClick,
                            onFileFetch = { onFilesFetch(listOf(it)) },
                            onFileDelete = onFileDelete,
                            onFileRename = onFileRename
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = AppTheme.colors.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun AndroidFileRow(
    file: RemoteFile,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onFolderClick: (RemoteFile) -> Unit,
    onFileFetch: (RemoteFile) -> Unit,
    onFileDelete: (RemoteFile) -> Unit,
    onFileRename: (RemoteFile, String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            file = file,
            onConfirm = { newName ->
                onFileRename(file, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    if (showDeleteConfirmDialog) {
        DeleteConfirmDialog(
            file = file,
            onConfirm = {
                onFileDelete(file)
                showDeleteConfirmDialog = false
            },
            onDismiss = { showDeleteConfirmDialog = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (file.isDirectory) onFolderClick(file) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = onSelectionChange
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) WarningColor else AppTheme.colors.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                if (file.isDirectory) "Directory" else SecureQtSdk.Utils.formatSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }

        IconButton(onClick = { onFileFetch(file) }) {
            Icon(
                Icons.Default.Download,
                contentDescription = if (file.isDirectory) "Download Directory (ZIP)" else "Download File",
                tint = AppTheme.colors.primary
            )
        }

        Box {
            IconButton(onClick = { showContextMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More Options")
            }
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(if (file.isDirectory) "Download as ZIP" else "Download") },
                    onClick = {
                        showContextMenu = false
                        onFileFetch(file)
                    },
                    leadingIcon = { Icon(Icons.Default.Download, null) }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showContextMenu = false
                        showRenameDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, null) }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = AppTheme.colors.error) },
                    onClick = {
                        showContextMenu = false
                        showDeleteConfirmDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = AppTheme.colors.error) }
                )
            }
        }
    }

}
@Composable
fun RenameDialog(
    file: RemoteFile,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var renameText by remember(file.name) { mutableStateOf(file.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Item") },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text("New Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                if (renameText.isNotBlank() && renameText != file.name) {
                    onConfirm(renameText)
                } else {
                    onDismiss()
                }
            }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    file: RemoteFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Deletion") },
        text = { Text("Are you sure you want to permanently delete '${file.name}' from the remote Android device?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
