package com.example.securequicktransferapp.di

import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.channel.SecureChannel
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
    fun provideSecureChannel(
        connectionManager: UsbConnectionManager
    ): SecureChannel = SecureQtSdk.createChannel(connectionManager)
}
