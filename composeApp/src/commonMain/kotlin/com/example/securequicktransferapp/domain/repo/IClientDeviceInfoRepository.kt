package com.example.securequicktransferapp.domain.repo

import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain repository contract for fetching and observing connected client application information.
 * Follows Clean Architecture and Dependency Inversion Principle.
 */
interface IClientDeviceInfoRepository {

    /**
     * Observes real-time cached or polled application details for a specific connected device ID.
     */
    fun observeDeviceInfo(deviceId: String): StateFlow<ClientAppDetails?>

    /**
     * Queries fresh application details from the connected client device.
     */
    suspend fun queryDeviceInfo(deviceId: String): Result<ClientAppDetails>

    /**
     * Retrieves the latest cached application details for the specified device ID, if available.
     */
    fun getCachedDeviceInfo(deviceId: String): ClientAppDetails?
}
