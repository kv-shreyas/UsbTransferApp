package com.example.usbtransferapp

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
        Window(onCloseRequest = ::exitApplication) {
            MainScreen(vm)
        }
    }
}
