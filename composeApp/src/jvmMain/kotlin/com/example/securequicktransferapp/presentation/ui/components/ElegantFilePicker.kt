package com.example.securequicktransferapp.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.securequicktransferapp.presentation.theme.AppTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PickerMode {
    FILES_ONLY, DIRECTORIES_ONLY, FILES_AND_DIRECTORIES
}

@Composable
fun ElegantFilePicker(
    title: String,
    mode: PickerMode,
    allowMultiple: Boolean = true,
    initialDirectory: File = File(System.getProperty("user.home")),
    onFileSelected: (List<File>) -> Unit,
    onClose: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDirectory) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var selectedFiles by remember { mutableStateOf(setOf<File>()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(currentDir, refreshTrigger) {
        val loadedFiles = withContext(Dispatchers.IO) {
            val list = currentDir.listFiles()?.toList() ?: emptyList()
            val visibleFiles = list.filter { !it.isHidden && !it.name.startsWith(".") }
            visibleFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        files = loadedFiles
        selectedFiles = emptySet()
    }

    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { 
                showCreateFolderDialog = false
                newFolderName = "" 
            },
            title = { Text("Create New Folder", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            val newDir = File(currentDir, newFolderName.trim())
                            newDir.mkdirs()
                            refreshTrigger++
                            showCreateFolderDialog = false
                            newFolderName = ""
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showCreateFolderDialog = false
                    newFolderName = "" 
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AppTheme.colors.surface,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.85f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {},
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Column(
                    modifier = Modifier.fillMaxWidth().background(AppTheme.colors.surfaceVariant.copy(alpha = 0.3f)).padding(20.dp)
                ) {
                    Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = AppTheme.colors.onSurface)
                    Spacer(Modifier.height(16.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { currentDir.parentFile?.let { currentDir = it } },
                            enabled = currentDir.parentFile != null,
                            modifier = Modifier.background(AppTheme.colors.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).size(36.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, "Up", tint = AppTheme.colors.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        
                        IconButton(
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier.background(AppTheme.colors.primary.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).size(36.dp)
                        ) {
                            Icon(Icons.Default.CreateNewFolder, "New Folder", tint = AppTheme.colors.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AppTheme.colors.background,
                            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Home, null, tint = AppTheme.colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(currentDir.absolutePath, fontSize = 13.sp, color = AppTheme.colors.onSurface, maxLines = 1)
                            }
                        }
                    }
                }

                // File List
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                    items(files) { file ->
                        val isSelected = selectedFiles.contains(file)
                        val canSelect = when(mode) {
                            PickerMode.FILES_ONLY -> !file.isDirectory
                            PickerMode.DIRECTORIES_ONLY -> file.isDirectory
                            PickerMode.FILES_AND_DIRECTORIES -> true
                        }
                        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                        val modified = sdf.format(Date(file.lastModified()))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (file.isDirectory) {
                                        currentDir = file
                                    } else if (canSelect) {
                                        if (allowMultiple) {
                                            selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                        } else {
                                            selectedFiles = setOf(file)
                                        }
                                    }
                                }
                                .background(if (isSelected) AppTheme.colors.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (file.isDirectory) Color(0xFFFBC02D) else AppTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, color = AppTheme.colors.onSurface, fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal)
                                Text(modified, color = AppTheme.colors.onSurfaceVariant, fontSize = 11.sp)
                            }
                            
                            if (canSelect) {
                                if (allowMultiple) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedFiles = if (checked) selectedFiles + file else selectedFiles - file
                                        }
                                    )
                                } else if (mode != PickerMode.DIRECTORIES_ONLY && isSelected) {
                                    Icon(Icons.Default.Check, null, tint = AppTheme.colors.primary, modifier = Modifier.size(24.dp).padding(4.dp))
                                }
                            }
                        }
                    }
                }
                
                HorizontalDivider(color = AppTheme.colors.outlineVariant)

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onClose) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(16.dp))
                    
                    val canConfirmCurrentDir = mode == PickerMode.DIRECTORIES_ONLY && selectedFiles.isEmpty()
                    
                    Button(
                        onClick = {
                            if (canConfirmCurrentDir) {
                                onFileSelected(listOf(currentDir))
                            } else {
                                onFileSelected(selectedFiles.toList())
                            }
                            onClose()
                        },
                        enabled = selectedFiles.isNotEmpty() || canConfirmCurrentDir,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        val btnText = if (canConfirmCurrentDir) {
                            "Select this Folder"
                        } else if (!allowMultiple) {
                            "Select"
                        } else {
                            "Select (${selectedFiles.size})"
                        }
                        Text(btnText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
