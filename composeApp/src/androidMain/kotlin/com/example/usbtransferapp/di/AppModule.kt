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
}
