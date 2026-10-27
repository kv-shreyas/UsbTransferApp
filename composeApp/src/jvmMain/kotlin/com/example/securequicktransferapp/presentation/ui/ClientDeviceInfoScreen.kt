package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.DetectedDeviceSummary
import com.example.securequicktransferapp.presentation.theme.AppTheme
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.vm.ClientDeviceInfoViewModel
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Desktop Host Screen displaying Application Details strictly for CONNECTED Android client devices.
 * Detected devices (plugged in via USB but not yet connected) are distinguished and presented
 * with dedicated connection actions rather than unverified device information.
 */
@Composable
fun ClientDeviceInfoScreen(
    vm: ClientDeviceInfoViewModel = getKoin().get(),
    onBack: () -> Unit
) {
    val uiState by vm.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── 1. Top Header Bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, AppTheme.colors.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.size(40.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Client Device Info",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppTheme.colors.onSurface
                        )
                        Spacer(Modifier.width(12.dp))

                        // Status Tag in Title
                        if (uiState.connectedDevices.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = SuccessColor.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, SuccessColor.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(SuccessColor)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Connected (${uiState.connectedDevices.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessColor
                                    )
                                }
                            }
                        } else if (uiState.detectedDevices.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = WarningColor.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, WarningColor.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(WarningColor)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Device Detected (Not Connected)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningColor
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (uiState.connectedDevices.isNotEmpty())
                            "Live application specifications and runtime diagnostics for active connected handheld"
                        else
                            "Device information is displayed strictly for active connected sessions",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            // Right Action: Refresh Button & Activity Spinner
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = AppTheme.colors.primary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Button(
                    onClick = { vm.refreshCurrentDevice() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Refresh",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── 2. Content Area ──────────────────────────────────────────────────────
        when {
            // State A: Devices connected and ready -> Display all Connected Device Banners in a list
            uiState.connectedDevices.isNotEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    uiState.connectedDevices.forEach { dev ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // ── Connected Device Hero Banner ─────────────────────────
                            ConnectedHeroDeviceBanner(
                                deviceId = dev.deviceId,
                                deviceName = dev.displayName,
                                app = dev.appDetails
                            )
                        }
                    }
                }
            }

            // State B: Devices detected (plugged in via USB) but NOT connected
            uiState.detectedDevices.isNotEmpty() -> {
                DeviceDetectedNotConnectedState(
                    detectedDevices = uiState.detectedDevices,
                    isLoading = uiState.isLoading,
                    onConnect = { vm.connectDevice(it) }
                )
            }

            // State C: No devices connected or detected at all
            else -> {
                EmptyClientDeviceState(onScan = { vm.refreshCurrentDevice() })
            }
        }
    }
}

/**
 * Top Hero Banner displaying connected client name, hardware key, active session tag,
 * and quick-glance metric chips. Rendered strictly for CONNECTED devices.
 */
@Composable
private fun ConnectedHeroDeviceBanner(
    deviceId: String,
    deviceName: String,
    app: ClientAppDetails?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            AppTheme.colors.primary.copy(alpha = 0.08f),
                            AppTheme.colors.surfaceVariant.copy(alpha = 0.25f),
                            AppTheme.colors.surface
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Device Identity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = AppTheme.colors.primary.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Smartphone,
                                contentDescription = null,
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(18.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = deviceName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.onSurface
                            )
                            Spacer(Modifier.width(10.dp))
                            Surface(
                                shape = CircleShape,
                                color = SuccessColor.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, SuccessColor.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(SuccessColor)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Connected (Ready)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessColor
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(5.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Usb,
                                contentDescription = null,
                                tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "Device Key: $deviceId",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Quick Stat Badges Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeroStatPill(
                        icon = Icons.Default.Verified,
                        label = "App Version",
                        value = if (app != null) "v${app.appVersion}" else "Querying...",
                        accentColor = AppTheme.colors.primary
                    )

                    HeroStatPill(
                        icon = Icons.Default.Android,
                        label = "Target Platform",
                        value = if (app != null) "Android ${app.osVersion}" else "Querying...",
                        accentColor = AppTheme.colors.secondary
                    )
                }
            }
        }
    }
}
@Composable
private fun HeroStatPill(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, AppTheme.colors.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.12f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.onSurface
                )
            }
        }
    }
}

/**
 * State rendered when a USB device is physically DETECTED, but NOT connected.
 * Informs the user clearly and provides an inline connection trigger.
 */
@Composable
private fun DeviceDetectedNotConnectedState(
    detectedDevices: List<DetectedDeviceSummary>,
    isLoading: Boolean,
    onConnect: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surface),
            border = BorderStroke(1.dp, WarningColor.copy(alpha = 0.35f)),
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // USB Status Icon
                Surface(
                    shape = CircleShape,
                    color = WarningColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.5.dp, WarningColor.copy(alpha = 0.3f)),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Usb,
                            contentDescription = null,
                            tint = WarningColor,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Handheld Device Detected",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = AppTheme.colors.onSurface
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "A physical USB device is plugged in, but an active Secure Quick Transfer session is not established. Client device info is displayed strictly for connected devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(24.dp))
                HorizontalDivider(thickness = 0.5.dp, color = AppTheme.colors.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(20.dp))

                // Detected Devices List
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    detectedDevices.forEach { dev ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AppTheme.colors.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, AppTheme.colors.outlineVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(WarningColor)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        Icons.Default.Smartphone,
                                        contentDescription = null,
                                        tint = AppTheme.colors.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = dev.displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTheme.colors.onSurface
                                        )
                                        Text(
                                            text = "Status: Detected • Idle Session",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = WarningColor
                                        )
                                    }
                                }

                                Button(
                                    onClick = { onConnect(dev.deviceId) },
                                    enabled = !dev.isConnecting && !isLoading,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    if (dev.isConnecting || isLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Connecting...", fontSize = 12.sp)
                                    } else {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Connect Device", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state rendered when no client devices are currently connected or detected via USB.
 */
@Composable
private fun EmptyClientDeviceState(onScan: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = AppTheme.colors.surfaceVariant.copy(alpha = 0.45f),
                border = BorderStroke(1.5.dp, AppTheme.colors.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.size(90.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.UsbOff,
                        contentDescription = null,
                        tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(46.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = "No Handheld Devices Connected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Plug in an Android handheld device via USB cable and ensure Secure Quick Transfer is running\nto inspect live application specifications and runtime telemetry.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan for Handheld Devices", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Helper translating Android API integer level to recognizable OS release names.
 */
private fun getAndroidVersionInfo(apiLevel: Int): Pair<String, String> {
    return when (apiLevel) {
        35 -> Pair("Android 15", "Vanilla Ice Cream")
        34 -> Pair("Android 14", "Upside Down Cake")
        33 -> Pair("Android 13", "Tiramisu")
        32 -> Pair("Android 12L", "Sv2")
        31 -> Pair("Android 12", "Snow Cone")
        30 -> Pair("Android 11", "Red Velvet Cake")
        29 -> Pair("Android 10", "Quince Tart")
        28 -> Pair("Android 9.0", "Pie")
        27 -> Pair("Android 8.1", "Oreo")
        26 -> Pair("Android 8.0", "Oreo")
        25 -> Pair("Android 7.1", "Nougat")
        24 -> Pair("Android 7.0", "Nougat")
        23 -> Pair("Android 6.0", "Marshmallow")
        21, 22 -> Pair("Android 5.0", "Lollipop")
        else -> Pair("Android $apiLevel", "API Level $apiLevel")
    }
}
