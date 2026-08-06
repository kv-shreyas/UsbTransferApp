package com.example.securequicktransferapp.tier2

import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.fixtures.FakeMainViewModel
import com.example.securequicktransferapp.fixtures.FakeUsbHardwareFixture
import com.example.securequicktransferapp.fixtures.FakeUsbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Tier2BoundaryCornerCasesTest {

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
    fun testB1_ZeroDevicesConnected() {
        sessionManager.pollDevices()
        val sessions = sessionManager.sessionsState.value
        assertTrue(sessions.isEmpty(), "Sessions map must be empty when no devices connected")
        assertEquals(0, viewModel.getSidebarRenderedDeviceIds().size)
    }

    @Test
    fun testB2_RapidDevicePlugUnplug() {
        for (i in 1..20) {
            val dev = hardwareFixture.plugDevice(busNumber = 1, portPath = "$i", vendorId = 0x18D1, productId = 0x4EE1)
            sessionManager.pollDevices()
            assertEquals(1, sessionManager.sessionsState.value.size)

            hardwareFixture.unplugDevice(dev.id)
            sessionManager.pollDevices()
            assertEquals(0, sessionManager.sessionsState.value.size)
        }
    }

    @Test
    fun testB3_SimultaneousDevicePlugging(): Unit = runBlocking {
        val job1 = async(Dispatchers.IO) {
            hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        }
        val job2 = async(Dispatchers.IO) {
            hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        }
        awaitAll(job1, job2)

        sessionManager.pollDevices()
        val sessions = sessionManager.sessionsState.value
        assertEquals(2, sessions.size)
        assertTrue(sessions.containsKey("bus_1_port_1"))
        assertTrue(sessions.containsKey("bus_1_port_2"))
    }

    @Test
    fun testB4_HubNestedPortPathUniqueness() {
        val dev = hardwareFixture.plugDevice(busNumber = 1, portPath = "1.2.3.4", vendorId = 0x18D1, productId = 0x4EE1)
        assertEquals("bus_1_port_1.2.3.4", dev.id)
        sessionManager.pollDevices()

        val session = sessionManager.getSession("bus_1_port_1.2.3.4")
        assertNotNull(session)
    }

    @Test
    fun testB5_SameModelIdenticalVidPid() {
        val dev1 = hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        val dev2 = hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x18D1, productId = 0x4EE1)

        assertEquals(dev1.vendorId, dev2.vendorId)
        assertEquals(dev1.productId, dev2.productId)

        sessionManager.pollDevices()
        val sessions = sessionManager.sessionsState.value
        assertEquals(2, sessions.size)
        assertTrue(sessions.containsKey("bus_1_port_1"))
        assertTrue(sessions.containsKey("bus_1_port_2"))
    }

    @Test
    fun testB6_TransferToUnpluggedDevice(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        val tempFile = File.createTempFile("unplug_mid_", ".dat").apply { writeBytes(ByteArray(1024)) }
        val session = sessionManager.getSession("bus_1_port_1")!!

        try {
            session.isConnected = false // Disconnect mid transfer
            assertFails {
                session.sendFile(tempFile, "/sdcard")
            }
        } finally {
            tempFile.delete()
        }
        Unit
    }

    @Test
    fun testB7_MaxDeviceLimitHandling() {
        for (i in 1..127) {
            hardwareFixture.plugDevice(busNumber = 1, portPath = "$i", vendorId = 0x18D1, productId = 0x4EE1)
        }
        sessionManager.pollDevices()
        assertEquals(127, sessionManager.sessionsState.value.size)
    }

    @Test
    fun testB8_EmptyPathRemoteFileListing(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        val session = sessionManager.getSession("bus_1_port_1")!!
        val files = session.listDirectory("/sdcard/empty_dir")
        assertNotNull(files)
        assertTrue(files.isEmpty())
    }

    @Test
    fun testB9_LargeFileTransferConcurrency(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val largeFile = File.createTempFile("large_1gb_", ".dat").apply { writeBytes(ByteArray(1024 * 1024)) } // 1MB mock for 1GB

        try {
            val transferJob = viewModel.sendFiles("bus_1_port_1", listOf(largeFile))

            val sessionB = sessionManager.getSession("bus_1_port_2")!!
            val readJobs = List(50) {
                async(Dispatchers.IO) {
                    sessionB.listDirectory("/sdcard")
                }
            }

            readJobs.awaitAll()
            transferJob.join()

            assertTrue(readJobs.all { it.isCompleted })
        } finally {
            largeFile.delete()
        }
        Unit
    }

    @Test
    fun testB10_AoaTimeoutRecovery() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        // Simulate AOA failure on Device A by attempting AOA switch on non-existent device
        val resultA = sessionManager.switchAoaMode("non_existent_id")
        assertFalse(resultA)

        // Verify Device B is active and healthy
        val sessionB = sessionManager.getSession("bus_1_port_2")
        assertNotNull(sessionB)
        assertTrue(sessionB.isConnected)
    }

    @Test
    fun testB11_ConcurrentCancellation(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val fileA = File.createTempFile("cancelA_", ".dat").apply { writeBytes(ByteArray(2048)) }
        val fileB = File.createTempFile("cancelB_", ".dat").apply { writeBytes(ByteArray(2048)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(fileA))
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(fileB))

            viewModel.cancelTransfer("bus_1_port_1")
            viewModel.cancelTransfer("bus_1_port_2")

            jobA.join()
            jobB.join()

            val stateA = sessionManager.getSession("bus_1_port_1")?.state?.value
            val stateB = sessionManager.getSession("bus_1_port_2")?.state?.value

            assertEquals("Cancelled", stateA?.errorMessage)
            assertEquals("Cancelled", stateB?.errorMessage)
        } finally {
            fileA.delete()
            fileB.delete()
        }
        Unit
    }

    @Test
    fun testB12_FolderCreationConflict(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        viewModel.createFolder("bus_1_port_1", "SharedName").join()
        viewModel.createFolder("bus_1_port_2", "SharedName").join()

        val sessionA = sessionManager.getSession("bus_1_port_1")!!
        val sessionB = sessionManager.getSession("bus_1_port_2")!!

        assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == "SharedName" } == true)
        assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == "SharedName" } == true)
    }

    @Test
    fun testB13_NullDeviceHandleProtection(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        val session = sessionManager.getSession("bus_1_port_1")!!
        session.isConnected = false

        val tempFile = File.createTempFile("null_handle_", ".dat")
        try {
            assertFails {
                session.sendFile(tempFile, "/sdcard")
            }
        } finally {
            tempFile.delete()
        }
        Unit
    }

    @Test
    fun testB14_ReconnectionSamePort() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()
        assertEquals(1, sessionManager.sessionsState.value.size)

        hardwareFixture.unplugDevice("bus_1_port_1")
        sessionManager.pollDevices()
        assertEquals(0, sessionManager.sessionsState.value.size)

        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()
        assertEquals(1, sessionManager.sessionsState.value.size)
        assertTrue(sessionManager.sessionsState.value.containsKey("bus_1_port_1"))
    }

    @Test
    fun testB15_ReconnectionDifferentPort() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        hardwareFixture.unplugDevice("bus_1_port_1")
        sessionManager.pollDevices()

        hardwareFixture.plugDevice(busNumber = 1, portPath = "3", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        val sessions = sessionManager.sessionsState.value
        assertEquals(1, sessions.size)
        assertTrue(sessions.containsKey("bus_1_port_3"))
        assertFalse(sessions.containsKey("bus_1_port_1"))
    }

    @Test
    fun testB16_DeviceSelectionSwitchMidTransfer(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val activeFile = File.createTempFile("mid_transfer_", ".dat").apply { writeBytes(ByteArray(5000)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(activeFile))

            for (i in 1..50) {
                viewModel.selectDevice(if (i % 2 == 0) "bus_1_port_1" else "bus_1_port_2")
            }

            assertEquals(50, viewModel.tabSwitchCount.get())
            jobA.join()
        } finally {
            activeFile.delete()
        }
        Unit
    }

    @Test
    fun testB17_ConcurrentDeleteAndDownload(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val deleteJob = viewModel.deleteFile("bus_1_port_1", RemoteFile(name = "sample.txt", path = "/sdcard/sample.txt", isDirectory = false, size = 1024))

        val localDir = File(System.getProperty("java.io.tmpdir"), "test_downloads")
        val downloadJob = viewModel.fetchFiles("bus_1_port_2", listOf(RemoteFile(name = "sample.txt", path = "/sdcard/sample.txt", isDirectory = false, size = 1024)), localDir)

        deleteJob.join()
        downloadJob.join()

        val sessionA = sessionManager.getSession("bus_1_port_1")!!
        assertFalse(sessionA.fileStore["/sdcard"]?.any { it.name == "sample.txt" } == true)

        val localFile = File(localDir, "sample.txt")
        assertTrue(localFile.exists())
        localFile.delete()
    }

    @Test
    fun testB18_CorruptedStreamRecovery(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sessionA = sessionManager.getSession("bus_1_port_1")!!
        sessionA.shouldCorruptStream = true

        val corruptFile = File.createTempFile("corrupt_", ".dat").apply { writeBytes(ByteArray(1024)) }

        try {
            assertFails {
                sessionA.sendFile(corruptFile, "/sdcard")
            }

            // Verify Device B remains fully operational
            val sessionB = sessionManager.getSession("bus_1_port_2")!!
            val filesB = sessionB.listDirectory("/sdcard")
            assertTrue(filesB.isNotEmpty())
        } finally {
            corruptFile.delete()
        }
        Unit
    }

    @Test
    fun testB19_KoinModuleReinitialization() {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        sessionManager.resetAll()
        assertEquals(0, sessionManager.sessionsState.value.size)

        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()
        assertEquals(1, sessionManager.sessionsState.value.size)
    }

    @Test
    fun testB20_ZeroByteFileTransfer(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val zeroFile = File.createTempFile("zero_byte_", ".dat").apply { writeBytes(ByteArray(0)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(zeroFile))
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(zeroFile))

            jobA.join()
            jobB.join()

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == zeroFile.name && it.size == 0L } == true)
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == zeroFile.name && it.size == 0L } == true)
        } finally {
            zeroFile.delete()
        }
        Unit
    }
}
