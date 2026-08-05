package com.example.secureqt.sdk.buffer

/**
 * A reusable sliding window buffer for accumulating streamed chunks
 * and extracting exact sized segments (e.g., extracting a 5-byte header,
 * then an N-byte payload from a fragmented USB stream).
 * 
 * Uses a pre-allocated circular buffer with head/tail pointers to avoid 
 * O(N²) array copies and GC pressure in high-throughput transfer loops.
 */
class SlidingWindowBuffer(initialCapacity: Int = 5 * 1024 * 1024) {

    private var buffer = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    /** The current number of bytes available to read. */
    val size: Int
        get() = tail - head

    /** Checks if the buffer contains at least [count] bytes. */
    fun has(count: Int): Boolean = size >= count

    /**
     * Appends data directly from a source array without an intermediate copy.
     * @param data source array
     * @param offset start offset in [data]
     * @param length number of bytes to copy from [data]
     */
    fun append(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        ensureCapacity(length)
        System.arraycopy(data, offset, buffer, tail, length)
        tail += length
    }

    /**
     * Extracts exactly [count] bytes from the front of the buffer and removes them.
     * @throws IllegalStateException if the buffer doesn't have enough bytes.
     */
    fun take(count: Int): ByteArray {
        if (!has(count)) {
            throw IllegalStateException("Cannot take $count bytes, only $size available")
        }
        val result = ByteArray(count)
        System.arraycopy(buffer, head, result, 0, count)
        head += count
        return result
    }

    /** Clears the buffer completely without de-allocating. */
    fun clear() {
        head = 0
        tail = 0
    }

    private fun compact() {
        if (head > 0) {
            val len = size
            System.arraycopy(buffer, head, buffer, 0, len)
            head = 0
            tail = len
        }
    }

    private fun ensureCapacity(additionalBytes: Int) {
        if (tail + additionalBytes <= buffer.size) return
        // Try compacting first – usually sufficient with 5MB buffer
        compact()
        if (tail + additionalBytes <= buffer.size) return
        // Rare path: expand buffer
        val newCapacity = (buffer.size * 2).coerceAtLeast(tail + additionalBytes)
        val newBuffer = ByteArray(newCapacity)
        System.arraycopy(buffer, 0, newBuffer, 0, tail)
        buffer = newBuffer
    }
}
