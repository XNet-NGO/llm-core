# ProviderConfig Reference

`llm-core` is config-driven: a provider is a `ProviderConfig` data record, and
`OpenAIProvider.from(config)` builds a working provider with no per-provider code. This is the
stable reference for the config surface and one example per implemented dialect.

Companion docs: `research/provider-dialects.md` (dialect taxonomy D1–D8),
`research/providers-100/` (top-100 provider config database, maintained by the research track).

## Building a provider

```kotlin
val provider = OpenAIProvider.from(config)                 // chat/completions/images/responses
val gateway  = OpenAIGateway.create(listOf(config1, ...))  // multi-provider router
val voice    = OpenAIProvider.voiceSession(voiceConfig)    // D6 live audio (see below)
```

`ProviderConfig.fromJson(json)` / `ProviderConfig.toJson(config)` use a lenient JSON codec
(unknown keys ignored, defaults encoded), so config files and DB rows survive schema evolution.

## Fields

See `research/providers-100/00-index.md` §0 for the authoritative field pin. Summary:

| field | notes |
|---|---|
| `id` | provider identity; the router matches a request's provider against this (or `name`) |
| `dialect` | `OPENAI_COMPAT`, `AZURE_OPENAI`, `ANTHROPIC`, `GEMINI`, `BEDROCK`, `RESPONSES`, `TEMPLATE`, `VOICE_REALTIME` |
| `baseUrl` | no trailing slash |
| `auth` | scheme + credentials (see Auth) |
| `endpoints` | per-operation path overrides; `null` = dialect default |
| `capabilities` | flat booleans + `voice` mode; explicit config wins over catalog inference |
| `catalog` | `mode` (AUTO/STATIC/MERGED), `ttlSeconds`, `path`, `models` |
| `streaming` | `SSE` (default), `EVENTSTREAM`, `WS`, `NDJSON`, `CHUNKED` |
| `aliases` | `Map<slug, upstreamModelId>` — remapped before dispatch |
| `imageInput`/`imageOutput`/`imageModelInPath` | TEMPLATE-dialect media knobs |

## Auth schemes

| scheme | fields | attached |
|---|---|---|
| `BEARER` | `apiKey` | `Authorization: Bearer <key>` |
| `X_API_KEY` | `apiKey`, `keyHeader` | `<keyHeader>: <key>` (e.g. `x-goog-api-key`) |
| `QUERY` | `apiKey`, `queryParam` | `?<queryParam>=<key>` |
| `NONE` | — | nothing (local runtimes) |
| `SIGV4` | `apiKey`(access-key-id), `secretKey`, `region`, `service`, `sessionToken?` | AWS v4 signature via built-in `AwsSigV4Signer` (auto-registered for Bedrock) |
| `OAUTH2` | `tokenUrl`, `clientId`, `clientSecret`, `scopes` | bearer/header from a host-registered `RequestSigner` |

`extraHeaders` / `queryParams` maps add static headers/params for any scheme.

SIGV4/OAUTH2 use a pluggable `RequestSigner` (see `CredentialProviders`). The AWS SigV4 signer
ships in-core; OAuth2 token acquisition is host-supplied:
```kotlin
CredentialProviders.register(AuthScheme.OAUTH2) { auth, ctx -> SignedCredentials(headers = ...) }
```

## Dialect examples

### D1 OPENAI_COMPAT
```json
{"id":"openai","dialect":"OPENAI_COMPAT","baseUrl":"https://api.openai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":"sk-..."}}
```

### D8 AZURE_OPENAI
```json
{"id":"azure","dialect":"AZURE_OPENAI","baseUrl":"https://my.openai.azure.com",
 "auth":{"scheme":"X_API_KEY","keyHeader":"api-key","apiKey":"...",
         "queryParams":{"api-version":"2024-10-21"}}}
```

### D2 ANTHROPIC
```json
{"id":"anthropic","dialect":"ANTHROPIC","baseUrl":"https://api.anthropic.com",
 "auth":{"scheme":"X_API_KEY","keyHeader":"x-api-key","apiKey":"sk-ant-...",
         "extraHeaders":{"anthropic-version":"2023-06-01"}}}
```

### D3 GEMINI
```json
{"id":"gemini","dialect":"GEMINI","baseUrl":"https://generativelanguage.googleapis.com",
 "auth":{"scheme":"QUERY","queryParam":"key","apiKey":"..."}}
```

### D5 RESPONSES
```json
{"id":"openai-responses","dialect":"RESPONSES","baseUrl":"https://api.openai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":"sk-..."}}
```

### D4 BEDROCK (OpenAI-compatible runtime + SigV4)
```json
{"id":"bedrock","dialect":"BEDROCK",
 "baseUrl":"https://bedrock-runtime.us-east-1.amazonaws.com",
 "auth":{"scheme":"SIGV4","apiKey":"AKIA...","secretKey":"...",
         "region":"us-east-1","service":"bedrock"}}
```

### D7 TEMPLATE (media)
```json
{"id":"flux","dialect":"TEMPLATE","baseUrl":"https://api.example.com",
 "auth":{"scheme":"BEARER","apiKey":"..."},
 "imageInput":"multipart","imageOutput":"json","imageModelInPath":true}
```

### D6 VOICE_REALTIME (built via `voiceSession`, not `from`)
```json
{"id":"gemini-live","dialect":"VOICE_REALTIME","baseUrl":"https://generativelanguage.googleapis.com",
 "auth":{"scheme":"X_API_KEY","keyHeader":"x-goog-api-key","apiKey":"..."},
 "capabilities":{"voice":"LIVE"}}
```
```kotlin
val session: VoiceSession = OpenAIProvider.voiceSession(config) // LIVE→Gemini, REALTIME→OpenAI, TURN_STREAM→Qwen TTS
```

## Catalog & capabilities

- `CatalogCache` provides a host-side TTL cache (`getOrFetch(config)` / `resolveCatalog(config)`).
- `CapabilityInference.resolve(config, catalog)` derives capabilities from catalog modalities/
  features, with explicit config taking precedence (spec §5).
