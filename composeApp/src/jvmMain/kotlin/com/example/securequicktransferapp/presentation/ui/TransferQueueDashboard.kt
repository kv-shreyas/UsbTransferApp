package com.example.securequicktransferapp.presentation.ui

import androidx.compose.animation.AnimatedVisibility
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.TransferItemStatus
import com.example.securequicktransferapp.domain.model.TransferQueueItem
import com.example.securequicktransferapp.domain.model.TransferType
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.presentation.theme.SuccessColor
import com.example.securequicktransferapp.presentation.theme.WarningColor
import com.example.securequicktransferapp.presentation.vm.MainViewModel

/**
 * Full-screen Transfer Queue Dashboard showing transfer details for ALL connected devices.
 */
@Composable
fun TransferQueueDashboard(
    vm: MainViewModel,
    onBack: () -> Unit,
    onShowHistory: () -> Unit
) {
    val sessions by vm.sessionsState.collectAsState()
    val selectedDeviceId by vm.selectedDeviceId.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // --- Header ---
        DashboardHeader(onBack = onBack, onShowHistory = onShowHistory)

        Spacer(Modifier.height(16.dp))

        if (sessions.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Usb,
                        null,
                        tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "No Devices Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        "Connect an Android device via USB to see transfer activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            // Scrollable list of device sections
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(sessions.entries.toList()) { (deviceId, sessionState) ->
                    DeviceQueueSection(
                        vm = vm,
                        deviceId = deviceId,
                        sessionState = sessionState,
                        isSelected = deviceId == selectedDeviceId
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHeader(onBack: () -> Unit, onShowHistory: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Transfer Queue",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurface
            )
            Text(
                "Monitor and manage all device transfers",
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = onShowHistory) {
            Icon(
                Icons.Default.History,
                "Transfer History",
                tint = AppTheme.colors.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Sync,
            null,
            tint = AppTheme.colors.primary.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp)
        )
    }
}

/**
 * Section for a single device, showing its connection status and queue items.
 */
@Composable
private fun DeviceQueueSection(
    vm: MainViewModel,
    deviceId: String,
    sessionState: UsbSessionState,
    isSelected: Boolean
) {
    val queue = vm.getDeviceQueue(deviceId)
    val queueItems = queue?.queue?.collectAsState()?.value ?: emptyList()
    val isProcessing = queue?.isProcessing?.collectAsState()?.value ?: false
    var isListExpanded by remember { mutableStateOf(true) }

    val isReady = sessionState.status is DeviceSessionStatus.Ready
    val isConnecting = sessionState.status is DeviceSessionStatus.Connecting
    val hasError = sessionState.status is DeviceSessionStatus.Error

    // Stats
    val activeItems = queueItems.filter { it.status == TransferItemStatus.ACTIVE }
    val pendingItems = queueItems.filter { it.status == TransferItemStatus.PENDING }
    val completedItems = queueItems.filter { it.status == TransferItemStatus.COMPLETED }
    val failedItems = queueItems.filter { it.status == TransferItemStatus.FAILED }
    val cancelledItems = queueItems.filter { it.status == TransferItemStatus.CANCELLED }

    val shortName = sessionState.deviceName
        .replace("Android Device (", "")
        .replace(")", "")
        .substringBefore(",")
        .trim()
        .ifBlank { deviceId }

    val borderColor = if (isSelected) AppTheme.colors.primary else AppTheme.colors.outlineVariant
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                AppTheme.colors.primaryContainer.copy(alpha = 0.08f)
            else
                AppTheme.colors.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // --- Device header row ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status indicator
                val dotColor = when {
                    isReady -> SuccessColor
                    isConnecting -> WarningColor
                    hasError -> AppTheme.colors.error
                    else -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
                }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        shortName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurface
                    )
                    Text(
                        when {
                            isReady -> "Connected • ${if (sessionState.isAoaMode) "AOA" else "Normal"} Protocol"
                            isConnecting -> "Connecting..."
                            hasError -> "Error: ${(sessionState.status as DeviceSessionStatus.Error).message}"
                            else -> "Detected (not connected)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = dotColor
                    )
                }

                // Select button if not already selected
                if (!isSelected) {
                    OutlinedButton(
                        onClick = { vm.selectDevice(deviceId) },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Select", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // Cancel all / Clear finished buttons
                if (isProcessing) {
                    OutlinedButton(
                        onClick = {
                            vm.selectDevice(deviceId)
                            vm.cancelTransfer()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.error)
                    ) {
                        Icon(Icons.Default.Cancel, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel All", fontSize = 11.sp)
                    }
                }
                if (completedItems.isNotEmpty() || failedItems.isNotEmpty() || cancelledItems.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            vm.selectDevice(deviceId)
                            vm.clearFinishedQueue()
                        }
                    ) {
                        Icon(Icons.Default.ClearAll, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // --- Stats row ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatChip(
                    label = "Active",
                    count = activeItems.size,
                    color = AppTheme.colors.primary
                )
                StatChip(
                    label = "Pending",
                    count = pendingItems.size,
                    color = WarningColor
                )
                StatChip(
                    label = "Done",
                    count = completedItems.size,
                    color = SuccessColor
                )
                if (failedItems.isNotEmpty()) {
                    StatChip(
                        label = "Failed",
                        count = failedItems.size,
                        color = AppTheme.colors.error
                    )
                }
                if (cancelledItems.isNotEmpty()) {
                    StatChip(
                        label = "Cancelled",
                        count = cancelledItems.size,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            if (queueItems.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = AppTheme.colors.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "No transfers in queue. Send or fetch files to see activity here.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isListExpanded = !isListExpanded }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        "Queue Items (${queueItems.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (isListExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Queue List",
                        tint = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = !isListExpanded && activeItems.isNotEmpty()) {
                    val activeItem = activeItems.first()
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                activeItem.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${activeItem.transferred} / Size: ${activeItem.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { activeItem.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)),
                            color = AppTheme.colors.primary,
                            trackColor = AppTheme.colors.surfaceVariant
                        )
                    }
                }

                // --- Queue items list ---
                AnimatedVisibility(visible = isListExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        queueItems.forEachIndexed { index, item ->
                            QueueItemRow(
                                item = item,
                                index = index + 1,
                                onCancel = {
                                    vm.selectDevice(deviceId)
                                    vm.cancelQueueItem(item.id)
                                },
                                onMoveToFront = {
                                    vm.selectDevice(deviceId)
                                    vm.moveQueueItemToFront(item.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, count: Int, color: Color) {
    Surface(
        color = color.copy(alpha = 0.08f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$count",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                fontSize = 10.sp,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Individual queue item row with progress, status icon, and action buttons.
 */
@Composable
private fun QueueItemRow(
    item: TransferQueueItem,
    index: Int,
    onCancel: () -> Unit,
    onMoveToFront: () -> Unit
) {
    val isActive = item.status == TransferItemStatus.ACTIVE
    val isPending = item.status == TransferItemStatus.PENDING
    val isCompleted = item.status == TransferItemStatus.COMPLETED
    val isFailed = item.status == TransferItemStatus.FAILED
    val isCancelled = item.status == TransferItemStatus.CANCELLED

    val backgroundColor = when {
        isActive -> AppTheme.colors.primaryContainer.copy(alpha = 0.2f)
        isCompleted -> SuccessColor.copy(alpha = 0.05f)
        isFailed -> AppTheme.colors.error.copy(alpha = 0.05f)
        isCancelled -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.03f)
        else -> Color.Transparent
    }

    var isExpanded by remember { mutableStateOf(isActive || isFailed) }

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status icon
                val (icon, iconTint) = when {
                    isActive -> Icons.Default.Sync to AppTheme.colors.primary
                    isPending -> Icons.Default.HourglassEmpty to WarningColor
                    isCompleted -> Icons.Default.CheckCircle to SuccessColor
                    isFailed -> Icons.Default.Error to AppTheme.colors.error
                    isCancelled -> Icons.Default.Cancel to AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
                    else -> Icons.Default.HourglassEmpty to Color.Gray
                }
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))

                // Transfer type icon
                val typeIcon = if (item.type == TransferType.SEND)
                    Icons.Default.CloudUpload else Icons.Default.Download
                val typeColor = if (item.type == TransferType.SEND)
                    AppTheme.colors.primary else SuccessColor
                Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))

                // File icon
                val fileIcon = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile
                Icon(
                    fileIcon, null,
                    tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))

                // File name and details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isCancelled) AppTheme.colors.onSurface.copy(alpha = 0.4f) else AppTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.displaySize,
                            fontSize = 10.sp,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        if (item.speed.isNotBlank() && isActive) {
                            Text(
                                " • ${item.speed}",
                                fontSize = 10.sp,
                                color = AppTheme.colors.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Progress percentage for active
                if (isActive) {
                    Text(
                        "${item.progress}%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))

                // Action buttons
                if (isPending) {
                    IconButton(onClick = onMoveToFront, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.KeyboardDoubleArrowUp,
                            "Move to front",
                            tint = AppTheme.colors.primary.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (isPending || isActive) {
                    IconButton(onClick = onCancel, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            "Cancel",
                            tint = AppTheme.colors.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Error message
                if (isFailed && item.error != null) {
                    Text(
                        item.error,
                        fontSize = 9.sp,
                        color = AppTheme.colors.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    // Full destination path
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Destination: ",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Text(
                            item.destinationPath,
                            fontSize = 10.sp,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    // Active item progress bar and details
                    if (isActive) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { item.progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = AppTheme.colors.primary,
                            trackColor = AppTheme.colors.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${item.transferred} / ${item.total}  •  ${item.speed}",
                                fontSize = 10.sp,
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Text(
                                "Elapsed: ${item.elapsed}  •  ETA: ${item.eta}",
                                fontSize = 10.sp,
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    } else if (item.total.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Size: ${item.total}",
                            fontSize = 10.sp,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        if (item.status == TransferItemStatus.COMPLETED && item.elapsed.isNotBlank() && item.elapsed != "0s") {
                            Text(
                                "Time taken: ${item.elapsed}",
                                fontSize = 10.sp,
                                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
    }
}
