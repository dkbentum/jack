package com.example.jack

/**
 * Turns the raw chunks arriving from the ESP32 (USB reads or BLE notifications, which can split
 * or merge lines arbitrarily) into whole lines. Every line is returned exactly once.
 *
 * Pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 */
class LineAssembler(private val maxPendingChars: Int = 512) {

    private val buffer = StringBuilder()

    /**
     * Adds a chunk and returns the complete lines it finished (trimmed, blank lines dropped).
     * Any text after the last line ending stays buffered until more data or [flush].
     */
    fun append(chunk: String): List<String> {
        buffer.append(chunk)
        val lines = mutableListOf<String>()

        while (true) {
            val end = buffer.indexOfFirst { it == '\n' || it == '\r' }
            if (end < 0) break
            val line = buffer.substring(0, end).trim()
            buffer.delete(0, end + 1)  // "\r\n" leaves a lone "\n" → an empty line, which is skipped
            if (line.isNotEmpty()) lines += line
        }

        // Guard against a device that never sends line endings: don't let the buffer grow forever.
        if (buffer.length > maxPendingChars) {
            flush()?.let { lines += it }
        }
        return lines
    }

    /** True when text without a line ending is waiting in the buffer. */
    val hasPending: Boolean
        get() = buffer.isNotBlank()

    /** Returns the waiting partial line (if any) and clears the buffer. */
    fun flush(): String? {
        val text = buffer.toString().trim()
        buffer.setLength(0)
        return text.ifEmpty { null }
    }
}
