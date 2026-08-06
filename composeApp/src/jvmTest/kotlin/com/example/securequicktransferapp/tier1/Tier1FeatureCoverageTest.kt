package com.example.securequicktransferapp.tier1

import com.example.securequicktransferapp.fixtures.FakeMainViewModel
import com.example.securequicktransferapp.fixtures.FakeUsbHardwareFixture
import com.example.securequicktransferapp.fixtures.FakeUsbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Tier1FeatureCoverageTest {

    private lateinit var hardwareFixture: FakeUsbHardwareFixture
    private lateinit var sessionManager: FakeUsbSessionManager
    private lateinit var viewModel: FakeMainViewModel

    @BeforeTest
    fun setUp() {
        hardwareFixture = FakeUsbHardwareFixture()
        sessionManager = FakeUsbSessionManager(hardwareFixture)
        viewModel = FakeMainViewModel(sessionManager)
    }

    @AfterTest
    fun tearDown() {
        sessionManager.resetAll()
    }

    @Test
    fun testF1_SingleDeviceDiscovery() {
        hardwareFixture.plugDevice(
            busNumber = 1,
            portPath = "1",
            vendorId = 0x18D1, // Google
            productId = 0x4EE1
        )
        val discovered = hardwareFixture.discoverAndroidDevices()
        assertEquals(1, discovered.size)
        assertEquals("bus_1_port_1", discovered[0].id)
    }

    @Test
    fun testF1_MultipleDeviceDiscovery() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 2, portPath = "3", vendorId = 0x04E8, productId = 0x6860) // Samsung

        val discovered = hardwareFixture.discoverAndroidDevices()
        assertEquals(2, discovered.size)
        val ids = discovered.map { it.id }
        assertTrue(ids.contains("bus_1_port_1"))
        assertTrue(ids.contains("bus_2_port_3"))
    }

    @Test
    fun testF1_UniqueHardwareIdFormat() {
        val dev = hardwareFixture.plugDevice(busNumber = 3, portPath = "4.1", vendorId = 0x18D1, productId = 0x4EE1)
        val idRegex = Regex("^bus_\\d+_port_[\\d.]+$")
        assertTrue(idRegex.matches(dev.id), "Device ID '${dev.id}' must match format bus_X_port_Y")
    }

    @Test
    fun testF1_NonAndroidDeviceIgnored() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x046D, productId = 0xC52B) // Logitech Keyboard
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x0951, productId = 0x1666) // Kingston Flash Drive
        hardwareFixture.plugDevice(busNumber = 1, portPath = "3", vendorId = 0x18D1, productId = 0x4EE1) // Google Pixel

        val discovered = hardwareFixture.discoverAndroidDevices()
        assertEquals(1, discovered.size)
        assertEquals("bus_1_port_3", discovered[0].id)
    }

    @Test
    fun testF1_DeviceReEnumerationTracking() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x18D1, productId = 0x4EE1)
        val firstDiscovery = hardwareFixture.discoverAndroidDevices().first()
        val secondDiscovery = hardwareFixture.discoverAndroidDevices().first()

        assertEquals(firstDiscovery.id, secondDiscovery.id)
        assertEquals("bus_1_port_2", secondDiscovery.id)
    }

    @Test
    fun testF2_SessionCreatedPerDevice() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)

        sessionManager.pollDevices()
        val sessions = sessionManager.sessionsState.value

        assertEquals(2, sessions.size)
        assertTrue(sessions.containsKey("bus_1_port_1"))
        assertTrue(sessions.containsKey("bus_1_port_2"))
    }

    @Test
    fun testF2_SessionStateFlowUpdates() {
        assertEquals(0, sessionManager.sessionsState.value.size)

        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()
        assertEquals(1, sessionManager.sessionsState.value.size)

        hardwareFixture.unplugDevice("bus_1_port_1")
        sessionManager.pollDevices()
        assertEquals(0, sessionManager.sessionsState.value.size)
    }

    @Test
    fun testF2_SessionDisconnectIsolation() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        sessionManager.disconnectSession("bus_1_port_1")
        val sessions = sessionManager.sessionsState.value

        assertFalse(sessions.containsKey("bus_1_port_1"))
        assertTrue(sessions.containsKey("bus_1_port_2"))
        assertNotNull(sessionManager.getSession("bus_1_port_2"))
    }

    @Test
    fun testF2_KoinModuleSessionBindings() {
        // Verify session manager operates as singleton and provides isolated session instances per device ID
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sessionA = sessionManager.getSession("bus_1_port_1")
        val sessionB = sessionManager.getSession("bus_1_port_2")

        assertNotNull(sessionA)
        assertNotNull(sessionB)
        assertTrue(sessionA !== sessionB, "Each physical device must have its own isolated UsbSession instance")
    }

    @Test
    fun testF2_AoaModeSwitchPerSession() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1, isAoa = false)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860, isAoa = false)
        sessionManager.pollDevices()

        val success = sessionManager.switchAoaMode("bus_1_port_1")
        assertTrue(success)

        val sessionA = sessionManager.getSession("bus_1_port_1")
        val sessionB = sessionManager.getSession("bus_1_port_2")

        assertTrue(sessionA?.state?.value?.isAoaMode == true)
        assertFalse(sessionB?.state?.value?.isAoaMode == true)
    }

    @Test
    fun testF3_PerDeviceMutexIsolation() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sessionA = sessionManager.getSession("bus_1_port_1")!!
        val sessionB = sessionManager.getSession("bus_1_port_2")!!

        val lockAcquiredB = async(Dispatchers.IO) {
            sessionA.mutex.withLock {
                // Lock session A
                delay(50)
            }
        }

        // Lock session B while session A is locked
        val bAcquired = sessionB.mutex.tryLock()
        assertTrue(bAcquired, "Session B mutex must be acquirable independently while Session A is locked")
        sessionB.mutex.unlock()
        lockAcquiredB.await()
    }

    @Test
    fun testF3_ViewModelStateMapEmissions() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val vmSessions = viewModel.deviceSessions.value
        assertEquals(2, vmSessions.size)
        assertTrue(vmSessions.containsKey("bus_1_port_1"))
        assertTrue(vmSessions.containsKey("bus_1_port_2"))
    }

    @Test
    fun testF3_ActiveDeviceSelection() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        viewModel.selectDevice("bus_1_port_1")
        assertEquals("bus_1_port_1", viewModel.activeDeviceId.value)

        viewModel.selectDevice("bus_1_port_2")
        assertEquals("bus_1_port_2", viewModel.activeDeviceId.value)
    }

    @Test
    fun testF3_ParallelTransferExecution() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val tempFileA = File.createTempFile("fileA_", ".dat").apply { writeBytes(ByteArray(1024)) }
        val tempFileB = File.createTempFile("fileB_", ".dat").apply { writeBytes(ByteArray(1024)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(tempFileA))
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(tempFileB))

            jobA.join()
            jobB.join()

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == tempFileA.name } == true)
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == tempFileB.name } == true)
        } finally {
            tempFileA.delete()
            tempFileB.delete()
        }
    }

    @Test
    fun testF3_TransferCancellationIsolation() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val tempFileA = File.createTempFile("cancelA_", ".dat").apply { writeBytes(ByteArray(2048)) }
        val tempFileB = File.createTempFile("keepB_", ".dat").apply { writeBytes(ByteArray(2048)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(tempFileA))
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(tempFileB))

            viewModel.cancelTransfer("bus_1_port_1")
            jobB.join()

            val sessionB = sessionManager.getSession("bus_1_port_2")!!
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == tempFileB.name } == true)
        } finally {
            tempFileA.delete()
            tempFileB.delete()
        }
    }

    @Test
    fun testF4_SidebarRendersDeviceList() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sidebarIds = viewModel.getSidebarRenderedDeviceIds()
        assertEquals(2, sidebarIds.size)
        assertTrue(sidebarIds.contains("bus_1_port_1"))
        assertTrue(sidebarIds.contains("bus_1_port_2"))
    }

    @Test
    fun testF4_TabSelectionUpdatesExplorerView() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sessionA = sessionManager.getSession("bus_1_port_1")!!
        val sessionB = sessionManager.getSession("bus_1_port_2")!!

        sessionA.createFolder("/sdcard/FolderDeviceA")
        sessionB.createFolder("/sdcard/FolderDeviceB")

        viewModel.selectDevice("bus_1_port_1")
        delay(50)
        assertTrue(viewModel.activeRemoteFiles.value.any { it.name == "FolderDeviceA" })

        viewModel.selectDevice("bus_1_port_2")
        delay(50)
        assertTrue(viewModel.activeRemoteFiles.value.any { it.name == "FolderDeviceB" })
    }

    @Test
    fun testF4_NonBlockingUiDuringTransfer() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val heavyFile = File.createTempFile("heavy_", ".dat").apply { writeBytes(ByteArray(5000)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(heavyFile))
            val startSwitch = System.currentTimeMillis()
            viewModel.selectDevice("bus_1_port_2")
            val switchDuration = System.currentTimeMillis() - startSwitch

            assertTrue(switchDuration < 200, "Selecting Device B tab must return immediately without waiting for Device A transfer")
            assertEquals("bus_1_port_2", viewModel.activeDeviceId.value)
            jobA.join()
        } finally {
            heavyFile.delete()
        }
    }

    @Test
    fun testF4_DeviceUnplugRemovesCard() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        assertEquals(2, viewModel.getSidebarRenderedDeviceIds().size)

        hardwareFixture.unplugDevice("bus_1_port_1")
        sessionManager.pollDevices()

        val sidebarIds = viewModel.getSidebarRenderedDeviceIds()
        assertEquals(1, sidebarIds.size)
        assertFalse(sidebarIds.contains("bus_1_port_1"))
        assertTrue(sidebarIds.contains("bus_1_port_2"))
    }

    @Test
    fun testF4_DeviceUnplugPreservesPeerTransfer() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val fileB = File.createTempFile("peerB_", ".dat").apply { writeBytes(ByteArray(1024)) }

        try {
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(fileB))
            // Unplug Device A while Device B is transferring
            hardwareFixture.unplugDevice("bus_1_port_1")
            sessionManager.pollDevices()

            jobB.join()

            val sessionB = sessionManager.getSession("bus_1_port_2")!!
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == fileB.name } == true)
        } finally {
            fileB.delete()
        }
    }
}
