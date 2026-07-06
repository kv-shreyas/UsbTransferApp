# USB Data Transfer & Project Updates Summary

This document serves as a comprehensive summary of recent project changes, the optimized sender/receiver mechanisms for high-speed data transfer, and the concurrent worker thread architectures powering both the Android and Desktop sides of the application.

---

## 1. Recent Project Commits

### Thread Safety in `MainViewModel`
- **Concurrency Control**: Introduced a `Mutex` (`usbMutex`) to ensure that only one USB operation can execute at a time.
- **Protected Operations**: Wrapped critical USB-related operations (`fetchFile`, `fetchDirectory`, `convertAnfToCsv`, and `receiveData`) inside `usbMutex.withLock` blocks to prevent simultaneous executions that could lead to crashes or data corruption.
- **Coroutines Refactoring**: Modified `fetchDirectory` to be a `suspend` function instead of launching its own internal coroutine, giving the caller better control over the transfer lifecycle.

### Desktop Distribution Branding & Configuration
- **Branding**: Renamed the desktop application package name to **"SecureQuickTransfer"** in `composeApp/build.gradle.kts`.
- **JDK Modules**: Switched to using `includeAllModules = true` (replacing hardcoded JDK modules like `java.naming`, `java.sql`) to ensure all necessary runtime dependencies are bundled seamlessly.
- **Windows Build**: Enabled the console (`console = true`) for Windows distribution builds.

---

## 2. Optimized Sender & Receiver Mechanism

To achieve massive speed improvements (especially for files >1GB) while navigating Android kernel limits, several crucial optimizations have been implemented:

### Zero-Allocation Sliding Window Buffer
Continuously creating new byte arrays during a transfer caused massive Android Garbage Collection (GC) pauses, destroying transfer speeds.
- **Solution**: Both Android and Desktop use a **5MB Pre-allocated Sliding Window Buffer**. Data is natively copied into and out of this fixed buffer using pointer heads/tails (`System.arraycopy`). This completely eliminates memory reallocation and GC freezes, dedicating 100% of CPU cycles to moving bytes.

### Cryptographic Batching (256KB Logical Chunks)
To establish a secure channel, all payloads are encrypted using AES-GCM. Doing this on tiny 16KB chunks caused enormous cryptographic overhead.
- **Solution**: Data is read from the disk and processed cryptographically in large **256KB blocks**. This reduces the number of encryption/decryption operations (and packet header wrapping) by **93.7%**, vastly improving throughput.

### The 16KB "Bursting" Wrapper (Bypassing Android Kernel Limits)
Android's underlying Linux USB kernel (`usbfs`) crashes or truncates data if you try to send synchronous transfers larger than 16,384 bytes (16KB).
- **Solution**: To keep the speed of 256KB chunks without crashing the kernel, the Android sender/receiver uses a transparent wrapper. It processes the file in memory as a fast 256KB chunk but slices it just before transmission, pushing it through the native USB API in safe **16KB bursts**.

### Expanded Desktop Hardware Buffers
To prevent the Desktop side from bottlenecking the incoming data stream, its direct hardware reading buffer was upgraded.
- **Solution**: The Desktop `ByteBuffer` allocation was increased from 128KB to **300KB**. This ensures it can comfortably swallow a full 256KB logical chunk (plus the AES headers and IVs) in a single hardware transaction without fragmenting the pipeline.

---

## 3. The 3-Stage Worker Pipeline (Android & Desktop)

The high-speed data transfer is powered by a highly concurrent **3-stage pipeline** utilizing Kotlin Coroutines. By decoupling the steps into separate worker threads connected by channels, the application ensures that the CPU and the USB connection are never idling. 

This exact same architecture is implemented on **both the Android (`androidMain`) and Desktop (`jvmMain`) sides**.

### When Sending Data (Sender)
1. **Worker 1 (Disk Read)**: Dedicated strictly to reading data from the storage drive in 256KB chunks.
2. **Worker 2 (Encrypt)**: Picks up the raw chunks from Worker 1 and performs AES-GCM encryption and IV/header generation.
3. **Worker 3 (USB Send / Write)**: Takes the fully encrypted packets from Worker 2 and blasts them over the USB interface to the receiving device.

### When Receiving Data (Receiver)
1. **Worker 1 (USB Receive)**: Dedicated entirely to listening on the USB hardware port and pulling incoming byte chunks into memory as fast as possible.
2. **Worker 2 (Decrypt)**: Takes the incoming encrypted packets, validates the authentication tags, and decrypts them via AES-GCM back into raw data.
3. **Worker 3 (Disk Write / Emit)**: Takes the decrypted plaintext chunks and safely streams them onto the local storage.

### The Importance of the Pipeline
If these operations were performed sequentially (Read -> Encrypt -> Send -> repeat), the USB connection would sit idle while the CPU was busy encrypting, and the CPU would sit idle while waiting for the USB to transmit.

With this **3-Worker Pipeline**, the system can simultaneously:
- **Worker 3**: Send Chunk `N` over the USB wire
- **Worker 2**: Encrypt Chunk `N+1`
- **Worker 1**: Read Chunk `N+2` from the disk

This continuous assembly line architecture keeps the physical USB pipe 100% saturated and the CPU working efficiently in parallel, preventing UI thread freezes and resulting in blazingly fast transfer speeds across platforms.
