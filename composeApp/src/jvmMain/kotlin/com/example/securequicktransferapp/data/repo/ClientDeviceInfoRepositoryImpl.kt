package com.example.securequicktransferapp.data.repo

import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.command.AppCommandIds
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetailsCodec
import com.example.securequicktransferapp.domain.repo.IClientDeviceInfoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
                            MutableStateFlow(null)
                        }
                        if (flow.value == null) {
                            scope.launch {
                                queryDeviceInfo(deviceId)
                            }
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
                scope.launch { queryDeviceInfo(deviceId) }
            }
            MutableStateFlow(null)
        }.asStateFlow()
    }

    override suspend fun queryDeviceInfo(deviceId: String): Result<ClientAppDetails> {
        val session = sessionManager.getSession(deviceId)
        if (session == null || session.sessionState.value.status !is DeviceSessionStatus.Ready) {
            return Result.failure(IllegalStateException("Device $deviceId is not connected."))
        }

        return try {
            val responseBytes = session.repository.executeCustomCommand(
                AppCommandIds.CMD_GET_APP_DETAILS,
                ByteArray(0)
            )

            if (responseBytes == null || responseBytes.isEmpty()) {
                Result.failure(IOException("Received empty or null response for CMD_GET_APP_DETAILS from device $deviceId"))
            } else {
                val details = ClientAppDetailsCodec.decode(responseBytes)
                val flow = appCache.computeIfAbsent(deviceId) { MutableStateFlow(null) }
                flow.value = details
                Result.success(details)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getCachedDeviceInfo(deviceId: String): ClientAppDetails? {
        val session = sessionManager.getSession(deviceId)
        if (session == null || session.sessionState.value.status !is DeviceSessionStatus.Ready) {
            return null
        }
        return appCache[deviceId]?.value
    }
}
