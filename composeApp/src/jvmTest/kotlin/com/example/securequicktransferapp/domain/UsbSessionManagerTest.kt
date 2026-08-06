package com.example.securequicktransferapp.domain

import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.di.appModule
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.domain.model.UsbSessionState
import com.example.securequicktransferapp.presentation.vm.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbSessionManagerTest {

    @BeforeTest
    fun setUp() {
        try {
            stopKoin()
        } catch (e: Exception) {
            // Ignore if not started
        }
    }

    @AfterTest
    fun tearDown() {
        try {
            stopKoin()
        } catch (e: Exception) {
            // Ignore
        }
    }

    @Test
    fun testUsbSessionStateDefaultsAndTransitions() {
        val state = UsbSessionState(
            deviceId = "bus_1_port_3",
            deviceName = "Android Device (bus_1_port_3)"
        )

        assertEquals("bus_1_port_3", state.deviceId)
        assertEquals("Android Device (bus_1_port_3)", state.deviceName)
        assertEquals(DeviceSessionStatus.Disconnected, state.status)
        assertEquals("/sdcard", state.currentPath)
        assertEquals("/sdcard", state.currentRemotePath)
        assertFalse(state.isAoaMode)

        val updated = state.copy(
            status = DeviceSessionStatus.Ready,
            remoteFiles = listOf(RemoteFile(name = "DCIM", path = "/sdcard/DCIM", isDirectory = true, size = 0)),
            isAoaMode = true
        )

        assertEquals(DeviceSessionStatus.Ready, updated.status)
        assertEquals(1, updated.remoteFiles.size)
        assertTrue(updated.isAoaMode)
    }

    @Test
    fun testUsbSessionMutexIsolation() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val dummyDeviceHandle: org.usb4java.Device = org.usb4java.Device::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        val devA = DiscoveredUsbDevice(
            id = "bus_1_port_1",
            busNumber = 1,
            portNumber = 1,
            vendorId = 0x18D1,
            productId = 0x4EE1,
            isAoa = false,
            deviceName = "Phone A",
            device = dummyDeviceHandle
        )

        val devB = DiscoveredUsbDevice(
            id = "bus_1_port_2",
            busNumber = 1,
            portNumber = 2,
            vendorId = 0x04E8,
            productId = 0x6860,
            isAoa = false,
            deviceName = "Phone B",
            device = dummyDeviceHandle
        )

        val sessionA = UsbSession("bus_1_port_1", devA, deviceManager)
        val sessionB = UsbSession("bus_1_port_2", devB, deviceManager)

        // Lock session A
        val lockAJob = async(Dispatchers.IO) {
            sessionA.sessionMutex.withLock {
                delay(100)
            }
        }

        delay(20)

        // Try locking session B while session A is locked
        val bAcquired = sessionB.sessionMutex.tryLock()
        assertTrue(bAcquired, "Session B mutex lock must not be blocked by Session A mutex lock")
        if (bAcquired) {
            sessionB.sessionMutex.unlock()
        }

        lockAJob.await()
    }

    @Test
    fun testUsbSessionManagerLifecycle() {
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)

        assertNotNull(sessionManager.sessionsState)
        assertEquals(0, sessionManager.sessionsState.value.size)

        sessionManager.startPolling()
        sessionManager.pollDevices()
        sessionManager.stopPolling()

        assertEquals(0, sessionManager.sessionsState.value.size)
    }

    @Test
    fun testKoinAppModuleResolution() {
        startKoin {
            modules(appModule)
        }

        val koin = GlobalContext.get()
        val devManager: UsbDeviceManager = koin.get()
        val sessionManager: UsbSessionManager = koin.get()
        val connection: UsbConnection = koin.get()
        val viewModel: MainViewModel = koin.get()

        assertNotNull(devManager)
        assertNotNull(sessionManager)
        assertNotNull(connection)
        assertNotNull(viewModel)
    }
}
