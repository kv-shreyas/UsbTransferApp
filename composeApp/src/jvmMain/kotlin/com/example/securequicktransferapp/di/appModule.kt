package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import org.koin.dsl.module

val appModule = module {
    single { UsbDeviceManager() }
    single { UsbSessionManager(get()) }
    factory { UsbConnection() }
    single { MainViewModel(get()) }
}
