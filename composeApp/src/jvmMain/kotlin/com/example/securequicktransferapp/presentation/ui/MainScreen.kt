package com.example.securequicktransferapp.presentation.ui

import androidx.compose.animation.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun MainScreen(vm: MainViewModel) {
    var currentScreen by remember { mutableStateOf("explorer") }
    val state by vm.state.collectAsState()
    val remoteFiles by vm.remoteFiles.collectAsState()
    val currentPath by vm.currentRemotePath.collectAsState()
    val progress by vm.progressState.collectAsState()
    val isPhysicallyConnected by vm.isPhysicallyConnected.collectAsState()
    val physicalDeviceName by vm.physicallyConnectedDeviceName.collectAsState()
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showCreateTextFileDialog by remember { mutableStateOf(false) }
    var newTextFileName by remember { mutableStateOf("") }
    var newTextFileContent by remember { mutableStateOf("") }

    val isConnected = state != "Idle" && state != "Searching..." && !state.contains("Failed") && !state.contains("Connection Lost") && !state.contains("Disconnect")

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            currentScreen = "explorer"
        }
    }

    if (progress.isVisible) {
        TransferProgressDialog(
            progress = progress, 
            onCancel = { vm.cancelTransfer() },
            onDismiss = { vm.dismissProgress() }
        )
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Sidebar
        Sidebar(
            vm = vm,
            state = state,
            currentScreen = currentScreen,
            isConnected = isConnected,
            onNavigate = { currentScreen = it },
            onDisconnect = { vm.disconnect() }
        )

        // Main Content
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            if (!isConnected) {
                DesktopNotConnectedView(
                    state = state,
                    isPhysicallyConnected = isPhysicallyConnected,
                    physicalDeviceName = physicalDeviceName,
                    onConnect = { vm.connect() }
                )
            } else if (currentScreen == "smartnav") {
                SmartNavDesktopDashboard(
                    vm = vm,
                    onNavigateToExplorerPath = { targetPath ->
                        currentScreen = "explorer"
                        val dummyFolder = RemoteFile(name = targetPath.substringAfterLast('/'), isDirectory = true, size = 0, path = targetPath)
                        vm.navigateTo(dummyFolder)
                    }
                )
            } else {
                Header(
                    "Remote File System", 
                    currentPath, 
                    onBack = { vm.navigateUp() }, 
                    onRefresh = { vm.refreshRemoteFiles() },
                    onCreateFolder = { showCreateFolderDialog = true },
                    onCreateTextFile = { showCreateTextFileDialog = true }
                )
                
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
                                    vm.createFolder(newFolderName)
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
                                    val name = newTextFileName
                                    vm.sendTextAsRemoteFile(name, newTextFileContent, currentPath)
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

                Spacer(Modifier.height(24.dp))

                FileList(
                    files = remoteFiles,
                    modifier = Modifier.weight(1f),
                    onFolderClick = { vm.navigateTo(it) },
                    onFilesFetch = { vm.fetchFiles(it) },
                    onFileDelete = { vm.deleteFile(it) },
                    onFileRename = { file, newName -> vm.renameFile(file, newName) }
                )

                Spacer(Modifier.height(16.dp))
                
                ActionBar(currentPath = currentPath, onSendFile = {
                    val fileChooser = JFileChooser()
                    fileChooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                    fileChooser.isMultiSelectionEnabled = true
                    if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        vm.sendFiles(fileChooser.selectedFiles.toList())
                    }
                })
            }
        }
    }
}

@Composable
fun Sidebar(vm: MainViewModel, state: String, currentScreen: String, isConnected: Boolean, onNavigate: (String) -> Unit, onDisconnect: () -> Unit) {
    val isPhysicallyConnected by vm.isPhysicallyConnected.collectAsState()
    val physicalDeviceName by vm.physicallyConnectedDeviceName.collectAsState()

    Surface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Control Panel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))

            if (isConnected) {
                NavigationItem("File Explorer", Icons.Default.Folder, currentScreen == "explorer") { onNavigate("explorer") }
                Spacer(Modifier.height(8.dp))
                NavigationItem("SmartNav Option", Icons.Default.Explore, currentScreen == "smartnav") { onNavigate("smartnav") }
                Spacer(Modifier.height(32.dp))
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect device to unlock File Explorer & SmartNav options.", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }

            ConnectionCard(state, vm.isAoaMode, isPhysicallyConnected, physicalDeviceName, onConnect = { vm.connect() }, onDisconnect = onDisconnect)

            if (isConnected) {
                Spacer(Modifier.height(32.dp))
                Text("Recent Transfers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                
                Box(modifier = Modifier.weight(1f)) {
                    Text("No active transfers", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            SecurityInfo()
        }
    }
}

@Composable
fun NavigationItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun ConnectionCard(state: String, isAoaMode: Boolean, isPhysicallyConnected: Boolean, physicalDeviceName: String?, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val isDisconnected = state == "Idle" || state == "Searching..." || state.contains("Failed") || state.contains("Connection Lost")
    val isConnected = !isDisconnected
    
    val statusColor = when {
        !isConnected -> if (state == "Idle" || state == "Searching...") Color.Gray else Color.Red
        state.contains("Error") || state.contains("Cancelled") -> Color(0xFFFFA500)
        else -> Color(0xFF4CAF50)
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                color = if (isPhysicallyConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isPhysicallyConnected) Color(0xFF4CAF50) else Color(0xFFE57373)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isPhysicallyConnected) Icons.Default.CheckCircle else Icons.Default.UsbOff,
                        null,
                        tint = if (isPhysicallyConnected) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (isPhysicallyConnected) "USB Cable Connected" else "USB Cable Unplugged",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPhysicallyConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                        if (isPhysicallyConnected && !physicalDeviceName.isNullOrBlank()) {
                            Text(
                                physicalDeviceName,
                                fontSize = 9.sp,
                                color = Color(0xFF2E7D32).copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Text(if (isConnected) "System Online" else "Disconnected", fontWeight = FontWeight.Medium)
            }
            if (isConnected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isAoaMode) "Protocol: AOA (Accessory)" else "Protocol: MTP/ADB (Normal)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect")
                }
            } else {
                Text(
                    if (isPhysicallyConnected) "Click Connect to initialize secure session." else "Plug in your Android device via USB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = state != "Searching..." && isPhysicallyConnected
                ) {
                    Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connect Device")
                }
            }
        }
    }
}



@Composable
fun Header(
    title: String, 
    path: String, 
    onBack: () -> Unit, 
    onRefresh: () -> Unit, 
    onCreateFolder: (() -> Unit)? = null,
    onCreateTextFile: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (onCreateFolder != null) {
            IconButton(onClick = onCreateFolder) {
                Icon(Icons.Default.Add, "Create Folder")
            }
        }
        if (onCreateTextFile != null) {
            IconButton(onClick = onCreateTextFile) {
                Icon(Icons.Default.EditNote, "Create File")
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Refresh")
        }
    }
}

@Composable
fun FileList(
    files: List<RemoteFile>, 
    modifier: Modifier = Modifier,
    onFolderClick: (RemoteFile) -> Unit, 
    onFilesFetch: (List<RemoteFile>) -> Unit,
    onFileDelete: (RemoteFile) -> Unit,
    onFileRename: (RemoteFile, String) -> Unit
) {
    var selectedFiles by androidx.compose.runtime.remember { mutableStateOf(setOf<RemoteFile>()) }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        if (files.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    Text("No compatible files found", color = Color.Gray)
                }
            }
        } else {
            Column {
                if (selectedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedFiles.size} items selected", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Button(onClick = { 
                            onFilesFetch(selectedFiles.toList())
                            selectedFiles = emptySet()
                        }) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fetch Selected")
                        }
                    }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(files) { file ->
                        FileRow(
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
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(
    file: RemoteFile, 
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onFolderClick: (RemoteFile) -> Unit, 
    onFileFetch: (RemoteFile) -> Unit,
    onFileDelete: (RemoteFile) -> Unit,
    onFileRename: (RemoteFile, String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember(file.name, showRenameDialog) { mutableStateOf(file.name) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("New Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (renameText.isNotBlank() && renameText != file.name) {
                        onFileRename(file, renameText)
                    }
                    showRenameDialog = false
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Confirm Deletion") },
            text = { Text("Are you sure you want to delete '${file.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        onFileDelete(file)
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { if (file.isDirectory) onFolderClick(file) }
                .padding(12.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (file.isDirectory) Color(0xFFFFC107) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Medium)
                Text(
                    if (file.isDirectory) "Directory" else formatSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            Box {
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false }
                ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Fetch") },
                    onClick = {
                        showContextMenu = false
                        onFileFetch(file)
                    },
                    leadingIcon = { Icon(Icons.Default.Download, "Fetch") }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        showContextMenu = false
                        showRenameDialog = true
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, "Rename") }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        showContextMenu = false
                        showDeleteConfirmDialog = true
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                )
            }
            }
        }
    }
}

    @Composable
    fun ActionBar(currentPath: String, onSendFile: () -> Unit) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Upload file to Android device", fontWeight = FontWeight.Medium)
                    Text(
                        "Destination: $currentPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Button(onClick = onSendFile) {
                    Text("Upload to Current Folder")
                }
            }
        }
    }

    @Composable
    fun SecurityInfo() {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Divider(thickness = 0.5.dp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Verified,
                    null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Encrypted Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("ECDH-P256 / AES-GCM", fontSize = 10.sp, color = Color.Gray)
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    @Composable
    fun TransferProgressDialog(
        progress: com.example.securequicktransferapp.presentation.vm.MainViewModel.TransferProgress,
        onCancel: () -> Unit,
        onDismiss: () -> Unit
    ) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { if (progress.isComplete) onDismiss() },
            properties = androidx.compose.ui.window.DialogProperties()
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.85f).widthIn(max = 1000.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Download,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                if (progress.isComplete) "Transfer Complete" else "Transferring Data",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (progress.isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                progress.statusMessage.ifEmpty { progress.filename },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (progress.totalFiles > 1) {
                                Text(
                                    "File ${progress.currentFileIndex} of ${progress.totalFiles}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    LinearProgressIndicator(
                        progress = progress.percentage / 100f,
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                "Speed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(progress.speed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Elapsed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(progress.elapsed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Remaining",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(progress.eta, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Progress",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(
                                "${progress.percentage}%",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${progress.transferred} / ${progress.total}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )

                        if (progress.totalFiles > 1) {
                            Text(
                                "Total Elapsed: ${progress.batchElapsed}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    if (progress.queue.isNotEmpty() && progress.totalFiles > 1) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Queue",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Gray.copy(alpha = 0.1f))
                                .padding(8.dp)
                        ) {
                            items(progress.queue.size) { i ->
                                val item = progress.queue[i]
                                val isCurrent = (i + 1) == progress.currentFileIndex
                                val isDone = (i + 1) < progress.currentFileIndex
                                val color = when {
                                    isDone -> Color(0xFF4CAF50)
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    else -> Color.Gray
                                }
                                Text(
                                    text = "${i + 1}. $item",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (progress.isComplete) {
                            Button(onClick = onDismiss) {
                                Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Finish")
                            }
                        } else {
                            TextButton(
                                onClick = onCancel,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Cancel Transfer")
                            }
                        }
                    }

                    if (!progress.isComplete) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Please do not disconnect the USB cable",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Red.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }

@Composable
fun DesktopNotConnectedView(
    state: String,
    isPhysicallyConnected: Boolean,
    physicalDeviceName: String?,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = if (isPhysicallyConnected) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPhysicallyConnected) Icons.Default.Usb else Icons.Default.Devices,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (isPhysicallyConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (state == "Searching...") "Searching for USB Device..." else if (isPhysicallyConnected) "USB Device Detected" else "No USB Device Connected",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (isPhysicallyConnected) {
                    "Your device (${physicalDeviceName ?: "Android Device"}) is physically connected via USB cable. Click below to connect and unlock the File Manager & SmartNav Suite."
                } else {
                    "Please connect your Android device via USB cable to access remote file management and the SmartNav package tools."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quick Connection Guide", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    StepRow(step = "1", text = "Connect your Android device using a data-capable USB cable.")
                    StepRow(step = "2", text = "Open the UsbTransfer app on Android and select Client Mode.")
                    StepRow(step = "3", text = "Click the Connect Device button below to initialize the USB session.")
                    StepRow(step = "4", text = "Once verified, File Explorer and SmartNav tabs unlock automatically.")
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onConnect,
                enabled = state != "Searching...",
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPhysicallyConnected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                if (state == "Searching...") {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (isPhysicallyConnected) "Connect to Device (${physicalDeviceName ?: "Android"})" else "Connect Device", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (state.contains("Failed") || state.contains("Error")) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Previous attempt status: $state",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun StepRow(step: String, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(step, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}
