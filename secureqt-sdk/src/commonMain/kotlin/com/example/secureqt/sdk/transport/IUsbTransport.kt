package com.example.secureqt.sdk.transport

/**
 * Interface representing a raw USB transport connection.
 * Platforms (Android, Desktop) must implement this to provide
 * raw read/write capabilities to the SDK.
 */
interface IUsbTransport {
    /**
     * Reads raw bytes from the USB endpoint into the provided buffer.
     * Should suspend or block until data is available or connection closes.
     * @return Number of bytes read, or -1 if disconnected/error.
     */
    suspend fun read(buffer: ByteArray): Int

    /**
     * Writes raw bytes to the USB endpoint.
     * @return true if successful, false otherwise.
     */
    suspend fun write(buffer: ByteArray): Boolean

    /**
     * Returns true if the transport is currently connected.
     */
    fun isConnected(): Boolean
    
    /**
     * Flushes/clears any stale hardware buffers if supported by the platform.
     */
    fun clearBuffer()
}
