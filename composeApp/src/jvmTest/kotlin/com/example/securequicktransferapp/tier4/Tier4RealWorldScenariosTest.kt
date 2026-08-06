package com.example.securequicktransferapp.tier4

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Tier4RealWorldScenariosTest {

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
    fun testS1_DualPhonePhotoBackup(): Unit = runBlocking {
        // Plug Phone 1 (Google Pixel) and Phone 2 (Samsung Galaxy)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1, deviceName = "Pixel 8")
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860, deviceName = "Galaxy S24")
        sessionManager.pollDevices()

        // Create 100 photo files for Phone 1 backup
        val photos = List(100) { index ->
            RemoteFile(name = "IMG_$index.jpg", path = "/sdcard/DCIM/Camera/IMG_$index.jpg", isDirectory = false, size = 500 * 1024)
        }

        val session1 = sessionManager.getSession("bus_1_port_1")!!
        session1.fileStore["/sdcard/DCIM/Camera"] = photos.toMutableList()

        val localBackupDir = File(System.getProperty("java.io.tmpdir"), "photo_backup_phone1")

        // Start backing up 100 photos from Phone 1
        val backupJob = viewModel.fetchFiles("bus_1_port_1", photos, localBackupDir)

        // Simultaneously browse videos folder on Phone 2
        viewModel.selectDevice("bus_1_port_2")
        val session2 = sessionManager.getSession("bus_1_port_2")!!
        val videos = session2.listDirectory("/sdcard/Movies")

        backupJob.join()

        assertNotNull(videos)
        assertEquals("bus_1_port_2", viewModel.activeDeviceId.value)

        localBackupDir.deleteRecursively()
    }

    @Test
    fun testS2_MultiDeviceFleetProvisioning() = runBlocking {
        // User connects 3 devices simultaneously
        val dev1 = hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1, deviceName = "Fleet Terminal 1")
        val dev2 = hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x18D1, productId = 0x4EE1, deviceName = "Fleet Terminal 2")
        val dev3 = hardwareFixture.plugDevice(busNumber = 1, portPath = "3", vendorId = 0x18D1, productId = 0x4EE1, deviceName = "Fleet Terminal 3")
        sessionManager.pollDevices()

        val configFile = File.createTempFile("fleet_config_", ".json").apply {
            writeText("""{"server":"https://fleet.company.internal","version":"2.4.0"}""")
        }

        try {
            // Provision all 3 devices in parallel
            val job1 = viewModel.sendFiles(dev1.id, listOf(configFile), "/sdcard/config")
            val job2 = viewModel.sendFiles(dev2.id, listOf(configFile), "/sdcard/config")
            val job3 = viewModel.sendFiles(dev3.id, listOf(configFile), "/sdcard/config")

            awaitAll(async { job1.join() }, async { job2.join() }, async { job3.join() })

            val session1 = sessionManager.getSession(dev1.id)!!
            val session2 = sessionManager.getSession(dev2.id)!!
            val session3 = sessionManager.getSession(dev3.id)!!

            assertTrue(session1.fileStore["/sdcard/config"]?.any { it.name == configFile.name } == true)
            assertTrue(session2.fileStore["/sdcard/config"]?.any { it.name == configFile.name } == true)
            assertTrue(session3.fileStore["/sdcard/config"]?.any { it.name == configFile.name } == true)
        } finally {
            configFile.delete()
        }
    }

    @Test
    fun testS3_InterroundedUnplugResiliency() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val largeFileA = File.createTempFile("largeA_", ".dat").apply { writeBytes(ByteArray(2048)) }
        val fileB = File.createTempFile("steadyB_", ".dat").apply { writeBytes(ByteArray(2048)) }

        try {
            // Device B starts steady transfer
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(fileB))

            // Device A transfer initiated
            val sessionA = sessionManager.getSession("bus_1_port_1")!!

            // Accidental unplug during Device A transfer
            sessionA.isConnected = false
            assertFails {
                sessionA.sendFile(largeFileA, "/sdcard")
            }
            hardwareFixture.unplugDevice("bus_1_port_1")
            sessionManager.pollDevices()

            // Plug Device A back in
            hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
            sessionManager.pollDevices()

            // Re-initiate Device A transfer
            val jobA2 = viewModel.sendFiles("bus_1_port_1", listOf(largeFileA))

            jobB.join()
            jobA2.join()

            val reconnectedSessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertTrue(reconnectedSessionA.fileStore["/sdcard"]?.any { it.name == largeFileA.name } == true)
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == fileB.name } == true)
        } finally {
            largeFileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun testS4_TabSwitchStressUnderLoad() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val heavyA = File.createTempFile("heavyA_", ".dat").apply { writeBytes(ByteArray(4000)) }
        val heavyB = File.createTempFile("heavyB_", ".dat").apply { writeBytes(ByteArray(4000)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(heavyA))
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(heavyB))

            // Rapidly click between Device A and Device B tabs under active transfer load
            for (i in 1..100) {
                viewModel.selectDevice(if (i % 2 == 0) "bus_1_port_1" else "bus_1_port_2")
                delay(2)
            }

            assertEquals(100, viewModel.tabSwitchCount.get())

            jobA.join()
            jobB.join()

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == heavyA.name } == true)
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == heavyB.name } == true)
        } finally {
            heavyA.delete()
            heavyB.delete()
        }
    }

    @Test
    fun testS5_LongRunningDualDeviceSync() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val sampleFiles = List(10) { index ->
            File.createTempFile("sync_$index", ".dat").apply { writeBytes(ByteArray(512)) }
        }

        try {
            // Simulated high-frequency sync loop over 20 iterations
            for (cycle in 1..20) {
                val jobA = viewModel.sendFiles("bus_1_port_1", sampleFiles, "/sdcard/sync")
                val jobB = viewModel.sendFiles("bus_1_port_2", sampleFiles, "/sdcard/sync")

                jobA.join()
                jobB.join()
            }

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertEquals(10, sessionA.fileStore["/sdcard/sync"]?.size)
            assertEquals(10, sessionB.fileStore["/sdcard/sync"]?.size)
        } finally {
            sampleFiles.forEach { it.delete() }
        }
    }
}
