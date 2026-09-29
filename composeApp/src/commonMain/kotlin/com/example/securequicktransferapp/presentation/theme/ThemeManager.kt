package com.example.securequicktransferapp.presentation.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

object ThemeManager {
    var isDarkTheme by mutableStateOf(false)
    var seedColor by mutableStateOf(Color(0xFF607D8B)) // Default Gray

    val availableColors = listOf(
        Color(0xFF2196F3), // Blue
        Color(0xFFF44336), // Red
        Color(0xFF4CAF50), // Green
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF9C27B0), // Purple
        Color(0xFFFF9800), // Orange
        Color(0xFF00BCD4), // Cyan
        Color(0xFF607D8B)  // Grey Gray
    )

    fun toggleTheme() {
        isDarkTheme = !isDarkTheme
    }

    fun updateSeedColor(color: Color) {
        seedColor = color
    }
}
