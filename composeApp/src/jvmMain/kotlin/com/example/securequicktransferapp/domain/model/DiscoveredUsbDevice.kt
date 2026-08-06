package com.example.securequicktransferapp.domain.model

import org.usb4java.Device

/**
 * Domain model representing a physically discovered USB device on the host system.
 *
 * @property id Unique physical hardware identifier in format "bus_{busNumber}_port_{portPathStr}" (e.g. "bus_1_port_3").
 * @property busNumber The USB bus number (1..255).
 * @property portNumber The USB port number on the hub/bus (1..255).
 * @property vendorId The 16-bit USB Vendor ID (VID).
 * @property productId The 16-bit USB Product ID (PID).
 * @property isAoa True if the device is currently in Android Open Accessory (AOA) mode (PID 0x2D00 or 0x2D01).
 * @property deviceName Human-readable display name summarizing device identity and connection mode.
 * @property device The underlying usb4java Device handle. Holds a retained reference (+1 ref count).
 */
data class DiscoveredUsbDevice(
    val id: String,
    val busNumber: Int,
    val portNumber: Int,
    val vendorId: Int,
    val productId: Int,
    val isAoa: Boolean,
    val deviceName: String,
    val device: Device
)
