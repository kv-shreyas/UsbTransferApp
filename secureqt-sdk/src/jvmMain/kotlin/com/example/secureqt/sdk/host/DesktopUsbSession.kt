package com.example.secureqt.sdk.host

import com.example.secureqt.sdk.client.ISecureTransferClient
import com.example.secureqt.sdk.logging.ILogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle status of a [DesktopUsbSession]. */
sealed class DesktopSessionStatus {
    object Disconnected : DesktopSessionStatus()
    object Connecting : DesktopSessionStatus()
    object Ready : DesktopSessionStatus()
    data class Error(val message: String) : DesktopSessionStatus()
}

/** Published state of a [DesktopUsbSession]. */
data class DesktopSessionState(
    val deviceId: String,
    val deviceName: String,
    val status: DesktopSessionStatus = DesktopSessionStatus.Disconnected,
    val isAoaMode: Boolean = false
)

/**
 * Ready-made session for one physical device. Once [connect] returns true,
 * use [client] for file transfers and commands.
 */
class DesktopUsbSession(
    val device: DesktopUsbDevice,
    private val deviceManager: DesktopUsbDeviceManager,
    private val connection: DesktopUsbConnection = DesktopUsbConnection(),
    private val logger: ILogger = StdoutLogger
) : ManagedUsbSession<DesktopSessionState> {

    private val TAG = "DesktopUsbSession"
    private val link = DesktopAoaLink(deviceManager, connection, device.id, logger)
    private val deviceReleased = AtomicBoolean(false)

    val client: ISecureTransferClient = link.client

    override val deviceId: String = device.id

    private val _sessionState = MutableStateFlow(
        DesktopSessionState(deviceId, device.deviceName, isAoaMode = device.isAoa)
    )
    override val sessionState: StateFlow<DesktopSessionState> = _sessionState.asStateFlow()

    override val isConnecting: Boolean
        get() = _sessionState.value.status is DesktopSessionStatus.Connecting

    override val isReady: Boolean
        get() = _sessionState.value.status is DesktopSessionStatus.Ready

    override val isTransportOpen: Boolean
        get() = connection.isOpen()

    override suspend fun connect(): Boolean {
        _sessionState.update { it.copy(status = DesktopSessionStatus.Connecting) }
        val success = try {
            withContext(Dispatchers.IO) { link.connect() }
        } catch (e: Exception) {
            logger.e(TAG, "Connection failed with exception for device $deviceId: ${e.message}")
            false
        }
        _sessionState.update {
            it.copy(
                status = if (success) DesktopSessionStatus.Ready else DesktopSessionStatus.Error("Connection or handshake failed"),
                isAoaMode = link.isAoaMode
            )
        }
        return success
    }

    /** Safe to call more than once: the native device reference is released only the first time. */
    override fun disconnect() {
        _sessionState.update { it.copy(status = DesktopSessionStatus.Disconnected) }
        try {
            link.disconnect()
        } catch (e: Exception) {
            logger.e(TAG, "Error during disconnect for $deviceId: ${e.message}")
        }
        if (deviceReleased.compareAndSet(false, true)) {
            deviceManager.releaseDevice(device.device)
        }
    }
}
