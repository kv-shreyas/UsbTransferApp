package com.example.usbdriverinstaller.api

/**
 * Listener interface for monitoring driver installation state changes.
 * Functional interface for SAM conversion in Java (e.g. `installer.addListener(state -> ...)`).
 */
fun interface DriverInstallListener {
    fun onStateChanged(state: DriverInstallState)
}
