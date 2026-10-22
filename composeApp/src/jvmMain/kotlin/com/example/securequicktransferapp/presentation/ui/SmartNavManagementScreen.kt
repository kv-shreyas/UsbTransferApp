package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.presentation.theme.AppTheme
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import java.io.File
import com.example.securequicktransferapp.presentation.ui.components.ElegantFilePicker
import com.example.securequicktransferapp.presentation.ui.components.PickerMode

@Composable
fun SmartNavManagementScreen(
    vm: MainViewModel,
    onBack: () -> Unit
) {
    val sessionsState by vm.sessionsState.collectAsState()
    val connectedDevices = sessionsState.filter { it.value.status is DeviceSessionStatus.Ready }.keys.toList()
    var selectedTargetDevices by remember { mutableStateOf(connectedDevices.toSet()) }

    LaunchedEffect(connectedDevices) {
        selectedTargetDevices = selectedTargetDevices.intersect(connectedDevices.toSet())
        if (selectedTargetDevices.isEmpty() && connectedDevices.isNotEmpty()) {
            selectedTargetDevices = connectedDevices.toSet()
        }
    }

    var showPicker by remember { mutableStateOf(false) }
    var pickerTitle by remember { mutableStateOf("") }
    var pickerMode by remember { mutableStateOf(PickerMode.FILES_AND_DIRECTORIES) }
    var pickerAllowMultiple by remember { mutableStateOf(true) }
    var onFilesPicked by remember { mutableStateOf<(List<File>) -> Unit>({}) }

    var pendingConflictAction by remember { mutableStateOf<((com.example.securequicktransferapp.data.usb.ConflictResolution) -> Unit)?>(null) }
    var pendingMessage by remember { mutableStateOf("") }
    var showConflictDialog by remember { mutableStateOf(false) }

    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { 
                showConflictDialog = false
                pendingConflictAction = null
            },
            title = { Text("File Conflict Detected") },
            text = { Text(pendingMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConflictAction?.invoke(com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE_ALL)
                        showConflictDialog = false
                        pendingConflictAction = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                ) {
                    Text("Overwrite")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { 
                        showConflictDialog = false
                        pendingConflictAction = null
                    }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            pendingConflictAction?.invoke(com.example.securequicktransferapp.data.usb.ConflictResolution.SKIP_ALL)
                            showConflictDialog = false
                            pendingConflictAction = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
                    ) {
                        Text("Skip")
                    }
                }
            }
        )
    }

    val checkSingleFile = { fileName: String, targetFolder: String, action: (com.example.securequicktransferapp.data.usb.ConflictResolution?) -> Unit ->
        vm.checkRemoteFileExists(targetFolder, fileName, targetDeviceIds = selectedTargetDevices) { exists ->
            if (exists) {
                pendingMessage = "The file '$fileName' already exists on the selected remote device(s). How should this existing file be handled?"
                pendingConflictAction = { resolution -> action(resolution) }
                showConflictDialog = true
            } else {
                action(null)
            }
        }
    }

    val checkMultipleFiles = { files: List<File>, targetFolder: String, action: (com.example.securequicktransferapp.data.usb.ConflictResolution?) -> Unit ->
        val targetDeviceIds = if (selectedTargetDevices.isNotEmpty()) selectedTargetDevices else vm.sessionsState.value.keys
        vm.checkCloneConflicts(files, targetDeviceIds, targetFolder) { conflicts ->
            if (conflicts.isNotEmpty()) {
                val count = conflicts.flatMap { it.folders }.flatMap { it.files }.size
                pendingMessage = "$count file(s) already exist in the target directory on the selected remote device(s). How should existing files be handled?"
                pendingConflictAction = { resolution -> action(resolution) }
                showConflictDialog = true
            } else {
                action(null)
            }
        }
    }
    var password by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_PASSWORD_VALUE) }
    var maintenancePassword by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_MAINTENANCE_PASSWORD_VALUE) }
    var kmmPassword by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_KMM_PASSWORD_VALUE) }
    var securityIdPassword by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_SECURITY_ID_PASSWORD_VALUE) }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Top Header Banner
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTheme.colors.primary)
            }
            Spacer(Modifier.width(16.dp))
            Surface(
                color = AppTheme.colors.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = AppTheme.colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "SmartNav Advanced Management",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onPrimaryContainer
                    )
                }
            }
        }

        // Target Devices Section
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
            colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = AppTheme.colors.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Target Devices", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            color = if (selectedTargetDevices.isNotEmpty()) AppTheme.colors.primaryContainer else AppTheme.colors.error,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "${selectedTargetDevices.size} / ${connectedDevices.size} selected",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (selectedTargetDevices.isNotEmpty()) AppTheme.colors.onPrimaryContainer else AppTheme.colors.error,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (connectedDevices.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = { selectedTargetDevices = connectedDevices.toSet() },
                                enabled = selectedTargetDevices.size != connectedDevices.size
                            ) {
                                Text("Select All")
                            }
                            TextButton(
                                onClick = { selectedTargetDevices = emptySet() },
                                enabled = selectedTargetDevices.isNotEmpty()
                            ) {
                                Text("Deselect All")
                            }
                        }
                    }
                }

                if (connectedDevices.isEmpty()) {
                    Text(
                        "No connected devices ready. Connect a device via USB to upload or fetch files.",
                        fontSize = 13.sp,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                } else {
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        connectedDevices.forEach { deviceId ->
                            val isSelected = selectedTargetDevices.contains(deviceId)
                            val sessionName = sessionsState[deviceId]?.deviceName
                            val deviceName = if (sessionName.isNullOrBlank()) deviceId else sessionName
                            val shortName = deviceName
                                .replace("Android Device (", "")
                                .replace(")", "")
                                .substringBefore(",")
                                .trim()

                            OutlinedCard(
                                onClick = {
                                    selectedTargetDevices = if (isSelected) {
                                        selectedTargetDevices - deviceId
                                    } else {
                                        selectedTargetDevices + deviceId
                                    }
                                },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (isSelected) SuccessColor.copy(alpha = 0.15f) else AppTheme.colors.surface
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) SuccessColor else AppTheme.colors.outlineVariant),
                                modifier = Modifier.width(180.dp).height(80.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Smartphone,
                                            contentDescription = null,
                                            tint = if (isSelected) SuccessColor else AppTheme.colors.onSurfaceVariant,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = null,
                                            tint = if (isSelected) SuccessColor else Color.Gray.copy(alpha = 0.5f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Text(
                                        text = shortName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTheme.colors.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Interactive Password Files Creator Section
            OutlinedCard(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VpnKey, null, tint = AppTheme.colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Interactive Password Creator", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Configure device access credentials below. These will be generated and uploaded to the SmartNav system.", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
                    
                    PasswordUploadRow(
                        label = "Password",
                        value = password,
                        onValueChange = { password = it },
                        fileName = Constants.SmartnavRoot.FILE_PASSWORD,
                        vm = vm,
                        targetDeviceIds = selectedTargetDevices,
                        onCheckConflict = checkSingleFile
                    )
                    PasswordUploadRow(
                        label = "Maintenance Password",
                        value = maintenancePassword,
                        onValueChange = { maintenancePassword = it },
                        fileName = Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD,
                        vm = vm,
                        targetDeviceIds = selectedTargetDevices,
                        onCheckConflict = checkSingleFile
                    )
                    PasswordUploadRow(
                        label = "KMM Password",
                        value = kmmPassword,
                        onValueChange = { kmmPassword = it },
                        fileName = Constants.SmartnavRoot.FILE_KMM_PASSWORD,
                        vm = vm,
                        targetDeviceIds = selectedTargetDevices,
                        onCheckConflict = checkSingleFile
                    )
                    PasswordUploadRow(
                        label = "Security ID Password",
                        value = securityIdPassword,
                        onValueChange = { securityIdPassword = it },
                        fileName = Constants.SmartnavRoot.FILE_SECURITY_ID_PASSWORD,
                        vm = vm,
                        targetDeviceIds = selectedTargetDevices,
                        onCheckConflict = checkSingleFile
                    )
                }
            }

            // System Asset Uploader
            OutlinedCard(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdateAlt, null, tint = AppTheme.colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("System Assets Uploader", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Select critical assets from your desktop to transfer directly to the respective SmartNav directories.", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
                    
                    AssetUploadRow(title = "Vector Maps", icon = Icons.Default.Map, vm = vm, targetDir = "${Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH}/${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_VECTOR}", targetDeviceIds = selectedTargetDevices, onCheckConflict = checkMultipleFiles, onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                    AssetUploadRow(title = "Raster Maps", icon = Icons.Default.Map, vm = vm, targetDir = "${Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH}/${Constants.SmartnavRoot.DIR_MAPS}/${Constants.SmartnavRoot.DIR_MAPS_RASTER}", targetDeviceIds = selectedTargetDevices, onCheckConflict = checkMultipleFiles, onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                    AssetUploadRow(title = "Firmware Upgrade", icon = Icons.Default.Memory, vm = vm, targetDir = "${Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH}/${Constants.SmartnavRoot.DIR_FIRMWARE_UPGRADE}", targetDeviceIds = selectedTargetDevices, onCheckConflict = checkMultipleFiles, onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                    AssetUploadRow(title = "OS Update", icon = Icons.Default.SystemUpdate, vm = vm, targetDir = "${Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH}/${Constants.SmartnavRoot.DIR_OS_UPDATE}", targetDeviceIds = selectedTargetDevices, onCheckConflict = checkMultipleFiles, onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                    AssetUploadRow(title = "App Update", icon = Icons.Default.Apps, vm = vm, targetDir = "${Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH}/${Constants.SmartnavRoot.DIR_APP_UPDATE}", targetDeviceIds = selectedTargetDevices, onCheckConflict = checkMultipleFiles, onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                }
            }
        }

        // Logs Downloader
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
            colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDownload, null, tint = AppTheme.colors.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Device Logs Downloader", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("Pull diagnostic and operational logs from the connected SmartNav device directly to your desktop.", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
                
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LogFetchRow(title = "Dev Logs", dirName = Constants.SmartnavRoot.DIR_DEV_LOGS, vm = vm, targetDeviceIds = selectedTargetDevices, modifier = Modifier.weight(1f), onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                    LogFetchRow(title = "GNSS Data Logs", dirName = Constants.SmartnavRoot.DIR_GNSS_DATA_LOGS, vm = vm, targetDeviceIds = selectedTargetDevices, modifier = Modifier.weight(1f), onRequestPicker = { t, m, am, r -> pickerTitle = t; pickerMode = m; pickerAllowMultiple = am; onFilesPicked = r; showPicker = true })
                }
            }
        }
    }
    
    if (showPicker) {
        ElegantFilePicker(
            title = pickerTitle,
            mode = pickerMode,
            allowMultiple = pickerAllowMultiple,
            onFileSelected = onFilesPicked,
            onClose = { showPicker = false }
        )
    }
}
}

@Composable
private fun AssetUploadRow(
    title: String, 
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    vm: MainViewModel, 
    targetDir: String, 
    targetDeviceIds: Set<String>,
    onCheckConflict: (List<File>, String, (com.example.securequicktransferapp.data.usb.ConflictResolution?) -> Unit) -> Unit,
    onRequestPicker: (title: String, mode: PickerMode, allowMultiple: Boolean, onResult: (List<File>) -> Unit) -> Unit
) {
    val isEnabled = targetDeviceIds.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth().background(AppTheme.colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AppTheme.colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                if (!isEnabled) return@OutlinedButton
                onRequestPicker("Select file(s) for $title", PickerMode.FILES_AND_DIRECTORIES, true) { files ->
                    if (files.isNotEmpty()) {
                        onCheckConflict(files, targetDir) { resolution ->
                            vm.sendFiles(files, targetDir, conflictResolution = resolution, targetDeviceIds = targetDeviceIds)
                        }
                    }
                }
            },
            enabled = isEnabled,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text("Select & Upload")
        }
    }
}

@Composable
private fun PasswordUploadRow(
    label: String, 
    value: String, 
    onValueChange: (String) -> Unit, 
    fileName: String, 
    vm: MainViewModel, 
    targetDeviceIds: Set<String>,
    onCheckConflict: (String, String, (com.example.securequicktransferapp.data.usb.ConflictResolution?) -> Unit) -> Unit
) {
    val isEnabled = targetDeviceIds.isNotEmpty()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            shape = RoundedCornerShape(8.dp)
        )
        Button(
            onClick = {
                if (!isEnabled) return@Button
                val target = "${Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH}/${Constants.SmartnavRoot.DIR_PASSWORD}"
                onCheckConflict(fileName, target) { resolution ->
                    if (resolution == com.example.securequicktransferapp.data.usb.ConflictResolution.SKIP ||
                        resolution == com.example.securequicktransferapp.data.usb.ConflictResolution.SKIP_ALL) {
                        return@onCheckConflict
                    }
                    vm.sendTextAsRemoteFile(
                        fileName = fileName,
                        content = value,
                        targetFolder = target,
                        overwrite = (resolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE ||
                                    resolution == com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE_ALL),
                        targetDeviceIds = targetDeviceIds
                    )
                }
            },
            enabled = isEnabled,
            modifier = Modifier.height(56.dp).padding(top = 8.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Upload")
        }
    }
}

@Composable
private fun LogFetchRow(
    title: String, 
    dirName: String, 
    vm: MainViewModel, 
    targetDeviceIds: Set<String>,
    modifier: Modifier = Modifier,
    onRequestPicker: (title: String, mode: PickerMode, allowMultiple: Boolean, onResult: (List<File>) -> Unit) -> Unit
) {
    val isEnabled = targetDeviceIds.isNotEmpty()
    OutlinedCard(
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surfaceVariant.copy(alpha = 0.3f)),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(dirName, fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant)
            }
            Button(
                onClick = {
                    if (!isEnabled) return@Button
                    onRequestPicker("Select Destination Folder for $title", PickerMode.DIRECTORIES_ONLY, false) { files ->
                        val dest = files.firstOrNull()
                        if (dest != null) {
                            val remoteDir = com.example.securequicktransferapp.domain.model.RemoteFile(
                                name = dirName,
                                isDirectory = true,
                                size = 0,
                                path = "${Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH}/$dirName"
                            )
                            vm.fetchFiles(listOf(remoteDir), dest, targetDeviceIds = targetDeviceIds)
                        }
                    }
                },
                enabled = isEnabled,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fetch to Desktop")
            }
        }
    }
}
