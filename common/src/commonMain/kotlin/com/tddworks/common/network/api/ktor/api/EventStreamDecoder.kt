package com.tddworks.common.network.api.ktor.api

/**
 * A single decoded `application/vnd.amazon.eventstream` message.
 *
 * @param headers all string-valued headers (type 7); AWS control headers like `:event-type`,
 *   `:message-type`, `:content-type`, `:exception-type` are the useful ones for Bedrock.
 * @param payload raw payload bytes (usually a UTF-8 JSON document for Bedrock Converse).
 */
data class EventStreamMessage(
    val headers: Map<String, String>,
    val payload: ByteArray,
) {
    val eventType: String? get() = headers[":event-type"]
    val messageType: String? get() = headers[":message-type"]
    val exceptionType: String? get() = headers[":exception-type"]

    /** Payload decoded as UTF-8 (Bedrock event payloads are JSON). */
    fun payloadText(): String = payload.decodeToString()

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is EventStreamMessage &&
                headers == other.headers &&
                payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * headers.hashCode() + payload.contentHashCode()
}

/**
 * Decoder for AWS event-stream framing (`application/vnd.amazon.eventstream`), used by Bedrock
 * `converse-stream`/`invoke-with-response-stream` and Transcribe. Pure-Kotlin, KMP-safe.
 *
 * Frame layout (all integers big-endian):
 * ```
 * [ total_len:4 ][ headers_len:4 ][ prelude_crc:4 ][ headers... ][ payload... ][ message_crc:4 ]
 * ```
 * Each header: [name_len:1][name][value_type:1][ if string/bytes: value_len:2 ][value].
 * Only string headers (type 7) are surfaced; other header value types are skipped by width.
 *
 * The decoder is CRC-lenient (it does not reject on checksum mismatch) so a single corrupt
 * frame does not abort a whole stream; integrity is enforced upstream by TLS.
 */
object EventStreamDecoder {

    /**
     * Decode as many complete messages as are fully present at the front of [buffer].
     * Returns the decoded messages plus the number of bytes consumed, leaving any trailing
     * partial frame for the caller to carry forward (streaming reassembly).
     */
    fun decode(buffer: ByteArray): Pair<List<EventStreamMessage>, Int> {
        val messages = mutableListOf<EventStreamMessage>()
        var offset = 0
        while (buffer.size - offset >= 16) { // minimum framing overhead
            val totalLen = readInt(buffer, offset)
            if (totalLen < 16 || offset + totalLen > buffer.size) break // incomplete frame
            val headersLen = readInt(buffer, offset + 4)

            var p = offset + 12 // skip total(4) + headers_len(4) + prelude_crc(4)
            val headersEnd = p + headersLen
            val headers = LinkedHashMap<String, String>()
            while (p < headersEnd) {
                val nameLen = buffer[p].toInt() and 0xFF
                p += 1
                val name = buffer.decodeToString(p, p + nameLen)
                p += nameLen
                val valueType = buffer[p].toInt() and 0xFF
                p += 1
                p = readHeaderValue(buffer, p, valueType, name, headers)
            }

            val payloadStart = headersEnd
            val payloadEnd = offset + totalLen - 4 // exclude trailing message CRC
            val payload = buffer.copyOfRange(payloadStart, payloadEnd)
            messages.add(EventStreamMessage(headers, payload))

            offset += totalLen
        }
        return messages to offset
    }

    /** Reads one header value by type, storing string/bytes values; returns the new offset. */
    private fun readHeaderValue(
        buffer: ByteArray,
        start: Int,
        valueType: Int,
        name: String,
        headers: MutableMap<String, String>,
    ): Int {
        var p = start
        when (valueType) {
            0, 1 -> {} // TRUE / FALSE — no value bytes
            2 -> p += 1 // BYTE
            3 -> p += 2 // SHORT
            4 -> p += 4 // INTEGER
            5, 8 -> p += 8 // LONG / TIMESTAMP
            6, 7 -> { // BYTE ARRAY / STRING (both length-prefixed with 2 bytes)
                val len = readShort(buffer, p)
                p += 2
                if (valueType == 7) headers[name] = buffer.decodeToString(p, p + len)
                p += len
            }
            9 -> p += 16 // UUID
            else -> {} // unknown type: cannot advance safely, caller loop will terminate on bounds
        }
        return p
    }

    private fun readInt(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 24) or
            ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or
            (b[i + 3].toInt() and 0xFF)

    private fun readShort(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
