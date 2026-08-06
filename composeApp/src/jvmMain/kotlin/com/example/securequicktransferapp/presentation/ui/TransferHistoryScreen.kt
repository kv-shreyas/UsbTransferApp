package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.background
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.securequicktransferapp.data.repo.HistoryEntry
import com.example.securequicktransferapp.data.repo.TransferHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistoryScreen(onBack: () -> Unit) {
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            history = TransferHistoryRepository.getHistory()
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer History", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear History")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppTheme.colors.primaryContainer,
                    titleContentColor = AppTheme.colors.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No transfer history found.", color = AppTheme.colors.onSurfaceVariant)
            }
        } else {
            val groupedHistory = history.groupBy { it.deviceName }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedHistory.forEach { (deviceName, entries) ->
                    item {
                        DeviceHistoryHeader(deviceName, entries.size)
                    }
                    items(entries) { entry ->
                        HistoryItemRow(entry)
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        if (showClearConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showClearConfirmDialog = false },
                title = { Text("Clear History") },
                text = { Text("Are you sure you want to clear all transfer history? This action cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        TransferHistoryRepository.clearHistory()
                        history = emptyList()
                        showClearConfirmDialog = false
                    }) {
                        Text("Clear", color = AppTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun HistoryItemRow(entry: HistoryEntry) {
    val isSend = entry.type == "SEND"
    val icon = if (isSend) Icons.Default.CloudUpload else Icons.Default.Download
    val iconColor = if (isSend) Color(0xFF42A5F5) else Color(0xFF66BB6A)
    val statusColor = when (entry.status) {
        "COMPLETED" -> Color(0xFF4CAF50)
        "FAILED" -> AppTheme.colors.error
        "CANCELLED" -> AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f)
        else -> AppTheme.colors.onSurfaceVariant
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = entry.type, tint = iconColor)
            }
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val displaySize = if (entry.size.isBlank()) "Unknown" else entry.size
                    HistoryDetailChip("Size", displaySize)
                    HistoryDetailChip("Time", entry.timeTaken)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.dateString,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(Modifier.width(8.dp))
            
            Surface(
                color = statusColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    entry.status,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
fun HistoryDetailChip(label: String, value: String) {
    Surface(
        color = AppTheme.colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label: ",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.8f)
            )
            Text(
                value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DeviceHistoryHeader(deviceName: String, count: Int) {
    Surface(
        color = AppTheme.colors.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Usb, contentDescription = null, tint = AppTheme.colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                deviceName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.onSecondaryContainer
            )
            Spacer(Modifier.weight(1f))
            Surface(
                color = AppTheme.colors.primary,
                shape = CircleShape
            ) {
                Text(
                    "$count transfers",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}
