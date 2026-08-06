package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.BorderStroke
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus

@Composable
fun SmartNavDesktopDashboard(
    vm: MainViewModel,
    onNavigateToExplorerPath: (String) -> Unit
) {
    var stagingDirPath by remember { mutableStateOf(java.io.File(System.getProperty("user.home"), "Downloads/SmartNavStaging").absolutePath) }
    val stagingDir = java.io.File(stagingDirPath)
    
    var stagingDirectories by remember { mutableStateOf(stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) }
    var selectedStagingDirs by remember { mutableStateOf(stagingDirectories.toSet()) }
    
    fun refreshStaging() {
        val newDirs = stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        stagingDirectories = newDirs
        selectedStagingDirs = newDirs.toSet()
    }

    var selectedBasePath by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH) }

    var passwordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_PASSWORD_VALUE) }
    var maintenancePasswordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_MAINTENANCE_PASSWORD_VALUE) }
    var kmmPasswordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_KMM_PASSWORD_VALUE) }

    val sessionsState by vm.sessionsState.collectAsState()
    val connectedDevices = sessionsState.filter { it.value.status is DeviceSessionStatus.Ready }.keys.toList()
    var selectedTargetDevices by remember { mutableStateOf(connectedDevices.toSet()) }
    
    LaunchedEffect(connectedDevices) {
        selectedTargetDevices = selectedTargetDevices.intersect(connectedDevices.toSet())
        if (selectedTargetDevices.isEmpty() && connectedDevices.isNotEmpty()) {
            selectedTargetDevices = connectedDevices.toSet()
        }
    }

    var showOverwriteDialog by remember { mutableStateOf(false) }
    var pendingPushAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingPushFileName by remember { mutableStateOf("") }

    if (showOverwriteDialog) {
        AlertDialog(
            onDismissRequest = { 
                showOverwriteDialog = false 
                pendingPushAction = null
            },
            title = { Text("File Already Exists") },
            text = { Text("The file '$pendingPushFileName' already exists on the remote device. Do you want to overwrite it?") },
            confirmButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    pendingPushAction?.invoke()
                    pendingPushAction = null
                }) { Text("Overwrite", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOverwriteDialog = false
                    pendingPushAction = null
                }) { Text("Cancel") }
            }
        )
    }

    val handlePush = { fileName: String, folder: String, action: () -> Unit ->
        vm.checkRemoteFileExists(folder, fileName) { exists ->
            if (exists) {
                pendingPushFileName = fileName
                pendingPushAction = action
                showOverwriteDialog = true
            } else {
                action()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Info Banner
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppTheme.colors.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, contentDescription = null, tint = AppTheme.colors.onPrimaryContainer, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "SmartNav Management Suite (Desktop Host)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onPrimaryContainer
                    )
                }
                Text(
                    text = "Manage and clone the complete SmartNav V3 directory architecture on the connected device over USB. Based directly on SmartNavRoot.kt specifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTheme.colors.onPrimaryContainer.copy(alpha = 0.9f)
                )

                Spacer(Modifier.height(4.dp))
                Text("Target Base Directory:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppTheme.colors.onPrimaryContainer)
                    FilterChip(
                        selected = selectedBasePath == Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH,
                        onClick = { selectedBasePath = Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH },
                        label = { Text(Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        border = BorderStroke(2.dp, AppTheme.colors.primary)
                    )
            }
        }

        // Package Initialization & Clone Section
        DesktopSectionCard(
            title = "Dynamic SmartNav Package Clone",
            subtitle = "Prepare staging folders locally, edit them, and select which to clone.",
            icon = Icons.Default.CreateNewFolder,
            iconColor = SuccessColor
        ) {
            OutlinedTextField(
                value = stagingDirPath,
                onValueChange = { stagingDirPath = it },
                label = { Text("Local Workspace Directory", fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val chooser = javax.swing.JFileChooser(stagingDirPath)
                            chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                            chooser.dialogTitle = "Select Workspace Directory"
                            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                stagingDirPath = chooser.selectedFile.absolutePath
                                refreshStaging()
                            }
                        }
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse...", tint = AppTheme.colors.primary)
                    }
                }
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { vm.prepareLocalSmartNavStaging(stagingDir) { refreshStaging() } },
                    modifier = Modifier.weight(1.5f).height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(18.dp), tint = AppTheme.colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Base", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = {
                        if (stagingDir.exists()) {
                            java.awt.Desktop.getDesktop().open(stagingDir)
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp), tint = AppTheme.colors.secondary)
                    Spacer(Modifier.width(8.dp))
                    Text("Explore", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                FilledTonalButton(
                    onClick = { refreshStaging() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp), tint = AppTheme.colors.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text("Reload", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (stagingDirectories.isNotEmpty()) {
                Surface(
                    color = AppTheme.colors.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AppTheme.colors.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Select Folders to Clone", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                                selectedStagingDirs = if (selectedStagingDirs.size == stagingDirectories.size) emptySet() else stagingDirectories.toSet()
                            }) {
                                Checkbox(
                                    checked = selectedStagingDirs.size == stagingDirectories.size && stagingDirectories.isNotEmpty(),
                                    onCheckedChange = null, // handled by row click
                                    colors = CheckboxDefaults.colors(checkedColor = AppTheme.colors.primary)
                                )
                                Text("Select All", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            stagingDirectories.forEach { dir ->
                                val isSelected = selectedStagingDirs.contains(dir)
                                Surface(
                                    modifier = Modifier.clickable {
                                        selectedStagingDirs = if (isSelected) selectedStagingDirs - dir else selectedStagingDirs + dir
                                    },
                                    color = if (isSelected) AppTheme.colors.primaryContainer.copy(alpha = 0.3f) else AppTheme.colors.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (isSelected) AppTheme.colors.primary else AppTheme.colors.outlineVariant)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) AppTheme.colors.primary else Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(dir.name, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                        
                        // Device Selection
                        Spacer(Modifier.height(16.dp))
                        if (connectedDevices.isNotEmpty()) {
                            Text("Target Devices for Parallel Clone", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.onSurface)
                            Spacer(Modifier.height(8.dp))
                            @OptIn(ExperimentalLayoutApi::class)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                connectedDevices.forEach { deviceId ->
                                    val isSelected = selectedTargetDevices.contains(deviceId)
                                    val deviceName = sessionsState[deviceId]?.deviceName ?: deviceId
                                    Surface(
                                        modifier = Modifier.clickable {
                                            selectedTargetDevices = if (isSelected) selectedTargetDevices - deviceId else selectedTargetDevices + deviceId
                                        },
                                        color = if (isSelected) SuccessColor.copy(alpha = 0.2f) else AppTheme.colors.surface,
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (isSelected) SuccessColor else AppTheme.colors.outlineVariant)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) SuccessColor else Color.Gray,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(deviceName, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("No devices connected & ready.", color = AppTheme.colors.error, fontSize = 13.sp)
                        }

                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { 
                                if (selectedStagingDirs.isNotEmpty() && selectedTargetDevices.isNotEmpty()) {
                                    vm.sendFilesToDevices(selectedStagingDirs.toList(), selectedTargetDevices, selectedBasePath)
                                }
                            },
                            enabled = selectedStagingDirs.isNotEmpty() && selectedTargetDevices.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Clone ${selectedStagingDirs.size} Folders to ${selectedTargetDevices.size} Device(s)", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        }
                    }
                }
            }
        }

        // Interactive Password Files Creator Section
        DesktopSectionCard(
            title = "Password Files Creator",
            subtitle = "Create & push password files into \$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}/",
            icon = Icons.Default.VpnKey,
            iconColor = WarningColor
        ) {
            DesktopPasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_PASSWORD,
                value = passwordInput,
                onValueChange = { passwordInput = it },
                onPush = {
                    val target = "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}"
                    handlePush(Constants.SmartnavRoot.FILE_PASSWORD, target) {
                        vm.sendTextAsRemoteFile(Constants.SmartnavRoot.FILE_PASSWORD, passwordInput, target)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppTheme.colors.outlineVariant)

            DesktopPasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD,
                value = maintenancePasswordInput,
                onValueChange = { maintenancePasswordInput = it },
                onPush = {
                    val target = "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}"
                    handlePush(Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD, target) {
                        vm.sendTextAsRemoteFile(Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD, maintenancePasswordInput, target)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = AppTheme.colors.outlineVariant)

            DesktopPasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_KMM_PASSWORD,
                value = kmmPasswordInput,
                onValueChange = { kmmPasswordInput = it },
                onPush = {
                    val target = "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}"
                    handlePush(Constants.SmartnavRoot.FILE_KMM_PASSWORD, target) {
                        vm.sendTextAsRemoteFile(Constants.SmartnavRoot.FILE_KMM_PASSWORD, kmmPasswordInput, target)
                    }
                }
            )
        }

        // Quick Jump & Inspection Shortcuts
        DesktopSectionCard(
            title = "Quick Directory Navigation",
            subtitle = "Jump directly inside the File Explorer to inspect SmartNav folders",
            icon = Icons.Default.FolderSpecial,
            iconColor = AppTheme.colors.primary
        ) {
            val quickFolders = listOf(
                Pair("Main Root ($selectedBasePath)", selectedBasePath),
                Pair("Password Dir ($selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}"),
                Pair("Tracks ($selectedBasePath/${Constants.SmartnavRoot.DIR_TRACKS})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_TRACKS}"),
                Pair("Maps Root ($selectedBasePath/${Constants.SmartnavRoot.DIR_MAPS})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_MAPS}"),
                Pair("App Update (${Constants.SmartnavRoot.PATH_APP_UPDATE})", Constants.SmartnavRoot.PATH_APP_UPDATE)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, path) in quickFolders) {
                    OutlinedCard(
                        onClick = { onNavigateToExplorerPath(path) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = WarningColor, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowForward, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = AppTheme.colors.onSurface)
                    Text(subtitle, fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun DesktopPasswordFileRow(
    fileName: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPush: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("File: $fileName", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Password Content", fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = onPush,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Create & Push", fontSize = 12.sp)
            }
        }
    }
}
