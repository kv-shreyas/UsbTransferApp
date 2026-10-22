package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.command.HostCommandSender
import com.example.securequicktransferapp.data.usb.connection.IUsbConnection
import com.example.securequicktransferapp.data.usb.connection.UsbConnectionManager
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
        connectionManager: UsbConnectionManager
    ): IUsbConnection = connectionManager

    @Provides
    @Singleton
    fun provideUsbRepository(
        sender: HostCommandSender
    ): com.example.securequicktransferapp.domain.repo.UsbRepository = sender

    @Provides
    @Singleton
    fun provideUsbUseCases(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) =
        com.example.securequicktransferapp.domain.usecases.UsbUseCases(repo)
}
