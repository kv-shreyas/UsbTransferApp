package com.example.securequicktransferapp.di

import com.example.securequicktransferapp.data.usb.command.ICommandHandler
import com.example.securequicktransferapp.data.usb.command.handlers.CreateFolderHandler
import com.example.securequicktransferapp.data.usb.command.handlers.DeleteHandler
import com.example.securequicktransferapp.data.usb.command.handlers.DisconnectHandler
import com.example.securequicktransferapp.data.usb.command.handlers.FetchDirHandler
import com.example.securequicktransferapp.data.usb.command.handlers.FetchFileHandler
import com.example.securequicktransferapp.data.usb.command.handlers.GetAppDetailsHandler
import com.example.securequicktransferapp.data.usb.command.handlers.ListDirHandler
import com.example.securequicktransferapp.data.usb.command.handlers.ReceiveDirHandler
import com.example.securequicktransferapp.data.usb.command.handlers.ReceiveFileHandler
import com.example.securequicktransferapp.data.usb.command.handlers.RenameHandler
import com.example.securequicktransferapp.data.usb.command.handlers.SetDeviceIdHandler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Hilt multibinding module that collects all [ICommandHandler] implementations
 * into a `Set<ICommandHandler>` injected into [CommandHandlerRegistry].
 *
 * Open-Closed Principle (OCP):
 * To add a new command in the future (e.g. `GetAppVersionHandler`), simply create
 * the handler class implementing [ICommandHandler] and add a `@Binds @IntoSet`
 * method here. Zero lines of existing command dispatcher or handler code need to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommandHandlerModule {

    @Binds
    @IntoSet
    abstract fun bindListDirHandler(impl: ListDirHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindReceiveFileHandler(impl: ReceiveFileHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindReceiveDirHandler(impl: ReceiveDirHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindFetchFileHandler(impl: FetchFileHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindFetchDirHandler(impl: FetchDirHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindDeleteHandler(impl: DeleteHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindRenameHandler(impl: RenameHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindCreateFolderHandler(impl: CreateFolderHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindSetDeviceIdHandler(impl: SetDeviceIdHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindGetAppDetailsHandler(impl: GetAppDetailsHandler): ICommandHandler

    @Binds
    @IntoSet
    abstract fun bindDisconnectHandler(impl: DisconnectHandler): ICommandHandler
}
