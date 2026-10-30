package com.example.secureqt.sdk.host

import com.example.secureqt.sdk.logging.ILogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * USB session orchestrator managing dynamic device polling, active session lifecycle,
 * and native LibUsb device handle reference count cleanup.
 *
 * Devices already in AOA mode connect automatically. For any other device, call
 * [ManagedUsbSession.connect] on its session when your application is ready to switch it.
 *
 * Use `SecureQtSdk.createDesktopSessionManager()` for the ready-made [DesktopUsbSession], or supply
 * your own session type through [sessionFactory]. The caller owns [deviceManager] and must call
 * [DesktopUsbDeviceManager.cleanup] after [close].
 *
 * @param deviceManager Native hardware enumerator instance used for device discovery and handle releases.
 * @param sessionFactory Creates a session for a new device. The session takes ownership of the device reference.
 */
open class DesktopUsbSessionManager<S : ManagedUsbSession<T>, T>(
    private val deviceManager: DesktopUsbDeviceManager,
    private val logger: ILogger = StdoutLogger,
    private val sessionFactory: (DesktopUsbDevice) -> S
) : AutoCloseable {
    private val TAG = "DesktopUsbSessionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeSessions = ConcurrentHashMap<String, S>()
    private val sessionJobs = ConcurrentHashMap<String, Job>()

    private val _sessionsState = MutableStateFlow<Map<String, T>>(emptyMap())
    val sessionsState: StateFlow<Map<String, T>> = _sessionsState.asStateFlow()

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
                    logger.e(TAG, "Polling loop error: ${e.message}")
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
        for ((_, job) in sessionJobs) {
            job.cancel()
        }
        sessionJobs.clear()
        for (session in activeSessions.values) {
            try {
                session.disconnect()
            } catch (e: Exception) {
                logger.e(TAG, "Disconnect error during stopPolling for ${session.deviceId}: ${e.message}")
            }
        }
        activeSessions.clear()
        _sessionsState.value = emptyMap()
    }

    /**
     * Stops polling, releases all sessions and shuts down the manager's coroutines.
     * The manager cannot be restarted afterwards.
     */
    override fun close() {
        stopPolling()
        scope.cancel()
    }

    /**
     * Executes a single polling iteration:
     * 1. Calls [DesktopUsbDeviceManager.discoverDevices] (+1 ref count on returned devices).
     * 2. Detects unplugged devices and disconnects their sessions.
     * 3. Detects newly plugged devices and creates new sessions taking handle ownership.
     * 4. For already-tracked devices, invokes [DesktopUsbDeviceManager.releaseDevice] to prevent native handle leaks.
     * 5. Updates the overall [sessionsState] Flow.
     *
     * [startPolling] calls this automatically; call it directly only to drive discovery yourself.
     */
    fun pollDevices() {
        val discoveredDevices = deviceManager.discoverDevices()
        val discoveredMap = discoveredDevices.associateBy { it.id }

        // 1. Identify and remove disconnected sessions
        val removedIds = activeSessions.keys.filter { id ->
            val session = activeSessions[id]
            val isConnecting = session?.isConnecting == true

            // If the handle died internally (e.g. from an I/O error dropping the connection), we MUST drop the session so it can be cleanly re-detected and re-initialized.
            // We only check this if the session is 'Ready', otherwise we might incorrectly drop freshly detected non-AOA devices before the user clicks Connect.
            val isDisconnectedInternally = session != null && session.isReady && !session.isTransportOpen

            (id !in discoveredMap && !isConnecting) || isDisconnectedInternally
        }
        for (id in removedIds) {
            sessionJobs.remove(id)?.cancel()
            val session = activeSessions.remove(id)
            if (session != null) {
                try {
                    session.disconnect()
                } catch (e: Exception) {
                    logger.e(TAG, "Error disconnecting removed session $id: ${e.message}")
                }
                logger.i(TAG, "Device removed: $id")
            }
        }

        // 2. Identify newly connected devices & manage native ref counts
        for ((id, devInfo) in discoveredMap) {
            if (!activeSessions.containsKey(id)) {
                logger.i(TAG, "New device detected: $id (${devInfo.deviceName})")
                val session = sessionFactory(devInfo)
                activeSessions[id] = session

                // Observe sessionState changes and propagate immediately to _sessionsState
                val job = scope.launch {
                    session.sessionState.collect {
                        publishSessionsState()
                    }
                }
                sessionJobs[id] = job

                // The device is now discovered and sits in its initial state.
                // We do NOT automatically connect here IF it's a fresh MTP device.
                // The application must explicitly call connect().
                // BUT, if it's already in AOA mode, it means we just successfully switched it,
                // or it was already in AOA mode, so we should automatically connect to finalize the handshake.
                if (devInfo.isAoa) {
                    scope.launch {
                        if (deviceManager.isDriverInstalling()) {
                            logger.i(TAG, "Device $id is AOA but driver install is in progress. Waiting...")
                            deviceManager.waitForDriverInstall()
                            delay(1200)
                        }
                        if (!session.isConnecting && !session.isReady) {
                            session.connect()
                        }
                    }
                }
            } else {
                // Device already tracked in activeSessions.
                // discoverDevices() returned a +1 refCount handle that won't be stored.
                // MUST release reference to prevent native libusb handle leak!
                deviceManager.releaseDevice(devInfo.device)
            }
        }

        // 3. Update published StateFlow
        publishSessionsState()
    }

    /**
     * Re-publishes current states of all active sessions into [sessionsState].
     */
    protected fun publishSessionsState() {
        _sessionsState.value = activeSessions.mapValues { it.value.sessionState.value }
    }

    /**
     * Retrieves an active session by physical device hardware identifier (e.g. "bus_1_port_3").
     */
    fun getSession(deviceId: String): S? = activeSessions[deviceId]

    /**
     * Returns an unmodifiable snapshot of all active sessions.
     */
    fun getActiveSessions(): Map<String, S> = activeSessions.toMap()
}
