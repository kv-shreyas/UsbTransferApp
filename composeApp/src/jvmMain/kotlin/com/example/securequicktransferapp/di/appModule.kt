package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.driver.DriverInstallManager
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import com.example.securequicktransferapp.presentation.vm.TransferQueueDashboardViewModel
import org.koin.dsl.module

val appModule = module {
    // Zadig-backed driver install state machine — singleton so UI can observe it from anywhere
    single { DriverInstallManager() }
    // UsbDeviceManager receives the DriverInstallManager to trigger automatic WinUSB probing on Windows
    single { UsbDeviceManager(driverInstallManager = get()) }
    single { UsbSessionManager(get()) }
    factory { UsbConnection() }
    single { MainViewModel(sessionManager = get(), driverInstallManager = get()) }
    single { TransferQueueDashboardViewModel(get()) }
}
