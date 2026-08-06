# Original User Request

## 2026-08-05T17:22:40+05:30

# Teamwork Project Prompt — Draft

> Status: Ready for launch — awaiting user approval
> Goal: Craft prompt → get user approval → delegate to teamwork_preview

Refactor the Desktop application architecture to support transferring data to and from multiple Android devices simultaneously via USB, decoupling the current singleton connection layer into a multi-device session manager.

Working directory: /home/accord/Documents/Shreyas_Proj/Projects/KMP/UsbDesktopApp
Integrity mode: development

## Requirements

### R1. Hardware Layer Refactor (`UsbDeviceManager.kt`)
Update `UsbDeviceManager` to discover and track multiple connected Android devices concurrently. It must return unique hardware identifiers (e.g., bus/port combinations) rather than just the first matched device.

### R2. Domain & Session Management (`UsbSessionManager`)
Replace the singleton `UsbRepositoryImpl` approach. Create a `UsbSessionManager` (injected via Koin) that polls for devices, instantiates a unique `UsbConnection` and `UsbRepositoryImpl` for each physical device, and exposes a `StateFlow` of all active sessions.

### R3. State & Concurrency (`MainViewModel.kt`)
Refactor the `MainViewModel` to manage a map of device states rather than a single state. Replace the global `usbMutex` with a concurrent map of mutexes (one per device) so transfers can happen in parallel without blocking one another.

### R4. UI Refactor (`MainScreen.kt`, `Sidebar.kt`)
Update the Compose UI Sidebar to display a list of all connected devices as selectable tabs/cards. Ensure that selecting a device updates the main File Explorer view to reflect that specific device's filesystem, and that background transfers do not block UI interaction with other devices.

## Acceptance Criteria

### Concurrent Execution & Independence
- [ ] Plugging in a second Android device successfully registers it in the Sidebar without dropping the first device.
- [ ] Starting a large file transfer to Device A does not freeze the UI.
- [ ] While Device A is transferring, the user can click Device B's tab, browse its filesystem, and initiate a separate transfer successfully.
- [ ] Unplugging Device A immediately removes it from the UI without failing or interrupting Device B's active transfer.
