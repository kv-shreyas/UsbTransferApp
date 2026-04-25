package com.example.usbtransferapp.presentation.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.usbtransferapp.domain.model.RemoteFile
import com.example.usbtransferapp.presentation.vm.MainViewModel
import java.io.File
import javax.swing.JFileChooser

@Composable
fun MainScreen(vm: MainViewModel) {
    var currentScreen by remember { mutableStateOf("explorer") }
    val state by vm.state.collectAsState()
    val remoteFiles by vm.remoteFiles.collectAsState()
    val currentPath by vm.currentRemotePath.collectAsState()
    val progress by vm.progressState.collectAsState()

    if (progress.isVisible) {
        TransferProgressDialog(progress, onCancel = { vm.cancelTransfer() })
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        // Sidebar
        Sidebar(vm, state, currentScreen, onNavigate = { currentScreen = it }, onDisconnect = { vm.disconnect() })

        // Main Content
        Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            if (currentScreen == "explorer") {
                Header("Remote File System", currentPath, onBack = { vm.navigateUp() }, onRefresh = { vm.refreshRemoteFiles() })
                
                Spacer(Modifier.height(24.dp))

                FileList(
                    files = remoteFiles,
                    modifier = Modifier.weight(1f),
                    onFolderClick = { vm.navigateTo(it) },
                    onFileFetch = { vm.fetchFile(it) },
                    onFileConvert = { vm.convertAnfFile(it) }
                )

                Spacer(Modifier.height(16.dp))
                
                ActionBar(onSendFile = {
                    val fileChooser = JFileChooser()
                    if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        vm.sendFile(fileChooser.selectedFile)
                    }
                })
            } else {
                AnfConverterScreen(vm, remoteFiles, currentPath)
            }
        }
    }
}

@Composable
fun Sidebar(vm: MainViewModel, state: String, currentScreen: String, onNavigate: (String) -> Unit, onDisconnect: () -> Unit) {
    Surface(
        modifier = Modifier.width(280.dp).fillMaxHeight(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Control Panel", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))

            NavigationItem("File Explorer", Icons.Default.Folder, currentScreen == "explorer") { onNavigate("explorer") }
            NavigationItem("ANF Converter", Icons.Default.Transform, currentScreen == "converter") { onNavigate("converter") }

            Spacer(Modifier.height(32.dp))
            ConnectionCard(state, onConnect = { vm.connect() }, onDisconnect = onDisconnect)

            Spacer(Modifier.height(32.dp))
            Text("Recent Transfers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            
            // Placeholder for transfer history
            Box(modifier = Modifier.weight(1f)) {
                Text("No active transfers", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            SecurityInfo()
        }
    }
}

@Composable
fun NavigationItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun ConnectionCard(state: String, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val isConnected = state == "Ready" || state.startsWith("Fetching") || state.startsWith("Sending") || state.contains("✅")
    val statusColor = if (isConnected) Color(0xFF4CAF50) else if (state.contains("Failed") || state.contains("Error")) Color.Red else Color(0xFFFFA500)

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(8.dp))
                Text(if (isConnected) "System Online" else "Disconnected", fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(12.dp))
            Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            
            if (isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.LinkOff, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect")
                }
                Spacer(Modifier.height(8.dp))
            }
            
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isConnected) Icons.Default.Refresh else Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isConnected) "Reconnect" else "Connect Device")
            }
        }
    }
}

@Composable
fun AnfConverterScreen(vm: MainViewModel, files: List<RemoteFile>, path: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        Header("ANF to CSV Converter", path, onBack = { vm.navigateUp() }, onRefresh = { vm.refreshRemoteFiles() })
        
        Spacer(Modifier.height(24.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Remote Conversion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Select an .anf file from the device to convert it to CSV format.", style = MaterialTheme.typography.bodyMedium)
            }
            
            Button(
                onClick = {
                    val fileChooser = JFileChooser()
                    fileChooser.fileFilter = object : javax.swing.filechooser.FileFilter() {
                        override fun accept(f: File) = f.isDirectory || f.name.endsWith(".anf")
                        override fun getDescription() = "ANF Log Files (*.anf)"
                    }
                    if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        vm.convertLocalAnfFile(fileChooser.selectedFile)
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.FileUpload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Choose Local .anf File")
            }
        }
        
        Spacer(Modifier.height(16.dp))

        FileList(
            files = files.filter { it.isDirectory || it.name.endsWith(".anf") },
            modifier = Modifier.weight(1f),
            onFolderClick = { vm.navigateTo(it) },
            onFileFetch = { vm.fetchFile(it) },
            onFileConvert = { vm.convertAnfFile(it) }
        )
    }
}

@Composable
fun Header(title: String, path: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(path, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Refresh")
        }
    }
}

@Composable
fun FileList(
    files: List<RemoteFile>, 
    modifier: Modifier = Modifier,
    onFolderClick: (RemoteFile) -> Unit, 
    onFileFetch: (RemoteFile) -> Unit,
    onFileConvert: (RemoteFile) -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface
    ) {
        if (files.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    Text("No compatible files found", color = Color.Gray)
                }
            }
        } else {
            LazyColumn {
                items(files) { file ->
                    FileRow(file, onFolderClick, onFileFetch, onFileConvert)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
fun FileRow(
    file: RemoteFile, 
    onFolderClick: (RemoteFile) -> Unit, 
    onFileFetch: (RemoteFile) -> Unit,
    onFileConvert: (RemoteFile) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { if (file.isDirectory) onFolderClick(file) }.padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (file.isDirectory) Color(0xFFFFC107) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, fontWeight = FontWeight.Medium)
            Text(if (file.isDirectory) "Directory" else formatSize(file.size), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (!file.isDirectory) {
            if (file.name.endsWith(".anf")) {
                Button(onClick = { onFileConvert(file) }, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.Transform, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Convert to CSV")
                }
            }
            TextButton(onClick = { onFileFetch(file) }) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Fetch")
            }
        }
    }
}

@Composable
fun ActionBar(onSendFile: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Text("Want to transfer files to Android?", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Button(onClick = onSendFile) {
                Text("Upload to Device")
            }
        }
    }
}

@Composable
fun SecurityInfo() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Divider(thickness = 0.5.dp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Encrypted Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text("ECDH-P256 / AES-GCM", fontSize = 10.sp, color = Color.Gray)
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

@Composable
fun TransferProgressDialog(
    progress: com.example.usbtransferapp.presentation.vm.MainViewModel.TransferProgress,
    onCancel: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties()
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.width(400.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Transferring Data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(progress.filename, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(24.dp))

                LinearProgressIndicator(
                    progress = progress.percentage / 100f,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text("Speed", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(progress.speed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Elapsed", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(progress.elapsed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Remaining", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text(progress.eta, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Progress", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        Text("${progress.percentage}%", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${progress.transferred} / ${progress.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                Spacer(Modifier.height(24.dp))
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel Transfer")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Please do not disconnect the USB cable", style = MaterialTheme.typography.labelSmall, color = Color.Red.copy(alpha = 0.5f))
            }
        }
    }
}
