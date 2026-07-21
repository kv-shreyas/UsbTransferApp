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
    factory { DisconnectUsbUseCase(get()) }
    factory { ReceiveFileUseCase(get()) }
    factory { SendFileUseCase(get()) }
    factory { FetchFileUseCase(get()) }
    factory { FetchDirectoryUseCase(get()) }
    factory { ListDirectoryUseCase(get()) }
    factory { CancelTransferUseCase(get()) }
    factory { DeleteFileUseCase(get()) }
    factory { RenameFileUseCase(get()) }
    factory { CreateFolderUseCase(get()) }

    single { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
