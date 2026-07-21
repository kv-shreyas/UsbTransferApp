package com.example.usbtransferapp.di

import com.example.usbtransferapp.data.usb.DelegatingUsbConnection
import com.example.usbtransferapp.data.usb.IUsbConnection
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
        sender: com.example.usbtransferapp.data.usb.HostCommandSender
    ): com.example.usbtransferapp.domain.repo.UsbRepository = sender

    @Provides
    fun provideListDirectoryUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.ListDirectoryUseCase(repo)

    @Provides
    fun provideSendFileUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.SendFileUseCase(repo)

    @Provides
    fun provideFetchFileUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.FetchFileUseCase(repo)

    @Provides
    fun provideFetchDirectoryUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.FetchDirectoryUseCase(repo)

    @Provides
    fun provideDeleteFileUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.DeleteFileUseCase(repo)

    @Provides
    @Singleton
    fun provideRenameFileUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.RenameFileUseCase(repo)

    @Provides
    @Singleton
    fun provideCreateFolderUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.CreateFolderUseCase(repo)

    @Provides
    fun provideCancelTransferUseCase(repo: com.example.usbtransferapp.domain.repo.UsbRepository) = com.example.usbtransferapp.domain.usecases.CancelTransferUseCase(repo)
}
