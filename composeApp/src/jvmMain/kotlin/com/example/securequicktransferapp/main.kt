package com.example.securequicktransferapp

import androidx.compose.ui.unit.dp
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.securequicktransferapp.di.appModule
import com.example.securequicktransferapp.presentation.ui.MainScreen
import org.koin.core.context.GlobalContext.startKoin
import org.koin.java.KoinJavaComponent.getKoin
import com.example.securequicktransferapp.presentation.theme.SecureTransferTheme

fun main() {
    startKoin {
        modules(appModule)
    }

    application {
        val vm: MainViewModel = getKoin().get()
        val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
        val state = androidx.compose.ui.window.rememberWindowState(
            width = screenSize.width.dp,
            height = screenSize.height.dp
        )
        Window(
            onCloseRequest = {
                vm.disconnectAll()
                exitApplication()
            },
            state = state,
            title = "Secure Quick Transfer"
        ) {
            val isDarkTheme = com.example.securequicktransferapp.presentation.theme.ThemeManager.isDarkTheme
            
            SecureTransferTheme(darkTheme = isDarkTheme) {
                MainScreen(vm = vm, isDarkTheme = isDarkTheme, onThemeToggle = { com.example.securequicktransferapp.presentation.theme.ThemeManager.toggleTheme() })
            }
        }
    }
}
