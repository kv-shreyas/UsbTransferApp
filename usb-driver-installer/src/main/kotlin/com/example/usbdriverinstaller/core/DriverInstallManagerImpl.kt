package com.example.usbdriverinstaller.core

import com.example.usbdriverinstaller.api.DriverInstallListener
import com.example.usbdriverinstaller.api.DriverInstallResult
import com.example.usbdriverinstaller.api.DriverInstallState
import com.example.usbdriverinstaller.api.IUsbDriverInstallManager
import com.example.usbdriverinstaller.api.IUsbDriverInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default implementation of [IUsbDriverInstallManager].
 * Coordinates automated driver installation, status transitions, listener notifications,
 * and deduplication of installation attempts.
 */
class DriverInstallManagerImpl(
    private val installer: IUsbDriverInstaller
) : IUsbDriverInstallManager {

    private val tag = "DriverInstallManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _driverInstallState = MutableStateFlow<DriverInstallState>(DriverInstallState.Idle)
    override val driverInstallState: StateFlow<DriverInstallState> = _driverInstallState.asStateFlow()

    private val attemptedDevices = mutableSetOf<String>()
    private val listeners = CopyOnWriteArrayList<DriverInstallListener>()

    init {
        scope.launch {
            _driverInstallState.collect { state ->
                for (listener in listeners) {
                    try {
                        listener.onStateChanged(state)
                    } catch (e: Exception) {
                        println("[$tag] Listener notification error: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onDeviceNeedsDriver(vendorId: Int, productId: Int, deviceName: String) {
        val key = deviceKey(vendorId, productId)
        val current = _driverInstallState.value
        if (current is DriverInstallState.Installing) return
        if (key in attemptedDevices) {
            println("[$tag] Driver install for $key already attempted this session — skipping.")
            return
        }

        println("[$tag] Auto-installing WinUSB driver for $deviceName ($key)...")
        startInstall(vendorId, productId, deviceName)
    }

    override fun retryInstall(vendorId: Int, productId: Int, deviceName: String) {
        val key = deviceKey(vendorId, productId)
        attemptedDevices.remove(key)
        println("[$tag] Retrying WinUSB driver install for $deviceName ($key)...")
        startInstall(vendorId, productId, deviceName)
    }

    override fun dismiss() {
        _driverInstallState.value = DriverInstallState.Idle
    }

    override fun resetAttempts() {
        attemptedDevices.clear()
    }

    override suspend fun waitForInstall(timeoutMs: Long): Boolean {
        if (_driverInstallState.value !is DriverInstallState.Installing) {
            return _driverInstallState.value is DriverInstallState.Installed
        }
        val startTime = System.currentTimeMillis()
        while (_driverInstallState.value is DriverInstallState.Installing) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                return false
            }
            delay(250)
        }
        return _driverInstallState.value is DriverInstallState.Installed
    }

    override fun waitForInstallBlocking(timeoutMs: Long): Boolean = runBlocking {
        waitForInstall(timeoutMs)
    }

    override fun launchZadig() {
        scope.launch {
            installer.launchZadigGuiSuspend()
        }
    }

    override fun addListener(listener: DriverInstallListener) {
        listeners.add(listener)
        listener.onStateChanged(_driverInstallState.value)
    }

    override fun removeListener(listener: DriverInstallListener) {
        listeners.remove(listener)
    }

    private fun startInstall(vendorId: Int, productId: Int, deviceName: String) {
        val key = deviceKey(vendorId, productId)
        attemptedDevices.add(key)
        _driverInstallState.value = DriverInstallState.Installing(deviceName, vendorId, productId)

        scope.launch {
            val result = installer.installWinUsbSuspend(
                vendorId = vendorId,
                productId = productId,
                deviceName = deviceName
            )

            when (result) {
                is DriverInstallResult.Success, is DriverInstallResult.NotWindows -> {
                    println("[$tag] Driver installed successfully for $deviceName")
                    _driverInstallState.value = DriverInstallState.Installed(deviceName, vendorId, productId)
                    delay(3000)
                    _driverInstallState.value = DriverInstallState.Idle
                }
                is DriverInstallResult.AccessDenied -> {
                    println("[$tag] Driver install for $deviceName denied — UAC rejected.")
                    _driverInstallState.value = DriverInstallState.Failed(
                        deviceName = deviceName,
                        reason = "Administrator permission was denied. Please click 'Yes' on the Windows security prompt and try again.",
                        vendorId = vendorId,
                        productId = productId
                    )
                }
                is DriverInstallResult.DeviceNotFound -> {
                    println("[$tag] Driver install for $deviceName failed — device not found.")
                    _driverInstallState.value = DriverInstallState.Failed(
                        deviceName = deviceName,
                        reason = "Device not found during installation. Ensure the USB cable is securely connected.",
                        vendorId = vendorId,
                        productId = productId
                    )
                }
                is DriverInstallResult.ExtractionFailed -> {
                    println("[$tag] Driver install failed — extraction error.")
                    _driverInstallState.value = DriverInstallState.Failed(
                        deviceName = deviceName,
                        reason = "Internal error: could not extract installer binaries from JAR resources.",
                        vendorId = vendorId,
                        productId = productId
                    )
                }
                is DriverInstallResult.UnsignedDriverBlocked -> {
                    println("[$tag] Driver install blocked by Windows signature policy.")
                    _driverInstallState.value = DriverInstallState.Failed(
                        deviceName = deviceName,
                        reason = "Windows blocked the driver because it is not digitally signed.",
                        vendorId = vendorId,
                        productId = productId
                    )
                }
                is DriverInstallResult.Failure -> {
                    println("[$tag] Driver install failed with exit code: ${result.exitCode}")
                    _driverInstallState.value = DriverInstallState.Failed(
                        deviceName = deviceName,
                        reason = if (result.exitCode == 2) {
                            "WinUSB driver required for PID 0x${String.format("%04X", productId)}.\n\nTip: Enable 'USB Debugging' on Android to use the pre-installed WinUSB driver automatically, or click 'Open Zadig' below."
                        } else {
                            "Installation failed (code ${result.exitCode}): ${result.message.take(120)}"
                        },
                        vendorId = vendorId,
                        productId = productId
                    )
                }
            }
        }
    }

    private fun deviceKey(vendorId: Int, productId: Int): String =
        "VID_${String.format("%04X", vendorId)}_PID_${String.format("%04X", productId)}"
}
