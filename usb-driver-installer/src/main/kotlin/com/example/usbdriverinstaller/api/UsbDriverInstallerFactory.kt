package com.example.usbdriverinstaller.api

import com.example.usbdriverinstaller.core.DriverInstallManagerImpl
import com.example.usbdriverinstaller.core.ZadigDriverInstallerImpl

/**
 * Factory class for creating instances of [IUsbDriverInstaller] and [IUsbDriverInstallManager].
 * Fully accessible from Java and Kotlin.
 */
object UsbDriverInstallerFactory {

    private val defaultInstance: IUsbDriverInstaller by lazy {
        ZadigDriverInstallerImpl()
    }

    /**
     * Creates a new standalone [IUsbDriverInstaller] instance.
     */
    @JvmStatic
    fun createInstaller(): IUsbDriverInstaller = ZadigDriverInstallerImpl()

    /**
     * Returns the shared singleton [IUsbDriverInstaller] instance.
     */
    @JvmStatic
    fun getDefaultInstaller(): IUsbDriverInstaller = defaultInstance

    /**
     * Creates a new reactive [IUsbDriverInstallManager] instance backed by the specified [installer].
     */
    @JvmStatic
    @JvmOverloads
    fun createManager(
        installer: IUsbDriverInstaller = getDefaultInstaller()
    ): IUsbDriverInstallManager = DriverInstallManagerImpl(installer)
}
