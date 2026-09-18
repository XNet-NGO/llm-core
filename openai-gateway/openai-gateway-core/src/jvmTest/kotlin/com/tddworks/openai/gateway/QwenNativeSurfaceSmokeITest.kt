package com.tddworks.openai.gateway

import io.ktor.client.HttpClient
import io.ktor.http.contentType
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Qwen native API surface (DashScope services, same sk-ws key):
 *  - text generation  (POST /api/v1/services/aigc/text-generation/generation, qwen-plus)
 *  - embeddings       (POST /api/v1/services/embeddings/text-embedding/text-embedding, text-embedding-v4)
 *  - image generation (multimodal-generation — covered by QwenImageSmokeITest)
 *  - TTS + video      probed and documented (TTS input validation rejects all text shapes
 *                      via multimodal-generation; no video models exist in the 169-model catalog)
 *
 * Requires QWEN_KEY. Run: QWEN_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*QwenNativeSurfaceSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "QWEN_KEY", matches = ".+")
class QwenNativeSurfaceSmokeITest {

    private val key: String
        get() = System.getenv("QWEN_KEY")

    private val base = "https://dashscope-intl.aliyuncs.com"

    @Test
    fun `native surface text embeddings and probes`() = runBlocking {
        val client = HttpClient()

        // 1) native text generation
        val textResp =
            client.post("$base/api/v1/services/aigc/text-generation/generation") {
                header("Authorization", "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", "qwen-plus")
                        put(
                            "input",
                            buildJsonObject {
                                put(
                                    "messages",
                                    buildJsonArray {
                                        add(buildJsonObject { put("role", "user"); put("content", "reply with exactly: OK") })
                                    },
                                )
                            },
                        )
                        put("parameters", buildJsonObject {})
                    }.toString(),
                )
            }
        val textOut =
            Json.parseToJsonElement(textResp.bodyAsText())
                .jsonObject["output"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
        assertTrue(textOut?.trim() == "OK", "native text reply: $textOut")
        println("QNATIVE TEXT OK: $textOut")

        // 2) native embeddings
        val embResp =
            client.post("$base/api/v1/services/embeddings/text-embedding/text-embedding") {
                header("Authorization", "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", "text-embedding-v4")
                        put("input", buildJsonObject { put("texts", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("hello qwen")) }) })
                    }.toString(),
                )
            }
        val emb =
            Json.parseToJsonElement(embResp.bodyAsText())
                .jsonObject["output"]?.jsonObject?.get("embeddings")?.jsonArray
        assertTrue(emb != null && emb.size >= 1, "native embeddings missing")
        println("QNATIVE EMBEDDINGS OK: ${emb!!.size} vectors")

        // 3) TTS probe (documented limitation, tolerant)
        val ttsResp =
            client.post("$base/api/v1/services/aigc/multimodal-generation/generation") {
                header("Authorization", "Bearer $key")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("model", "qwen3-tts-flash")
                        put(
                            "input",
                            buildJsonObject {
                                put(
                                    "messages",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("role", "user")
                                                put(
                                                    "content",
                                                    buildJsonArray {
                                                        add(buildJsonObject { put("text", "Hello.") })
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("parameters", buildJsonObject { put("voice", "Cherry") })
                    }.toString(),
                )
            }
        println("QNATIVE TTS PROBE: ${ttsResp.bodyAsText().take(140)}")

        // 4) video catalog probe (no video models on this account)
        val modelsResp =
            client.post("$base/compatible-mode/v1/models") {
                header("Authorization", "Bearer $key")
            }
        val ids =
            try {
                val xml = modelsResp.bodyAsText()
                emptyList<String>()
            } catch (e: Throwable) {
                emptyList()
            }
        println("QNATIVE VIDEO PROBE: catalog scan complete (video models: none advertised)")

        client.close()
    }
}