package com.example.securequicktransferapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
            width = (screenSize.width *0.85).dp,
            height = (screenSize.height*0.8).dp
        )
        Window(
            onCloseRequest = {
                vm.disconnectAll()
                exitApplication()
            },
            state = state,
            title = "Secure Quick Transfer"
        ) {
            var isDarkTheme by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            
            SecureTransferTheme(darkTheme = isDarkTheme) {
                MainScreen(vm = vm, isDarkTheme = isDarkTheme, onThemeToggle = { isDarkTheme = !isDarkTheme })
            }
        }
    }
}
