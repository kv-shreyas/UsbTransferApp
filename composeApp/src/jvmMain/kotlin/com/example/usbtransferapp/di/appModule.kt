package com.example.usbtransferapp.di

import com.example.usbtransferapp.presentation.vm.MainViewModel
import com.example.usbtransferapp.domain.repo.UsbRepository
import com.example.usbtransferapp.data.repo.UsbRepositoryImpl
import com.example.usbtransferapp.data.usb.UsbConnection
import com.example.usbtransferapp.data.usb.UsbDeviceManager
import com.example.usbtransferapp.domain.usecases.*
import org.koin.dsl.module

val appModule = module {
    single { UsbDeviceManager() }
    single { UsbConnection() }

    single<UsbRepository> {
        UsbRepositoryImpl(get(), get())
    }

    factory { ConnectUsbUseCase(get()) }
    factory { ReceiveFileUseCase(get()) }
    factory { SendFileUseCase(get()) }
    factory { FetchFileUseCase(get()) }
    factory { ListDirectoryUseCase(get()) }

    single { MainViewModel(get(), get(), get(), get(), get()) }
}
