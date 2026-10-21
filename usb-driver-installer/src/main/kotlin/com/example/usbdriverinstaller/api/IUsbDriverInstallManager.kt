package com.example.usbdriverinstaller.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Reactive state machine interface that manages automated background driver installations.
 * Provides a reactive [driverInstallState] for Jetpack Compose/Flow UI observers and
 * a callback-based [addListener] for Java or non-coroutine consumers.
 */
interface IUsbDriverInstallManager {

    /**
     * Observable [StateFlow] emitting the current [DriverInstallState].
     */
    val driverInstallState: StateFlow<DriverInstallState>

    /**
     * Trigger automatic driver installation when a device lacks a compatible WinUSB driver.
     * Prevents duplicate attempts within the same session.
     *
     * @param vendorId USB Vendor ID
     * @param productId USB Product ID
     * @param deviceName Display name of the device
     */
    fun onDeviceNeedsDriver(vendorId: Int, productId: Int, deviceName: String)

    /**
     * Retries installation after a failure by clearing previous session records.
     */
    fun retryInstall(vendorId: Int, productId: Int, deviceName: String)

    /**
     * Dismisses the active dialog/notification state, resetting back to [DriverInstallState.Idle].
     */
    fun dismiss()

    /**
     * Clears session-level attempt history to allow re-trying all previously attempted devices.
     */
    fun resetAttempts()

    /**
     * Suspends until any ongoing driver installation completes or reaches timeout.
     * @return true if installation succeeded or is already installed, false otherwise.
     */
    suspend fun waitForInstall(timeoutMs: Long = 45_000L): Boolean

    /**
     * Blocking version of [waitForInstall] for Java callers.
     */
    fun waitForInstallBlocking(timeoutMs: Long = 45_000L): Boolean

    /**
     * Launches the full interactive Zadig GUI.
     */
    fun launchZadig()

    /**
     * Registers a listener for state changes (Java friendly).
     */
    fun addListener(listener: DriverInstallListener)

    /**
     * Removes a registered listener.
     */
    fun removeListener(listener: DriverInstallListener)
}
