package com.example.securequicktransferapp.domain.model

/**
 * Sealed representation of a USB device session lifecycle status.
 */
sealed class DeviceSessionStatus {
    object Disconnected : DeviceSessionStatus()
    object Connecting : DeviceSessionStatus()
    object Ready : DeviceSessionStatus()
    data class Error(val message: String) : DeviceSessionStatus()
}

/**
 * Domain model representing the reactive state of an isolated USB device session.
 *
 * @property deviceId Unique physical hardware identifier (e.g. "bus_1_port_3").
 * @property deviceName Human-readable display name summarizing device identity.
 * @property status Current session lifecycle status (Disconnected, Connecting, Ready, Error).
 * @property remoteFiles List of files currently loaded in the device's remote file browser view.
 * @property currentPath Current active remote directory path (defaults to "/sdcard").
 * @property progress Active transfer progress metrics for this specific device session.
 * @property isAoaMode True if the device has successfully negotiated AOA accessory transport.
 */
data class UsbSessionState(
    val deviceId: String,
    val deviceName: String,
    val status: DeviceSessionStatus = DeviceSessionStatus.Disconnected,
    val remoteFiles: List<RemoteFile> = emptyList(),
    val currentPath: String = "/sdcard",
    val progress: TransferProgress = TransferProgress(),
    val isAoaMode: Boolean = false
) {
    val currentRemotePath: String
        get() = currentPath
}
