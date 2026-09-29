package com.example.securequicktransferapp.presentation.ui
import com.example.secureqt.sdk.SecureQtSdk
import com.example.securequicktransferapp.presentation.theme.AppTheme

import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.securequicktransferapp.data.UsbRole
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.data.UsbUiState
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.viewmodel.UsbTransferViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(viewModel: UsbTransferViewModel = hiltViewModel(), isDarkTheme: Boolean = true, onThemeToggle: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    val isCableConnected by viewModel.isCablePhysicallyConnected.collectAsState()
    val role by viewModel.usbRole.collectAsState()
    val progressState by viewModel.progressState.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val logFilePath = viewModel.getLogFilePath()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.detectAndConnect()
    }

    if (progressState.isVisible) {
        AndroidTransferProgressDialog(
            progress = progressState,
            onCancel = { viewModel.cancelTransfer() },
            onDismiss = { viewModel.dismissProgress() }
        )
    }

    var showAboutDialog by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }

    if (showSettingsScreen) {
        SettingsScreen(
            isDarkTheme = isDarkTheme,
            onThemeToggle = onThemeToggle,
            onBack = { showSettingsScreen = false },
            onShowAbout = { showAboutDialog = true },
            onExitApp = { (context as? android.app.Activity)?.finishAffinity() }
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
                title = { Text("USB Secure Transfer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppTheme.colors.primaryContainer,
                    titleContentColor = AppTheme.colors.onPrimaryContainer
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
                        onConnect = { viewModel.requestPermissionAndConnect() },
                        onSelectRole = { viewModel.selectRoleAndConnect(it) },
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
fun StatusHeader(state: UsbUiState) {
    val (color, text, icon) = when (state) {
        is UsbUiState.Idle -> Triple(AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f), "Idle", Icons.Default.Info)
        is UsbUiState.NoDevice -> Triple(AppTheme.colors.error, "Disconnected", Icons.Default.UsbOff)
        is UsbUiState.DeviceDetected -> Triple(WarningColor, "Device Ready", Icons.Default.Usb)
        is UsbUiState.RequestingPermission -> Triple(WarningColor, "Authenticating", Icons.Default.Lock)
        is UsbUiState.Connecting -> Triple(AppTheme.colors.primary, "Connecting", Icons.Default.SettingsEthernet)
        is UsbUiState.Transferring -> Triple(AppTheme.colors.primary, "Secure Link Active", Icons.Default.Sync)
        is UsbUiState.Receiving -> Triple(AppTheme.colors.primary, "Transfer in Progress", Icons.Default.Download)
        is UsbUiState.Success -> Triple(SuccessColor, "Connected & Secure", Icons.Default.VerifiedUser)
        is UsbUiState.Error -> Triple(AppTheme.colors.error, "Error", Icons.Default.Error)
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
    onConnect: () -> Unit,
    onSelectRole: (UsbRole) -> Unit,
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
                    BigIcon(Icons.Default.Usb, AppTheme.colors.primary)
                    Text("USB Cable Connected", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                } else {
                    BigIcon(Icons.Default.UsbOff, AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
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
                BigIcon(Icons.Default.Devices, AppTheme.colors.primary)
                Text("USB Device Detected", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))

                val isBusyCheck = state.name.contains("Waiting", ignoreCase = true) ||
                                  state.name.contains("Checking", ignoreCase = true) ||
                                  state.name.contains("Switching", ignoreCase = true) ||
                                  state.name.contains("Establishing", ignoreCase = true)

                if (isBusyCheck) {
                    Surface(
                        color = AppTheme.colors.tertiaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(40.dp), color = AppTheme.colors.onTertiaryContainer)
                            Text(
                                "Negotiating & Verifying Connection...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AppTheme.colors.onTertiaryContainer
                            )
                            Text(
                                state.name,
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp,
                                color = AppTheme.colors.onTertiaryContainer.copy(alpha = 0.9f)
                            )
                        }
                    }
                } else {
                    Text(state.name, color = AppTheme.colors.secondary)
                    Spacer(Modifier.height(16.dp))

                    if (role != null) {
                        val roleText = if (role is UsbRole.Host) "Host (Initiating Connection)" else "Client (Receiver)"
                        Surface(
                            color = AppTheme.colors.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text(
                                "Role: $roleText",
                                modifier = Modifier.padding(8.dp),
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AppTheme.colors.onPrimaryContainer
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
                    HostModeDashboard()
                } else {
                    BigIcon(Icons.Default.CheckCircle, SuccessColor)
                    Text("Ready & Secure", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AppTheme.colors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("✅ Device is ready (Client Mode)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = SuccessColor)
                            Spacer(Modifier.height(4.dp))
                            Text(state.message, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTheme.colors.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("Waiting for commands from the connected Host device.", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
            is UsbUiState.Error -> {
                BigIcon(Icons.Default.Error, AppTheme.colors.error)
                Text("Connection Failed", style = MaterialTheme.typography.headlineSmall)
                Text(state.error, color = AppTheme.colors.error, textAlign = TextAlign.Center)
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
                Text("Please accept the system dialog to proceed.", color = AppTheme.colors.secondary, textAlign = TextAlign.Center)
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
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(64.dp), tint = AppTheme.colors.primary)
                Spacer(Modifier.height(24.dp))
                Text("Transferring...", fontWeight = FontWeight.Medium)
                Text(state.fileName, color = AppTheme.colors.secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (state.progress >= 0) state.progress else 0f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                )
                if (state.progress >= 0f) {
                    Spacer(Modifier.height(8.dp))
                    Text("${(state.progress * 100).toInt()}%", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                }
            }
            else -> {
                BigIcon(Icons.Default.Usb, AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                Text("USB Transfer", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Plug in the USB cable to get started.\nThe device will be detected automatically.",
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
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
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceVariant.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, contentDescription = null, tint = AppTheme.colors.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isCableConnected) "USB Role Setup (Cable Connected)" else "USB Connection Setup",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = AppTheme.colors.primary
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
                    color = AppTheme.colors.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "USB Cable Detected (Peripheral Port)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = AppTheme.colors.onPrimaryContainer
                        )
                        Text(
                            "This phone connected as the Upstream/Peripheral USB port. Select the Client role below so the other Host device can connect.",
                            fontSize = 11.sp,
                            color = AppTheme.colors.onPrimaryContainer.copy(alpha = 0.9f)
                        )
                    }
                }
            } else {
                Surface(
                    color = AppTheme.colors.surface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "How it works:",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = AppTheme.colors.onSurface
                        )
                        Text("Step 1: Select a Role below for THIS device.", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text("Step 2: Connect to the other device (Desktop or Android) via USB / OTG cable.", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
                        Text("Step 3: Accept any USB permission popup that appears on screen.", fontSize = 11.sp, color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
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
                    color = if (currentRole is UsbRole.Host) AppTheme.colors.primaryContainer else AppTheme.colors.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Selected Role: $roleName", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (currentRole is UsbRole.Host) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSecondaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(nextAction, fontSize = 11.sp, color = if (currentRole is UsbRole.Host) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSecondaryContainer)
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
            border = if (isHostSelected) BorderStroke(2.dp, AppTheme.colors.primary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isHostSelected) AppTheme.colors.primaryContainer else AppTheme.colors.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Upload, null, tint = AppTheme.colors.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text("Host", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isHostSelected) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Initiate connection &\nbrowse/manage files", fontSize = 10.sp, textAlign = TextAlign.Center, color = if (isHostSelected) AppTheme.colors.onPrimaryContainer.copy(alpha = 0.8f) else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }

        val isClientSelected = currentRole is UsbRole.Client
        Card(
            onClick = { onSelectRole(UsbRole.Client("Remote Host")) },
            modifier = Modifier.weight(1f),
            border = if (isClientSelected) BorderStroke(2.dp, AppTheme.colors.secondary) else null,
            colors = CardDefaults.cardColors(
                containerColor = if (isClientSelected) AppTheme.colors.secondaryContainer else AppTheme.colors.surface
            )
        ) {
            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Download, null, tint = AppTheme.colors.secondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(6.dp))
                Text("Client", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (isClientSelected) AppTheme.colors.onSecondaryContainer else AppTheme.colors.onSurface)
                Spacer(Modifier.height(2.dp))
                Text("Receive connection &\nallow file transfers", fontSize = 10.sp, textAlign = TextAlign.Center, color = if (isClientSelected) AppTheme.colors.onSecondaryContainer.copy(alpha = 0.8f) else AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f))
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
    onNavigateDir: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedOption by remember { mutableStateOf("fileManager") }

    Column(modifier = Modifier.fillMaxSize()) {
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
                val isFetchOrReceive = progress.statusMessage.contains("Fetch", ignoreCase = true) ||
                                       progress.statusMessage.contains("Download", ignoreCase = true) ||
                                       progress.statusMessage.contains("Receiv", ignoreCase = true)
                val isUpload = progress.statusMessage.contains("Upload", ignoreCase = true) ||
                               progress.statusMessage.contains("Send", ignoreCase = true)

                val titleText = when {
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
                        if (progress.isComplete) Icons.Default.CheckCircle else if (isUpload) Icons.Default.Upload else Icons.Default.Download,
                        null,
                        tint = if (progress.isComplete) SuccessColor else AppTheme.colors.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (progress.isComplete) SuccessColor else AppTheme.colors.onSurface
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
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
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
                    } else {
                        TextButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.textButtonColors(contentColor = AppTheme.colors.error)
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
fun UsbPhysicalIndicator(isPhysicallyConnected: Boolean) {
    val bgColor = if (isPhysicallyConnected) SuccessColor.copy(alpha = 0.15f) else AppTheme.colors.error.copy(alpha = 0.15f)
    val borderColor = if (isPhysicallyConnected) SuccessColor else AppTheme.colors.error
    val iconColor = if (isPhysicallyConnected) SuccessColor else AppTheme.colors.error
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
