package com.tddworks.smoke

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.tddworks.common.network.api.ktor.api.EventStreamDecoder
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.AwsSigV4Signer
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.SigningContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * On-device smoke: runs real llm-core code on the Pixel's arm64-v8a CPU via ART and logs
 * results to logcat (tag SMOKE). Exercises config parse + provider build + AWS SigV4 signing
 * + EventStream decode — the core pieces added this session.
 */
class SmokeActivity : Activity() {
    @OptIn(ExperimentalSerializationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = "SMOKE"
        try {
            Log.i(tag, "start on ${android.os.Build.MODEL} / ${android.os.Build.SUPPORTED_ABIS.joinToString()}")

            // 1. Config parse + provider build (D1 OpenAI-compat)
            val config = ProviderConfig.fromJson(
                """{"id":"openai","dialect":"OPENAI_COMPAT","baseUrl":"https://api.openai.com/v1",
                    "auth":{"scheme":"BEARER","apiKey":"sk-test"},"aliases":{"fast":"gpt-4o-mini"}}""",
            )
            val provider = OpenAIProvider.from(config)
            Log.i(tag, "provider id=${provider.id} dialect-ok aliases=${config.aliases}")

            // 2. AWS SigV4 signing (real HMAC-SHA256 on device)
            val creds = runBlocking {
                AwsSigV4Signer.sign(
                    ProviderAuth(apiKey = "AKIDEXAMPLE", secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", region = "us-east-1", service = "bedrock"),
                    SigningContext("POST", "bedrock.us-east-1.amazonaws.com", "/model/x/invoke"),
                )
            }
            val auth = creds.headers["Authorization"] ?: ""
            Log.i(tag, "sigv4 authHeaderPrefix=${auth.take(24)} hasSig=${auth.contains("Signature=")}")

            // 3. EventStream decode
            val frame = buildFrame("""{"delta":{"text":"hi-device"}}""".encodeToByteArray())
            val (messages, _) = EventStreamDecoder.decode(frame)
            Log.i(tag, "eventstream messages=${messages.size} type=${messages.firstOrNull()?.eventType} payload=${messages.firstOrNull()?.payloadText()}")

            val pass = provider.id == "openai" &&
                auth.startsWith("AWS4-HMAC-SHA256") && auth.contains("Signature=") &&
                messages.size == 1 && messages[0].eventType == "contentBlockDelta"
            Log.i(tag, if (pass) "RESULT PASS" else "RESULT FAIL")
        } catch (t: Throwable) {
            Log.e(tag, "RESULT FAIL exception", t)
        }
        finish()
    }

    private fun buildFrame(payload: ByteArray): ByteArray {
        val name = ":event-type".encodeToByteArray()
        val value = "contentBlockDelta".encodeToByteArray()
        val headers = byteArrayOf(name.size.toByte()) + name + byteArrayOf(7) + be16(value.size) + value
        val total = 8 + 4 + headers.size + payload.size + 4
        return be32(total) + be32(headers.size) + be32(0) + headers + payload + be32(0)
    }
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}
