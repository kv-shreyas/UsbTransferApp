package com.example.usbtransferapp.presentation.ui

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.usbtransferapp.data.UsbRole
import com.example.usbtransferapp.data.UsbUiState
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.presentation.viewmodel.UsbTransferViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(viewModel: UsbTransferViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val isCableConnected by viewModel.isCablePhysicallyConnected.collectAsState()
    val role by viewModel.usbRole.collectAsState()
    val remoteFiles by viewModel.remoteFiles.collectAsState()
    val currentRemotePath by viewModel.currentRemotePath.collectAsState()
    val isRemoteLoading by viewModel.isRemoteLoading.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val logFilePath = viewModel.getLogFilePath()

    LaunchedEffect(Unit) {
        viewModel.detectAndConnect()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("USB Secure Transfer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusHeader(state)

            UsbPhysicalIndicator(isPhysicallyConnected = isCableConnected)

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
                        fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut()
                    }
                ) { targetState ->
                    StateContent(
                        state = targetState,
                        role = role,
                        isCableConnected = isCableConnected,
                        remoteFiles = remoteFiles,
                        currentRemotePath = currentRemotePath,
                        isRemoteLoading = isRemoteLoading,
                        onConnect = { viewModel.requestPermissionAndConnect() },
                        onSelectRole = { viewModel.selectRoleAndConnect(it) },
                        onNavigateDir = { viewModel.fetchRemoteFiles(it) },
                        onRefreshDir = { viewModel.fetchRemoteFiles(it, forceRefresh = true) },
                        onFetchFile = { remotePath, isDir ->
                            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            if (isDir) {
                                viewModel.fetchRemoteDirectory(remotePath, downloadDir)
                            } else {
                                viewModel.fetchRemoteFile(remotePath, downloadDir)
                            }
                        },
                        onDeleteFile = { viewModel.deleteRemoteFile(it) }
                    )
                }
            }

            // Live Diagnostic Logs Viewer (Collapsible / Scrollable)
            DebugLogsViewer(
                logLines = logLines,
                logFilePath = logFilePath,
                onClear = { viewModel.clearLogs() }
            )

            SecurityFooter()
        }
    }
}

@Composable
fun StatusHeader(state: UsbUiState) {
    val (color, text, icon) = when (state) {
        is UsbUiState.Idle -> Triple(Color.Gray, "Idle", Icons.Default.Info)
        is UsbUiState.NoDevice -> Triple(Color.Red, "Disconnected", Icons.Default.UsbOff)
        is UsbUiState.DeviceDetected -> Triple(Color(0xFFFFA500), "Device Ready", Icons.Default.Usb)
        is UsbUiState.RequestingPermission -> Triple(Color(0xFFFFA500), "Authenticating", Icons.Default.Lock)
        is UsbUiState.Connecting -> Triple(MaterialTheme.colorScheme.primary, "Connecting", Icons.Default.SettingsEthernet)
        is UsbUiState.Transferring -> Triple(MaterialTheme.colorScheme.primary, "Secure Link Active", Icons.Default.Sync)
        is UsbUiState.Receiving -> Triple(MaterialTheme.colorScheme.primary, "Transfer in Progress", Icons.Default.Download)
        is UsbUiState.Success -> Triple(Color(0xFF4CAF50), "Connected & Secure", Icons.Default.VerifiedUser)
        is UsbUiState.Error -> Triple(Color.Red, "Error", Icons.Default.Error)
    }

    Surface(
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
fun StateContent(
    state: UsbUiState,
    role: UsbRole?,
    isCableConnected: Boolean,
    remoteFiles: List<RemoteFile>,
    currentRemotePath: String,
    isRemoteLoading: Boolean,
    onConnect: () -> Unit,
    onSelectRole: (UsbRole) -> Unit,
    onNavigateDir: (String) -> Unit,
    onRefreshDir: (String) -> Unit,
    onFetchFile: (String, Boolean) -> Unit,
    onDeleteFile: (String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        when (state) {
            is UsbUiState.Idle -> {
                RoleSelectionCards(currentRole = role, onSelectRole = onSelectRole)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onConnect,
                    enabled = role != null,
                    modifier = Modifier
                        .height(54.dp)
                        .widthIn(min = 220.dp)
                ) {
                    Icon(Icons.Default.Usb, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Initialize Connection", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            is UsbUiState.NoDevice -> {
                if (isCableConnected) {
                    BigIcon(Icons.Default.Usb, MaterialTheme.colorScheme.primary)
                    Text("USB Cable Connected", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                } else {
                    BigIcon(Icons.Default.UsbOff, Color.Gray)
                    Text("USB Connection Setup", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                }
                ConnectionGuideCard(
                    currentRole = role,
                    isCableConnected = isCableConnected,
                    onSelectRole = onSelectRole
                )
            }
            is UsbUiState.DeviceDetected -> {
                BigIcon(Icons.Default.Devices, MaterialTheme.colorScheme.primary)
                Text("USB Device Detected", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))

                val isBusyCheck = state.name.contains("Waiting", ignoreCase = true) ||
                                  state.name.contains("Checking", ignoreCase = true) ||
                                  state.name.contains("Switching", ignoreCase = true) ||
                                  state.name.contains("Establishing", ignoreCase = true)

                if (isBusyCheck) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(40.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                "Negotiating & Verifying Connection...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                state.name,
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                } else {
                    Text(state.name, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(16.dp))

                    if (role != null) {
                        val roleText = if (role is UsbRole.Host) "Host (Initiating Connection)" else "Client (Receiver)"
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text(
                                "Role: $roleText",
                                modifier = Modifier.padding(8.dp),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Button(
                            onClick = onConnect,
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Link, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Initialize Connection")
                        }
                    } else {
                        Text("Select this device's role to proceed:", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        RoleSelectionCards(currentRole = null, onSelectRole = onSelectRole)
                    }
                }
            }
            is UsbUiState.Success -> {
                if (role is UsbRole.Host) {
                    RemoteFileManager(
                        remoteFiles = remoteFiles,
                        currentRemotePath = currentRemotePath,
                        isRemoteLoading = isRemoteLoading,
                        onNavigateDir = onNavigateDir,
                        onRefreshDir = onRefreshDir,
                        onFetchFile = onFetchFile,
                        onDeleteFile = onDeleteFile
                    )
                } else {
                    BigIcon(Icons.Default.CheckCircle, Color(0xFF4CAF50))
                    Text("Ready & Secure", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("✅ Device is ready (Client Mode)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            Spacer(Modifier.height(4.dp))
                            Text("Waiting for commands from the connected Host device.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
            is UsbUiState.Error -> {
                BigIcon(Icons.Default.Error, Color.Red)
                Text("Connection Failed", style = MaterialTheme.typography.headlineSmall)
                Text(state.error, color = Color.Red, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onConnect) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Retry")
                }
            }
            is UsbUiState.RequestingPermission -> {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Awaiting OS Permission...", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text("Please accept the system dialog to proceed.", color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
            }
            is UsbUiState.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Establishing Connection...", fontWeight = FontWeight.Medium)
            }
            is UsbUiState.Transferring -> {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Handshaking...", fontWeight = FontWeight.Medium)
            }
            is UsbUiState.Receiving -> {
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("Transferring...", fontWeight = FontWeight.Medium)
                Text(state.fileName, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (state.progress >= 0) state.progress else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                if (state.progress >= 0f) {
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            else -> {
                BigIcon(Icons.Default.Usb, Color.Gray)
                Text("USB Transfer", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Plug in the USB cable to get started.\nThe device will be detected automatically.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ConnectionGuideCard(
    currentRole: UsbRole?,
    isCableConnected: Boolean,
    onSelectRole: (UsbRole) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isCableConnected) "USB Role Setup (Cable Connected)" else "USB Connection Setup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (isCableConnected && currentRole is UsbRole.Host) {
                Surface(
                    color = Color(0xFFFFF3CD),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFECB5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Color(0xFF856404), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Hardware Role Notice", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF856404))
                        }
                        Text(
                            "USB cable is plugged in, but this device's hardware port negotiated the Peripheral role (it cannot act as USB Host with this cable/port state).\n\n" +
                            "👉 To act as Host from this phone: Flip the USB-C cable ends or use a USB OTG adapter.\n" +
                            "👉 Or: Select Client role below to connect immediately!",
                            fontSize = 11.sp,
                            color = Color(0xFF664D03)
                        )
                    }
                }
            } else if (isCableConnected && currentRole is UsbRole.Client) {
                Surface(
                    color = Color(0xFFD1E7DD),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFBADBCC)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF0F5132), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Waiting for Host Device", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0F5132))
                        }
                        Text(
                            "USB cable detected in Peripheral mode. Select 'Initialize Connection' on the other Host device to establish secure link.",
                            fontSize = 11.sp,
                            color = Color(0xFF0F5132)
                        )
                    }
                }
            } else if (isCableConnected && currentRole == null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "USB Cable Detected (Peripheral Port)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "This phone connected as the Upstream/Peripheral USB port. Select the Client role below so the other Host device can connect.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                        )
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "How it works:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Step 1: Select a Role below for THIS device.", fontSize = 11.sp, color = Color.Gray)
                        Text("Step 2: Connect to the other device (Desktop or Android) via USB / OTG cable.", fontSize = 11.sp, color = Color.Gray)
                        Text("Step 3: Accept any USB permission popup that appears on screen.", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }

            Text("Select this device's role to proceed:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            RoleSelectionCards(currentRole = currentRole, onSelectRole = onSelectRole)

            if (currentRole != null) {
                val roleName = if (currentRole is UsbRole.Host) "Host (Initiator)" else "Client (Receiver)"
                val nextAction = if (currentRole is UsbRole.Host) {
                    "👉 Now plug the USB cable into both devices. When prompted, tap 'Allow / OK' to initiate the connection and browse/manage files on the remote device."
                } else {
                    "👉 Now tap 'Initialize Connection' on the Host device. This device will automatically authenticate and connect."
                }
                Surface(
                    color = if (currentRole is UsbRole.Host) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Selected Role: $roleName", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (currentRole is UsbRole.Host) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(nextAction, fontSize = 11.sp, color = if (currentRole is UsbRole.Host) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val isHostSelected = currentRole is UsbRole.Host
        Card(
            onClick = { onSelectRole(UsbRole.Host("Remote Device")) },
            modifier = Modifier.weight(1f),
            border = if (isHostSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isHostSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Upload, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text("Host", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isHostSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Initiate connection &\nbrowse/manage files", fontSize = 10.sp, textAlign = TextAlign.Center, color = if (isHostSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else Color.Gray)
            }
        }

        val isClientSelected = currentRole is UsbRole.Client
        Card(
            onClick = { onSelectRole(UsbRole.Client("Remote Host")) },
            modifier = Modifier.weight(1f),
            border = if (isClientSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isClientSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text("Client", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isClientSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Receive connection &\nallow file transfers", fontSize = 10.sp, textAlign = TextAlign.Center, color = if (isClientSelected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f) else Color.Gray)
            }
        }
    }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connection Diagnostics & Logs (${logLines.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (expanded) {
                        TextButton(onClick = onClear, contentPadding = PaddingValues(2.dp)) {
                            Text("Clear", fontSize = 11.sp, color = Color.Red)
                        }
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Logs"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "File written to: $logFilePath",
                        fontSize = 10.sp,
                        color = Color.Gray,
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
                            Text("No log messages yet...", color = Color.Gray, fontSize = 11.sp)
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
fun RemoteFileManager(
    remoteFiles: List<RemoteFile>,
    currentRemotePath: String,
    isRemoteLoading: Boolean = false,
    onNavigateDir: (String) -> Unit,
    onRefreshDir: (String) -> Unit = onNavigateDir,
    onFetchFile: (String, Boolean) -> Unit,
    onDeleteFile: (String) -> Unit
) {
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
                    onNavigateDir(parent)
                },
                enabled = currentRemotePath != "/sdcard" && currentRemotePath != "/" && currentRemotePath.isNotEmpty()
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Remote: $currentRemotePath",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = { onRefreshDir(currentRemotePath) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        if (isRemoteLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading folder contents...", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else if (remoteFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Directory is empty", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(remoteFiles) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (file.isDirectory) {
                                onNavigateDir(file.path)
                            }
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isDirectory) Color(0xFFFFA500) else MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, fontWeight = FontWeight.SemiBold)
                                if (!file.isDirectory) {
                                    val sizeText = if (file.size > 0) "${file.size / 1024} KB" else "File"
                                    Text(sizeText, fontSize = 12.sp, color = Color.Gray)
                                } else {
                                    Text("Directory", fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            IconButton(onClick = { onFetchFile(file.path, file.isDirectory) }) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = if (file.isDirectory) "Download Directory (ZIP)" else "Download File",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = { onDeleteFile(file.path) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }
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
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "End-to-end encrypted via ECDH & AES-256",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun UsbPhysicalIndicator(isPhysicallyConnected: Boolean) {
    val bgColor = if (isPhysicallyConnected) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
    val borderColor = if (isPhysicallyConnected) Color(0xFF4CAF50) else Color(0xFFE57373)
    val iconColor = if (isPhysicallyConnected) Color(0xFF2E7D32) else Color(0xFFC62828)
    val icon = if (isPhysicallyConnected) Icons.Default.CheckCircle else Icons.Default.UsbOff
    val title = if (isPhysicallyConnected) "USB Cable Physically Connected" else "USB Cable Unplugged / Not Detected"
    val subtitle = if (isPhysicallyConnected) "Hardware link active. Ready to initialize secure connection." else "Plug in a USB or OTG cable between your devices to begin."

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = iconColor
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = iconColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}
