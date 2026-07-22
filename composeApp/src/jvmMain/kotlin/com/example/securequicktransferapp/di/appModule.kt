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
