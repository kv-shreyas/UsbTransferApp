package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.presentation.vm.MainViewModel
import com.example.securequicktransferapp.domain.repo.UsbRepository
import com.example.securequicktransferapp.data.repo.UsbRepositoryImpl
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.domain.usecases.*
import org.koin.dsl.module

val appModule = module {
    single { UsbDeviceManager() }
    single { UsbConnection() }

    single<UsbRepository> {
        UsbRepositoryImpl(get(), get())
    }

    factory { UsbUseCases(get()) }

    single { MainViewModel(get(), get()) }
}
