# USB Transfer Protocol Documentation

## 1. Transport Layer (AOA)
The app uses the **Android Open Accessory (AOA) Protocol**. The Desktop app uses `libusb` to scan for Android devices and sends a special control signal that switches the Android device into "Accessory Mode". This allows the apps to communicate directly over USB Bulk Endpoints (IN and OUT) without needing ADB or network connections.

## 2. Base Packet Structure
All data transferred over the wire is wrapped in a custom 5-byte header:
* **`Type` (1 byte):** Indicates the packet purpose (`0x01` = Public Key, `0x02` = Encrypted Data, `0x03` = ACK/Ready).
* **`Length` (4 bytes):** A 32-bit integer specifying the size of the payload that follows.
* **`Payload` (N bytes):** The actual data.

## 3. Security & Handshake
The protocol guarantees end-to-end encryption before any files are sent:
1. **Key Exchange:** The Desktop sends an unencrypted `TYPE_PUBLIC_KEY` packet containing its ECDH-P256 public key. Android responds with its own public key.
2. **Shared Secret:** Both sides independently derive a shared secret using ECDH and generate AES keys.
3. **SYNC:** Android sends a `TYPE_ACK` packet to signal it's ready.
4. **Encrypted Channel:** From this point forward, all packets use `TYPE_DATA`. The payload consists of a 12-byte IV (Initialization Vector) followed by **AES-GCM** ciphertext.

## 4. Application Commands (Inside the Encrypted Payload)
Once the secure channel is established, the Desktop app acts as the client and sends specific 1-byte command prefixes inside the encrypted payload to control the Android app:

* **`CMD_LIST (0x00)`**: 
  * *Desktop sends:* The requested folder path.
  * *Android responds:* An integer count of files, followed by individual packets for each item (containing a boolean for isDirectory, file size, and file name).
* **`CMD_SEND (0x01)`**: 
  * *Desktop sends:* The destination file path and the total file size. Immediately following this header, the desktop streams the raw file bytes in chunks (typically 16KB).
  * *Android responds:* Listens passively and streams the chunks directly to disk. 
* **`CMD_FETCH (0x02)`**: 
  * *Desktop sends:* The remote file path it wants to download.
  * *Android responds:* The total file size (8 bytes), followed by a continuous stream of file chunks.
* **`CMD_FETCH_DIR (0x03)`**: 
  * *Desktop sends:* A directory path.
  * *Android responds:* Zips the entire directory on the fly into the cache, sends the ZIP size, and streams the ZIP file back to the Desktop.
