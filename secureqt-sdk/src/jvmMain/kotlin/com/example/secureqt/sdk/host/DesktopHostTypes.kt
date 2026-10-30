package com.example.secureqt.sdk.host

import com.example.secureqt.sdk.logging.ILogger
import kotlinx.coroutines.flow.StateFlow
import org.usb4java.Device

/**
 * A physically discovered Android USB device on the host system.
 *
 * @property id Unique physical hardware identifier in format "bus_{busNumber}_port_{portPathStr}" (e.g. "bus_1_port_3").
 * @property busNumber The USB bus number (1..255).
 * @property portNumber The USB port number on the hub/bus (1..255).
 * @property vendorId The 16-bit USB Vendor ID (VID).
 * @property productId The 16-bit USB Product ID (PID).
 * @property isAoa True if the device is currently in Android Open Accessory (AOA) mode.
 * @property deviceName Human-readable display name summarizing device identity and connection mode.
 * @property device The underlying usb4java Device handle. Holds a retained reference (+1 ref count).
 */
data class DesktopUsbDevice(
    val id: String,
    val busNumber: Int,
    val portNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String,
    val device: Device
)

/**
 * Connects the host layer to whatever installs USB drivers on the user's machine.
 * Optional: leave it out if drivers are handled elsewhere.
 */
interface UsbDriverSupport {
    /** A device was found that libusb cannot open because it has no compatible driver. */
    fun onDeviceNeedsDriver(vendorId: Int, productId: Int, deviceName: String)

    /** True while a driver install is underway. */
    fun isInstalling(): Boolean

    /** Suspends until the active install finishes. Returns true if the driver is ready to use. */
    suspend fun waitForInstall(timeoutMs: Long): Boolean
}

/**
 * A device session that [DesktopUsbSessionManager] can track.
 *
 * @param T The state type published for this session.
 */
interface ManagedUsbSession<T> {
    val deviceId: String
    val sessionState: StateFlow<T>

    /** True while [connect] is running. A connecting session survives the device briefly vanishing during its AOA switch. */
    val isConnecting: Boolean

    /** True when the session has completed its handshake. */
    val isReady: Boolean

    /** True while the native transport is open. A ready session whose transport closed is dropped and re-detected. */
    val isTransportOpen: Boolean

    suspend fun connect(): Boolean

    /** Closes the transport and releases the session's native device reference. */
    fun disconnect()
}

/** Default [ILogger]: writes to standard output. */
object StdoutLogger : ILogger {
    override fun d(tag: String, message: String) = println("[$tag] $message")
    override fun i(tag: String, message: String) = println("[$tag] $message")
    override fun w(tag: String, message: String, throwable: Throwable?) =
        println("[$tag] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
    override fun e(tag: String, message: String, throwable: Throwable?) =
        println("[$tag] $message" + (throwable?.let { ": ${it.message}" } ?: ""))
}
