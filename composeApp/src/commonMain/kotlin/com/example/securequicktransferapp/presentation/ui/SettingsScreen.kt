package com.example.securequicktransferapp.presentation.ui

import androidx.compose.foundation.clickable
import com.example.securequicktransferapp.presentation.theme.AppTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onBack: () -> Unit,
    onShowAbout: (() -> Unit)? = null,
    onExitApp: (() -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppTheme.colors.primaryContainer,
                    titleContentColor = AppTheme.colors.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text("Appearance", style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AppTheme.colors.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode, 
                        contentDescription = "Theme Icon",
                        tint = AppTheme.colors.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dark Theme", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("Switch between Light and Dark aesthetics", style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
                    }
                    Switch(
                        checked = isDarkTheme,
                        onCheckedChange = { onThemeToggle() }
                    )
                }
            }

            if (onShowAbout != null || onExitApp != null) {
                Spacer(Modifier.height(32.dp))
                Text("System", style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AppTheme.colors.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column {
                        if (onShowAbout != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onShowAbout)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(androidx.compose.material.icons.Icons.Default.Info, contentDescription = "About", tint = AppTheme.colors.onSurfaceVariant)
                                Spacer(Modifier.width(16.dp))
                                Text("About Application", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (onShowAbout != null && onExitApp != null) {
                            HorizontalDivider(color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.2f))
                        }
                        if (onExitApp != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(onClick = onExitApp)
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(androidx.compose.material.icons.Icons.Default.ExitToApp, contentDescription = "Exit App", tint = AppTheme.colors.error)
                                Spacer(Modifier.width(16.dp))
                                Text("Exit Application", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = AppTheme.colors.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
