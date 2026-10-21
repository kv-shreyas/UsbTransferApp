package com.example.usbdriverinstaller.api

/**
 * Supported driver types for USB installation.
 * Corresponds to wdi-simple.exe driver types.
 */
enum class DriverType(val typeId: Int, val description: String) {
    WINUSB(0, "WinUSB"),
    LIBUSB_WIN32(1, "libusb-win32"),
    LIBUSBK(2, "libusbK");

    companion object {
        @JvmStatic
        fun fromId(id: Int): DriverType = entries.firstOrNull { it.typeId == id } ?: WINUSB
    }
}
