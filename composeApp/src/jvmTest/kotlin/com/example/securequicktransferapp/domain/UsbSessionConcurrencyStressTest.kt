package com.example.securequicktransferapp.domain

import com.example.securequicktransferapp.data.usb.UsbConnection
import com.example.securequicktransferapp.data.usb.UsbDeviceManager
import com.example.securequicktransferapp.data.usb.UsbSession
import com.example.securequicktransferapp.data.usb.UsbSessionManager
import com.example.securequicktransferapp.domain.model.DeviceSessionStatus
import com.example.securequicktransferapp.domain.model.DiscoveredUsbDevice
import com.example.securequicktransferapp.domain.model.UsbSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.usb4java.Device
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Empirical Stress Challenge Harness for M2:
 * Session Concurrency & Per-Device Mutex Isolation in UsbSessionManager and UsbSession.
 */
class UsbSessionConcurrencyStressTest {

    private fun createDummyDeviceHandle(): Device {
        val constructor = Device::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }

    private fun createDiscoveredDevice(id: String, deviceName: String = "Test Device $id"): DiscoveredUsbDevice {
        return DiscoveredUsbDevice(
            id = id,
            busNumber = 1,
            portNumber = (id.hashCode() and 0x7FFFFFFF) % 100 + 1,
            vendorId = 0x18D1,
            productId = 0x4EE1,
            isAoa = false,
            deviceName = deviceName,
            device = createDummyDeviceHandle()
        )
    }

    /**
     * Requirement 2: Verify per-device sessionMutex isolation.
     * Operations on Device A (locking sessionA.sessionMutex) must NOT block operations on Device B (sessionB.sessionMutex).
     * Operations on Device A MUST block subsequent operations on Device A (serializing per-device transfers).
     */
    @Test
    fun testPerDeviceSessionMutexIsolation_DeviceADoesNotBlockDeviceB() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val devA = createDiscoveredDevice("bus_1_port_1", "Phone A")
        val devB = createDiscoveredDevice("bus_1_port_2", "Phone B")

        val sessionA = UsbSession("bus_1_port_1", devA, deviceManager)
        val sessionB = UsbSession("bus_1_port_2", devB, deviceManager)

        val lockHoldTimeMs = 300L
        val aLockedLatch = CountDownLatch(1)
        val bFinishedLatch = CountDownLatch(1)
        val secondAFinishedLatch = CountDownLatch(1)

        var bAcquireTimeMs = -1L
        var secondAAcquireTimeMs = -1L

        // 1. Lock Session A for lockHoldTimeMs
        val lockAJob = launch(Dispatchers.IO) {
            sessionA.sessionMutex.withLock {
                aLockedLatch.countDown()
                delay(lockHoldTimeMs)
            }
        }

        // Wait until Session A lock is active
        assertTrue(aLockedLatch.await(1, TimeUnit.SECONDS), "Session A lock must be acquired")

        // 2. Concurrently attempt to acquire Session B lock (should NOT be blocked by A)
        val lockBJob = launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            sessionB.sessionMutex.withLock {
                bAcquireTimeMs = System.currentTimeMillis() - startTime
            }
            bFinishedLatch.countDown()
        }

        // 3. Concurrently attempt to acquire Session A lock again (MUST be blocked by A)
        val secondLockAJob = launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            sessionA.sessionMutex.withLock {
                secondAAcquireTimeMs = System.currentTimeMillis() - startTime
            }
            secondAFinishedLatch.countDown()
        }

        // Session B lock acquisition should complete almost instantly (< 100ms), while Session A is still held!
        val bCompleted = bFinishedLatch.await(200, TimeUnit.MILLISECONDS)
        assertTrue(bCompleted, "Session B mutex lock must NOT be blocked by Session A lock")
        assertTrue(bAcquireTimeMs in 0..100, "Session B acquire time ($bAcquireTimeMs ms) must be < 100ms")

        // Session A second lock must wait until first A lock finishes (>= lockHoldTimeMs - wait offset)
        val secondACompleted = secondAFinishedLatch.await(1, TimeUnit.SECONDS)
        assertTrue(secondACompleted, "Second Session A lock must complete after Session A lock is released")
        assertTrue(
            secondAAcquireTimeMs >= 200,
            "Second Session A acquire time ($secondAAcquireTimeMs ms) must be >= 200ms showing proper serialization"
        )

        joinAll(lockAJob, lockBJob, secondLockAJob)
    }

    /**
     * High Concurrency Test: 100 parallel tasks across 10 devices.
     * Validates that independent device locks execute in parallel without cross-device contention or deadlock.
     */
    @Test
    fun testConcurrentMultiDeviceOperations_100ParallelTasks() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val numDevices = 10
        val tasksPerDevice = 10
        val totalTasks = numDevices * tasksPerDevice

        val sessions = (1..numDevices).map { i ->
            val id = "bus_1_port_$i"
            val dev = createDiscoveredDevice(id, "Device $i")
            UsbSession(id, dev, deviceManager)
        }

        val completedCount = AtomicInteger(0)
        val executionTime = measureTimeMillis {
            coroutineScope {
                (0 until totalTasks).forEach { taskIdx ->
                    launch(Dispatchers.IO) {
                        val session = sessions[taskIdx % numDevices]
                        session.sessionMutex.withLock {
                            delay(10) // Simulate work
                            completedCount.incrementAndGet()
                        }
                    }
                }
            }
        }

        assertEquals(totalTasks, completedCount.get(), "All $totalTasks parallel tasks must complete successfully")
        println("[StressTest] 100 parallel tasks across 10 devices completed in ${executionTime}ms")
    }

    /**
     * Requirement 3: Stress-test StateFlow map emissions under high concurrency load.
     * Validates atomic StateFlow emissions during continuous concurrent session state updates across multiple sessions.
     */
    @Test
    fun testStateFlowMapEmissions_HighConcurrencyUpdates() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)

        val totalUpdatesPerSession = 50
        val updateCount = AtomicInteger(0)
        val numSessions = 5

        // Pre-populate sessions into sessionManager via reflection/poll or state mutation
        val activeSessionsField = UsbSessionManager::class.java.getDeclaredField("activeSessions")
        activeSessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeMap = activeSessionsField.get(sessionManager) as ConcurrentHashMap<String, UsbSession>

        for (i in 1..numSessions) {
            val devId = "bus_1_port_$i"
            val dev = createDiscoveredDevice(devId, "Device $i")
            val session = UsbSession(devId, dev, deviceManager)
            activeMap[devId] = session
        }
        sessionManager.updateSessionsStateMap()

        assertEquals(numSessions, sessionManager.sessionsState.value.size)

        // 5 coroutines continuously mutating session states concurrently
        val updateJobs = (1..numSessions).map { devIdx ->
            launch(Dispatchers.IO) {
                val devId = "bus_1_port_$devIdx"
                repeat(totalUpdatesPerSession) { step ->
                    sessionManager.updateSessionState(devId) { state ->
                        state.copy(currentPath = "/sdcard/folder_$step")
                    }
                    updateCount.incrementAndGet()
                    delay(1)
                }
            }
        }

        updateJobs.joinAll()

        assertEquals(numSessions * totalUpdatesPerSession, updateCount.get(), "Total updates must equal ${numSessions * totalUpdatesPerSession}")
        val finalStateMap = sessionManager.sessionsState.value
        assertEquals(numSessions, finalStateMap.size, "Final state map must contain all $numSessions sessions")
        for (i in 1..numSessions) {
            val devState = finalStateMap["bus_1_port_$i"]
            assertNotNull(devState, "State for bus_1_port_$i must exist")
            assertEquals("/sdcard/folder_49", devState.currentPath, "Final currentPath for device $i must be folder_49")
        }
    }

    /**
     * Requirement 3: Test concurrent session removal and state map clean up.
     */
    @Test
    fun testConcurrentSessionRemoval_StateFlowMapCleanup() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)

        val activeSessionsField = UsbSessionManager::class.java.getDeclaredField("activeSessions")
        activeSessionsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val activeMap = activeSessionsField.get(sessionManager) as ConcurrentHashMap<String, UsbSession>

        val numSessions = 20
        for (i in 1..numSessions) {
            val devId = "bus_1_port_$i"
            val dev = createDiscoveredDevice(devId, "Device $i")
            val session = UsbSession(devId, dev, deviceManager)
            activeMap[devId] = session
        }
        sessionManager.updateSessionsStateMap()

        assertEquals(numSessions, sessionManager.sessionsState.value.size)

        // Concurrently remove half of the sessions while updating the other half
        val removeJobs = (1..numSessions / 2).map { devIdx ->
            launch(Dispatchers.IO) {
                val devId = "bus_1_port_$devIdx"
                val removed = activeMap.remove(devId)
                removed?.disconnect()
                sessionManager.updateSessionsStateMap()
            }
        }

        val updateJobs = (numSessions / 2 + 1..numSessions).map { devIdx ->
            launch(Dispatchers.IO) {
                val devId = "bus_1_port_$devIdx"
                sessionManager.updateSessionState(devId) { state ->
                    state.copy(status = DeviceSessionStatus.Ready)
                }
            }
        }

        joinAll(*(removeJobs + updateJobs).toTypedArray())

        val finalMap = sessionManager.sessionsState.value
        assertEquals(numSessions / 2, finalMap.size, "Final map must contain exactly remaining 10 sessions")
    }

    /**
     * Test stopPolling teardown and handle cleanup logic.
     */
    @Test
    fun testStopPolling_CleanlyDisconnectsSessionsAndResetsState() = runBlocking {
        val deviceManager = UsbDeviceManager()
        val sessionManager = UsbSessionManager(deviceManager)

        sessionManager.startPolling()
        sessionManager.pollDevices()

        sessionManager.stopPolling()

        assertEquals(0, sessionManager.sessionsState.value.size, "sessionsState map must be empty after stopPolling()")
        assertEquals(0, sessionManager.getActiveSessions().size, "getActiveSessions() must be empty after stopPolling()")
    }
}
