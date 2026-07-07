package com.example.usbtransferapp

import androidx.compose.ui.unit.dp
import com.example.usbtransferapp.presentation.vm.MainViewModel
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.example.usbtransferapp.di.appModule
import com.example.usbtransferapp.presentation.ui.MainScreen
import org.koin.core.context.GlobalContext.startKoin
import org.koin.java.KoinJavaComponent.getKoin

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
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Secure Quick Transfer"
        ) {
            MainScreen(vm)
        }
    }
}
