package com.example.usbdriverinstaller.api

import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Core interface for Windows USB Driver Installation using Zadig / wdi-simple.
 * Provides both synchronous/blocking and asynchronous methods for seamless Java and Kotlin integration.
 */
interface IUsbDriverInstaller {

    /**
     * Checks whether the current operating system is Windows.
     */
    fun isWindows(): Boolean

    /**
     * Heuristically checks if a compatible WinUSB, libusbK, or libusb0 driver is currently
     * bound to the device in the Windows Registry.
     *
     * @param vendorId USB Vendor ID (e.g. 0x18D1)
     * @param productId USB Product ID (e.g. 0x4EE2)
     * @return true if a compatible service is found, false otherwise.
     */
    fun isDriverAlreadyInstalled(vendorId: Int, productId: Int): Boolean

    /**
     * Installs the WinUSB driver for the specified USB device (synchronous/blocking).
     *
     * @param vendorId USB Vendor ID (e.g. 0x18D1)
     * @param productId USB Product ID (e.g. 0x2D00)
     * @param deviceName Human-readable device description
     * @param timeoutMs Timeout in milliseconds (default 120,000 ms)
     * @return [DriverInstallResult] indicating the outcome
     */
    fun installWinUsb(
        vendorId: Int,
        productId: Int,
        deviceName: String = "Android USB Device",
        timeoutMs: Long = 120_000L
    ): DriverInstallResult

    /**
     * Asynchronously installs the WinUSB driver (Java friendly [CompletableFuture]).
     */
    fun installWinUsbAsync(
        vendorId: Int,
        productId: Int,
        deviceName: String = "Android USB Device",
        timeoutMs: Long = 120_000L
    ): CompletableFuture<DriverInstallResult>

    /**
     * Kotlin Coroutines suspend version of [installWinUsb].
     */
    suspend fun installWinUsbSuspend(
        vendorId: Int,
        productId: Int,
        deviceName: String = "Android USB Device",
        timeoutMs: Long = 120_000L
    ): DriverInstallResult

    /**
     * Launches the full interactive Zadig GUI in elevated mode (blocking).
     *
     * @param timeoutMs Timeout waiting for the GUI process to terminate (default 300,000 ms)
     * @return true if exited with code 0, false otherwise
     */
    fun launchZadigGui(timeoutMs: Long = 300_000L): Boolean

    /**
     * Asynchronously launches the interactive Zadig GUI (Java friendly [CompletableFuture]).
     */
    fun launchZadigGuiAsync(timeoutMs: Long = 300_000L): CompletableFuture<Boolean>

    /**
     * Kotlin Coroutines suspend version of [launchZadigGui].
     */
    suspend fun launchZadigGuiSuspend(timeoutMs: Long = 300_000L): Boolean

    /**
     * Extracts all bundled tools (wdi-simple.exe, zadig.exe, driver_pkg, usb_driver)
     * into the specified directory.
     *
     * @param destinationDirectory Directory to unpack tools into
     * @return true if extraction succeeded, false otherwise
     */
    fun extractTools(destinationDirectory: File): Boolean

    /**
     * Cleans up conflicting OEM driver packages (e.g. Samsung ssudbus or CDC serial usbser)
     * claiming the given device ID.
     */
    fun cleanupConflictingDrivers(vendorId: Int, productId: Int): Boolean

    /**
     * Rescans the PnP device tree and restarts the target USB device.
     */
    fun rescanAndRestartDevice(vendorId: Int, productId: Int): Boolean
}
