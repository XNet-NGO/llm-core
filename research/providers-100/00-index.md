# llm-core — Top-100 Provider Configuration Database

Config-by-data reference for the build agent: every provider in the top-100
catalog expressed in the exact `ProviderConfig` JSON schema the core router
parses (`JsonLenientConfig`: lenient, unknown keys ignored, defaults encoded).
Adding a provider never requires code — copy the JSON block, fill `apiKey` (or
a secret-store/env ref at the host layer), wire into the portal.

Companion docs: `../provider-dialects.md` (dialect taxonomy D1–D8),
`../borrow-patterns.md`. Files:

| file | range | content |
|---|---|---|
| 01-frontier.md | #001–#030 | Frontier APIs, clouds, big aggregators |
| 02-hosts-embed-gateway.md | #031–#051 | Hosted open-weights, embeddings/rerank, gateways |
| 03-local-runtimes.md | #052–#065 | Local inference servers (OpenAI-compat dials) |
| 04-china.md | #066–#081 | Chinese providers (all dialects) |
| 05-media-voice.md | #082–#100 | Image/video/TTS/STT/voice |

## 0. Schema pin (exact field names)

```kotlin
ProviderConfig(
  id: String,                       // routing identity: provider/model
  name: String = id,
  enabled: Boolean = true,
  dialect: Dialect,                 // OPENAI_COMPAT|ANTHROPIC|GEMINI|BEDROCK|RESPONSES|VOICE_REALTIME|AZURE_OPENAI|TEMPLATE
  baseUrl: String,                  // NO trailing slash; path or host-only per dialect notes
  auth: ProviderAuth(
    scheme: AuthScheme,             // BEARER|X_API_KEY|QUERY|SIGV4|OAUTH2|NONE
    apiKey: String = "",
    keyHeader: String = "X-API-Key",// header for X_API_KEY scheme
    queryParam: String = "api_key", // query-param name for QUERY scheme
    extraHeaders: Map<String,String> = {},   // static headers, e.g. anthropic-version
    queryParams: Map<String,String> = {},    // static query params, e.g. api-version (Azure)
    // SIGV4 (AWS/Bedrock) — consumed by built-in AwsSigV4Signer; apiKey = access key id
    region: String = "",            // e.g. us-east-1
    service: String = "",           // e.g. bedrock
    secretKey: String = "",         // AWS secret access key
    sessionToken: String = "",      // optional temp-cred token
    // OAUTH2 (Vertex/Entra) — consumed by a host-registered RequestSigner
    tokenUrl: String = "",
    clientId: String = "",
    clientSecret: String = "",
    scopes: List<String> = [],
  ),
  endpoints: Endpoints(             // null = dialect default path (dialect defaults listed in §1)
    chat, completions, interactions, batches, files, embeddings, models,
    responses, imagesGenerations, imagesEdits, audioSpeech, audioTranscriptions,
    rerank, moderation, videos, tasks — all String? = null
  ),
  capabilities: Capabilities(
    chat=true, completions=false, embeddings=false, responses=false, rerank=false,
    moderation=false, tts=false, stt=false, imagesGenerate=false, imagesEdit=false,
    voice: enum? = null,            // REALTIME|LIVE|TURN_STREAM
  ),
  catalog: Catalog(
    mode: CatalogMode = AUTO,       // AUTO|STATIC|MERGED
    ttlSeconds: Long = 3600,
    path: String = "/models",
    models: List<CatalogModel> = [],// id, contextLength?, maxOutputLength?,
                                    // inputModalities[], outputModalities[], supportedFeatures[]
  ),
  streaming: StreamFormat = SSE,    // SSE|EVENTSTREAM|WS|NDJSON|CHUNKED
  timeoutMs: Long = 120_000,
  imageInput: String = "multipart", // "multipart"|"json" (template dialect)
  imageOutput: String = "json",     // "json" (result.image b64) | "raw" (binary body)
  imageModelInPath: Boolean = true, // model id appended to image path
  aliases: Map<String,String> = {}, // gateway slug → upstream model id
)
```

## 1. Dialect default endpoints (when `endpoints.*` is null)

| op | OPENAI_COMPAT | ANTHROPIC | GEMINI | RESPONSES |
|---|---|---|---|---|
| chat | POST {base}/chat/completions | POST {base}/v1/messages | POST {base}/v1beta/models/{model}:generateContent | — |
| completions | POST {base}/completions | — | — | — |
| responses | POST {base}/responses | — | — | POST {base}/responses |
| embeddings | POST {base}/embeddings | — | {base}/v1beta/models/{model}:embedContent | — |
| models | GET {base}/models | static (no public list) | GET {base}/v1beta/models | — |
| imagesGenerations | POST {base}/images/generations | — | {model}:predict (Imagen via Vertex) | — |
| imagesEdits | POST {base}/images/edits | — | — | — |
| audioSpeech | POST {base}/audio/speech | — | — | — |
| audioTranscriptions | POST {base}/audio/transcriptions | — | — | — |
| rerank | POST {base}/rerank (xe extension) | — | — | — |
| moderation | POST {base}/moderations | — | — | — |
| video/tasks | POST {base}/videos, GET {base}/tasks | — | — | — |
| stream | SSE data: JSON, optional [DONE] | SSE content-block events | SSE alt=sse data: JSON | SSE response.* events |

BEDROCK: `POST {base}/model/{modelId}/converse|converse-stream` (+ SigV4, service=bedrock).
AZURE_OPENAI: D1 paths under `/openai/deployments/{deployment}/…` + `queryParams.api-version` +
`auth.keyHeader=api-key`.
VOICE_REALTIME: WS `{base}/v1/realtime?model=…` (OpenAI) — see voice-client core.
TEMPLATE: no defaults; every op needs explicit path + transformation (see 05-media-voice.md).

Implementation status (per COMPLETION_SPEC.md + commits, 2026-09-18):
- Functional via `OpenAIProvider.from(config)`: D1/D8 (ConfigOpenAIProvider), D5
  (ResponsesOpenAIProvider), D7 (mediaProvider template), D2 ANTHROPIC, D3 GEMINI
  (both wired in 7d7189a, Koin-free instance construction), D4 BEDROCK (partial — builds
  via Bedrock OpenAI-compat runtime, D1 surface, auto-registered AwsSigV4Signer; native
  Converse + EVENTSTREAM framing deferred to v1.1).
- D6 VOICE_REALTIME: sessions already implemented in `voice-client-core`
  (GeminiLiveSession, OpenAIRealtimeSession, QwenTtsSession — Ktor WS, binary frames,
  live smoke tests incl. Gemini bidi); gateway-core `from(config)` binding + WS
  StreamFormat engine still pending (COMPLETION_SPEC §5.2).
- Auth: SIGV4/OAUTH2 fields live; signing via host-registered `RequestSigner` SPI;
  concrete `AwsSigV4Signer` (kotlincrypto 0.8.0, verified vs AWS reference vectors).
  `apiKey` = access key id for SIGV4.
- Streaming: SSE hardened (CRLF, spaceless `data:`/`[DONE]`, comments/keepalives) and
  NDJSON bare records auto-detected (ollama-style). EVENTSTREAM/WS engines deferred.
- `aliases` is `Map<String,String>` (List form removed in 7d7189a); no other schema drift.
- Tests: gateway-core 95/0 green; common stream 19/0.

## 2. Per-dialect required overrides (gotchas that break naive D1)

| provider family | must NOT have /v1 baked into baseUrl | header hacks | path hacks |
|---|---|---|---|
| Google AI Studio | HOST-ONLY `https://generativelanguage.googleapis.com` (fork HttpRequester replaces path) | `x-goog-api-key` | chat=/v1beta/openai/chat/completions (verified); interactions /v1beta/openai/batches |
| DashScope | `https://dashscope-intl.aliyuncs.com` (host only) | Bearer sk- | chat=/compatible-mode/v1/chat/completions, models=/compatible-mode/v1/models, embeddings=/compatible-mode/v1/embeddings (all verified) |
| Cloudflare | full base `/client/v4/accounts/{ACCOUNT_ID}/ai/v1` | Bearer CF API token | chat=/chat/completions (openai-compat); media via /run/{model} (template) |
| DeepInfra | base ends `/v1/openai` | Bearer | chat=/chat/completions |
| Novita | base ends `/v3/openai` | Bearer | chat=/v1/chat/completions (i.e. /v3/openai/v1/chat/completions) |
| Fireworks | base `/inference/v1` | Bearer | model ids `accounts/fireworks/models/…` |
| Azure OpenAI | `https://{res}.openai.azure.com/openai` | `api-key` header | deployments/{deployment}/chat/completions + api-version query |
| GitHub Models | `https://models.inference.ai.azure.com` | Bearer PAT | /v1/* ; also /anthropic compat |
| Ollama | `http://localhost:11434` | none | /v1/chat/completions (D1) or /api/chat (NDJSON) |

## 3. Capability/knob vocabulary (shared across configs)

- **Reasoning/thinking**: D1 `reasoning_effort` (low|medium|high, some: none/minimal) +
  `reasoning_content` passthrough in stream deltas; D2 `thinking:{type:"enabled",budget_tokens:N}`;
  D3 `generationConfig.thinkingConfig{thinkingBudget}`; DeepSeek/moonshot `thinking` param.
- **Structured output**: D1 `response_format={type:"json_object"|"json_schema",json_schema:{…}}`;
  D2 `tool_choice` w/ input_schema; D3 `generationConfig.responseMimeType/responseSchema`.
- **Vision**: D1 `messages[].content[]` parts {type:"image_url",image_url:{url:"data:…;base64,…"|http}};
  D2 content_block {type:"image",source:{type:"base64",media_type,data}};
  D3 parts {inline_data:{mime_type,data}}; some providers only URL (Perplexity no vision on sonar;
  Qwen ok both).
- **Streaming**: always `stream:true` in body; `stream_options:{include_usage:true}` (D1 mirrors vary);
  keepalive `data: ping` tolerated; never hard-fail on missing [DONE].
- **Auth schemes as config**: SIGV4 (Bedrock), OAUTH2 (Vertex, Azure Entra, Baidu legacy), QUERY (few),
  NONE (local, pollinations).

## 4. Build-agent guidance

1. Every green-check config below has been live-verified in `openai-gateway-core` smoke tests
   (AI Studio, DashScope intl, Cloudflare, freeinference). Everything else pins the documented
   upstream contract; mark the provider "untested" until a smoke test lands.
2. `capabilities` always wins over catalog inference (explicit config > `GET /models` fields).
3. Use `catalog.mode=MERGED` for hosts with rich `/models` (freeinference shape) and
   `STATIC` for Anthropic/private/legacy hosts; `path` overrides the catalog path.
4. Model aliases go in `aliases` (portal slug → upstream id); NEVER edit the provider block per customer.
5. Rate-limit headers observed per provider are noted; honoring them is host-layer, not core.
6. When a provider is D1 *and* has a native dialect (Qwen, MiniMax, Cohere, GitHub Models, Bedrock
   Runtime, iFlytek), ship TWO config entries with different `id` suffixes (`qwen-compat`,
   `qwen-native`) — the router treats them as distinct providers.

## 5. Master table (100 configs)

| # | id | dialect | base | auth | caps (beyond chat/stream) |
|---|---|---|---|---|---|
| 001 | openai | OPENAI_COMPAT+RESPONSES | api.openai.com/v1 | bearer | emb/img/audio/mod/batch/rerank– |
| 002 | anthropic | ANTHROPIC | api.anthropic.com | x-api-key+v | thinking/batch |
| 003 | google-ai-studio | OPENAI_COMPAT(host-only) | generativelanguage.googleapis.com | x-goog-api-key | emb/img/voice live/batch/interactions |
| 004 | google-vertex | GEMINI | {region}-aiplatform.googleapis.com | oauth2 | emb/img/voice |
| 005 | xai | OPENAI_COMPAT (+D2 skin) | api.xai.com/v1 | bearer | img (grok) |
| 006 | mistral | OPENAI_COMPAT | api.mistral.ai/v1 | bearer | emb/rerank/mod/agents/fim |
| 007 | cohere | OPENAI_COMPAT(+native) | api.cohere.com | bearer | emb/rerank/classify/gen |
| 008 | deepseek | OPENAI_COMPAT (+D2 beta) | api.deepseek.com | bearer | reasoning/fim/vision/files |
| 009 | ai21 | OPENAI_COMPAT | api.ai21.com/v1 | bearer | emb/rerank/summarize |
| 010 | nvidia-nim | OPENAI_COMPAT | integrate.api.nvidia.com/v1 | bearer | emb/rerank |
| 011 | watsonx | TEMPLATE(+D1 compat) | us-south.ml.cloud.ibm.com | oauth2(bearer) | emb |
| 012 | bedrock | BEDROCK (+D1 runtime) | bedrock-runtime.{r}.amazonaws.com | sigv4 | everything via models |
| 013 | azure-openai | AZURE_OPENAI | {res}.openai.azure.com/openai | api-key+qv | emb/img/audio/batch |
| 014 | oci-genai | TEMPLATE | generativeai.oci.{r}.oci.oraclecloud.com | oci-sig | emb |
| 015 | perplexity | OPENAI_COMPAT(+agent D5) | api.perplexity.ai | bearer | web search/embeddings |
| 016 | openrouter | OPENAI_COMPAT (+D5 relay) | openrouter.ai/api/v1 | bearer | route/transforms |
| 017 | groq | OPENAI_COMPAT (+D5) | api.groq.com/openai/v1 | bearer | stt/tts/vision/mod |
| 018 | together | OPENAI_COMPAT | api.together.xyz/v1 | bearer | emb/img |
| 019 | fireworks | OPENAI_COMPAT | api.fireworks.ai/inference/v1 | bearer | emb?/img |
| 020 | cerebras | OPENAI_COMPAT | api.cerebras.ai/v1 | bearer | — |
| 021 | sambanova | OPENAI_COMPAT(+D2 skin) | api.sambanova.ai/v1 | bearer | — |
| 022 | lambda | OPENAI_COMPAT | api.lambdalabs.com/v1 | bearer | — |
| 023 | novita | OPENAI_COMPAT (+v3 openai) | api.novita.ai/v3/openai | bearer | img |
| 024 | deepinfra | OPENAI_COMPAT | api.deepinfra.com/v1/openai | bearer | emb/stt |
| 025 | baseten | OPENAI_COMPAT | app.baseten.co | bearer | byo deploy |
| 026 | anyscale | OPENAI_COMPAT | api.endpoints.anyscale.com/v1 | bearer | emb |
| 027 | lepton | OPENAI_COMPAT | api.lepton.ai | bearer | img |
| 028 | replicate | TEMPLATE(async)+D1 teaser | api.replicate.com/v1 | bearer | img/video/audio |
| 029 | hf-inference | OPENAI_COMPAT(+TEI) | router.huggingface.co/v1 | bearer | emb |
| 030 | cloudflare-workers-ai | OPENAI_COMPAT(+template) | api.cloudflare.com/client/v4/accounts/{id}/ai/v1 | bearer | emb/img/tts |
| 031 | github-models | — 🔴 RETIRED 2026-07-30 | migrate to Azure AI Foundry | — | — |
| 032 | nebius | OPENAI_COMPAT | api.studio.nebius.ai/v1 | bearer | emb/img |
| 033 | siliconflow | OPENAI_COMPAT | api.siliconflow.cn/v1 (com intl) | bearer | emb/img/audio |
| 034 | freeinference | OPENAI_COMPAT | (see block) | bearer | catalog-rich |
| 035 | pollinations | OPENAI_COMPAT | text.pollinations.ai | none | img (separate host) |
| 036 | cline-api | OPENAI_COMPAT | api.cline.bot | bearer | — |
| 037 | zen | OPENAI_COMPAT | api.opencode.ai | bearer | — |
| 038 | snowflake-cortex | TEMPLATE | {org}-{acct}.snowflakecomputing.com/api/v2/cortex | bearer(OAuth) | search |
| 039 | writer | OPENAI_COMPAT | api.writer.com/v1 | bearer | emb |
| 040 | upstage | OPENAI_COMPAT | api.upstage.ai/v1 | bearer | emb/rerank/doc-ai |
| 041 | reka | OPENAI_COMPAT | api.reka.ai/v1 | bearer | vision |
| 042 | hyperbolic | OPENAI_COMPAT | api.hyperbolic.xyz/v1 | bearer | img |
| 043 | friendliai | OPENAI_COMPAT | api.friendli.ai/server/v1 | bearer | img? |
| 044 | akash | OPENAI_COMPAT | console.akash.network/api/v1? | bearer | — |
| 045 | modal | OPENAI_COMPAT | user-supplied | key | byo |
| 046 | nomic | OPENAI_COMPAT | api-atlas.nomic.ai/v1 | bearer | emb |
| 047 | voyage | OPENAI_COMPAT | api.voyageai.com/v1 | bearer | emb/rerank |
| 048 | jina | OPENAI_COMPAT(+D7 reader) | api.jina.ai/v1 | bearer | emb/rerank/omni |
| 049 | mixedbread | OPENAI_COMPAT | api.mixedbread.ai/v1 | bearer(+x-mb) | emb/rerank |
| 050 | vercel-ai-gateway | OPENAI_COMPAT | ai-gateway.vercel.sh/v1 | bearer | relay |
| 051 | clarifai | OPENAI_COMPAT | api.clarifai.com/v2/openai | bearer | — |
| 052 | ollama | OPENAI_COMPAT+NDJSON | localhost:11434 | none | emb/vision |
| 053 | vllm | OPENAI_COMPAT | localhost:8000/v1 | optional | emb |
| 054 | sglang | OPENAI_COMPAT | localhost:30000/v1 | optional | emb |
| 055 | tgi | OPENAI_COMPAT(+native) | localhost:8000 | optional | emb/rerank |
| 056 | llama.cpp-server | OPENAI_COMPAT | localhost:8080/v1 | none | emb/rerank/infill |
| 057 | lm-studio | OPENAI_COMPAT | localhost:1234/v1 | none | emb |
| 058 | localai | OPENAI_COMPAT | localhost:8080/v1 | optional | emb/rerank/img/audio |
| 059 | aphrodite | OPENAI_COMPAT | localhost:8000/v1 | optional | emb |
| 060 | jan | OPENAI_COMPAT | localhost:1337/v1 | none | — |
| 061 | koboldcpp | OPENAI_COMPAT | localhost:5001 | none | — |
| 062 | tensorrt-llm | OPENAI_COMPAT | {triton}:8000 | optional | — |
| 063 | gpt4all | OPENAI_COMPAT | localhost:4891/v1 | none | — |
| 064 | oobabooga | OPENAI_COMPAT | localhost:5000/v1 | none | — |
| 065 | petals | OPENAI_COMPAT(hybrid) | petals.ml | none | — |
| 066 | qwen-dashscope | OPENAI_COMPAT(+D7 native) | dashscope-intl.aliyuncs.com | bearer | emb/img/video/omni/ws-tts |
| 067 | zhipu-glm | OPENAI_COMPAT(+D2) | open.bigmodel.cn/api/paas/v4 | bearer | img(cogview)/emb/rerank |
| 068 | moonshot-kimi | OPENAI_COMPAT(+D2) | api.moonshot.cn/v1 | bearer | emb/cache |
| 069 | minimax | ANTHROPIC skin (VERIFIED CN) | api.minimax.cn/anthropic | bearer | img/video/audio |
| 070 | 01ai-yi | OPENAI_COMPAT | api.lingyiwanwu.com/v1 | bearer | — |
| 071 | stepfun | OPENAI_COMPAT | api.stepfun.com/v1 | bearer | vision/aso |
| 072 | baidu-qianfan | OPENAI_COMPAT(v2) | qianfan.baidubce.com/v2 | bearer | emb |
| 073 | tencent-hunyuan | OPENAI_COMPAT(+D2) | api.hunyuan.cloud.tencent.com/v1 | bearer | img/video |
| 074 | volcengine-ark | OPENAI_COMPAT(+D7) | ark.cn-beijing.volces.com/api/v3 | bearer | emb/img/video/tts |
| 075 | iflytek-spark | OPENAI_COMPAT | spark-api-open.xf-yun.com/v1 | bearer(apikey:secret) | emb/tts/stt |
| 076 | baichuan | OPENAI_COMPAT | api.baichuan-ai.com/v1 | bearer | emb |
| 077 | xverse | OPENAI_COMPAT | api.xverse.cn/v1 | bearer | — |
| 078 | sensenova | OPENAI_COMPAT | api.sensenova.cn/v1 | x-access-token | img |
| 079 | zhinao-360 | OPENAI_COMPAT | api.ai.360.com/v1 | bearer | — |
| 080 | kling | TEMPLATE(async) | api.klingai.com/v1 | bearer | video/img |
| 081 | xiaomi-mimo | OPENAI_COMPAT | api.xiaomi.com/v1 | bearer | — |
| 082 | elevenlabs | TEMPLATE(+WS turn) | api.elevenlabs.io | xi-api-key | tts/stt/sfx |
| 083 | cartesia | TEMPLATE(+WS) | api.cartesia.ai | bearer | tts/voice |
| 084 | playht | TEMPLATE | api.play.ht/api/v2 | bearer+x-user-id | tts/stt |
| 085 | deepgram | TEMPLATE | api.deepgram.com | bearer | stt/tts ws |
| 086 | assemblyai | TEMPLATE(async) | api.assemblyai.com | bearer | stt/lemur |
| 087 | speechmatics | TEMPLATE(ws) | ws://…speechmatics.com | bearer | stt |
| 088 | hume | TEMPLATE(+WS) | api.hume.ai | bearer | voice evi/tts |
| 089 | resemble | TEMPLATE(ws) | api.resemble.ai | bearer | tts |
| 090 | fish-audio | TEMPLATE | api.fish.audio | bearer | tts/voices |
| 091 | stability | TEMPLATE | api.stability.ai/v2beta | bearer | img/audio |
| 092 | ideogram | TEMPLATE | api.ideogram.ai/v1 | bearer | img |
| 093 | bfl-flux | TEMPLATE(async) | api.bfl.ai/v1 | bearer | img/video (FLUX 3) |
| 094 | recraft | TEMPLATE | api.recraft.ai/v1 | bearer | img |
| 095 | runway | TEMPLATE(async) | api.dev.runwayml.com/v1 | bearer | video/img |
| 096 | luma | TEMPLATE(async) | api.lumalabs.ai/dream-machine/v1 | bearer | video/img |
| 097 | pika | TEMPLATE(async) | api.pika.art/v2 | bearer | video |
| 098 | adobe-firefly | TEMPLATE | firefly-api.adobe.io/v2 | oauth2 | img |
| 099 | azure-speech | TEMPLATE | {r}.tts.speech.microsoft.com | ocp-key | tts/stt |
| 100 | minimax-audio | TEMPLATE (VERIFIED OpenAPI) | api.minimax.cn (CN) | bearer | tts |