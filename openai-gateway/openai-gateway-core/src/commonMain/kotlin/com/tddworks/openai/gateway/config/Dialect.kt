package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable

/**
 * Wire dialect of a provider. The dialect selects the generic request/response engine;
 * provider-specific behavior is configuration, not code.
 */
@Serializable
enum class Dialect {
    OPENAI_COMPAT,
    ANTHROPIC,
    GEMINI,
    BEDROCK,
    RESPONSES,
    VOICE_REALTIME,
    AZURE_OPENAI,
    TEMPLATE,
}