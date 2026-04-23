package com.example.usbtransferapp.presentation.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.usbtransferapp.data.UsbUiState
import com.example.usbtransferapp.presentation.viewmodel.UsbTransferViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(viewModel: UsbTransferViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.detectDevice()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("USB Secure Transfer", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusHeader(state)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = {
                        fadeIn() + scaleIn() togetherWith fadeOut() + scaleOut()
                    }
                ) { targetState ->
                    StateContent(targetState, onConnect = { viewModel.requestPermissionAndConnect() })
                }
            }

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
fun StateContent(state: UsbUiState, onConnect: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp)
    ) {
        when (state) {
            is UsbUiState.NoDevice -> {
                BigIcon(Icons.Default.UsbOff, Color.LightGray)
                Text("No USB Device Found", style = MaterialTheme.typography.headlineSmall)
                Text("Please connect your desktop via USB cable.", textAlign = TextAlign.Center)
            }
            is UsbUiState.DeviceDetected -> {
                BigIcon(Icons.Default.Devices, MaterialTheme.colorScheme.primary)
                Text("Desktop Found", style = MaterialTheme.typography.headlineSmall)
                Text(state.name, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Link, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Initialize Secure Link")
                }
            }
            is UsbUiState.Success -> {
                BigIcon(Icons.Default.CheckCircle, Color(0xFF4CAF50))
                Text("Channel Encrypted", style = MaterialTheme.typography.headlineSmall)
                Text("Waiting for desktop commands...", color = MaterialTheme.colorScheme.secondary)
            }
            is UsbUiState.Error -> {
                BigIcon(Icons.Default.Error, Color.Red)
                Text("Connection Failed", style = MaterialTheme.typography.headlineSmall)
                Text(state.error, color = Color.Red, textAlign = TextAlign.Center)
            }
            is UsbUiState.Transferring, is UsbUiState.Connecting -> {
                CircularProgressIndicator(modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(16.dp))
                Text("Handshaking...", fontWeight = FontWeight.Medium)
            }
            else -> {
                Text("Ready to scan")
            }
        }
    }
}

@Composable
fun BigIcon(icon: ImageVector, color: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(100.dp),
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
