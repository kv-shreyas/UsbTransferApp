package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.repo.ClientDeviceInfoRepositoryImpl
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.repo.IClientDeviceInfoRepository
import com.example.securequicktransferapp.presentation.vm.ClientDeviceInfoViewModel
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import com.example.securequicktransferapp.presentation.vm.TransferQueueDashboardViewModel
import com.example.usbdriverinstaller.api.IUsbDriverInstallManager
import com.example.usbdriverinstaller.api.UsbDriverInstallerFactory
import org.koin.dsl.module

val appModule = module {
    // Zadig-backed driver install state machine — singleton so UI can observe it from anywhere
    single<IUsbDriverInstallManager> { UsbDriverInstallerFactory.createManager() }
    // UsbDeviceManager receives the DriverInstallManager to trigger automatic WinUSB probing on Windows
    single { UsbDeviceManager(driverInstallManager = get()) }
    single { UsbSessionManager(get()) }
    factory { UsbConnection() }
    single <IClientDeviceInfoRepository> { ClientDeviceInfoRepositoryImpl(sessionManager = get()) }
    single { ClientDeviceInfoViewModel(repository = get(), sessionManager = get()) }
    single { MainViewModel(sessionManager = get(), driverInstallManager = get()) }
    single { TransferQueueDashboardViewModel(get()) }
}
