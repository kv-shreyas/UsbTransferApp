# System Architecture & Security Specifications

This document outlines the core architecture of the USB connection states, particularly detailing Android Open Accessory (AOA) behaviors, alongside the cryptographic protocols used to secure data in transit.

## 1. Android Open Accessory (AOA) & Developer Options

There is a common misconception that AOA mode cannot function when Android Developer Options and USB Debugging (ADB) are enabled. However, the AOA protocol was specifically engineered by Google to support a "composite" mode, allowing USB debugging traffic and accessory data to run simultaneously.

### How the AOA Handshake Works:
When the Desktop application (acting as the USB Host) sends the `ACCESSORY_START` control transfer, the Android device essentially "reboots" its USB port. The device changes its hardware Product ID (PID) to enter Accessory Mode:

*   **When USB Debugging is OFF:** 
    The Android device switches to PID `0x2D00`. In this state, it exposes exactly one USB interface (Interface 0). This interface handles the Bulk IN/OUT endpoints dedicated solely to your Accessory data transfer.
*   **When USB Debugging is ON:** 
    The Android device switches to PID `0x2D01`. In this state, it exposes a **composite device** containing two distinct interfaces:
    *   **Interface 0:** Handles the AOA data transfer.
    *   **Interface 1:** Handles the ADB (Android Debug Bridge) traffic.

Because the Desktop application's USB connection layer (`usb4java`) is designed to dynamically scan for the specific interface offering the required Bulk transfer endpoints (typically grabbing Interface 0), it successfully establishes the data pipe regardless of whether the ADB interface is present alongside it. Therefore, the connection works seamlessly irrespective of the user's USB Debugging settings.

---

## 2. Cryptographic Security Protocol

The system employs a highly secure, industry-standard cryptographic pipeline broken down into two distinct phases: **Key Exchange** and **Data Transfer**.

### Phase 1: Key Exchange (ECDH + SHA-256)
Before any file data or filesystem commands are transmitted, the Desktop and Android device must securely agree on a shared symmetric key without transmitting it in plaintext.

1.  **Key Generation:** Both the Android and Desktop applications mathematically generate their own Elliptic Curve (EC) private/public key pairs over a 256-bit curve.
2.  **Public Key Exchange:** Both sides transmit their Public Keys in plaintext over the USB cable during the handshake.
3.  **Key Agreement:** Using **ECDH (Elliptic-Curve Diffie-Hellman)**, each side mathematically combines their own Private Key with the received Public Key. This guarantees that both sides independently calculate the exact same Shared Secret.
4.  **Key Derivation:** To ensure the key is perfectly uniform and highly secure, the raw derived secret is processed through a **SHA-256** hash function, producing the final 256-bit symmetric AES key.

### Phase 2: File Encryption & Decryption (AES-GCM)
With the 256-bit AES key established, all subsequent commands and file chunks are encrypted.

*   **Algorithm:** `AES/GCM/NoPadding` (Advanced Encryption Standard in Galois/Counter Mode).

**The Encryption Pipeline:**
1.  **Randomization (IV):** For *every single chunk* of data transmitted (e.g., each 256KB block), the sender generates a brand new, random 12-byte Initialization Vector (IV). This ensures that identical files result in entirely different ciphertexts, preventing pattern analysis.
2.  **Encryption:** The sender uses the 256-bit AES key and the 12-byte IV to encrypt the payload.
3.  **Authentication (GCM):** GCM is an Authenticated Encryption algorithm. It automatically calculates a 128-bit (16-byte) Authentication Tag, which is attached to the encrypted data.
4.  **Packet Transmission:** The sender structures the packet as: `[12-byte IV] + [Encrypted Payload] + [16-byte Auth Tag]` and transmits it over the USB bulk endpoints.
5.  **Decryption & Integrity Check:** The receiver separates the IV and feeds the ciphertext into the AES-GCM decipher. If even a single bit of the file is corrupted due to a faulty USB cable, or if a malicious agent attempts to inject data into the stream, the Authentication Tag will fail to match. The decipher throws an `AEADBadTagException` and forcefully rejects the data, guaranteeing both absolute privacy and data integrity.
