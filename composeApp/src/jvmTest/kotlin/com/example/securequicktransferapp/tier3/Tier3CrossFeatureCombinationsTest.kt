package com.example.securequicktransferapp.tier3

import com.example.securequicktransferapp.domain.model.RemoteFile
import com.example.securequicktransferapp.fixtures.FakeMainViewModel
import com.example.securequicktransferapp.fixtures.FakeUsbHardwareFixture
import com.example.securequicktransferapp.fixtures.FakeUsbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Tier3CrossFeatureCombinationsTest {

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
    fun testC1_MultiDeviceDiscoveryAndParallelTransfer() = runBlocking {
        // Step 1: Discover 2 devices
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        assertEquals(2, viewModel.getSidebarRenderedDeviceIds().size)

        // Step 2: Select Device A and start transfer
        viewModel.selectDevice("bus_1_port_1")
        val fileA = File.createTempFile("combA_", ".dat").apply { writeBytes(ByteArray(2048)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(fileA))

            // Step 3: Switch tab to Device B
            viewModel.selectDevice("bus_1_port_2")
            assertEquals("bus_1_port_2", viewModel.activeDeviceId.value)

            // Step 4: Verify Device A transfer completes in background
            jobA.join()
            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == fileA.name } == true)
        } finally {
            fileA.delete()
        }
    }

    @Test
    fun testC2_UnplugDeviceA_During_DeviceB_Transfer() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val fileB = File.createTempFile("combB_", ".dat").apply { writeBytes(ByteArray(3000)) }

        try {
            // Start transfer on Device B
            val jobB = viewModel.sendFiles("bus_1_port_2", listOf(fileB))

            // Unplug Device A while Device B transfer is in progress
            hardwareFixture.unplugDevice("bus_1_port_1")
            sessionManager.pollDevices()

            // Verify Device A card disappears from UI
            val sidebarIds = viewModel.getSidebarRenderedDeviceIds()
            assertFalse(sidebarIds.contains("bus_1_port_1"))
            assertTrue(sidebarIds.contains("bus_1_port_2"))

            // Verify Device B transfer finishes successfully
            jobB.join()
            val sessionB = sessionManager.getSession("bus_1_port_2")!!
            assertTrue(sessionB.fileStore["/sdcard"]?.any { it.name == fileB.name } == true)
        } finally {
            fileB.delete()
        }
    }

    @Test
    fun testC3_AoaModeSwitch_During_ActivePeerTransfer() = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        sessionManager.pollDevices()

        val activeFileA = File.createTempFile("aoaPeerA_", ".dat").apply { writeBytes(ByteArray(4000)) }

        try {
            val jobA = viewModel.sendFiles("bus_1_port_1", listOf(activeFileA))

            // Plug Device B in normal MTP mode
            hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860, isAoa = false)
            sessionManager.pollDevices()

            // Device B undergoes AOA mode reset while Device A is transferring
            sessionManager.switchAoaMode("bus_1_port_2")

            jobA.join()

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            val sessionB = sessionManager.getSession("bus_1_port_2")!!

            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == activeFileA.name } == true)
            assertTrue(sessionB.state.value.isAoaMode)
        } finally {
            activeFileA.delete()
        }
    }

    @Test
    fun testC4_ConcurrentMultiDeviceUploadDownload(): Unit = runBlocking {
        hardwareFixture.plugDevice(busNumber = 1, portPath = "1", vendorId = 0x18D1, productId = 0x4EE1)
        hardwareFixture.plugDevice(busNumber = 1, portPath = "2", vendorId = 0x04E8, productId = 0x6860)
        sessionManager.pollDevices()

        val uploadFileA = File.createTempFile("uploadA_", ".dat").apply { writeBytes(ByteArray(2048)) }
        val localDownloadDir = File(System.getProperty("java.io.tmpdir"), "c4_downloads")

        try {
            // Upload to Device A
            val uploadJob = viewModel.sendFiles("bus_1_port_1", listOf(uploadFileA))

            // Download from Device B concurrently
            val downloadJob = viewModel.fetchFiles(
                "bus_1_port_2",
                listOf(RemoteFile(name = "sample.txt", path = "/sdcard/sample.txt", isDirectory = false, size = 1024)),
                localDownloadDir
            )

            uploadJob.join()
            downloadJob.join()

            val sessionA = sessionManager.getSession("bus_1_port_1")!!
            assertTrue(sessionA.fileStore["/sdcard"]?.any { it.name == uploadFileA.name } == true)

            val downloadedFile = File(localDownloadDir, "sample.txt")
            assertTrue(downloadedFile.exists())
            downloadedFile.delete()
        } finally {
            uploadFileA.delete()
        }
    }
}
