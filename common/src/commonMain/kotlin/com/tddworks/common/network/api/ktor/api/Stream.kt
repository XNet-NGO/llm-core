package com.tddworks.common.network.api.ktor.api

import com.tddworks.di.getInstance
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.Json

const val STREAM_PREFIX = "data:"
private const val STREAM_END_TOKEN = "[DONE]"

/**
 * Get data as
 * [Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events#Event_stream_format).
 *
 * Tolerant of real-world provider quirks (spec §4): CRLF line endings, `data:` with or
 * without a following space, `[DONE]` sentinels with or without a space, SSE comment /
 * keepalive lines (`: ping`), blank separator lines, and bare-JSON (NDJSON) records that
 * omit the `data:` prefix (ollama-style).
 */
suspend inline fun <reified T> FlowCollector<T>.streamEventsFrom(response: HttpResponse) {
    val channel: ByteReadChannel = response.body()
    val json = json()
    while (!channel.isClosedForRead) {
        // readUTF8Line strips the trailing \n and \r; trim guards any residual whitespace.
        val line = channel.readUTF8Line()?.trim() ?: continue
        if (line.isEmpty()) continue // SSE event separator
        if (line.startsWith(":")) continue // SSE comment / keepalive (": ping")
        val value: T =
            when {
                isStreamResponse(line) -> {
                    val payload = line.removePrefix(STREAM_PREFIX).trim()
                    if (isEndPayload(payload)) break
                    if (payload.isEmpty()) continue
                    json.decodeFromString(payload)
                }
                isJsonResponse(line) ->
                    json.decodeFromString(
                        line
                    ) // Ollama - response is a json object without `data:` prefix
                else -> continue
            }
        emit(value)
    }
}

fun json(): Json {
    return getInstance()
}

fun isStreamResponse(line: String) = line.startsWith(STREAM_PREFIX)

/** True when a `data:` payload (already stripped of the prefix) is the stream-end sentinel. */
fun isEndPayload(payload: String) = payload == STREAM_END_TOKEN

/** True when a full line is a stream-end event, with or without a space after `data:`. */
fun endStreamResponse(line: String) =
    isStreamResponse(line) && isEndPayload(line.removePrefix(STREAM_PREFIX).trim())

fun isJsonResponse(line: String) = line.startsWith("{") && line.endsWith("}")
