package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.background
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.TransferQueueItem
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import javax.swing.JFileChooser

@Composable
fun MainScreen(vm: MainViewModel, isDarkTheme: Boolean = true, onThemeToggle: () -> Unit = {}) {
    var currentScreen by remember { mutableStateOf("explorer") }
    val state by vm.state.collectAsState()
    val remoteFiles by vm.remoteFiles.collectAsState()
    val currentPath by vm.currentRemotePath.collectAsState()
    val progress by vm.progressState.collectAsState()
    val sessions by vm.sessionsState.collectAsState()
    val selectedDeviceId by vm.selectedDeviceId.collectAsState()
    val isQueueProcessing by vm.isQueueProcessing.collectAsState()
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showCreateTextFileDialog by remember { mutableStateOf(false) }
    var newTextFileName by remember { mutableStateOf("") }
    var newTextFileContent by remember { mutableStateOf("") }

    val isConnected = state == "Ready"

    LaunchedEffect(isConnected) {
        if (!isConnected) {
            currentScreen = "explorer"
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(AppTheme.colors.surface)) {
        // Sidebar
        Sidebar(
            vm = vm,
            state = state,
            currentScreen = currentScreen,
            isConnected = isConnected,
            sessions = sessions,
            selectedDeviceId = selectedDeviceId,
            onSelectDevice = { vm.selectDevice(it) },
            onNavigate = { currentScreen = it },
            onDisconnect = { vm.disconnect() }
        )

        // Main Content
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            if (currentScreen == "settings") {
                SettingsScreen(
                    isDarkTheme = isDarkTheme,
                    onThemeToggle = onThemeToggle,
                    onBack = { currentScreen = "explorer" }
                )
            } else if (currentScreen == "transfer_history") {
                TransferHistoryScreen(
                    onBack = { currentScreen = "transfers" }
                )
            } else if (currentScreen == "transfers") {
                TransferQueueDashboard(
                    vm = vm,
                    onBack = { currentScreen = "explorer" },
                    onShowHistory = { currentScreen = "transfer_history" }
                )
            } else if (!isConnected) {
                DesktopNotConnectedView(
                    state = state,
                    isPhysicallyConnected = sessions.isNotEmpty(),
                    physicalDeviceName = sessions.values.firstOrNull()?.deviceName,
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
                    onBack = if (isQueueProcessing) null else { { vm.navigateUp() } }, 
                    onRefresh = if (isQueueProcessing) null else { { vm.refreshRemoteFiles() } },
                    onCreateFolder = if (isQueueProcessing) null else { { showCreateFolderDialog = true } },
                    onCreateTextFile = if (isQueueProcessing) null else { { showCreateTextFileDialog = true } }
                )

                if (isQueueProcessing) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = AppTheme.colors.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = "Info", tint = AppTheme.colors.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "File Explorer is paused while transfers are active to ensure data integrity.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTheme.colors.onSurfaceVariant
                            )
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
                    onFolderClick = { if (!isQueueProcessing) vm.navigateTo(it) },
                    onFilesFetch = { if (!isQueueProcessing) vm.fetchFiles(it) },
                    onFilesDelete = { files -> if (!isQueueProcessing) files.forEach { vm.deleteFile(it) } },
                    onFileRename = { file, newName -> if (!isQueueProcessing) vm.renameFile(file, newName) }
                )

                Spacer(Modifier.height(16.dp))
                
                ActionBar(currentPath = currentPath, onSendFile = {
                    if (!isQueueProcessing) {
                        val fileChooser = JFileChooser()
                        fileChooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                        fileChooser.isMultiSelectionEnabled = true
                        if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            vm.sendFiles(fileChooser.selectedFiles.toList())
                        }
                    }
                })
            }
        }
    }
}

@Composable
fun Sidebar(
    vm: MainViewModel,
    state: String,
    currentScreen: String,
    isConnected: Boolean,
    sessions: Map<String, UsbSessionState>,
    selectedDeviceId: String?,
    onSelectDevice: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        color = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f),
        contentColor = AppTheme.colors.onSurfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Control Panel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            if (isConnected) {
                NavigationItem("File Explorer", Icons.Default.Folder, currentScreen == "explorer") { onNavigate("explorer") }
                Spacer(Modifier.height(8.dp))
                NavigationItem("SmartNav Option", Icons.Default.Explore, currentScreen == "smartnav") { onNavigate("smartnav") }
                Spacer(Modifier.height(24.dp))
            } else {
                Surface(
                    color = AppTheme.colors.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, null, tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connect a device to unlock File Explorer & SmartNav.", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            }

            NavigationItem("Settings", Icons.Default.Settings, currentScreen == "settings") { onNavigate("settings") }
            Spacer(Modifier.height(8.dp))
            NavigationItem("Transfers", Icons.Default.Sync, currentScreen == "transfers") { onNavigate("transfers") }
            Spacer(Modifier.height(24.dp))

            // --- Multi-Device Section ---
            Text(
                "Connected Devices (${sessions.size})",
                style = MaterialTheme.typography.labelLarge,
                color = AppTheme.colors.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                Surface(
                    color = AppTheme.colors.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.UsbOff, null, tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("No devices detected.\nPlug in an Android device via USB.", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(sessions.entries.toList()) { (deviceId, sessionState) ->
                        // Collect queue items for this specific device
                        val deviceQueue = vm.getDeviceQueue(deviceId)
                        val queueItems = deviceQueue?.queue?.collectAsState()?.value ?: emptyList()
                        val isProcessing = deviceQueue?.isProcessing?.collectAsState()?.value ?: false

                        DeviceCard(
                            deviceId = deviceId,
                            sessionState = sessionState,
                            isSelected = deviceId == selectedDeviceId,
                            queueItems = queueItems,
                            isQueueProcessing = isProcessing,
                            onSelect = { onSelectDevice(deviceId) },
                            onConnect = {
                                onSelectDevice(deviceId)
                                vm.connect()
                            },
                            onDisconnect = {
                                onSelectDevice(deviceId)
                                onDisconnect()
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            SecurityInfo()
        }
    }
}

@Composable
fun DeviceCard(
    deviceId: String,
    sessionState: UsbSessionState,
    isSelected: Boolean,
    queueItems: List<TransferQueueItem> = emptyList(),
    isQueueProcessing: Boolean = false,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isReady = sessionState.status is DeviceSessionStatus.Ready
    val isConnecting = sessionState.status is DeviceSessionStatus.Connecting
    val hasError = sessionState.status is DeviceSessionStatus.Error

    // Queue stats
    val activeItem = queueItems.firstOrNull { it.status == TransferItemStatus.ACTIVE }
    val pendingCount = queueItems.count { it.status == TransferItemStatus.PENDING }
    val completedCount = queueItems.count { it.status == TransferItemStatus.COMPLETED }
    val totalCount = queueItems.count { it.status != TransferItemStatus.CANCELLED }
    val hasActiveQueue = isQueueProcessing || activeItem != null

    val containerColor = when {
        isSelected -> AppTheme.colors.primaryContainer.copy(alpha = 0.4f)
        else -> AppTheme.colors.surfaceVariant.copy(alpha = 0.3f)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                val dotColor = when {
                    isReady -> Color(0xFF4CAF50)
                    isConnecting -> Color(0xFFFFA500)
                    hasError -> AppTheme.colors.error
                    else -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Shorten the deviceName for display
                    val shortName = sessionState.deviceName
                        .replace("Android Device (", "")
                        .replace(")", "")
                        .substringBefore(",")
                        .trim()
                    Text(
                        shortName.ifBlank { deviceId },
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = AppTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            isReady -> "Connected ✅"
                            isConnecting -> "Connecting..."
                            hasError -> "Error"
                            else -> "Detected"
                        },
                        fontSize = 10.sp,
                        color = dotColor
                    )
                }

                // Action button
                if (isReady) {
                    IconButton(onClick = onDisconnect, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.LinkOff, "Disconnect", tint = AppTheme.colors.error, modifier = Modifier.size(16.dp))
                    }
                } else if (!isConnecting) {
                    IconButton(onClick = onConnect, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Link, "Connect", tint = AppTheme.colors.primary, modifier = Modifier.size(16.dp))
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            if (isReady && sessionState.isAoaMode) {
                Text(
                    "AOA Protocol",
                    fontSize = 9.sp,
                    color = AppTheme.colors.primary,
                    modifier = Modifier.padding(start = 18.dp, top = 2.dp)
                )
            }

            // --- Inline Transfer Queue Progress ---
            if (hasActiveQueue && isReady) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = AppTheme.colors.surface.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        // Currently transferring file name
                        if (activeItem != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val typeIcon = if (activeItem.type == com.example.securequicktransferapp.domain.model.TransferType.SEND)
                                    Icons.Default.CloudUpload else Icons.Default.Download
                                val typeColor = if (activeItem.type == com.example.securequicktransferapp.domain.model.TransferType.SEND)
                                    Color(0xFF42A5F5) else Color(0xFF66BB6A)
                                Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    activeItem.displayName,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppTheme.colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${activeItem.progress}%",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.primary
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            // Progress bar
                            LinearProgressIndicator(
                                progress = { activeItem.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                color = AppTheme.colors.primary,
                                trackColor = AppTheme.colors.surfaceVariant
                            )
                        }

                        // Queue summary
                        if (pendingCount > 0 || completedCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (completedCount > 0) {
                                    Text(
                                        "✓ $completedCount done",
                                        fontSize = 9.sp,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                                if (completedCount > 0 && pendingCount > 0) {
                                    Text(" · ", fontSize = 9.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f))
                                }
                                if (pendingCount > 0) {
                                    Text(
                                        "$pendingCount queued",
                                        fontSize = 9.sp,
                                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NavigationItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        color = if (selected) AppTheme.colors.primaryContainer else Color.Transparent
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(icon, null, tint = if (selected) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

// ConnectionCard removed — replaced by per-device DeviceCard in Sidebar



@Composable
fun Header(
    title: String, 
    path: String, 
    onBack: (() -> Unit)? = null, 
    onRefresh: (() -> Unit)? = null, 
    onCreateFolder: (() -> Unit)? = null,
    onCreateTextFile: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack ?: {}, enabled = onBack != null, modifier = Modifier.background(if (onBack != null) AppTheme.colors.surfaceVariant else AppTheme.colors.surfaceVariant.copy(alpha = 0.5f), CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = if (onBack != null) AppTheme.colors.onSurface else AppTheme.colors.onSurface.copy(alpha = 0.3f))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
            Text(path, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.secondary)
        }
        if (onCreateFolder != null) {
            IconButton(onClick = onCreateFolder) {
                Icon(Icons.Default.Add, "Create Folder")
            }
        } else {
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Default.Add, "Create Folder", tint = AppTheme.colors.onSurface.copy(alpha = 0.3f))
            }
        }
        if (onCreateTextFile != null) {
            IconButton(onClick = onCreateTextFile) {
                Icon(Icons.Default.EditNote, "Create File")
            }
        } else {
            IconButton(onClick = {}, enabled = false) {
                Icon(Icons.Default.EditNote, "Create File", tint = AppTheme.colors.onSurface.copy(alpha = 0.3f))
            }
        }
        IconButton(onClick = onRefresh ?: {}, enabled = onRefresh != null) {
            Icon(Icons.Default.Refresh, "Refresh", tint = if (onRefresh != null) AppTheme.colors.onSurface else AppTheme.colors.onSurface.copy(alpha = 0.3f))
        }
    }
}

@Composable
fun FileList(
    files: List<RemoteFile>, 
    modifier: Modifier = Modifier,
    onFolderClick: (RemoteFile) -> Unit, 
    onFilesFetch: (List<RemoteFile>) -> Unit,
    onFilesDelete: (List<RemoteFile>) -> Unit,
    onFileRename: (RemoteFile, String) -> Unit
) {
    var selectedFiles by androidx.compose.runtime.remember { mutableStateOf(setOf<RemoteFile>()) }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        color = AppTheme.colors.surface
    ) {
        if (files.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("No compatible files found", color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            Column {
                if (selectedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(AppTheme.colors.primaryContainer).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${selectedFiles.size} items selected", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = AppTheme.colors.onPrimaryContainer)
                        Button(onClick = { 
                            onFilesFetch(selectedFiles.toList())
                            selectedFiles = emptySet()
                        }) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Fetch Selected")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { 
                                onFilesDelete(selectedFiles.toList())
                                selectedFiles = emptySet()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                        ) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete Selected")
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
                            onFileDelete = { onFilesDelete(listOf(it)) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
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
                tint = if (file.isDirectory) Color(0xFFFFC107) else AppTheme.colors.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Medium)
                Text(
                    if (file.isDirectory) "Directory" else SecureQtSdk.Utils.formatSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
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
                            tint = AppTheme.colors.error
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
            color = AppTheme.colors.primaryContainer.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CloudUpload, null, tint = AppTheme.colors.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Upload file to Android device", fontWeight = FontWeight.Medium)
                    Text(
                        "Destination: $currentPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.secondary
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
            Text("ECDH-P256 / AES-GCM", fontSize = 10.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
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
                color = if (isPhysicallyConnected) Color(0xFFE8F5E9) else AppTheme.colors.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPhysicallyConnected) Icons.Default.Usb else Icons.Default.Devices,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (isPhysicallyConnected) Color(0xFF2E7D32) else AppTheme.colors.primary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = if (state == "Searching...") "Searching for USB Device..." else if (isPhysicallyConnected) "USB Device Detected" else "No USB Device Connected",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (isPhysicallyConnected) {
                    "Your device (${physicalDeviceName ?: "Android Device"}) is physically connected via USB cable. Click below to connect and unlock the File Manager & SmartNav Suite."
                } else {
                    "Please connect your Android device via USB cable to access remote file management and the SmartNav package tools."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quick Connection Guide", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppTheme.colors.primary)
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
                    containerColor = if (isPhysicallyConnected) Color(0xFF2E7D32) else AppTheme.colors.primary
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
                    color = AppTheme.colors.error,
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
            color = AppTheme.colors.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(step, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.primary)
            }
        }
        Text(text, fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}
