package com.example.usbtransferapp.presentation.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.usbtransferapp.domain.constants.Constants
import com.example.usbtransferapp.presentation.viewmodel.UsbTransferViewModel

@Composable
fun SmartNavAndroidDashboard(
    viewModel: UsbTransferViewModel,
    onSwitchToFileManagerAndNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val stagingDir = remember { java.io.File("/sdcard/SmartNavStaging") }
    var stagingDirectories by remember { mutableStateOf(stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()) }
    var selectedStagingDirs by remember { mutableStateOf(stagingDirectories.toSet()) }
    
    fun refreshStaging() {
        val newDirs = stagingDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        stagingDirectories = newDirs
        selectedStagingDirs = newDirs.toSet()
    }

    var selectedBasePath by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH) }

    var passwordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_PASSWORD_VALUE) }
    var maintenancePasswordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_MAINTENANCE_PASSWORD_VALUE) }
    var kmmPasswordInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_KMM_PASSWORD_VALUE) }
    var logCounterInput by remember { mutableStateOf(Constants.SmartnavRoot.DEFAULT_LOG_COUNTER_VALUE) }

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
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Explore, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "SmartNav Management Suite",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    text = "Manage and clone the complete SmartNav V3 directory architecture on the connected device. Based directly on SmartNavRoot.kt specifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )

                // Base Path Selection
                Spacer(Modifier.height(4.dp))
                Text("Target Base Directory:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = selectedBasePath == Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH,
                        onClick = { selectedBasePath = Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH },
                        label = { Text(Constants.SmartnavRoot.DEFAULT_SDCARD_ROOT_PATH, fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedBasePath == Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH,
                        onClick = { selectedBasePath = Constants.SmartnavRoot.DEFAULT_APP_EXTERNAL_ROOT_PATH },
                        label = { Text("App External Files", fontSize = 11.sp) }
                    )
                }
            }
        }

        // Package Initialization & Clone Section
        SmartNavSectionCard(
            title = "Dynamic SmartNav Package Clone",
            subtitle = "Prepare staging folders locally, edit them, and select which to clone.",
            icon = Icons.Default.CreateNewFolder,
            iconColor = Color(0xFF4CAF50)
        ) {
            Text(
                "Staging Location: ${stagingDir.absolutePath}",
                fontSize = 11.sp,
                color = Color.Gray
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { 
                        viewModel.prepareLocalSmartNavStaging(stagingDir) { refreshStaging() }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Initialize Staging", fontSize = 11.sp)
                }
                Button(
                    onClick = { refreshStaging() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh List", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (stagingDirectories.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Select folders to clone:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedStagingDirs.size == stagingDirectories.size && stagingDirectories.isNotEmpty(),
                            onCheckedChange = { checked ->
                                selectedStagingDirs = if (checked) stagingDirectories.toSet() else emptySet()
                            }
                        )
                        Text("Select All", fontSize = 12.sp)
                    }
                }
                stagingDirectories.forEach { dir ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedStagingDirs.contains(dir),
                            onCheckedChange = { checked ->
                                selectedStagingDirs = if (checked) selectedStagingDirs + dir else selectedStagingDirs - dir
                            }
                        )
                        Text(dir.name, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { 
                        if (selectedStagingDirs.isNotEmpty()) {
                            viewModel.sendMultipleFiles(selectedStagingDirs.toList(), selectedBasePath)
                        }
                    },
                    enabled = selectedStagingDirs.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clone Selected to Device", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Interactive Password Files Creator Section
        SmartNavSectionCard(
            title = "Password Files Creator",
            subtitle = "Create & push password files into \$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}/",
            icon = Icons.Default.VpnKey,
            iconColor = Color(0xFFFFA500)
        ) {
            // password.txt
            PasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_PASSWORD,
                value = passwordInput,
                onValueChange = { passwordInput = it },
                onPush = {
                    viewModel.sendTextAsRemoteFile(context, Constants.SmartnavRoot.FILE_PASSWORD, passwordInput, "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // maintenancepassword.txt
            PasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD,
                value = maintenancePasswordInput,
                onValueChange = { maintenancePasswordInput = it },
                onPush = {
                    viewModel.sendTextAsRemoteFile(context, Constants.SmartnavRoot.FILE_MAINTENANCE_PASSWORD, maintenancePasswordInput, "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}")
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

            // kmmpassword.txt
            PasswordFileRow(
                fileName = Constants.SmartnavRoot.FILE_KMM_PASSWORD,
                value = kmmPasswordInput,
                onValueChange = { kmmPasswordInput = it },
                onPush = {
                    viewModel.sendTextAsRemoteFile(context, Constants.SmartnavRoot.FILE_KMM_PASSWORD, kmmPasswordInput, "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}")
                }
            )
        }

        // Log Counter Creator Section
        SmartNavSectionCard(
            title = "Log Manager & Counter",
            subtitle = "Create or reset ${Constants.SmartnavRoot.FILE_LOG_COUNTER} inside \$selectedBasePath/${Constants.SmartnavRoot.DIR_LOG_MANAGER}/",
            icon = Icons.Default.ReceiptLong,
            iconColor = MaterialTheme.colorScheme.primary
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = logCounterInput,
                    onValueChange = { logCounterInput = it },
                    label = { Text("Log Counter Value", fontSize = 12.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        viewModel.sendTextAsRemoteFile(context, Constants.SmartnavRoot.FILE_LOG_COUNTER, logCounterInput, "$selectedBasePath/${Constants.SmartnavRoot.DIR_LOG_MANAGER}")
                    },
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Push File")
                }
            }
        }

        // Quick Jump & Inspection Shortcuts
        SmartNavSectionCard(
            title = "Quick Directory Navigation",
            subtitle = "Jump directly inside the File Manager to inspect SmartNav folders",
            icon = Icons.Default.FolderSpecial,
            iconColor = Color(0xFF0288D1)
        ) {
            val quickFolders = listOf(
                Pair("Main Root ($selectedBasePath)", selectedBasePath),
                Pair("Password Dir ($selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_PASSWORD}"),
                Pair("Tracks ($selectedBasePath/${Constants.SmartnavRoot.DIR_TRACKS})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_TRACKS}"),
                Pair("Maps Root ($selectedBasePath/${Constants.SmartnavRoot.DIR_MAPS})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_MAPS}"),
                Pair("Crash Logs ($selectedBasePath/${Constants.SmartnavRoot.DIR_DEV_LOGS}/${Constants.SmartnavRoot.DIR_CRASH_LOGS})", "$selectedBasePath/${Constants.SmartnavRoot.DIR_DEV_LOGS}/${Constants.SmartnavRoot.DIR_CRASH_LOGS}"),
                Pair("App Update (${Constants.SmartnavRoot.PATH_APP_UPDATE})", Constants.SmartnavRoot.PATH_APP_UPDATE)
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, path) in quickFolders) {
                    OutlinedCard(
                        onClick = { onSwitchToFileManagerAndNavigate(path) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFFFA000), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowForwardIos, null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SmartNavSectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, fontSize = 11.sp, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun PasswordFileRow(
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
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Create & Push", fontSize = 12.sp)
            }
        }
    }
}
