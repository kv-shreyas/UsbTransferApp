package com.example.securequicktransferapp.data.repo

import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.constants.Constants
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.repo.IClientDeviceInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Repository implementation for querying and observing connected client application details on Host Desktop.
 * Provides clean architectural abstraction between UI/ViewModel and underlying USB communication.
 * Information is strictly retrieved and populated for devices with active, connected sessions (DeviceSessionStatus.Ready).
 */
class ClientDeviceInfoRepositoryImpl(
    private val sessionManager: UsbSessionManager
) : IClientDeviceInfoRepository {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val appCache = ConcurrentHashMap<String, MutableStateFlow<ClientAppDetails?>>()

    init {
        // Observe active session changes and automatically populate / update application details for connected sessions
        scope.launch {
            sessionManager.sessionsState.collect { sessions ->
                sessions.forEach { (deviceId, sessionState) ->
                    if (sessionState.status is DeviceSessionStatus.Ready) {
                        val flow = appCache.computeIfAbsent(deviceId) {
                            MutableStateFlow(createInitialAppDetails())
                        }
                        if (flow.value == null) {
                            flow.value = createInitialAppDetails()
                        }
                    } else {
                        // Clear cached application info when device is disconnected or only detected
                        appCache[deviceId]?.value = null
                    }
                }
            }
        }
    }

    override fun observeDeviceInfo(deviceId: String): StateFlow<ClientAppDetails?> {
        return appCache.computeIfAbsent(deviceId) {
            val session = sessionManager.getSession(deviceId)
            if (session != null && session.sessionState.value.status is DeviceSessionStatus.Ready) {
                MutableStateFlow(createInitialAppDetails())
            } else {
                MutableStateFlow(null)
            }
        }.asStateFlow()
    }

    override suspend fun queryDeviceInfo(deviceId: String): Result<ClientAppDetails> {
        val session = sessionManager.getSession(deviceId)
        if (session == null || session.sessionState.value.status !is DeviceSessionStatus.Ready) {
            return Result.failure(IllegalStateException("Device $deviceId is not connected."))
        }

        // Simulate network/RPC round-trip latency to client
        delay(400.milliseconds)

        // Dispatch CMD_GET_DEVICE_INFO RPC over USB wire protocol in future business logic phase
        val details = createInitialAppDetails()

        val flow = appCache.computeIfAbsent(deviceId) { MutableStateFlow(null) }
        flow.value = details

        return Result.success(details)
    }

    override fun getCachedDeviceInfo(deviceId: String): ClientAppDetails? {
        val session = sessionManager.getSession(deviceId)
        if (session == null || session.sessionState.value.status !is DeviceSessionStatus.Ready) {
            return null
        }
        return appCache[deviceId]?.value
    }

    private fun createInitialAppDetails(): ClientAppDetails {
        return ClientAppDetails(
            appName = Constants.App.NAME,
            packageName = "com.example.securequicktransferapp",
            versionName = Constants.App.PACKAGE_VERSION,
            versionCode = 1L,
            minSdk = 26,
            targetSdk = 34,
            isDebuggable = false,
            dataDir = "/sdcard/Android/data/com.example.smartnav_v3/files/SmartNavV3",
            cacheSizeBytes = 12L * 1024L * 1024L // 12 MB
        )
    }
}
