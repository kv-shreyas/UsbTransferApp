package com.example.securequicktransferapp.data.usb

import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton USB Session Orchestrator managing dynamic device polling, active session lifecycle,
 * per-device Mutex isolation, and native LibUsb device handle reference count cleanup.
 *
 * @param deviceManager Native hardware enumerator instance used for device discovery and handle releases.
 */
class UsbSessionManager(
    private val deviceManager: UsbDeviceManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = ConcurrentHashMap<String, UsbSession>()
    private val sessionJobs = ConcurrentHashMap<String, Job>()

    private val _sessionsState = MutableStateFlow<Map<String, UsbSessionState>>(emptyMap())
    val sessionsState: StateFlow<Map<String, UsbSessionState>> = _sessionsState.asStateFlow()

    @Volatile
    private var isPolling = false
    private var pollingJob: Job? = null

    /**
     * Starts the periodic device polling loop (runs every 1500ms) on [Dispatchers.IO].
     */
    fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollingJob = scope.launch {
            while (isActive && isPolling) {
                try {
                    pollDevices()
                } catch (e: Exception) {
                    println("[UsbSessionManager] Polling loop error: ${e.message}")
                }
                delay(1500)
            }
        }
    }

    /**
     * Stops the device polling loop and cleanly disconnects and releases all active USB sessions.
     */
    fun stopPolling() {
        isPolling = false
        pollingJob?.cancel()
        pollingJob = null
        for ((id, job) in sessionJobs) {
            job.cancel()
        }
        sessionJobs.clear()
        for (session in activeSessions.values) {
            try {
                session.disconnect()
            } catch (e: Exception) {
                println("[UsbSessionManager] Disconnect error during stopPolling for ${session.deviceId}: ${e.message}")
            }
        }
        activeSessions.clear()
        _sessionsState.value = emptyMap()
    }

    /**
     * Executes a single polling iteration:
     * 1. Calls [deviceManager.discoverDevices] (+1 ref count on returned devices).
     * 2. Detects unplugged devices and disconnects their [UsbSession] instances.
     * 3. Detects newly plugged devices and instantiates new [UsbSession] instances taking handle ownership.
     * 4. For already-tracked devices, invokes [deviceManager.releaseDevice] to prevent native handle leaks.
     * 5. Updates the overall [sessionsState] Flow.
     */
    fun pollDevices() {
        val discoveredDevices = deviceManager.discoverDevices()
        val discoveredMap = discoveredDevices.associateBy { it.id }

        // 1. Identify and remove disconnected sessions
        val removedIds = activeSessions.keys.filter { it !in discoveredMap }
        for (id in removedIds) {
            sessionJobs.remove(id)?.cancel()
            val session = activeSessions.remove(id)
            if (session != null) {
                try {
                    session.disconnect()
                } catch (e: Exception) {
                    println("[UsbSessionManager] Error disconnecting removed session $id: ${e.message}")
                }
                println("[UsbSessionManager] Device removed: $id")
            }
        }

        // 2. Identify newly connected devices & manage native ref counts
        for ((id, devInfo) in discoveredMap) {
            if (!activeSessions.containsKey(id)) {
                println("[UsbSessionManager] New device detected: $id (${devInfo.deviceName})")
                val session = UsbSession(
                    deviceId = id,
                    discoveredDevice = devInfo,
                    deviceManager = deviceManager
                )
                activeSessions[id] = session

                // Observe sessionState changes and propagate immediately to _sessionsState
                val job = scope.launch {
                    session.sessionState.collect {
                        updateSessionsStateMap()
                    }
                }
                sessionJobs[id] = job

                // Asynchronously attempt connection and handshake
                scope.launch {
                    session.connect()
                }
            } else {
                // Device already tracked in activeSessions.
                // discoverDevices() returned a +1 refCount handle that won't be stored.
                // MUST release reference to prevent native libusb handle leak!
                deviceManager.releaseDevice(devInfo.device)
            }
        }

        // 3. Update published StateFlow
        updateSessionsStateMap()
    }

    /**
     * Re-publishes current states of all active sessions into [sessionsState].
     */
    fun updateSessionsStateMap() {
        _sessionsState.value = activeSessions.mapValues { it.value.sessionState.value }
    }

    /**
     * Retrieves an active [UsbSession] by physical device hardware identifier (e.g. "bus_1_port_3").
     */
    fun getSession(deviceId: String): UsbSession? = activeSessions[deviceId]

    /**
     * Returns an unmodifiable snapshot of all active sessions.
     */
    fun getActiveSessions(): Map<String, UsbSession> = activeSessions

    /**
     * Updates state for a given session and refreshes [sessionsState].
     */
    fun updateSessionState(deviceId: String, transform: (UsbSessionState) -> UsbSessionState) {
        val session = activeSessions[deviceId] ?: return
        session.updateState(transform)
        updateSessionsStateMap()
    }
}
