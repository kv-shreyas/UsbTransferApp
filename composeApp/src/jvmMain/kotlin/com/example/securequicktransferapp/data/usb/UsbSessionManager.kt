package com.example.securequicktransferapp.data.usb

import com.example.secureqt.sdk.host.DesktopUsbSessionManager
import com.example.securequicktransferapp.domain.model.UsbSessionState

/**
 * App-level session manager: the SDK's [DesktopUsbSessionManager] owns polling and native
 * handle lifecycle, while each [UsbSession] adds this app's transfer queue and UI state.
 */
class UsbSessionManager(
    deviceManager: UsbDeviceManager
) : DesktopUsbSessionManager<UsbSession, UsbSessionState>(
    deviceManager = deviceManager,
    sessionFactory = { device ->
        UsbSession(deviceId = device.id, discoveredDevice = device, deviceManager = deviceManager)
    }
) {
    /**
     * Re-publishes current states of all active sessions into [sessionsState].
     */
    fun updateSessionsStateMap() = publishSessionsState()

    /**
     * Updates state for a given session and refreshes [sessionsState].
     */
    fun updateSessionState(deviceId: String, transform: (UsbSessionState) -> UsbSessionState) {
        val session = getSession(deviceId) ?: return
        session.updateState(transform)
        updateSessionsStateMap()
    }
}
