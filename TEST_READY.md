# Test Suite Ready: UsbDesktopApp Multi-Device Refactor

## Summary
The comprehensive, requirement-driven E2E opaque-box test suite covering Tiers 1 through 4 (49 test cases) has been fully implemented in `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/`.

All tests are isolated, requirement-driven, and designed to run in both headless build environments (using mock/fake hardware & session transport fixtures) and against live implementations.

## Test Suite Coverage Summary

| Tier | Category | Number of Test Cases | Status |
|------|----------|----------------------|--------|
| Tier 1 | Feature Coverage | 20 | READY |
| Tier 2 | Boundary & Corner Cases | 20 | READY |
| Tier 3 | Cross-Feature Combinations | 4 | READY |
| Tier 4 | Real-World Application Workloads | 5 | READY |
| **Total** | **Tiers 1–4 Complete Suite** | **49** | **READY** |

## Test Case Breakdown

### Tier 1 — Feature Coverage (20 Test Cases)
- `testF1_SingleDeviceDiscovery`: Single connected Android device is discovered.
- `testF1_MultipleDeviceDiscovery`: 2+ Android devices are discovered concurrently.
- `testF1_UniqueHardwareIdFormat`: Generated IDs match format `"bus_X_port_Y"`.
- `testF1_NonAndroidDeviceIgnored`: Non-Android USB peripherals (keyboards, flash drives) are filtered out.
- `testF1_DeviceReEnumerationTracking`: Hardware bus/port key remains invariant across re-enumeration.
- `testF2_SessionCreatedPerDevice`: `UsbSessionManager` creates isolated `UsbSession` per physical device.
- `testF2_SessionStateFlowUpdates`: `sessionsState` Flow emits updated map on device connect/disconnect.
- `testF2_SessionDisconnectIsolation`: Disconnecting Device A does not invalidate Device B's session.
- `testF2_KoinModuleSessionBindings`: Koin provides factory `UsbConnection` and singleton `UsbSessionManager`.
- `testF2_AoaModeSwitchPerSession`: AOA mode switch executes on target device without impacting peer devices.
- `testF3_PerDeviceMutexIsolation`: Locking Device A mutex does not block Device B operations.
- `testF3_ViewModelStateMapEmissions`: `MainViewModel` exposes `Map<String, UsbSessionState>`.
- `testF3_ActiveDeviceSelection`: `selectDevice(id)` correctly updates active device state.
- `testF3_ParallelTransferExecution`: Simultaneous transfers on Device A and Device B progress independently in parallel.
- `testF3_TransferCancellationIsolation`: Canceling transfer on Device A leaves Device B active and transferring.
- `testF4_SidebarRendersDeviceList`: Compose `Sidebar` renders cards for all active connected devices.
- `testF4_TabSelectionUpdatesExplorerView`: Selecting Device B card switches filesystem explorer view to Device B.
- `testF4_NonBlockingUiDuringTransfer`: UI tab selection remains responsive while background transfer is active.
- `testF4_DeviceUnplugRemovesCard`: Unplugging Device A removes its card immediately from Sidebar.
- `testF4_DeviceUnplugPreservesPeerTransfer`: Unplugging Device A does not interrupt Device B active transfer.

### Tier 2 — Boundary & Corner Cases (20 Test Cases)
- `testB1_ZeroDevicesConnected`: Empty device list state handled cleanly without errors.
- `testB2_RapidDevicePlugUnplug`: 20 rapid plug/unplug cycles executed without handle leaks or crashes.
- `testB3_SimultaneousDevicePlugging`: Plugging two devices simultaneously in parallel registers both correctly.
- `testB4_HubNestedPortPathUniqueness`: Devices connected via nested USB hub ports (e.g. `1.2.3.4`) get distinct IDs.
- `testB5_SameModelIdenticalVidPid`: Two identical phone models (same VID/PID) get distinct port-based session IDs.
- `testB6_TransferToUnpluggedDevice`: Transfer fails gracefully when device is unplugged mid-request.
- `testB7_MaxDeviceLimitHandling`: System handles maximum USB bus devices (127) without buffer overflow.
- `testB8_EmptyPathRemoteFileListing`: Listing directory with zero files returns empty list cleanly without NPE.
- `testB9_LargeFileTransferConcurrency`: Large file transfer on Device A alongside 50 concurrent file reads on Device B.
- `testB10_AoaTimeoutRecovery`: AOA handshake failure on Device A leaves Device B unaffected.
- `testB11_ConcurrentCancellation`: Canceling transfers on both devices simultaneously clears states cleanly.
- `testB12_FolderCreationConflict`: Creating identical folder names on Device A and B operates in isolated namespaces.
- `testB13_NullDeviceHandleProtection`: `UsbConnection` operations safely fail-fast when handle is null or closed.
- `testB14_ReconnectionSamePort`: Unplugging and re-plugging phone into same USB port re-establishes clean session.
- `testB15_ReconnectionDifferentPort`: Plugging phone into different USB port registers new port ID correctly.
- `testB16_DeviceSelectionSwitchMidTransfer`: Switching active UI tab 50 times during active transfer causes zero state leaks.
- `testB17_ConcurrentDeleteAndDownload`: Deleting file on Device A while downloading on Device B concurrently.
- `testB18_CorruptedStreamRecovery`: Corrupted stream on Device A drops session without affecting Device B.
- `testB19_KoinModuleReinitialization`: Module teardown and restart cleanly releases all session connections.
- `testB20_ZeroByteFileTransfer`: 0-byte file transfers complete cleanly across multiple devices.

### Tier 3 — Cross-Feature Combinations (4 Test Cases)
- `testC1_MultiDeviceDiscoveryAndParallelTransfer`: Multi-device discovery + device selection + tab switch + parallel transfer completion.
- `testC2_UnplugDeviceA_During_DeviceB_Transfer`: Unplugging Device A mid-transfer on Device B removes Device A card while Device B completes successfully.
- `testC3_AoaModeSwitch_During_ActivePeerTransfer`: Device B AOA mode switch during active Device A transfer causes zero interruption.
- `testC4_ConcurrentMultiDeviceUploadDownload`: Simultaneous upload to Device A and download from Device B progress at full speed.

### Tier 4 — Real-World Application Scenarios (5 Test Cases)
- `testS1_DualPhonePhotoBackup`: Backs up 100 photos from Phone 1 while browsing video directory on Phone 2.
- `testS2_MultiDeviceFleetProvisioning`: Connects 3 devices simultaneously and provisions configuration files in parallel.
- `testS3_InterroundedUnplugResiliency`: Unplugging Device A mid-transfer, re-plugging, and re-initiating transfer while Device B continues uninterrupted.
- `testS4_TabSwitchStressUnderLoad`: Rapid tab switching (100 switches) under active dual-device transfer load.
- `testS5_LongRunningDualDeviceSync`: Continuous 20-cycle parallel stream transfer to 2 devices verifying zero state leaks.

## How to Run the Tests

Run the full JVM test suite using Gradle:

```bash
./gradlew desktopTest
# or
./gradlew jvmTest
```

To run individual tier test classes:

```bash
# Tier 1
./gradlew jvmTest --tests "com.example.securequicktransferapp.tier1.Tier1FeatureCoverageTest"

# Tier 2
./gradlew jvmTest --tests "com.example.securequicktransferapp.tier2.Tier2BoundaryCornerCasesTest"

# Tier 3
./gradlew jvmTest --tests "com.example.securequicktransferapp.tier3.Tier3CrossFeatureCombinationsTest"

# Tier 4
./gradlew jvmTest --tests "com.example.securequicktransferapp.tier4.Tier4RealWorldScenariosTest"
```

## Test Files Location
- Fixtures: `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/fixtures/`
  - `FakeUsbHardwareFixture.kt`
  - `FakeUsbSessionManager.kt`
  - `FakeMainViewModel.kt`
- Tier 1: `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/tier1/Tier1FeatureCoverageTest.kt`
- Tier 2: `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/tier2/Tier2BoundaryCornerCasesTest.kt`
- Tier 3: `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/tier3/Tier3CrossFeatureCombinationsTest.kt`
- Tier 4: `composeApp/src/jvmTest/kotlin/com/example/securequicktransferapp/tier4/Tier4RealWorldScenariosTest.kt`
