# End-to-End USB Transfer Protocol Lifecycle

This document describes the complete chronological lifecycle of a data transfer session between the Desktop Application and the Android Application, from initial plug-in to disconnection.

---

## 1. Connection Establishment & Accessory Mode

The connection process transforms the standard Android device into a dedicated USB accessory.

1. **Device Discovery:** The Desktop app uses `usb4java` (`libusb`) to scan the USB bus for connected Android devices (matching known Vendor IDs like Samsung, Google, etc.).
2. **AOA Initiation:** The Desktop sends three critical Control Transfers to the device's default endpoint (Endpoint 0):
   * `ACCESSORY_GET_PROTOCOL`: Verifies the device supports Android Open Accessory (AOA) version 1 or 2.
   * `ACCESSORY_SEND_STRING`: Sends manufacturer, model, and version strings (e.g., "AndroidOpenAccessory", "DataTransfer").
   * `ACCESSORY_START`: Instructs the Android device to reboot its USB port into Accessory Mode.
3. **Re-Enumeration & Intent:** The Android device disconnects and immediately reconnects with a new Hardware ID (`0x2D00` or `0x2D01`). The Android OS reads the identifying strings and fires a system intent (`USB_ACCESSORY_ATTACHED`), which launches/notifies the Android App.
4. **Endpoint Claiming:** The Desktop app scans the new accessory device, claims Interface 0, and locates the Bulk IN (reading) and Bulk OUT (writing) endpoints.

## 2. Cryptographic Handshake (Key Exchange)

Before any file metadata or data can be transmitted, the two applications establish a secure channel.

1. **Key Generation:** Both apps generate a 256-bit Elliptic Curve (EC) key pair.
2. **Desktop Sends Key:** The Desktop app sends a Packet `(TYPE_PUBLIC_KEY)` containing its public key in plaintext.
3. **Android Replies:** The Android app receives it, extracts the public key, and sends back its own `TYPE_PUBLIC_KEY` packet.
4. **Derivation:** Both sides use Elliptic-Curve Diffie-Hellman (ECDH) followed by a SHA-256 hash to independently derive the exact same 256-bit AES-GCM symmetric key. The channel is now secure.

---

## 3. Data Transfer Architecture (256KB vs 16KB)

To achieve maximum throughput while respecting legacy Android Linux kernel (`usbfs`) limits, the protocol separates *logical* processing from *physical* transmission:

* **Logical Processing (256KB):** Both applications read files and manage buffers in **256KB** blocks. This means AES-GCM encryption, IV generation, and packet header wrapping only happen once per 256KB. This drastically reduces CPU overhead and avoids unnecessary memory allocations using a 5MB Sliding Window Buffer.
* **Physical Transmission (16KB Splitting):** When a 256KB encrypted packet is passed to the native Android USB API (`bulkTransfer`), a wrapper safely slices the payload into **16KB (16,384 bytes)** bursts. This circumvents the hard `MAX_USBFS_BUFFER_SIZE` limits in Android's JNI layer, preventing silent data truncation while maintaining the blazing speed of the 256KB cryptography pipeline.

---

## 4. Sending a File (Desktop to Android)

When the user selects a file on the Desktop to send to the Android device:

1. **Send Metadata Header:** The Desktop creates a `CMD_SEND` (Byte `0x01`) packet containing the destination path length, the path string, and the exact 8-byte file size. It encrypts this header using AES-GCM and transmits it.
2. **Android Prepares:** The Android app receives and decrypts the header. It extracts the path, checks storage permissions, and opens a `FileOutputStream` to the destination.
3. **Data Streaming:**
   * The Desktop reads `256KB` of the local file.
   * It encrypts the chunk (generating a new 12-byte IV and 16-byte Auth Tag).
   * It transmits the resulting ~262KB secure packet over the Bulk OUT endpoint.
   * This loops continuously until the file is fully read.
4. **Android Receiving & Sinking:** 
   * The Android app's Sliding Window Buffer reads from the Bulk IN endpoint in chunks until it has a complete packet.
   * It decrypts the packet and writes the plaintext 256KB block directly to disk.
   * *(Safety Mechanism: If the Android app encounters a file permission error, it will enter a "sinking" mode, intentionally consuming and discarding the incoming bytes. This prevents the USB pipe from stalling and crashing the Desktop app).*

---

## 5. Fetching a File (Android to Desktop)

When the user requests to download a file from the Android device to the Desktop:

1. **Fetch Request:** The Desktop sends a `CMD_FETCH` (Byte `0x02`) packet containing the path of the remote file.
2. **Android Responds with Size:** The Android app locates the file, retrieves its size, and sends an encrypted 8-byte response back to the Desktop. (If the file doesn't exist, it returns `0L`).
3. **Data Streaming:** 
   * The Android app begins reading the file in `256KB` logical chunks. 
   * It encrypts the chunk using AES-GCM.
   * It hands the payload to the connection manager, which rapidly transmits it in safe `16KB` bursts over the native USB pipe.
4. **Desktop Receiving:** The Desktop reads from the USB pipe (using a large `300KB` direct `ByteBuffer` limit to accommodate the incoming packets without fragmenting). It decrypts the data and writes it to the local disk.

---

## 6. Disconnection Process

When the user clicks Disconnect, or the physical cable is unplugged:

1. **Job Cancellation:** All active Kotlin Coroutine streaming jobs are cancelled, halting any ongoing file reads or encryption cycles.
2. **Interface Release:** The Desktop app explicitly calls `LibUsb.releaseInterface(handle, 0)` to release its claim on the Accessory bulk endpoints.
3. **Handle Closure:** `LibUsb.close(handle)` is called to terminate the USB session.
4. **State Reset:** Both applications clear their AES keys, zero-out their sliding window buffers, and return to an `Idle` state, ready for a new handshake when reconnected.
