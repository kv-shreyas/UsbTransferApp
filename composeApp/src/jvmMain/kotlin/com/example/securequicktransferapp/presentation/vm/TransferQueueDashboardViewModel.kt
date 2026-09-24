package com.example.securequicktransferapp.presentation.vm

import com.example.securequicktransferapp.data.usb.ConflictResolution
import com.example.securequicktransferapp.data.usb.TransferQueue
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.TransferProgress
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.flow.StateFlow

class TransferQueueDashboardViewModel(
    private val sessionManager: UsbSessionManager
) {
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = sessionManager.sessionsState

    fun getDeviceQueue(deviceId: String): TransferQueue? {
        return sessionManager.getSession(deviceId)?.transferQueue
    }

    fun cancelAll(deviceId: String) {
        val session = sessionManager.getSession(deviceId) ?: return
        session.transferQueue.cancelAll()
        session.updateProgress(TransferProgress())
        session.updateStatus(DeviceSessionStatus.Ready)
    }

    fun cancelQueueItem(deviceId: String, itemId: String) {
        sessionManager.getSession(deviceId)?.transferQueue?.cancelItem(itemId)
    }

    fun retryQueueItem(deviceId: String, itemId: String) {
        sessionManager.getSession(deviceId)?.transferQueue?.retry(itemId)
    }

    fun resolveConflict(deviceId: String, itemId: String, resolution: ConflictResolution) {
        sessionManager.getSession(deviceId)?.transferQueue?.resolveConflict(itemId, resolution)
    }

    fun clearFinishedQueue(deviceId: String) {
        sessionManager.getSession(deviceId)?.transferQueue?.clearFinished()
    }

    fun moveQueueItemToFront(deviceId: String, itemId: String) {
        sessionManager.getSession(deviceId)?.transferQueue?.moveToFront(itemId)
    }
}
