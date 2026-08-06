package com.example.securequicktransferapp.fixtures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Fake UsbSessionManager implementation for E2E opaque-box requirement testing.
 */
class FakeUsbSessionManager(
    val hardwareFixture: FakeUsbHardwareFixture
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()

    private val _sessionsState = MutableStateFlow<Map<String, UsbSessionState>>(emptyMap())
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = _sessionsState.asStateFlow()

    private var pollingJob: Job? = null

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (true) {
                pollDevices()
                delay(100)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun pollDevices() {
        val discovered = hardwareFixture.discoverAndroidDevices()
        val currentIds = discovered.map { it.id }.toSet()

        // Remove disconnected sessions
        val toRemove = activeSessions.keys.filter { it !in currentIds }
        toRemove.forEach { id ->
            val sess = activeSessions.remove(id)
            sess?.isConnected = false
        }

        // Add or update active sessions
        discovered.forEach { dev ->
            if (!activeSessions.containsKey(dev.id)) {
                val session = UsbSession(
                    deviceId = dev.id,
                    deviceName = dev.deviceName,
                    isAoaMode = dev.isAoa
                )
                activeSessions[dev.id] = session
            } else {
                activeSessions[dev.id]?.updateAoaMode(dev.isAoa)
            }
        }

        updateSessionsStateMap()
    }

    private fun updateSessionsStateMap() {
        val newMap = activeSessions.mapValues { (_, session) ->
            session.state.value
        }
        _sessionsState.update { newMap }
    }

    fun getSession(deviceId: String): UsbSession? {
        return activeSessions[deviceId]
    }

    fun switchAoaMode(deviceId: String): Boolean {
        val session = activeSessions[deviceId] ?: return false
        val success = hardwareFixture.switchAoaMode(deviceId)
        if (success) {
            pollDevices()
        }
        return success
    }

    fun disconnectSession(deviceId: String) {
        val session = activeSessions.remove(deviceId)
        session?.isConnected = false
        hardwareFixture.unplugDevice(deviceId)
        updateSessionsStateMap()
    }

    fun resetAll() {
        stopPolling()
        activeSessions.values.forEach { it.isConnected = false }
        activeSessions.clear()
        _sessionsState.value = emptyMap()
        hardwareFixture.clear()
    }
}
