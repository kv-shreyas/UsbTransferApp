package com.example.securequicktransferapp.presentation.ui

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.securequicktransferapp.data.UsbRole
import com.example.securequicktransferapp.data.UsbUiState
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.presentation.theme.AppTheme
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.viewmodel.UsbTransferViewModel


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    viewModel: UsbTransferViewModel = hiltViewModel(),
    isDarkTheme: Boolean = true,
    onThemeToggle: () -> Unit = {}
) {
    val TAG = "TransferScreen"
    val state by viewModel.uiState.collectAsState()
    val isCableConnected by viewModel.isCablePhysicallyConnected.collectAsState()
    val role by viewModel.usbRole.collectAsState()
    val progressState by viewModel.progressState.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val logFilePath = viewModel.getLogFilePath()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.detectAndConnect() }

    val connectedNameDialog = (state as? UsbUiState.Success)?.deviceId?.takeIf { it.isNotBlank() } ?: when (role) {
        is UsbRole.Host -> (role as UsbRole.Host).connectedDeviceName
        is UsbRole.Client -> (role as UsbRole.Client).connectedHostName
        null -> null
    }

    if (progressState.isVisible) {
        AndroidTransferProgressDialog(
            progress = progressState,
            connectedName = connectedNameDialog,
            onCancel = { viewModel.cancelTransfer() },
            onDismiss = { viewModel.dismissProgress() }
        )
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = AppTheme.colors.primary) },
            title = { Text("Reset Connection?") },
            text = {
                Text("This will disconnect the active session, clear saved roles, stop background services, and reset the connection state to start fresh and ready to connect to any device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        viewModel.resetConnection()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                ) {
                    Text("Reset & Start Fresh")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSettingsScreen) {
        val isUnpluggedFromHost = !isCableConnected || state is UsbUiState.NoDevice
        SettingsScreen(
            isDarkTheme = isDarkTheme,
            onThemeToggle = onThemeToggle,
            onBack = { showSettingsScreen = false },
            onShowAbout = { showAboutDialog = true },
            onExitApp = { (context as? android.app.Activity)?.finishAffinity() },
            extraSettingsContent = if (isUnpluggedFromHost) {
                {
                    SettingsSection(title = "Connection Management", icon = Icons.Default.Usb) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Reset active USB connection, clear cached roles, and reset state to connect to a fresh device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    showSettingsScreen = false
                                    viewModel.resetConnection()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Reset Connection & Start Fresh")
                            }
                        }
                    }
                }
            } else null
        )
        if (showAboutDialog) {
            AboutAppDialog(
                onDismiss = { showAboutDialog = false },
                onUpdate = { viewModel.installAppUpdate(context) }
            )
        }
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Secure Transfer", fontWeight = FontWeight.ExtraBold)
                        val connectedName = (state as? UsbUiState.Success)?.deviceId?.takeIf { it.isNotBlank() } ?: when (role) {
                            is UsbRole.Host -> (role as UsbRole.Host).connectedDeviceName
                            is UsbRole.Client -> (role as UsbRole.Client).connectedHostName
                            null -> null
                        }
                        Log.i(TAG, "Connected Name: $connectedName | State: $state")
                        val isConnectedState = state is UsbUiState.Success || state is UsbUiState.Receiving || state is UsbUiState.Transferring
                        if (connectedName != null && isConnectedState) {
                            Text(
                                text = "Connected: $connectedName",
                                fontSize = 12.sp,
                                color = AppTheme.colors.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = AppTheme.colors.onSurface
                ),
                actions = {
                    IconButton(onClick = { showSettingsScreen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppTheme.colors.surface)
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = {
                        fadeIn() + scaleIn(initialScale = 0.95f) togetherWith fadeOut() + scaleOut(targetScale = 0.95f)
                    }
                ) { targetState ->
                    if (targetState is UsbUiState.Success && role is UsbRole.Host) {
                        HostModeDashboard(
                            role = role as UsbRole.Host,
                            onReset = { showResetConfirmDialog = true }
                        )
                    } else {
                        UnifiedStateCard(
                            state = targetState,
                            role = role,
                            isCableConnected = isCableConnected,
                            onConnect = { viewModel.requestPermissionAndConnect() },
                            onSelectRole = { viewModel.selectRoleAndConnect(it) },
                            onReset = { showResetConfirmDialog = true }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            DebugLogsViewer(
                logLines = logLines,
                logFilePath = logFilePath,
                onClear = { viewModel.clearLogs() }
            )
            Spacer(Modifier.height(16.dp))
            SecurityFooter()
        }
    }
}


@Composable
fun AboutAppDialog(onDismiss: () -> Unit, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (e: Exception) {
        null
    }
    val versionName = packageInfo?.versionName ?: "Unknown"
    
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null, tint = AppTheme.colors.primary) },
        title = { Text("About") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("SmartNav USB Manager", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Version: $versionName", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = onUpdate) {
                Text("Update App")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
@Composable
fun DebugLogsViewer(
    logLines: List<String>,
    logFilePath: String,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connection Diagnostics & Logs (${logLines.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded) {
                        TextButton(onClick = onClear, contentPadding = PaddingValues(2.dp)) {
                            Text("Clear", fontSize = 11.sp, color = AppTheme.colors.error)
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Logs"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = AppTheme.colors.outlineVariant)
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "File written to: $logFilePath",
                        fontSize = 10.sp,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    val listState = rememberLazyListState()
                    LaunchedEffect(logLines.size) {
                        if (logLines.isNotEmpty()) {
                            listState.animateScrollToItem(logLines.lastIndex)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E1E1E))
                            .padding(8.dp)
                    ) {
                        if (logLines.isEmpty()) {
                            Text("No log messages yet...", color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(logLines) { line ->
                                    val textColor = when {
                                        line.contains("[ERROR]") || line.contains("failed", ignoreCase = true) -> Color(0xFFFF6B6B)
                                        line.contains("[WARN]") -> Color(0xFFFFA500)
                                        line.contains("SUCCESS", ignoreCase = true) -> Color(0xFF69DB7C)
                                        else -> Color(0xFFE0E0E0)
                                    }
                                    Text(
                                        text = line,
                                        color = textColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
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
fun HostModeDashboard(
    role: UsbRole.Host? = null,
    onNavigateDir: ((String) -> Unit)? = null,
    onReset: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedOption by remember { mutableStateOf("fileManager") }

    Column(modifier = Modifier.fillMaxSize()) {
        if (role != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Connected to ${role.connectedDeviceName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.primary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                if (onReset != null) {
                    TextButton(
                        onClick = onReset,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = AppTheme.colors.error)
                        Spacer(Modifier.width(4.dp))
                        Text("Reset", color = AppTheme.colors.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        // Top Option Selector / Tab Bar
        Surface(
            color = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isFileManager = selectedOption == "fileManager"
                Button(
                    onClick = { selectedOption = "fileManager" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFileManager) AppTheme.colors.primary else Color.Transparent,
                        contentColor = if (isFileManager) AppTheme.colors.onPrimary else AppTheme.colors.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("File Manager", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                val isSmartNav = selectedOption == "smartnav"
                Button(
                    onClick = { selectedOption = "smartnav" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSmartNav) AppTheme.colors.primary else Color.Transparent,
                        contentColor = if (isSmartNav) AppTheme.colors.onPrimary else AppTheme.colors.onSurfaceVariant
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f).height(42.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Explore, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("SmartNav Option", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedOption == "smartnav") {
                SmartNavAndroidDashboard(
                    onSwitchToFileManagerAndNavigate = { targetPath ->
                        selectedOption = "fileManager"
                        onNavigateDir?.invoke(targetPath)
                    }
                )
            } else {
                RemoteFileManager(
                    onNavigateDir = onNavigateDir
                )
            }
        }
    }
}




@Composable
fun AndroidTransferProgressDialog(
    progress: TransferProgress,
    connectedName: String? = null,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (progress.isComplete) onDismiss() }
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (connectedName != null) {
                    Text(
                        text = "Connected: $connectedName",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTheme.colors.primary,
                        modifier = Modifier.padding(bottom = 12.dp).fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                val isCancelled = progress.statusMessage.contains("Cancel", ignoreCase = true)
                val isFetchOrReceive = progress.statusMessage.contains("Fetch", ignoreCase = true) ||
                                       progress.statusMessage.contains("Download", ignoreCase = true) ||
                                       progress.statusMessage.contains("Receiv", ignoreCase = true)
                val isUpload = progress.statusMessage.contains("Upload", ignoreCase = true) ||
                               progress.statusMessage.contains("Send", ignoreCase = true)

                val titleText = when {
                    isCancelled -> "Transfer Cancelled"
                    progress.isComplete -> when {
                        isFetchOrReceive -> "Receive Complete"
                        isUpload -> "Upload Complete"
                        else -> "Transfer Complete"
                    }
                    else -> when {
                        isFetchOrReceive -> "Receiving Data"
                        isUpload -> "Uploading Data"
                        else -> "Transferring Data"
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            isCancelled -> Icons.Default.Cancel
                            progress.isComplete -> Icons.Default.CheckCircle
                            isUpload -> Icons.Default.Upload
                            else -> Icons.Default.Download
                        },
                        null,
                        tint = when {
                            isCancelled -> AppTheme.colors.error
                            progress.isComplete -> SuccessColor
                            else -> AppTheme.colors.primary
                        },
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isCancelled -> AppTheme.colors.error
                                progress.isComplete -> SuccessColor
                                else -> AppTheme.colors.onSurface
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        val fileLabel = when {
                            progress.totalFiles > 1 && progress.isComplete -> "Files: "
                            progress.totalFiles > 1 -> "File (${progress.currentFileIndex}/${progress.totalFiles}): "
                            else -> "File: "
                        }
                        Text(
                            text = "$fileLabel${progress.filename.ifEmpty { "Unknown File" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (progress.statusMessage.isNotEmpty() && progress.statusMessage != progress.filename && !progress.statusMessage.startsWith("Receiving ${progress.filename}") && !progress.statusMessage.startsWith("Sending ${progress.filename}") && !progress.statusMessage.startsWith("Fetching ${progress.filename}")) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = progress.statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                LinearProgressIndicator(
                    progress = { progress.percentage / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = when {
                        isCancelled -> AppTheme.colors.error
                        progress.isComplete -> SuccessColor
                        else -> MaterialTheme.colorScheme.primary
                    }
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text(progress.speed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Elapsed", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text(progress.elapsed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Remaining", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text(progress.eta, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Progress", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text("${progress.percentage}%", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                        color = AppTheme.colors.secondary
                    )
                    if (progress.totalFiles > 1) {
                        Text(
                            "Total Elapsed: ${progress.batchElapsed}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.secondary
                        )
                    }
                }

                if (progress.queue.isNotEmpty() && progress.totalFiles > 1) {
                    Spacer(Modifier.height(16.dp))
                    Text("Queue", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 100.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f).copy(alpha = 0.1f))
                            .padding(8.dp)
                    ) {
                        items(progress.queue.size) { i ->
                            val item = progress.queue[i]
                            val isCurrent = (i + 1) == progress.currentFileIndex
                            val isDone = (i + 1) < progress.currentFileIndex
                            val color = when {
                                isDone -> SuccessColor
                                isCurrent -> AppTheme.colors.primary
                                else -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
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

                Spacer(Modifier.height(20.dp))

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
                    } /*else {
                        TextButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.error)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel Transfer")
                        }
                    }*/
                }

                if (!progress.isComplete) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Please do not disconnect the USB cable",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.error.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun BigIcon(icon: ImageVector, color: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(60.dp),
        tint = color
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
fun SecurityFooter() {
    Surface(
        color = AppTheme.colors.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, null, tint = AppTheme.colors.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "End-to-end encrypted via ECDH & AES-256",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}


@Composable
fun UnifiedStateCard(
    state: UsbUiState,
    role: UsbRole?,
    isCableConnected: Boolean,
    onConnect: () -> Unit,
    onSelectRole: (UsbRole) -> Unit,
    onReset: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxWidth().animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (state) {
                is UsbUiState.Idle, is UsbUiState.NoDevice -> {
                    if (role == null) {
                        BigIcon(Icons.Default.Devices, AppTheme.colors.primary)
                        Text("Select Role", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Choose how this device will act.",
                            color = AppTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        RoleSelectionCards(currentRole = null, onSelectRole = onSelectRole)
                    } else {
                        val icon = if (isCableConnected) Icons.Default.Usb else Icons.Default.UsbOff
                        val color = if (isCableConnected) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant
                        BigIcon(icon, color)
                        Text(
                            if (isCableConnected) "Ready to Connect" else "Plug in Cable", 
                            style = MaterialTheme.typography.headlineMedium, 
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isCableConnected) "USB detected. Initialize the secure link." else "Connect the USB cable to the remote device.",
                            color = AppTheme.colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = onConnect,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Initialize Connection", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = { onSelectRole(if (role is UsbRole.Host) UsbRole.Client("Unknown Host") else UsbRole.Host("Remote Device")) }) {
                            Text("Change Role")
                        }
                    }
                }
                is UsbUiState.DeviceDetected, is UsbUiState.Connecting, is UsbUiState.Transferring, is UsbUiState.RequestingPermission -> {
                    CircularProgressIndicator(modifier = Modifier.size(64.dp), color = AppTheme.colors.primary, strokeWidth = 6.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        when (state) {
                            is UsbUiState.DeviceDetected -> state.name
                            is UsbUiState.Connecting -> "Connecting"
                            is UsbUiState.Transferring -> "Transferring"
                            is UsbUiState.RequestingPermission -> "Requesting Permission"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Negotiating secure end-to-end encrypted link...",
                        color = AppTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                is UsbUiState.Success -> {
                    val connectedName = (state as? UsbUiState.Success)?.deviceId?.takeIf { it.isNotBlank() } ?: when (role) {
                        is UsbRole.Host -> role.connectedDeviceName
                        is UsbRole.Client -> role.connectedHostName
                        null -> "Remote Device"
                    }
                    BigIcon(Icons.Default.CheckCircle, SuccessColor)
                    Text("Connected to $connectedName", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SuccessColor, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message.ifBlank { "Waiting for commands from the Host device." }, color = AppTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)

                }
                is UsbUiState.Receiving -> {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(64.dp), tint = AppTheme.colors.primary)
                    Spacer(Modifier.height(24.dp))
                    Text("Receiving File", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.fileName, color = AppTheme.colors.primary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { if (state.progress >= 0) state.progress else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                }
                is UsbUiState.Error -> {
                    BigIcon(Icons.Default.Error, AppTheme.colors.error)
                    Text("Connection Failed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = AppTheme.colors.error)
                    Spacer(Modifier.height(8.dp))
                    Text(state.error, color = AppTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.error)
                    ) {
                        Text("Retry", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = onReset) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset & Start Fresh")
                        }
                        TextButton(onClick = { onSelectRole(if (role is UsbRole.Host) UsbRole.Client("Unknown Host") else UsbRole.Host("Remote Device")) }) {
                            Text("Change Role")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoleSelectionCards(
    currentRole: UsbRole?,
    onSelectRole: (UsbRole) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val isHost = currentRole is UsbRole.Host
        val isClient = currentRole is UsbRole.Client

        Card(
            onClick = { onSelectRole(UsbRole.Host("Remote Device")) },
            modifier = Modifier.fillMaxWidth(),
            border = if (isHost) BorderStroke(2.dp, AppTheme.colors.primary) else BorderStroke(1.dp, AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f)),
            colors = CardDefaults.cardColors(containerColor = if (isHost) AppTheme.colors.primaryContainer.copy(alpha = 0.3f) else AppTheme.colors.surface)
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Devices, null, tint = if (isHost) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Host (Initiator)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isHost) AppTheme.colors.primary else AppTheme.colors.onSurface)
                    Text("Manage files on the remote device", fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant)
                }
            }
        }

        Card(
            onClick = { onSelectRole(UsbRole.Client("Unknown Host")) },
            modifier = Modifier.fillMaxWidth(),
            border = if (isClient) BorderStroke(2.dp, AppTheme.colors.primary) else BorderStroke(1.dp, AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f)),
            colors = CardDefaults.cardColors(containerColor = if (isClient) AppTheme.colors.primaryContainer.copy(alpha = 0.3f) else AppTheme.colors.surface)
        ) {
            Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null, tint = if (isClient) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Client (Receiver)", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isClient) AppTheme.colors.primary else AppTheme.colors.onSurface)
                    Text("Wait for Host to connect", fontSize = 12.sp, color = AppTheme.colors.onSurfaceVariant)
                }
            }
        }
    }
}
