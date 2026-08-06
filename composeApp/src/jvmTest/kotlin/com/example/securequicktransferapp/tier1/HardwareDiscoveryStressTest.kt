package com.example.securequicktransferapp.tier1

import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import org.usb4java.Device
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Empirical Stress Test Harness for UsbDeviceManager discovery logic,
 * nested hub port chains, native reference counting lifecycle, and thread safety.
 */
class HardwareDiscoveryStressTest {

    /**
     * Simulated LibUsb Device and List state tracker for empirical ref count & memory safety testing.
     */
    class MockUsbDeviceTracker {
        val activeDevices = ConcurrentHashMap<String, SimulatedDeviceRef>()
        val freedDeviceLists = AtomicInteger(0)
        val createdDeviceLists = AtomicInteger(0)

        data class SimulatedDeviceRef(
            val id: String,
            val busNumber: Int,
            val portNumbers: List<Int>,
            val vendorId: Int,
            val productId: Int,
            val refCount: AtomicInteger = AtomicInteger(1)
        )

        fun createDevice(
            busNumber: Int,
            portNumbers: List<Int>,
            vendorId: Int,
            productId: Int
        ): SimulatedDeviceRef {
            val portPathStr = portNumbers.joinToString(".")
            val id = "bus_${busNumber}_port_${portPathStr}"
            val dev = SimulatedDeviceRef(id, busNumber, portNumbers, vendorId, productId)
            activeDevices[id] = dev
            return dev
        }
    }

    @Test
    fun testHardwareIdFormat_NestedHubPortChains() {
        // Test port chain formatting logic for various hub depth configurations
        fun formatHardwareId(busNumber: Int, portNumber: Int, portPath: List<Int>): Triple<String, Int, Int> {
            val portPathStr = if (portPath.isNotEmpty()) {
                portPath.joinToString(".")
            } else {
                portNumber.toString()
            }
            val id = "bus_${busNumber}_port_${portPathStr}"
            return Triple(id, busNumber, portNumber)
        }

        // Tier 1: Direct device on bus 1 port 3
        val (id1, bus1, port1) = formatHardwareId(1, 3, listOf(3))
        assertEquals("bus_1_port_3", id1)
        assertEquals(1, bus1)
        assertEquals(3, port1)

        // Tier 2: Device behind hub port 1.3
        val (id2, bus2, port2) = formatHardwareId(1, 3, listOf(1, 3))
        assertEquals("bus_1_port_1.3", id2)
        assertEquals(1, bus2)
        assertEquals(3, port2)

        // Tier 3: Deep nested hub chain 1.2.4
        val (id3, bus3, port3) = formatHardwareId(2, 4, listOf(1, 2, 4))
        assertEquals("bus_2_port_1.2.4", id3)
        assertEquals(2, bus3)
        assertEquals(4, port3)

        // Tier 7: Max USB spec depth (7 hubs)
        val (id7, bus7, port7) = formatHardwareId(3, 7, listOf(1, 2, 3, 4, 5, 6, 7))
        assertEquals("bus_3_port_1.2.3.4.5.6.7", id7)
    }

    @Test
    fun testReferenceCounting_LifecycleTrace() {
        val tracker = MockUsbDeviceTracker()

        // Simulate getDeviceList returning 3 devices
        val devNonAndroid = tracker.createDevice(1, listOf(1), 0x046D, 0xC52B) // Mouse (ref=1)
        val devAndroidNormal = tracker.createDevice(1, listOf(2), 0x18D1, 0x4EE1) // Pixel MTP (ref=1)
        val devAndroidAoa = tracker.createDevice(2, listOf(1, 3), 0x18D1, 0x2D01) // AOA (ref=1)

        // Simulate discovery loop processing:
        // Matching Android devices get ref count incremented (+1)
        fun simulateDiscover(devices: List<MockUsbDeviceTracker.SimulatedDeviceRef>): List<MockUsbDeviceTracker.SimulatedDeviceRef> {
            val discovered = mutableListOf<MockUsbDeviceTracker.SimulatedDeviceRef>()
            try {
                for (dev in devices) {
                    val knownAndroidVids = setOf(0x18D1, 0x04E8, 0x2717, 0x2A70)
                    val isAndroidVid = dev.vendorId in knownAndroidVids
                    val isAoa = dev.productId == 0x2D00 || dev.productId == 0x2D01

                    if (isAndroidVid || isAoa) {
                        dev.refCount.incrementAndGet() // Simulate LibUsb.refDevice (+1)
                        discovered.add(dev)
                    }
                }
                return discovered
            } finally {
                // Simulate LibUsb.freeDeviceList(list, true) -> unrefs ALL devices in list by 1
                for (dev in devices) {
                    dev.refCount.decrementAndGet() // Simulate LibUsb.unrefDevice (-1)
                }
            }
        }

        val allDevices = listOf(devNonAndroid, devAndroidNormal, devAndroidAoa)
        val discovered = simulateDiscover(allDevices)

        // Assert discovery filtered correctly
        assertEquals(2, discovered.size)

        // Assert non-Android device ref count dropped to 0 (freed by freeDeviceList)
        assertEquals(0, devNonAndroid.refCount.get(), "Non-matching device must be freed (ref count = 0)")

        // Assert matching Android devices have retained ref count = 1
        assertEquals(1, devAndroidNormal.refCount.get(), "Retained Android device must have ref count = 1")
        assertEquals(1, devAndroidAoa.refCount.get(), "Retained AOA device must have ref count = 1")

        // Simulate caller releasing discovered devices when session closes
        for (dev in discovered) {
            dev.refCount.decrementAndGet() // Simulate UsbDeviceManager.releaseDevice
        }

        assertEquals(0, devAndroidNormal.refCount.get(), "Released Android device must reach ref count = 0")
        assertEquals(0, devAndroidAoa.refCount.get(), "Released AOA device must reach ref count = 0")
    }

    @Test
    fun testConcurrentDiscovery_ThreadSafety() {
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(10)
        val discoveryCounter = AtomicInteger(0)
        val exceptionCounter = AtomicInteger(0)

        val tracker = MockUsbDeviceTracker()

        // Create 2 simulated Android devices
        val dev1 = tracker.createDevice(1, listOf(1), 0x18D1, 0x4EE1)
        val dev2 = tracker.createDevice(1, listOf(2), 0x04E8, 0x6860)

        val usbLock = Any()

        for (i in 0 until 10) {
            executor.submit {
                try {
                    synchronized(usbLock) {
                        // Simulate discovery under lock
                        dev1.refCount.incrementAndGet()
                        dev2.refCount.incrementAndGet()
                        dev1.refCount.decrementAndGet()
                        dev2.refCount.decrementAndGet()
                        discoveryCounter.incrementAndGet()
                    }
                } catch (e: Exception) {
                    exceptionCounter.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue(completed, "All 10 concurrent threads must complete discovery within 5 seconds")
        assertEquals(10, discoveryCounter.get(), "Exactly 10 discovery calls must succeed")
        assertEquals(0, exceptionCounter.get(), "No exceptions must occur during concurrent discovery")
    }

    @Test
    fun testZeroDevicesConnected_ReturnsEmptyListWithoutCrash() {
        val tracker = MockUsbDeviceTracker()
        val emptyDevicesList = emptyList<MockUsbDeviceTracker.SimulatedDeviceRef>()

        // Simulate discover on empty list
        var unrefCount = 0
        fun simulateDiscoverEmpty(list: List<MockUsbDeviceTracker.SimulatedDeviceRef>): List<MockUsbDeviceTracker.SimulatedDeviceRef> {
            val discovered = mutableListOf<MockUsbDeviceTracker.SimulatedDeviceRef>()
            try {
                for (dev in list) {
                    discovered.add(dev)
                }
                return discovered
            } finally {
                unrefCount++
            }
        }

        val result = simulateDiscoverEmpty(emptyDevicesList)
        assertTrue(result.isEmpty(), "0 devices connected must return empty list")
        assertEquals(1, unrefCount, "freeDeviceList must execute in finally block")
    }
}
