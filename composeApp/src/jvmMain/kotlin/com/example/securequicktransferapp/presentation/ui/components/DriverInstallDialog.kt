package com.example.securequicktransferapp.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.usbdriverinstaller.api.DriverInstallState
import com.example.securequicktransferapp.presentation.theme.AppTheme
import com.example.securequicktransferapp.presentation.vm.MainViewModel

/**
 * Non-intrusive status dialog that reports the progress of the fully-automated
 * WinUSB driver installation (wdi-simple.exe, bundled inside the app).
 *
 * The user never has to choose a tool or click "Install" — driver setup starts
 * automatically the moment a device without a WinUSB driver is detected.
 * This dialog only appears to:
 *   • Show a progress spinner while wdi-simple.exe is running (UAC prompt visible to user).
 *   • Confirm success before auto-dismissing.
 *   • Report a failure and offer a single "Retry" button.
 */
@Composable
fun DriverInstallDialog (vm: MainViewModel) {
    val installState by vm.driverInstallState.collectAsState()

    val isVisible = installState !is DriverInstallState.Idle
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit  = fadeOut()
    ) {
        Dialog(onDismissRequest = {
            // Block dismissal while actively installing
            if (installState !is DriverInstallState.Installing) {
                vm.dismissDriverDialog()
            }
        }) {
            Surface(
                shape         = RoundedCornerShape(20.dp),
                color         = MaterialTheme.colorScheme.surface,
                tonalElevation  = 8.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    when (val state = installState) {

                        // ── Installing ─────────────────────────────────────────────────────
                        is DriverInstallState.Installing -> {
                            // Animated USB icon inside a pulsing ring
                            Box(
                                modifier = Modifier
                                    .size(68.dp)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(40.dp),
                                    strokeWidth = 3.dp,
                                    color       = AppTheme.colors.primary
                                )
                            }

                            Text(
                                text       = "Setting Up USB Driver",
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "Installing the WinUSB driver for ${state.deviceName}.\n\n" +
                                        "A Windows security prompt may appear — please click Yes to allow the installation.",
                                style      = MaterialTheme.typography.bodyMedium,
                                textAlign  = TextAlign.Center,
                                color      = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }

                        // ── Installed (success) ─────────────────────────────────────────────
                        is DriverInstallState.Installed -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE8F5E9)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector    = Icons.Default.Check,
                                    contentDescription = null,
                                    tint           = Color(0xFF2E7D32),
                                    modifier       = Modifier.size(38.dp)
                                )
                            }

                            Text(
                                text       = "Driver Ready",
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.onSurface
                            )

                            Text(
                                text = "WinUSB driver installed successfully for ${state.deviceName}.\n\nConnecting automatically...", 
                                style     = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Button(
                                onClick  = {
                                    vm.dismissDriverDialog()
                                    vm.connect()
                                },
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                                shape    = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector    = Icons.Default.Usb,
                                    contentDescription = null,
                                    modifier       = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Connect Now", fontWeight = FontWeight.Bold)
                            }
                        }

                        // ── Failed ────────────────────────────────────────────────────────
                        is DriverInstallState.Failed -> {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector    = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint           = MaterialTheme.colorScheme.error,
                                    modifier       = Modifier.size(38.dp)
                                )
                            }

                            Text(
                                text       = "Driver Setup Failed",
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color      = MaterialTheme.colorScheme.error
                            )

                            Text(
                                text      = state.reason,
                                style     = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick  = { vm.launchZadig() },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape    = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Open Zadig", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick  = {
                                        vm.retryDriverInstall(
                                            state.vendorId,
                                            state.productId,
                                            state.deviceName
                                        )
                                    },
                                    modifier = Modifier.height(44.dp),
                                    shape    = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Retry")
                                }

                                OutlinedButton(
                                    onClick  = { vm.dismissDriverDialog() },
                                    modifier = Modifier.height(44.dp),
                                    shape    = RoundedCornerShape(10.dp)
                                ) {
                                    Text("Dismiss")
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}
