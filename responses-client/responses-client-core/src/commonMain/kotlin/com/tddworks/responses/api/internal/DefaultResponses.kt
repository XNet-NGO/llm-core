package com.tddworks.responses.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.api.performRequest
import com.tddworks.common.network.api.ktor.api.streamRequest
import com.tddworks.di.getInstance
import com.tddworks.responses.api.Response
import com.tddworks.responses.api.ResponseCreateRequest
import com.tddworks.responses.api.ResponseStreamEvent
import com.tddworks.responses.api.Responses
import com.tddworks.responses.api.ResponsesConfig
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Default [Responses] implementation — stateful Responses API over [HttpRequester].
 * Streaming parses SSE `data:` lines into typed [ResponseStreamEvent]s, tolerating
 * unknown/irregular event shapes.
 */
internal class DefaultResponses(
    private val requester: HttpRequester,
    private val responsesPath: String = Responses.RESPONSES_PATH,
) : Responses {

    override suspend fun create(request: ResponseCreateRequest): Response =
        requester.performRequest {
            method = HttpMethod.Post
            url(path = responsesPath)
            setBody(request.copy(stream = false))
            contentType(ContentType.Application.Json)
        }

    override fun stream(request: ResponseCreateRequest): Flow<ResponseStreamEvent> =
        flow {
            val collector: FlowCollector<ResponseStreamEvent> = this
            requester.streamRequest(
                {
                    method = HttpMethod.Post
                    url(path = responsesPath)
                    setBody(request.copy(stream = true))
                    contentType(ContentType.Application.Json)
                    accept(ContentType.Text.EventStream)
                    headers {
                        append(HttpHeaders.CacheControl, "no-cache")
                        append(HttpHeaders.Connection, "keep-alive")
                    }
                },
            ) { response -> collector.emitEventsFrom(response) }
        }.catch { e -> emit(ResponseStreamEvent.Failed(null)) }

    override suspend fun retrieve(id: String): Response =
        requester.performRequest {
            method = HttpMethod.Get
            url(path = "$responsesPath/$id")
        }

    override suspend fun cancel(id: String): Response =
        requester.performRequest {
            method = HttpMethod.Post
            url(path = "$responsesPath/$id/cancel")
        }
}

suspend fun FlowCollector<ResponseStreamEvent>.emitEventsFrom(response: HttpResponse) {
    val json = getInstance<Json>()
    val channel: ByteReadChannel = response.body()
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: continue
        val payload = ssePayload(line) ?: continue
        emit(parseEvent(json, payload))
    }
}

private fun ssePayload(line: String): String? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("data:")) return null
    val payload = trimmed.removePrefix("data:").trim()
    if (payload.isEmpty()) return null
    if (payload.contains("[DONE]")) return null
    return payload
}

private fun parseEvent(json: Json, payload: String): ResponseStreamEvent {
    val element: JsonElement =
        try {
            json.parseToJsonElement(payload)
        } catch (e: Throwable) {
            return ResponseStreamEvent.Unknown("unparsable", json.parseToJsonElement("{}"))
        }
    val type =
        element.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            ?: return ResponseStreamEvent.Unknown("untyped", element)
    return try {
        when (type) {
            "response.created" -> json.decodeFromJsonElement<ResponseStreamEvent.Created>(element)
            "response.in_progress" -> json.decodeFromJsonElement<ResponseStreamEvent.InProgress>(element)
            "response.output_item.added" -> json.decodeFromJsonElement<ResponseStreamEvent.OutputItemAdded>(element)
            "response.output_item.done" -> json.decodeFromJsonElement<ResponseStreamEvent.OutputItemDone>(element)
            "response.content_part.added" -> json.decodeFromJsonElement<ResponseStreamEvent.ContentPartAdded>(element)
            "response.output_text.delta" -> json.decodeFromJsonElement<ResponseStreamEvent.OutputTextDelta>(element)
            "response.output_text.done" -> json.decodeFromJsonElement<ResponseStreamEvent.OutputTextDone>(element)
            "response.function_call_arguments.delta" -> json.decodeFromJsonElement<ResponseStreamEvent.FunctionCallArgumentsDelta>(element)
            "response.function_call_arguments.done" -> json.decodeFromJsonElement<ResponseStreamEvent.FunctionCallArgumentsDone>(element)
            "response.reasoning_summary_text.delta" -> json.decodeFromJsonElement<ResponseStreamEvent.ReasoningSummaryTextDelta>(element)
            "response.completed" -> json.decodeFromJsonElement<ResponseStreamEvent.Completed>(element)
            "response.failed" -> json.decodeFromJsonElement<ResponseStreamEvent.Failed>(element)
            "response.incomplete" -> json.decodeFromJsonElement<ResponseStreamEvent.Incomplete>(element)
            "error" -> json.decodeFromJsonElement<ResponseStreamEvent.ErrorEvent>(element)
            else -> ResponseStreamEvent.Unknown(type, element)
        }
    } catch (e: Throwable) {
        ResponseStreamEvent.Unknown(type, element)
    }
}

fun Responses.Companion.default(config: ResponsesConfig): Responses =
    DefaultResponses(requester = com.tddworks.responses.di.responsesHttpRequester(config))