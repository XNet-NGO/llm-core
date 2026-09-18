package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Declarative per-operation transformation for the TEMPLATE dialect (D7) - the shape
 * pinned in 00-index.md section 0/3 and HANDOFF. A provider supplies one entry per
 * operation served by the template engine (e.g. audioSpeech) when the default op
 * handling is not enough (non-Qwen bodies, non-JSON responses, hex/base64 audio,
 * extra auth headers, path parameters).
 *
 * - path: endpoint path relative to baseUrl; {{key}} placeholders substituted
 *   (e.g. {{voice_id}}, {{model}}).
 * - method: HTTP method, default POST.
 * - headers: extra request headers; values may contain {{key}} placeholders.
 * - requestTemplate: JSON body template; string values containing {{key}}
 *   placeholders are substituted. Null -> no body.
 * - responseMapper: how to turn the HTTP response into the operation result.
 */
@Serializable
data class TemplateTransform(
    val path: String? = null,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val requestTemplate: JsonElement? = null,
    val responseMapper: ResponseMapper? = null,
)

/**
 * Response mapping for a transformed operation.
 *
 * - from: JSON path into the parsed response body ($ = whole body,
 *   $.data.audio = nested value). Ignored when decode is raw.
 * - decode: result decoding:
 *   raw -> raw response bytes (audio files);
 *   base64 -> JSON string value base64-decoded;
 *   hex -> JSON string value hex-decoded (DashScope/MiniMax style);
 *   text -> JSON string value as UTF-8 bytes.
 */
@Serializable
data class ResponseMapper(
    val from: String = "$",
    val decode: String = "raw",
)
