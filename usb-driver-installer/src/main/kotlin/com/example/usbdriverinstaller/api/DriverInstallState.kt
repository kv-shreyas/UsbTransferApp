package com.example.usbdriverinstaller.api

/**
 * State of the USB driver installer state machine.
 */
sealed class DriverInstallState {
    /** No pending driver action. Normal idle state. */
    object Idle : DriverInstallState()

    /** Driver installation is running in the background. UAC prompt is displayed to the user. */
    data class Installing(
        val deviceName: String,
        val vendorId: Int = 0,
        val productId: Int = 0
    ) : DriverInstallState()

    /** Driver was installed successfully. UI can show a success confirmation. */
    data class Installed(
        val deviceName: String,
        val vendorId: Int = 0,
        val productId: Int = 0
    ) : DriverInstallState()

    /**
     * Driver installation failed.
     * Contains user-friendly diagnostic reason, along with target VID/PID for retry.
     */
    data class Failed(
        val deviceName: String,
        val reason: String,
        val vendorId: Int,
        val productId: Int
    ) : DriverInstallState()
}
