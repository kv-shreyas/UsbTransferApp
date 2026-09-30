package com.example.securequicktransferapp.presentation.ui
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.BorderStroke
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
    onNavigateToManagement: () -> Unit,
    onNavigateToExplorerPath: (String) -> Unit
) {
    val stagingDirPath by vm.stagingDirPath.collectAsState()
    val stagingDir = java.io.File(stagingDirPath)
    
    var stagingDirectories by remember { mutableStateOf(stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) }
    var selectedStagingDirs by remember { mutableStateOf(stagingDirectories.toSet()) }
    
    fun refreshStaging() {
        val newDirs = stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        stagingDirectories = newDirs
        selectedStagingDirs = selectedStagingDirs.intersect(newDirs.toSet())
    }
    
    LaunchedEffect(stagingDirPath) {
        val newDirs = stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        stagingDirectories = newDirs
        selectedStagingDirs = newDirs.toSet()
    }

    var selectedBasePath by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH) }


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

    var showConflictDialog by remember { mutableStateOf(false) }
    var showNormalCloneDialog by remember { mutableStateOf(false) }
    var isCheckingConflicts by remember { mutableStateOf(false) }
    var conflictDetailsList by remember { mutableStateOf<List<com.example.securequicktransferapp.presentation.vm.DeviceConflictInfo>>(emptyList()) }

    if (showConflictDialog) {
        AlertDialog(
            modifier = Modifier.widthIn(min = 450.dp, max = 1200.dp).width(if (conflictDetailsList.size > 1) 900.dp else 450.dp).wrapContentHeight(),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { showConflictDialog = false },
            title = { Text("Confirm Global Conflict Resolution") },
            text = { 
                Column {
                    Text("You are about to queue ${selectedStagingDirs.size} folders to ${selectedTargetDevices.size} device(s).")
                    Spacer(Modifier.height(8.dp))
                    Text("Conflicts detected! The following files already exist on the target devices:", fontWeight = FontWeight.Bold, color = WarningColor)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .heightIn(max = 350.dp)
                            .fillMaxWidth()
                            .background(AppTheme.colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        val vScrollState = androidx.compose.foundation.rememberScrollState()
                        val hScrollState = androidx.compose.foundation.rememberScrollState()
                        
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 12.dp) // Leave room for horizontal scrollbar
                                .verticalScroll(vScrollState)
                                .horizontalScroll(hScrollState),
                            horizontalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            conflictDetailsList.forEach { deviceConflict ->
                                Column(modifier = Modifier.width(300.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppTheme.colors.primary)
                                        Spacer(Modifier.width(6.dp))
                                        Text(deviceConflict.deviceName, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppTheme.colors.onSurface)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    
                                    deviceConflict.folders.forEach { folderConflict ->
                                        Row(modifier = Modifier.padding(start = 22.dp)) {
                                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.colors.secondary)
                                            Spacer(Modifier.width(6.dp))
                                            Text(folderConflict.folderPath, fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Medium)
                                        }
                                        
                                        folderConflict.files.forEach { file ->
                                            Row(modifier = Modifier.padding(start = 42.dp, top = 2.dp, bottom = 2.dp)) {
                                                Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.Gray)
                                                Spacer(Modifier.width(6.dp))
                                                Text(file, fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant)
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                }
                            }
                        }
                        
                        // Explicit horizontal scrollbar placed at the bottom of the Box
                        androidx.compose.foundation.HorizontalScrollbar(
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
                            adapter = androidx.compose.foundation.rememberScrollbarAdapter(hScrollState)
                        )
                        // Explicit vertical scrollbar placed at the end of the Box
                        androidx.compose.foundation.VerticalScrollbar(
                            modifier = Modifier.fillMaxHeight().align(Alignment.CenterEnd).padding(bottom = 12.dp),
                            adapter = androidx.compose.foundation.rememberScrollbarAdapter(vScrollState)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("How should these existing files be handled?")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.sendFilesToDevices(
                            selectedStagingDirs.toList(), 
                            selectedTargetDevices, 
                            selectedBasePath,
                            com.example.securequicktransferapp.data.usb.ConflictResolution.OVERWRITE_ALL
                        )
                        showConflictDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                ) {
                    Text("Overwrite Existing")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showConflictDialog = false }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            vm.sendFilesToDevices(
                                selectedStagingDirs.toList(), 
                                selectedTargetDevices, 
                                selectedBasePath,
                                com.example.securequicktransferapp.data.usb.ConflictResolution.SKIP_ALL
                            )
                            showConflictDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
                    ) {
                        Text("Skip Existing")
                    }
                }
            }
        )
    }

    if (showNormalCloneDialog) {
        AlertDialog(
            onDismissRequest = { showNormalCloneDialog = false },
            title = { Text("Confirm Transfer") },
            text = { Text("You are about to queue ${selectedStagingDirs.size} folders to ${selectedTargetDevices.size} device(s).\n\nNo file conflicts were detected. Proceed with transfer?") },
            confirmButton = {
                Button(
                    onClick = {
                        vm.sendFilesToDevices(
                            selectedStagingDirs.toList(), 
                            selectedTargetDevices, 
                            selectedBasePath,
                            null
                        )
                        showNormalCloneDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary)
                ) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNormalCloneDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 32.dp, top = 24.dp, bottom = 24.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {


        OutlinedCard(
            modifier = Modifier.fillMaxWidth().clickable { onNavigateToManagement() },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f)),
            colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.primaryContainer.copy(alpha = 0.1f))
        ) {
            Row(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(40.dp))
                Spacer(Modifier.width(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("SmartNav Advanced Management", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("Interactive Password Creator & Asset Uploader (Maps, Firmware, OS, App Updates)", fontSize = 13.sp, color = AppTheme.colors.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppTheme.colors.primary)
            }
        }

        Spacer(Modifier.height(16.dp))
            if (stagingDirectories.isNotEmpty()) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
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
                                OutlinedCard(
                                    onClick = { selectedStagingDirs = if (isSelected) selectedStagingDirs - dir else selectedStagingDirs + dir },
                                    colors = CardDefaults.outlinedCardColors(
                                        containerColor = if (isSelected) AppTheme.colors.primaryContainer.copy(alpha = 0.3f) else AppTheme.colors.surface
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(2.dp, if (isSelected) AppTheme.colors.primary else AppTheme.colors.outlineVariant),
                                    modifier = Modifier.width(200.dp).height(140.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(18.dp),
                                        verticalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                            Icon(Icons.Default.Folder, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(48.dp))
                                            Icon(
                                                if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (isSelected) AppTheme.colors.primary else Color.Gray.copy(alpha = 0.5f),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        Text(
                                            text = dir.name, 
                                            fontSize = 16.sp, 
                                            fontWeight = FontWeight.ExtraBold, 
                                            color = AppTheme.colors.onSurface, 
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
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
                                    val sessionName = sessionsState[deviceId]?.deviceName
                                    val deviceName = if (sessionName.isNullOrBlank()) deviceId else sessionName
                                    OutlinedCard(
                                        onClick = { selectedTargetDevices = if (isSelected) selectedTargetDevices - deviceId else selectedTargetDevices + deviceId },
                                        colors = CardDefaults.outlinedCardColors(
                                            containerColor = if (isSelected) SuccessColor.copy(alpha = 0.2f) else AppTheme.colors.surface
                                        ),
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(2.dp, if (isSelected) SuccessColor else AppTheme.colors.outlineVariant),
                                        modifier = Modifier.width(200.dp).height(140.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(18.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                                                Icon(Icons.Default.Smartphone, contentDescription = null, tint = SuccessColor, modifier = Modifier.size(48.dp))
                                                Icon(
                                                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = null,
                                                    tint = if (isSelected) SuccessColor else Color.Gray.copy(alpha = 0.5f),
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            val shortName = deviceName
                                                .replace("Android Device (", "")
                                                .replace(")", "")
                                                .substringBefore(",")
                                                .trim()
                                            Text(
                                                text = shortName,
                                                fontSize = 16.sp, 
                                                fontWeight = FontWeight.ExtraBold, 
                                                color = AppTheme.colors.onSurface, 
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
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
                                    isCheckingConflicts = true
                                    vm.checkCloneConflicts(
                                        files = selectedStagingDirs.toList(),
                                        targetDeviceIds = selectedTargetDevices,
                                        defaultTargetPath = selectedBasePath
                                    ) { conflicts ->
                                        isCheckingConflicts = false
                                        if (conflicts.isNotEmpty()) {
                                            conflictDetailsList = conflicts
                                            showConflictDialog = true
                                        } else {
                                            showNormalCloneDialog = true
                                        }
                                    }
                                }
                            },
                            enabled = selectedStagingDirs.isNotEmpty() && selectedTargetDevices.isNotEmpty() && !isCheckingConflicts,
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            if (isCheckingConflicts) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AppTheme.colors.onPrimary, strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Checking for conflicts...", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Clone ${selectedStagingDirs.size} Folders to ${selectedTargetDevices.size} Device(s)", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }



        }
        
        // Right side pane for TransferQueueDashboard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 16.dp, top = 24.dp, bottom = 24.dp, end = 32.dp)
        ) {
            OutlinedCard(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                colors = CardDefaults.outlinedCardColors(containerColor = AppTheme.colors.surface)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Active Transfers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTheme.colors.primary)
                    }
                    Spacer(Modifier.height(12.dp))
                    TransferQueueDashboard(
                        showHeader = false,
                        onBack = {}
                    )
                }
            }
        }
    }
}