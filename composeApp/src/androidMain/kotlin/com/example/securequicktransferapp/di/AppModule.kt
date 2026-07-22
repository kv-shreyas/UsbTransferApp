package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.DelegatingUsbConnection
import com.example.securequicktransferapp.data.usb.IUsbConnection
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideIUsbConnection(
        delegating: DelegatingUsbConnection
    ): IUsbConnection = delegating

    @Provides
    @Singleton
    fun provideUsbRepository(
        sender: com.example.securequicktransferapp.data.usb.HostCommandSender
    ): com.example.securequicktransferapp.domain.repo.UsbRepository = sender

    @Provides
    fun provideListDirectoryUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.ListDirectoryUseCase(repo)

    @Provides
    fun provideSendFileUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.SendFileUseCase(repo)

    @Provides
    fun provideFetchFileUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.FetchFileUseCase(repo)

    @Provides
    fun provideFetchDirectoryUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.FetchDirectoryUseCase(repo)

    @Provides
    fun provideDeleteFileUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.DeleteFileUseCase(repo)

    @Provides
    @Singleton
    fun provideRenameFileUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.RenameFileUseCase(repo)

    @Provides
    @Singleton
    fun provideCreateFolderUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.CreateFolderUseCase(repo)

    @Provides
    fun provideCancelTransferUseCase(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.CancelTransferUseCase(repo)
}
