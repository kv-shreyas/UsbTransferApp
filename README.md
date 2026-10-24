# Secure Quick Transfer (SecureQT)

A high-performance, cross-platform Kotlin Multiplatform (KMP) system for end-to-end encrypted, multi-gigabyte data transfer over physical USB cables using the **Android Open Accessory (AOA)** protocol.

---

## Architecture & Asymmetric Topology

SecureQT strictly separates roles across the physical USB wire:

- **Desktop (JVM / Windows / Linux / macOS)**: **USB Host (Initiator)**. Discovers connected devices, triggers AOA mode switching, verifies/installs WinUSB drivers, initiates mutual ECDH-256 key exchange, and controls file and directory transfers.
- **Android**: **USB Client / Accessory (Responder)**. Responds to AOA attachment, opens the `UsbAccessory` file descriptor, completes ECDH-256 key agreement as responder, and handles encrypted file reception and command dispatch.

```
                      Physical USB Cable (AOA Bulk Pipe)
┌────────────────────────────────┐         ┌────────────────────────────────┐
│   Desktop Host (JVM / PC)      │         │     Android Client Device      │
│  - usb-driver-installer.jar    │ ======= │  - secureqt-sdk.aar            │
│  - secureqt-sdk-jvm.jar        │         │  - UsbAccessory / usbfs        │
│  - ECDH Initiator (AES-256-GCM)│         │  - ECDH Responder (AES-256-GCM)│
└────────────────────────────────┘         └────────────────────────────────┘
```

---

## Distributable Artifacts

| Artifact | Type | Target | Description |
|---|---|---|---|
| **`secureqt-sdk-jvm.jar`** | JAR | Desktop Host (JVM) | Core protocol, ECDH encryption, sliding window buffering, AOA switcher, and Desktop bulk transport. |
| **`secureqt-sdk-release.aar`** | AAR | Android Client | Core protocol, ECDH encryption, sliding window buffering, and 16 KB burst `usbfs` AOA transport. |
| **`usb-driver-installer-standalone-all.jar`** | Fat JAR | Windows (JVM) | Automated, background WinUSB driver installation via embedded Zadig/WDI binaries. |

### Building Artifacts from Source

Run the following Gradle commands to produce the distributables:

```bash
# 1. Build Desktop Host SDK JAR
./gradlew :secureqt-sdk:jvmJar
# Output: secureqt-sdk/build/libs/secureqt-sdk-jvm.jar

# 2. Build Android Client SDK AAR
./gradlew :secureqt-sdk:bundleReleaseAar
# Output: secureqt-sdk/build/outputs/aar/secureqt-sdk-release.aar

# 3. Build Windows Driver Installer Standalone JAR (Fat-JAR with bundled binaries)
./gradlew :usb-driver-installer:standaloneJar
# Output: usb-driver-installer/build/libs/usb-driver-installer-standalone-1.0.0-all.jar
```

---

## Prerequisites & Basic Setup

### A. Desktop Host Setup (Before using `secureqt-sdk-jvm.jar` and `usb-driver-installer.jar`)

1. **Java Runtime**: JDK 11 or higher (compatible with Java 11, 17, and 21).
2. **Runtime Dependencies**:
   Ensure `usb4java` and Kotlin coroutines are present in your project:
   ```kotlin
   // build.gradle.kts
   dependencies {
       implementation(files("libs/secureqt-sdk-jvm.jar"))
       implementation(files("libs/usb-driver-installer-standalone-1.0.0-all.jar"))
       implementation("org.usb4java:usb4java:1.3.0")
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
   }
   ```
3. **Operating System USB Permissions & Drivers**:
   - **Windows**:
     - Windows requires a WinUSB-compatible driver assigned to the device's Google AOA interface (`VID: 0x18D1`, `PID: 0x2D00` or `0x2D01`).
     - **Automated**: Use `usb-driver-installer` (see [Driver Installer Integration](#windows-driver-installer-setup)) to silently install WinUSB or launch the embedded Zadig GUI.
     - **ADB Coexistence**: If the Android Debug Bridge (`adb`) is running, it may hold an exclusive lock on the USB device. Stop ADB (`adb kill-server`) before initiating AOA switching.
   - **Linux**:
     - Grant non-root user access to Android and Google AOA USB devices by creating `/etc/udev/rules.d/51-android.rules`:
       ```bash
       SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
       ```
     - Reload udev rules:
       ```bash
       sudo udevadm control --reload-rules && sudo udevadm trigger
       ```
   - **macOS**:
     - Native IOKit backend works out-of-the-box via `usb4java`; no additional kernel drivers needed.

---

### B. Android Client Setup (Before using `secureqt-sdk-release.aar`)

1. **SDK Levels**:
   - `minSdkVersion 24` (Android 7.0 Nougat) or higher.
   - `targetSdkVersion 34+`.

2. **Add AAR Dependency**:
   Place `secureqt-sdk-release.aar` into your app's `libs/` directory:
   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/secureqt-sdk-release.aar"))
       implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
   }
   ```

3. **`AndroidManifest.xml` Configuration**:
   Declare USB accessory hardware support and permissions:
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android">

       <!-- Require USB Accessory capability -->
       <uses-feature android:name="android.hardware.usb.accessory" android:required="true" />

       <!-- Required for background transfers on Android 14+ -->
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

       <application ...>

           <!-- Receiver or Service to handle AOA attachment -->
           <receiver
               android:name=".UsbAttachmentReceiver"
               android:exported="true">
               <intent-filter>
                   <action android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED" />
               </intent-filter>
               <meta-data
                   android:name="android.hardware.usb.action.USB_ACCESSORY_ATTACHED"
                   android:resource="@xml/accessory_filter" />
           </receiver>

       </application>
   </manifest>
   ```

4. **Accessory Filter XML (`res/xml/accessory_filter.xml`)**:
   Specify matching manufacturer and model strings (these must match the strings sent by Desktop during AOA negotiation):
   ```xml
   <?xml version="1.0" encoding="utf-8"?>
   <resources>
       <usb-accessory
           manufacturer="AndroidOpenAccessory"
           model="DataTransfer" />
   </resources>
   ```

---

## Integration Guide

### 1. Windows Driver Installer Integration (Desktop)

[`usb-driver-installer`](file:///home/aosp/Documents/Shreyas/KMP/SQT/usb-driver-installer) manages automatic driver installation for devices requiring WinUSB without external tool installations:

```kotlin
import com.example.usbdriverinstaller.api.UsbDriverInstallerFactory
import com.example.usbdriverinstaller.api.DriverInstallState

// 1. Create a reactive driver manager instance
val driverManager = UsbDriverInstallerFactory.createManager()

// 2. Observe install states (e.g. in Compose Desktop or Coroutine scope)
scope.launch {
    driverManager.driverInstallState.collect { state ->
        when (state) {
            is DriverInstallState.Idle -> { /* Ready */ }
            is DriverInstallState.Installing -> {
                println("Installing WinUSB driver for ${state.deviceName} (${state.progressPercentage}%)...")
            }
            is DriverInstallState.Installed -> {
                println("Driver installed successfully for ${state.deviceName}!")
            }
            is DriverInstallState.Failed -> {
                println("Driver install failed: ${state.message}")
            }
        }
    }
}

// 3. Trigger installation when an unconfigured device is detected
driverManager.onDeviceNeedsDriver(vendorId = 0x18D1, productId = 0x2D00, deviceName = "Android AOA")

// Or manually launch the interactive Zadig GUI if preferred
driverManager.launchZadig()
```

---

### 2. Desktop Host Implementation (JVM)

```kotlin
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createDesktopTransport
import com.example.secureqt.sdk.findDesktopBulkEndpoints
import com.example.secureqt.sdk.switchDeviceToAoa
import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.io.File

// 1. Detect candidate Android device and reboot into AOA mode
val device: Device = findConnectedUsbDevice()
val switched = SecureQtSdk.switchDeviceToAoa(
    device = device,
    manufacturer = "AndroidOpenAccessory",
    model = "DataTransfer"
)
if (!switched) {
    println("Device is already in AOA mode or switch rejected.")
}

// 2. Wait for USB re-enumeration (VID: 0x18D1, PID: 0x2D00/0x2D01) and locate endpoints
val aoaDevice: Device = waitForAoaDevice()
val endpointInfo = SecureQtSdk.findDesktopBulkEndpoints(aoaDevice)
    ?: error("AOA Bulk endpoints not found")

// 3. Open device handle and claim interface via libusb
val handle = DeviceHandle()
LibUsb.open(aoaDevice, handle)
LibUsb.claimInterface(handle, endpointInfo.interfaceNumber)

// 4. Create Desktop AOA Transport
val transport = SecureQtSdk.createDesktopTransport(
    handle = handle,
    inEp = endpointInfo.inAddr,
    outEp = endpointInfo.outAddr,
    ifaceNum = endpointInfo.interfaceNumber
)

// 5. Establish SecureChannel & execute ECDH Handshake as Initiator
val channel = SecureQtSdk.createChannel(transport)
val sessionEstablished = channel.performHandshake(isInitiator = true)
check(sessionEstablished) { "Failed to establish secure session with Android" }

// 6. Perform file transfers and commands
val transferClient = SecureQtSdk.createTransferClient(channel)

// Send a single file
transferClient.sendFile(File("sample.pdf"), destinationPath = "/sdcard/Download") { sent, total ->
    println("Progress: ${(sent * 100) / total}%")
}

// Query remote directory listing
val remoteFiles = transferClient.listRemoteFiles("/sdcard/Download")
remoteFiles.forEach { file ->
    println("File: ${file.name} (${file.size} bytes)")
}

// Clean up
channel.disconnect()
transport.close()
```

---

### 3. Android Client Implementation (AAR)

```kotlin
import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import com.example.secureqt.sdk.SecureQtSdk
import com.example.secureqt.sdk.createAndroidAccessoryTransport
import com.example.secureqt.sdk.protocol.CommandPayloadParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runAndroidClient(context: Context, accessory: UsbAccessory) = withContext(Dispatchers.IO) {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // 1. Open AOA Transport directly from accessory
    val transport = SecureQtSdk.createAndroidAccessoryTransport(usbManager, accessory)
        ?: error("Failed to open accessory parcel descriptor")

    // 2. Establish SecureChannel & execute ECDH Handshake as Responder
    val channel = SecureQtSdk.createChannel(transport)
    val sessionEstablished = channel.performHandshake(isInitiator = false)
    if (!sessionEstablished) {
        transport.close()
        return@withContext
    }

    println("Secure channel established with Desktop Host!")

    // 3. Command Receive Loop
    try {
        while (transport.isConnected()) {
            val rawPayload = channel.receiveSecure() ?: break

            // Parse incoming command header
            val command = CommandPayloadParser.parse(rawPayload) ?: continue
            when (command.commandId) {
                SecureQtSdk.Commands.CMD_LIST_DIR -> {
                    val path = CommandPayloadParser.readString(command.payload)
                    // Query storage and send response...
                }
                SecureQtSdk.Commands.CMD_SEND_FILE_START -> {
                    val (fileName, fileSize) = CommandPayloadParser.readFileStart(command.payload)
                    // Stream decrypted incoming data...
                }
            }
        }
    } finally {
        channel.disconnect()
        transport.close()
    }
}
```

---

### 4. Custom Commands (Open-Closed Principle)

To add new custom commands between your Desktop host and Android client without modifying the SDK:

```kotlin
// Host: Send custom command (e.g. 0x20 - GET_DEVICE_STATS)
val payload = SecureQtSdk.CommandPayloadBuilder.buildSimple(0x20)
val responseBytes = transferClient.executeCustomCommand(0x20.toByte(), payload)

// Client: Respond to custom command
when (command.commandId) {
    0x20.toByte() -> {
        val statsResponse = "Battery: 95%, Temp: 32C".toByteArray(Charsets.UTF_8)
        channel.sendSecure(statsResponse)
    }
}
```

---

## Using `secureqt-sdk-jvm.jar` in a Windows C# Project (Visual Studio)

To consume `secureqt-sdk-jvm.jar` from a .NET 6/7/8 or .NET Framework project in Visual Studio on Windows, choose one of the three established approaches:

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                          Windows C# / .NET Application                         │
├─────────────────────────┬──────────────────────────────┬───────────────────────┤
│  Option A: IKVM.NET     │  Option B: Sidecar / CLI     │  Option C: Native C#  │
│  (Direct In-Process DLL)│  (Zero-Interop Process)      │  (Pure .NET Protocol) │
│  Compile JAR -> .NET DLL│  Launch headless JAR via CLI │  System.Security &    │
│  IKVM NuGet reference   │  JSON-RPC / StdIO IPC        │  LibUsbDotNet         │
└─────────────────────────┴──────────────────────────────┴───────────────────────┘
```

---

### Option A: Direct In-Process via IKVM (.NET Assembly Conversion) — *Recommended*

[IKVM](https://github.com/ikvm-revived/ikvm) is an active, open-source Java-to-.NET bytecode compiler. It translates Java/Kotlin bytecode directly into a native .NET assembly (`.dll`), enabling direct C# calls in Visual Studio.

#### 1. Install IKVM NuGet Package
In Visual Studio, open the **Package Manager Console** (or NuGet Package Manager) and install:
```powershell
Install-Package IKVM -Version 8.10.3
```

#### 2. Configure Your `.csproj`
Place `secureqt-sdk-jvm.jar` and its dependencies in a `libs` folder in your project, then add `<IkvmReference>` tags to your project file:

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0-windows</TargetFramework>
    <Nullable>enable</Nullable>
  </PropertyGroup>

  <ItemGroup>
    <PackageReference Include="IKVM" Version="8.10.3" />
  </ItemGroup>

  <ItemGroup>
    <!-- Core SecureQT SDK JAR -->
    <IkvmReference Include="libs\secureqt-sdk-jvm.jar" />
    <!-- Required Transitive JVM Dependencies -->
    <IkvmReference Include="libs\usb4java-1.3.0.jar" />
    <IkvmReference Include="libs\kotlin-stdlib-1.9.23.jar" />
    <IkvmReference Include="libs\kotlinx-coroutines-core-jvm-1.7.3.jar" />
  </ItemGroup>
</Project>
```

> **Tip:** You can extract all runtime dependencies from this repo into a `libs` directory via:
> ```bash
> ./gradlew :secureqt-sdk:copyDependencies
> ```

#### 3. Compile Standalone `.dll` via `ikvmc` CLI (Alternative)
If you prefer building a static `.dll` once and adding it as an ordinary Visual Studio Assembly Reference:
```cmd
ikvmc -target:library ^
      -out:SecureQtSdk.dll ^
      -reference:libs\usb4java-1.3.0.jar ^
      -reference:libs\kotlin-stdlib-1.9.23.jar ^
      -reference:libs\kotlinx-coroutines-core-jvm-1.7.3.jar ^
      libs\secureqt-sdk-jvm.jar
```
Then right-click **Dependencies > Add Project Reference... > Browse** in Visual Studio and select `SecureQtSdk.dll`.

#### 4. C# Code Example in Visual Studio
```csharp
using System;
using System.IO;
using com.example.secureqt.sdk;
using org.usb4java;

namespace SecureQuickTransfer.CSharp
{
    public class Program
    {
        public static void Main(string[] args)
        {
            // 1. Initialize libusb
            var context = new org.usb4java.Context();
            LibUsb.init(context);

            // 2. Discover device and command AOA reboot
            Device device = FindConnectedAndroidDevice();
            bool aoaSwitched = SecureQtSdk.INSTANCE.switchDeviceToAoa(
                device,
                "AndroidOpenAccessory",
                "DataTransfer"
            );
            Console.WriteLine($"AOA Switched: {aoaSwitched}");

            // 3. Locate bulk endpoints
            var endpoints = SecureQtSdk.INSTANCE.findDesktopBulkEndpoints(device);
            if (endpoints == null)
            {
                Console.WriteLine("Endpoints not found; device may still be re-enumerating.");
                return;
            }

            // 4. Open device and claim interface
            var handle = new DeviceHandle();
            LibUsb.open(device, handle);
            LibUsb.claimInterface(handle, endpoints.getInterfaceNumber());

            // 5. Create Desktop Transport
            var transport = SecureQtSdk.INSTANCE.createDesktopTransport(
                handle,
                endpoints.getInAddr(),
                endpoints.getOutAddr(),
                endpoints.getInterfaceNumber()
            );

            // 6. Create Channel
            var channel = SecureQtSdk.INSTANCE.createChannel(transport);
            Console.WriteLine("SecureChannel created successfully in C#!");
        }

        private static Device FindConnectedAndroidDevice()
        {
            // libusb device iteration logic...
            return null!;
        }
    }
}
```

> [!NOTE]
> **Calling Kotlin Coroutines (`suspend fun`) in C#**:
> Kotlin suspend functions compile to methods with a trailing `kotlin.coroutines.Continuation` parameter. To invoke them synchronously from C#, use `kotlinx.coroutines.BuildersKt.runBlocking`:
> ```csharp
> // Example: executing suspend fun performHandshake(boolean, Continuation)
> var isReady = (bool)kotlinx.coroutines.BuildersKt.runBlocking(
>     kotlin.coroutines.EmptyCoroutineContext.INSTANCE,
>     new HandshakeCoroutineFunction(channel)
> );
> ```

---

### Option B: Sidecar / CLI Process (Zero-Interop Friction)

If you prefer zero Java-to-CLR interop complexities, run a small headless CLI/daemon JAR alongside your C# application and communicate via Standard I/O (JSON) or Named Pipes:

```csharp
using System.Diagnostics;

var psi = new ProcessStartInfo
{
    FileName = "javaw.exe", // or a bundled JRE / GraalVM native binary
    Arguments = "-jar secureqt-host-cli.jar --send \"C:\\data.zip\" --dest \"/sdcard/Download\"",
    RedirectStandardOutput = true,
    UseShellExecute = false,
    CreateNoWindow = true
};

using var process = Process.Start(psi)!;
string output = process.StandardOutput.ReadToEnd();
process.WaitForExit();
```

- **Advantages**: Complete memory isolation, no GC collisions between CLR and JVM, and works with any version of .NET without IKVM dependencies.

---

### Option C: Pure Native .NET C# Protocol Implementation

Because SecureQT is an open standard wire protocol, you can also implement the client directly in pure C# using standard .NET libraries:

| Layer | .NET Standard / C# Library | Corresponding SDK Component |
|---|---|---|
| **USB Access** | `LibUsbDotNet` or `P/Invoke WinUSB` | [`DesktopAoaTransport`](file:///home/aosp/Documents/Shreyas/KMP/SQT/secureqt-sdk/src/jvmMain/kotlin/com/example/secureqt/sdk/transport/desktop/DesktopAoaTransport.kt) |
| **AOA Switch** | Control Transfer 51, 52, 53 via `UsbSetupPacket` | [`DesktopAoaConnector`](file:///home/aosp/Documents/Shreyas/KMP/SQT/secureqt-sdk/src/jvmMain/kotlin/com/example/secureqt/sdk/transport/desktop/DesktopAoaConnector.kt) |
| **ECDH Key Agreement** | `System.Security.Cryptography.ECDiffieHellman` (NistP256) | [`EcdhKeyExchange`](file:///home/aosp/Documents/Shreyas/KMP/SQT/secureqt-sdk/src/commonMain/kotlin/com/example/secureqt/sdk/crypto/EcdhKeyExchange.kt) |
| **Encryption** | `System.Security.Cryptography.AesGcm` (standard in .NET 6+) | [`AesGcmEngine`](file:///home/aosp/Documents/Shreyas/KMP/SQT/secureqt-sdk/src/commonMain/kotlin/com/example/secureqt/sdk/crypto/AesGcmEngine.kt) |
| **Framing** | 5-byte header: `[1 Byte Type][4 Bytes BigEndian Length]` | [`PacketCodec`](file:///home/aosp/Documents/Shreyas/KMP/SQT/secureqt-sdk/src/commonMain/kotlin/com/example/secureqt/sdk/protocol/PacketCodec.kt) |

---

## Running the Reference Application (`composeApp`)

The repository includes a complete reference implementation demonstrating the host and client architecture:

### Build & Run Desktop Host Application
```bash
# macOS/Linux
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
```

### Build & Install Android Client Application
```bash
# Debug APK
./gradlew :composeApp:assembleDebug

# Release APK
./gradlew :composeApp:assembleRelease
```

---

## Technical Specifications

- **Wire Framing**: 5-byte big-endian framing (`[1B Type][4B Payload Length]`).
- **Key Exchange**: Ephemeral Elliptic Curve Diffie-Hellman (ECDH) over SECP256R1 / NIST P-256 with uncompressed SEC1 public key interchange.
- **Symmetric Cipher**: AES-256-GCM (12-byte random IV + 16-byte authentication tag per packet).
- **Physical Burst Size**: 16 KB bursts on Linux `usbfs` (Android) to eliminate kernel EOVERFLOW stalls; up to 300 KB direct bulk buffers on Desktop.
- **Buffering**: Pre-allocated 5 MB circular sliding window buffer eliminating garbage collector allocations during multi-gigabyte transfers.
