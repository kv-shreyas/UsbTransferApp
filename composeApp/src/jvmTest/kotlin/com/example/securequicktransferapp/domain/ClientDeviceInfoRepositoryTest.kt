package com.example.securequicktransferapp.domain

import com.example.securequicktransferapp.data.repo.ClientDeviceInfoRepositoryImpl
import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.command.AppCommandIds
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetails
import com.example.securequicktransferapp.domain.model.deviceinfo.ClientAppDetailsCodec
import com.example.securequicktransferapp.domain.repo.UsbRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.usb4java.Device
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientDeviceInfoRepositoryTest {

    private fun createDummyDeviceHandle(): Device {
        val constructor = Device::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }

    private class FakeCommandUsbRepository(
        private val cannedResponse: ByteArray?
    ) : UsbRepository {
        var lastCommandId: Byte? = null
        var lastPayload: ByteArray? = null

        override var isAoaMode: Boolean = true
        override fun connect(): Boolean = true
        override fun disconnect() {}
        override suspend fun listDirectory(path: String): List<RemoteFile> = emptyList()
        override fun sendFile(file: File, destinationPath: String, isDirectory: Boolean, remoteFileName: String): Flow<Int> = flowOf(100)
        override fun fetchFile(remotePath: String, localFile: File): Flow<Int> = flowOf(100)
        override fun fetchDirectory(remotePath: String, localFile: File): Flow<Int> = flowOf(100)
        override suspend fun deleteFile(remotePath: String): Boolean = true
        override suspend fun renameFile(remotePath: String, newName: String): Boolean = true
        override suspend fun createFolder(remotePath: String): Boolean = true
        override fun cancelTransfer() {}
        override fun checkPhysicalConnection(): Pair<Boolean, String?> = Pair(true, "Mock")

        override suspend fun executeCustomCommand(commandId: Byte, payload: ByteArray): ByteArray? {
            lastCommandId = commandId
            lastPayload = payload
            return cannedResponse
        }
    }

    @Test
    fun testQueryDeviceInfoFailsWhenDeviceNotConnected() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)
        val repo = ClientDeviceInfoRepositoryImpl(sessionManager)

        val result = repo.queryDeviceInfo("unknown_device")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("not connected") == true)
    }

    @Test
    fun testQueryDeviceInfoSuccessAndDecoding() = runBlocking {
        val expectedDetails = ClientAppDetails(
            appName = "Secure Quick Transfer",
            packageName = "com.example.securequicktransferapp",
            appVersion = "2.1.0",
            osVersion = "34"
        )
        val encodedBytes = ClientAppDetailsCodec.encode(expectedDetails)

        val fakeRepo = FakeCommandUsbRepository(cannedResponse = encodedBytes)
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)

        val discoveredDevice = DiscoveredUsbDevice(
            id = "dev_1",
            busNumber = 1,
            portNumber = 2,
            vendorId = 0x18D1,
            productId = 0x4EE1,
            isAoa = true,
            deviceName = "Pixel 8",
            device = createDummyDeviceHandle()
        )

        val session = UsbSession(
            deviceId = "dev_1",
            discoveredDevice = discoveredDevice,
            deviceManager = deviceManager,
            connection = UsbConnection(),
            repository = fakeRepo
        )

        // Set status to Ready using reflection
        val statusField: Field = session::class.java.getDeclaredField("_sessionState")
        statusField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = statusField.get(session) as kotlinx.coroutines.flow.MutableStateFlow<com.example.securequicktransferapp.domain.model.UsbSessionState>
        stateFlow.value = stateFlow.value.copy(status = DeviceSessionStatus.Ready)

        // Inject session into sessionManager's activeSessions map via reflection
        val activeSessionsField = sessionManager::class.java.getDeclaredField("activeSessions")
        activeSessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeSessions = activeSessionsField.get(sessionManager) as ConcurrentHashMap<String, UsbSession>
        activeSessions["dev_1"] = session

        val repo = ClientDeviceInfoRepositoryImpl(sessionManager)
        val result = repo.queryDeviceInfo("dev_1")

        assertTrue(result.isSuccess)
        assertEquals(AppCommandIds.CMD_GET_APP_DETAILS, fakeRepo.lastCommandId)
        val details = result.getOrThrow()
        assertEquals(expectedDetails, details)
        assertEquals(expectedDetails, repo.getCachedDeviceInfo("dev_1"))
    }
}
