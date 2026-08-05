package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.command.HostCommandSender

import com.example.securequicktransferapp.data.usb.connection.DelegatingUsbConnection
import com.example.securequicktransferapp.data.usb.connection.IUsbConnection
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
        sender: com.example.securequicktransferapp.data.usb.command.HostCommandSender
    ): com.example.securequicktransferapp.domain.repo.UsbRepository = sender

    @Provides
    @Singleton
    fun provideUsbUseCases(repo: com.example.securequicktransferapp.domain.repo.UsbRepository) = com.example.securequicktransferapp.domain.usecases.UsbUseCases(repo)
}
