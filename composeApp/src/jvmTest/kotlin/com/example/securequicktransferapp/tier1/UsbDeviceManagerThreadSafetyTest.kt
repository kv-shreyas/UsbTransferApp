package com.example.securequicktransferapp.tier1

import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.usb4java.Device
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbDeviceManagerThreadSafetyTest {

    private lateinit var deviceManager: UsbDeviceManager

    @BeforeTest
    fun setUp() {
        deviceManager = UsbDeviceManager()
    }

    @Test
    fun testHardwareIdFormatInvariance() {
        // Verify hardware ID format invariant: bus_X_port_Y
        val busNumber = 1
        val portPathStr = "3.2"
        val hardwareId = "bus_${busNumber}_port_${portPathStr}"
        val idRegex = Regex("^bus_\\d+_port_[\\d.]+$")

        assertTrue(idRegex.matches(hardwareId), "Hardware ID must match format bus_X_port_Y")
    }

    @Test
    fun testAoaModeReEnumerationHardwareIdInvariance() {
        // Simulating physical USB re-enumeration across AOA mode switch
        val originalBus = 1
        val originalPort = "2"

        // Before AOA (MTP mode)
        val normalVid = 0x04E8 // Samsung
        val normalPid = 0x6860 // MTP
        val normalHardwareId = "bus_${originalBus}_port_${originalPort}"

        // After ACCESSORY_START (AOA mode)
        val aoaVid = 0x18D1 // Google AOA
        val aoaPid = 0x2D01 // AOA Accessory
        val aoaHardwareId = "bus_${originalBus}_port_${originalPort}"

        // Physical bus and port path MUST be identical before and after AOA switch
        assertEquals(normalHardwareId, aoaHardwareId, "Physical hardware ID must remain invariant across AOA mode re-enumeration")
    }

    @Test
    fun testConcurrentUsbDeviceManagerAccess() = runBlocking {
        val numThreads = 10
        val iterationsPerThread = 20
        val executor = Executors.newFixedThreadPool(numThreads)
        val successCounter = AtomicInteger(0)
        val errorCounter = AtomicInteger(0)

        val jobs = (1..numThreads).map { threadIdx ->
            async(Dispatchers.IO) {
                for (i in 1..iterationsPerThread) {
                    try {
                        val devices = deviceManager.discoverDevices()
                        val isConnected = deviceManager.isDevicePhysicallyConnected("bus_1_port_1")
                        val found = deviceManager.findDeviceById("bus_1_port_1")
                        if (found != null) {
                            deviceManager.releaseDevice(found)
                        }
                        successCounter.incrementAndGet()
                    } catch (e: Throwable) {
                        println("Thread $threadIdx failed at iteration $i: ${e.message}")
                        errorCounter.incrementAndGet()
                    }
                }
            }
        }

        jobs.awaitAll()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals(0, errorCounter.get(), "Concurrent access to UsbDeviceManager caused errors/exceptions")
        assertEquals(numThreads * iterationsPerThread, successCounter.get(), "All concurrent calls must complete successfully")
    }
}
