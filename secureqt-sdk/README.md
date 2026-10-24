# SecureQT SDK (`secureqt-sdk`)

A lightweight, zero-dependency Kotlin Multiplatform (KMP) library for high-speed, end-to-end encrypted data transfer over physical USB connections using the **Android Open Accessory (AOA)** protocol.

Produces both a JVM JAR (`secureqt-sdk-jvm.jar`) for Desktop Host applications and an Android AAR (`secureqt-sdk.aar`) for Android Client applications.

---

## Architecture & Topology

The SDK implements an asymmetric USB topology:
- **Desktop (JVM)** acts as the **USB Host (Initiator)**: scans the USB bus, commands the Android device to reboot into AOA mode, performs ECDH key exchange as initiator, and transmits commands and files.
- **Android** acts as the **USB Client / Accessory (Responder)**: opens the `UsbAccessory` file descriptor, performs ECDH key exchange as responder, and executes incoming commands.

```
secureqt-sdk
├── commonMain
│   ├── SecureQtSdk.kt            (Public entry point facade)
│   ├── channel/SecureChannel.kt  (Framed, AES-GCM encrypted transport channel)
│   ├── client/                   (ISecureTransferClient + SecureTransferClientImpl)
│   ├── crypto/                   (ECDH key agreement + AES-GCM 256-bit encryption)
│   ├── protocol/                 (PacketCodec, PacketTypes, CommandPayloadBuilder, CommandPayloadParser)
│   └── transport/IUsbTransport.kt(Raw USB transport abstraction)
├── jvmMain (Desktop Host)
│   ├── DesktopAoaTransport.kt    (Direct libusb bulk endpoint I/O)
│   ├── DesktopAoaConnector.kt    (Low-level AOA control transfers & endpoint resolution)
│   └── SecureQtSdkDesktopExt.kt  (createDesktopTransport, findDesktopBulkEndpoints, switchDeviceToAoa)
└── androidMain (Android Client)
    ├── AndroidAoaTransport.kt    (16 KB usbfs bursting client transport + UsbAccessory open factory)
    └── SecureQtSdkAndroidExt.kt  (createAndroidAccessoryTransport)
```

---

## Quickstart

### 1. Desktop Host (JVM) Usage
Add `secureqt-sdk-jvm.jar` and `usb4java` to your project dependencies:

```kotlin
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createDesktopTransport
import com.example.secureqt.sdk.findDesktopBulkEndpoints
import com.example.secureqt.sdk.switchDeviceToAoa

// 1. Switch connected Android device to AOA mode
SecureQtSdk.switchDeviceToAoa(device)

// 2. Locate bulk endpoints and open transport
val endpoints = SecureQtSdk.findDesktopBulkEndpoints(device)!!
val transport = SecureQtSdk.createDesktopTransport(
    handle = deviceHandle,
    inEp = endpoints.inAddr,
    outEp = endpoints.outAddr,
    ifaceNum = endpoints.interfaceNumber
)

// 3. Establish encrypted channel & perform ECDH handshake (Initiator)
val channel = SecureQtSdk.createChannel(transport)
val isConnected = channel.performHandshake(isInitiator = true)

// 4. Send files or custom commands
val client = SecureQtSdk.createTransferClient(channel)
client.sendFile(File("photo.jpg"), "/sdcard/Download")
```

### 2. Android Client (AAR) Usage
Add `secureqt-sdk.aar` to your Android application:

```kotlin
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createAndroidAccessoryTransport

// 1. Create transport from attached UsbAccessory
val transport = SecureQtSdk.createAndroidAccessoryTransport(usbManager, accessory)!!

// 2. Establish encrypted channel & perform ECDH handshake (Responder)
val channel = SecureQtSdk.createChannel(transport)
val isConnected = channel.performHandshake(isInitiator = false)

// 3. Receive secure payloads
val payload = channel.receiveSecure()
val command = SecureQtSdk.CommandPayloadParser.parse(payload)
```

### 3. Custom Commands (Open-Closed Principle)
Add project-specific commands without modifying the SDK:

```kotlin
// Host sends custom command (e.g. 0x10)
val payload = SecureQtSdk.CommandPayloadBuilder.buildSimple(0x10)
val response = client.executeCustomCommand(0x10, payload)

// Client parses received payload
val command = SecureQtSdk.CommandPayloadParser.parse(decryptedBytes)
when (command?.commandId) {
    0x10.toByte() -> { /* Handle custom command */ }
}
```

---

## Key Features
- **End-to-End Encryption**: ECDH P-256 ephemeral key exchange with AES-256-GCM symmetric encryption for every payload.
- **High Throughput**: Zero-copy native `ByteBuffer` slicing on JVM and 16 KB chunked `usbfs` burst transfers on Android to prevent kernel buffer overflows.
- **SOLID Design**: Decoupled transport, crypto, wire framing, and command semantics.
