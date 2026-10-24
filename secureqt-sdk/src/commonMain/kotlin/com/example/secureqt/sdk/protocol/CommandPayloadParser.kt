package com.example.secureqt.sdk.protocol

import java.nio.ByteBuffer

/**
 * Parsed representation of a received application-layer command.
 *
 * @property commandId The single byte identifying which command was received.
 * @property payload   A [ByteBuffer] positioned immediately after [commandId], containing
 *                     all command-specific arguments. Zero-copy slice — reading it does
 *                     not affect the original receive buffer.
 */
data class ReceivedCommand(
    val commandId: Byte,
    val payload: ByteBuffer
)

/**
 * Parses a raw decrypted byte array (output of [SecureChannel.receiveSecure]) into a
 * structured [ReceivedCommand], and provides typed argument extraction helpers.
 *
 * **Architecture decision — why this lives in the SDK:**
 * This class handles wire-format decoding of the application command payload layer:
 *   `[1 byte: commandId] [N bytes: command-specific payload]`
 * This encoding rule is part of the transport contract between host and client.
 * The SDK knows *how* to serialize and deserialize the primitive wire structures
 * (strings, numbers, pairs) without coupling itself to application-specific command IDs.
 *
 * Sits as the direct decoding counterpart to [CommandPayloadBuilder].
 * Both the Android client/host and Desktop host use this parser to ensure decoding
 * logic cannot drift between platforms.
 *
 * @see CommandPayloadBuilder for the complementary encoder (send side).
 */
object CommandPayloadParser {

    /**
     * Parses [raw] bytes into a [ReceivedCommand].
     *
     * @param raw The decrypted payload from [SecureChannel.receiveSecure].
     *            Must have at least 1 byte (the command ID).
     * @return A [ReceivedCommand] with the commandId and a zero-copy sliced payload buffer,
     *         or `null` if [raw] is null or empty.
     */
    fun parse(raw: ByteArray?): ReceivedCommand? {
        if (raw == null || raw.isEmpty()) return null
        val buffer = ByteBuffer.wrap(raw)
        val commandId = buffer.get()
        // slice() returns a ByteBuffer view over the remaining bytes — zero-copy.
        return ReceivedCommand(commandId = commandId, payload = buffer.slice())
    }

    /**
     * Reads a 4-byte length-prefixed UTF-8 string from [payload].
     * Compatible with [CommandPayloadBuilder.buildWithString].
     */
    fun readString(payload: ByteBuffer): String {
        val len = payload.int
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        payload.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reads two consecutive 4-byte length-prefixed UTF-8 strings from [payload].
     * Compatible with [CommandPayloadBuilder.buildWithTwoStrings] (e.g. rename operations).
     */
    fun readTwoStrings(payload: ByteBuffer): Pair<String, String> {
        val first = readString(payload)
        val second = readString(payload)
        return Pair(first, second)
    }

    /**
     * Reads a file-transfer start header from [payload]: a length-prefixed UTF-8 path
     * followed by an 8-byte (Long) file size.
     * Compatible with [CommandPayloadBuilder.buildFileStart].
     */
    fun readFileStart(payload: ByteBuffer): Pair<String, Long> {
        val path = readString(payload)
        val size = payload.long
        return Pair(path, size)
    }

    /**
     * Reads a single 8-byte Long from [payload].
     */
    fun readLong(payload: ByteBuffer): Long = payload.long

    /**
     * Reads a single 4-byte Int from [payload].
     */
    fun readInt(payload: ByteBuffer): Int = payload.int

    /**
     * Reads a 1-byte Boolean (0 = false, non-zero = true) from [payload].
     */
    fun readBoolean(payload: ByteBuffer): Boolean = payload.hasRemaining() && payload.get().toInt() != 0
}
