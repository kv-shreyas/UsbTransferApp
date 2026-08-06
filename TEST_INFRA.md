# E2E Test Infra: UsbDesktopApp Multi-Device Refactor

## Test Philosophy
- Opaque-box, requirement-driven test suite.
- Derived directly from `ORIGINAL_REQUEST.md` acceptance criteria without depending on internal implementation details.
- Systematic methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinations + Real-World Application Workloads.

## Feature Inventory
| # | Feature | Source | Tier 1 (Coverage) | Tier 2 (Boundary) | Tier 3 (Pairwise) | Tier 4 (Workload) |
|---|---------|--------|:-----------------:|:-----------------:|:-----------------:|:-----------------:|
| F1 | Hardware Multi-Device Discovery (`UsbDeviceManager`) | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ | ✓ |
| F2 | Domain & Session Management (`UsbSessionManager`) | ORIGINAL_REQUEST §R2 | 5 | 5 | ✓ | ✓ |
| F3 | ViewModel State & Parallel Concurrency (`MainViewModel`) | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ | ✓ |
| F4 | UI Sidebar & Active Device Tabs (`MainScreen`) | ORIGINAL_REQUEST §R4 | 5 | 5 | ✓ | ✓ |

## Test Architecture
- **Test Harness**: `composeApp/src/commonTest/kotlin/com/example/securequicktransferapp/` & JVM desktop integration test suites.
- **Invocation Command**: `./gradlew desktopTest` (or `./gradlew jvmTest`)
- **Pass/Fail Semantics**: All test assertions pass, zero unexpected exceptions, exit code 0.

## Test Case Breakdown (Tiers 1–4)

### Tier 1 — Feature Coverage (20 Test Cases)
- `testF1_SingleDeviceDiscovery`: Verify single connected Android device is discovered.
- `testF1_MultipleDeviceDiscovery`: Verify 2+ Android devices are discovered concurrently.
- `testF1_UniqueHardwareIdFormat`: Verify generated IDs use invariant bus/port formatting (`"bus_X_port_Y"`).
- `testF1_NonAndroidDeviceIgnored`: Verify non-Android USB peripherals are filtered out.
- `testF1_DeviceReEnumerationTracking`: Verify device bus/port key remains invariant across re-enumeration.
- `testF2_SessionCreatedPerDevice`: Verify `UsbSessionManager` creates isolated `UsbSession` per device.
- `testF2_SessionStateFlowUpdates`: Verify `sessionsState` Flow emits updated state on connect/disconnect.
- `testF2_SessionDisconnectIsolation`: Verify disconnecting Device A does not invalidate Device B's session.
- `testF2_KoinModuleSessionBindings`: Verify Koin provides factory `UsbConnection` and singleton `UsbSessionManager`.
- `testF2_AoaModeSwitchPerSession`: Verify AOA mode switch executes on target device without impacting peer devices.
- `testF3_PerDeviceMutexIsolation`: Verify transfer lock on Device A mutex does not block Device B operations.
- `testF3_ViewModelStateMapEmissions`: Verify `MainViewModel` exposes `Map<String, UsbSessionState>`.
- `testF3_ActiveDeviceSelection`: Verify `selectDevice(id)` correctly updates current active device state.
- `testF3_ParallelTransferExecution`: Verify two simultaneous transfers progress independently.
- `testF3_TransferCancellationIsolation`: Verify canceling transfer on Device A leaves Device B active.
- `testF4_SidebarRendersDeviceList`: Verify Compose `Sidebar` renders cards for all active devices.
- `testF4_TabSelectionUpdatesExplorerView`: Verify clicking Device B card switches filesystem view to Device B.
- `testF4_NonBlockingUiDuringTransfer`: Verify UI tab selection remains responsive while transfer is active on background device.
- `testF4_DeviceUnplugRemovesCard`: Verify unplugging Device A removes its card immediately from Sidebar.
- `testF4_DeviceUnplugPreservesPeerTransfer`: Verify unplugging Device A does not interrupt Device B active transfer.

### Tier 2 — Boundary & Corner Cases (20 Test Cases)
- `testB1_ZeroDevicesConnected`: Verify empty device list state handled gracefully.
- `testB2_RapidDevicePlugUnplug`: Verify rapid plug/unplug cycles do not leak handles or crash session manager.
- `testB3_SimultaneousDevicePlugging`: Verify plugging two devices at exact same time registers both correctly.
- `testB4_HubNestedPortPathUniqueness`: Verify devices connected via nested USB hub ports get distinct identifiers.
- `testB5_SameModelIdenticalVidPid`: Verify two identical phone models (same VID/PID) get distinct session IDs.
- `testB6_TransferToUnpluggedDevice`: Verify immediate transfer error handling when device is unplugged mid-request.
- `testB7_MaxDeviceLimitHandling`: Verify system handles maximum supported USB bus devices without buffer overflow.
- `testB8_EmptyPathRemoteFileListing`: Verify listing empty remote directory handles null/empty file lists cleanly.
- `testB9_LargeFileTransferConcurrency`: Verify 1GB file transfer on Device A alongside high-frequency file reads on Device B.
- `testB10_AoaTimeoutRecovery`: Verify AOA handshake failure on Device A leaves Device B unaffected.
- `testB11_ConcurrentCancellation`: Verify canceling transfers on both devices simultaneously clears states cleanly.
- `testB12_FolderCreationConflict`: Verify creating identical folder names on Device A and B operates in isolated namespaces.
- `testB13_NullDeviceHandleProtection`: Verify `UsbConnection` operations safely fail-fast if handle is null.
- `testB14_ReconnectionSamePort`: Verify unplugging and re-plugging phone into same USB port re-establishes clean session.
- `testB15_ReconnectionDifferentPort`: Verify plugging phone into different USB port registers new port ID correctly.
- `testB16_DeviceSelectionSwitchMidTransfer`: Verify switching active UI tab 50 times during active transfer causes no state leak.
- `testB17_ConcurrentDeleteAndDownload`: Verify deleting file on Device A while downloading on Device B.
- `testB18_CorruptedStreamRecovery`: Verify corrupted stream on Device A drops session without affecting Device B.
- `testB19_KoinModuleReinitialization`: Verify Koin module teardown and restart cleanly releases all session connections.
- `testB20_ZeroByteFileTransfer`: Verify 0-byte file transfers complete cleanly across multiple devices.

### Tier 3 — Cross-Feature Combinations (4 Test Cases)
- `testC1_MultiDeviceDiscoveryAndParallelTransfer`: Combine F1 + F2 + F3 + F4 (Discover 2 devices, select Device A, start transfer, switch tab to Device B, verify isolated state and transfer completion).
- `testC2_UnplugDeviceA_During_DeviceB_Transfer`: Combine F1 + F2 + F3 + F4 (Start transfer on Device B, physically disconnect Device A, verify Device A card disappears while Device B transfer finishes successfully).
- `testC3_AoaModeSwitch_During_ActivePeerTransfer`: Combine F1 + F2 + F3 (Device A is actively transferring; Device B is plugged in and undergoes AOA mode reset; verify Device A transfer is not interrupted).
- `testC4_ConcurrentMultiDeviceUploadDownload`: Combine F2 + F3 + F4 (Device A uploading file from PC to phone; Device B downloading file from phone to PC; verify both progress at full speed).

### Tier 4 — Real-World Application Scenarios (5 Test Cases)
- `testS1_DualPhonePhotoBackup`: User plugs in Phone 1 and Phone 2; backs up 100 photos from Phone 1 while browsing video folder on Phone 2.
- `testS2_MultiDeviceFleetProvisioning`: User connects 3 devices simultaneously, sending configuration files to all 3 devices in parallel.
- `testS3_InterroundedUnplugResiliency`: User transfers large file to Device A, accidentally unplugs Device A, plugs Device A back in, re-initiates transfer while Device B continues uninterrupted.
- `testS4_TabSwitchStressUnderLoad`: User initiates parallel transfers on 2 devices and rapidly clicks between Device A and Device B tabs to inspect real-time progress bars.
- `testS5_LongRunningDualDeviceSync`: Continuous 5-minute parallel stream transfer to 2 devices verifying zero memory leaks or thread deadlocks.

## Minimum Coverage Thresholds
- Tier 1: ≥ 20 tests
- Tier 2: ≥ 20 tests
- Tier 3: ≥ 4 tests
- Tier 4: ≥ 5 tests
- **Total Minimum Test Suite Size**: 49 tests
