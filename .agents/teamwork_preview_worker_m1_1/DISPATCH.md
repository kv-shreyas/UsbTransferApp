## 2026-08-05T11:56:47Z
You are teamwork_preview_worker_m1_1 (Milestone 1 Hardware Layer Worker).
Your working directory is: /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/.agents/teamwork_preview_worker_m1_1

Mandatory Documents to Read:
1. ORIGINAL_REQUEST.md: /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/.agents/ORIGINAL_REQUEST.md
2. PROJECT.md: /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/PROJECT.md
3. Explorer Reports:
   - /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/.agents/teamwork_preview_explorer_m1_1/handoff.md
   - /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/.agents/teamwork_preview_explorer_m1_2/handoff.md
   - /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp/.agents/teamwork_preview_explorer_m1_3/handoff.md

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_preview_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Scope & File Ownership:
You have exclusive write ownership of:
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/DiscoveredUsbDevice.kt`
- `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/data/usb/UsbDeviceManager.kt`

Task:
1. Create `DiscoveredUsbDevice.kt` under `composeApp/src/jvmMain/kotlin/com/example/securequicktransferapp/domain/model/`.
2. Refactor `UsbDeviceManager.kt` to:
   - Implement `discoverDevices(): List<DiscoveredUsbDevice>` using `LibUsb.getDeviceList()`, filtering for known Android VIDs (`knownAndroidVids`) and AOA PIDs (`0x2D00`, `0x2D01`).
   - Extract invariant physical hardware IDs (`"bus_${busNumber}_port_${portPathStr}"`) using `LibUsb.getBusNumber()`, `LibUsb.getPortNumber()`, and `LibUsb.getPortNumbers()`.
   - Call `LibUsb.refDevice(device)` on returned `DiscoveredUsbDevice` instances and safely free the `DeviceList` with `LibUsb.freeDeviceList(list, true)`.
   - Implement `findDeviceById(hardwareId: String): Device?` targeting specific bus/port ID with retained reference (+1).
   - Implement `isDevicePhysicallyConnected(hardwareId: String): Pair<Boolean, String?>`.
   - Refactor legacy `findAndroidDevice()` and `isDevicePhysicallyConnected()` to delegate to `discoverDevices()` with proper memory cleanup.
   - Guard native `LibUsb.getDeviceList` calls with synchronization locks for thread safety.
3. Run build and test verification commands (`./gradlew desktopTest`, `./gradlew compileKotlinJvm`, or `./gradlew build`) and document commands and output in your handoff report.
4. Record your work, file modifications, build/test results, and handoff report in handoff.md in your working directory.
5. Create and update progress.md in your working directory with heartbeat timestamp `Last visited: [timestamp]`.
6. Send a completion message back to Project Orchestrator with summary of changes and path to handoff.md.
