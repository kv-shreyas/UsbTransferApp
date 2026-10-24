package com.example.secureqt.sdk.protocol

import java.nio.ByteBuffer

/**
 * Builds application-layer command payloads in the established wire format:
 *   `[1 byte: commandId] [command-specific arguments…]`
 *
 * **Architecture decision — why this lives in the SDK:**
 * The *structure* of how arguments are serialised (length-prefixed UTF-8 strings,
 * 8-byte file sizes, etc.) is part of the shared transport contract between host
 * and client. Both the Desktop host (`SecureTransferClientImpl`) and the Android
 * host (`AndroidHostCommandSender`) need identical serialisation. Centralising it here
 * eliminates the repetitive `ByteBuffer.allocate(1 + 4 + bytes.size)` pattern that
 * previously appeared in every command method.
 *
 * The specific command *IDs* (what byte `0x00` means) remain in the app layer
 * (`AppCommandIds`) — the SDK is intentionally unaware of their semantics.
 *
 * **Wire argument patterns:**
 * - **Simple**     (no args):           `[cmd]`
 * - **WithString** (one path/name):     `[cmd][4: strLen][UTF-8 bytes]`
 * - **FileStart**  (path + size):       `[cmd][4: nameLen][name bytes][8: fileSize]`
 * - **TwoStrings** (old path + new):    `[cmd][4: s1Len][s1 bytes][4: s2Len][s2 bytes]`
 * - **Custom**     (generic extension): `[cmd]` then each segment length-prefixed
 *
 * @see CommandPayloadParser for the complementary decoder (receive side).
 */
object CommandPayloadBuilder {

    /**
     * Builds a command payload with no arguments (e.g. `CMD_DISCONNECT`).
     *
     * Wire format: `[cmd]`
     */
    fun buildSimple(commandId: Byte): ByteArray = byteArrayOf(commandId)

    /**
     * Builds a command payload with one UTF-8 string argument (e.g. a file path).
     *
     * Wire format: `[cmd][4: strLen][strLen bytes: UTF-8 string]`
     */
    fun buildWithString(commandId: Byte, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + bytes.size)
            .put(commandId)
            .putInt(bytes.size)
            .put(bytes)
            .array()
    }

    /**
     * Builds a command payload for initiating a file or directory transfer,
     * encoding the remote path and total stream size.
     *
     * Wire format: `[cmd][4: nameLen][name bytes][8: fileSize]`
     *
     * @param commandId  The application command byte (e.g. `CMD_SEND_FILE_START`).
     * @param remotePath Fully-qualified remote destination path.
     * @param fileSize   Total bytes to be transferred. Pass `0L` when unknown (streaming ZIP).
     */
    fun buildFileStart(commandId: Byte, remotePath: String, fileSize: Long): ByteArray {
        val bytes = remotePath.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + bytes.size + 8)
            .put(commandId)
            .putInt(bytes.size)
            .put(bytes)
            .putLong(fileSize)
            .array()
    }

    /**
     * Builds a command payload with two UTF-8 string arguments (e.g. rename: oldPath + newName).
     *
     * Wire format: `[cmd][4: s1Len][s1 bytes][4: s2Len][s2 bytes]`
     */
    fun buildWithTwoStrings(commandId: Byte, first: String, second: String): ByteArray {
        val b1 = first.toByteArray(Charsets.UTF_8)
        val b2 = second.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + 4 + b1.size + 4 + b2.size)
            .put(commandId)
            .putInt(b1.size)
            .put(b1)
            .putInt(b2.size)
            .put(b2)
            .array()
    }

    /**
     * Generic builder for custom or future commands with arbitrary binary segments.
     * Each segment is automatically length-prefixed (4-byte big-endian int).
     *
     * Wire format: `[cmd] ([4: segLen][seg bytes])*`
     *
     * **Examples:**
     * ```kotlin
     * // Simple no-payload custom command:
     * buildCustom(AppCommandIds.CMD_GET_APP_VERSION)
     *
     * // Custom command with two opaque binary segments:
     * buildCustom(MY_CMD, payloadA, payloadB)
     * ```
     */
    fun buildCustom(commandId: Byte, vararg segments: ByteArray): ByteArray {
        val totalSize = 1 + segments.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(totalSize).put(commandId)
        for (segment in segments) buffer.putInt(segment.size).put(segment)
        return buffer.array()
    }
}
