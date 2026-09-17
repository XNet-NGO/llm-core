# llm-core — Provider Wire-Protocol Research

Research for the config-driven router: how the top ~100 LLM providers expose their
APIs, so `llm-core` dialects + `ProviderConfig` can express any of them without code.

Sources: provider API docs (OpenAI, Anthropic, Google Gemini, AWS Bedrock, Azure,
DeepSeek, Qwen, MiniMax, Z.ai, Moonshot, Mistral, xAI, Groq, Together, Fireworks,
Cerebras, Perplexity, Cohere, NVIDIA NIM, OpenRouter, Ollama, vLLM, SGLang, TGI,
llama.cpp, LM Studio, LocalAI, GitHub Models, Cloudflare Workers AI, HuggingFace,
Replicate, ElevenLabs, Deepgram, Cartesia, Stability, Ideogram, Voyage, Jina),
OpenRouter public model catalog (444 models / 59 provider slugs, 2026-09).

## 1. Key finding: wire-format convergence

~85% of the catalog speaks one of two dialects:

| Dialect | Share | Providers |
|---|---|---|
| OpenAI-compatible `POST /v1/chat/completions` + SSE | ~75% | all open-weights hosts, all major API vendors (dual-mode), every local runtime |
| Anthropic `/v1/messages` (+ `anthropic-version` header, content-block SSE) | ~10% | Anthropic + compat skins (xAI, Kimi, MiniMax, GLM, Bedrock Converse) |
| Everything else | ~15% | Gemini native, Bedrock native, Responses API, Realtime/Live voice, vendor-native REST |

Consequence for the router: **5 generic dialects + template-based custom REST covers the
whole catalog**. No provider-specific code paths are required.

## 2. Dialect taxonomy

### D1 `openai-compat` — chat/completions, SSE, Bearer
- Endpoints: `{base}/chat/completions`, `/completions`, `/embeddings`, `/models`,
  `/images/generations|edits|variations`, `/audio/speech|transcriptions|translations`,
  `/moderations`, `/rerank` (xe-provider extension), `/responses` (pass-through)
- Body: OpenAI chat schema (messages, tools, tool_choice, response_format,
  temperature, top_p, stream, stream_options, seed, max_tokens/max_completion_tokens,
  reasoning_effort)
- Stream: SSE `data:` chunks; optional `[DONE]`; some add `data: ping` keepalives
- Auth: `Authorization: Bearer <key>` (occasionally `x-api-key`)
- Tolerance notes (observed in the wild → router should not hard-fail on):
  - `max_tokens` vs `max_completion_tokens` (o-series + several mirrors use completion)
  - reasoning fields: `reasoning_content`, `reasoning_effort`, chain-of-thought wrappers
  - tool_call id shapes, duplicate `delta` object nesting in some mirrors
  - `/v1` prefix optional on some hosts (e.g. ollama serves both)
  - `model` rename aliases (gateways map slugs → upstream ids)
- Providers: OpenAI, OpenRouter, Azure OpenAI (D1+D8), DeepSeek, Qwen/DashScope
  (openai mode), MiniMax, Z.ai/GLM, Moonshot/Kimi, Mistral, xAI (also D2), Groq,
  Together, Fireworks, Cerebras, Perplexity, Cohere (openai-compat mode), NVIDIA NIM,
  GitHub Models, Cloudflare Workers AI, HuggingFace Inference Endpoints, Replicate,
  Baseten, DeepInfra, SambaNova, Lambda, Novita, Anyscale, Modal, Lepton,
  freeinference, pollinations, Cline API (api.cline.bot), Zen (opencode.ai),
  text.pollinations.ai, GitHub Models
- Local runtimes: Ollama, vLLM, SGLang, TGI, llama.cpp server, LM Studio, LocalAI,
  Aphrodite, Jan, KoboldCPP, TensorRT-LLM

### D2 `anthropic` — /v1/messages, content-block SSE
- Endpoints: `{base}/messages`, `/models` (not standardized upstream — static config),
  `/messages/count_tokens`
- Body: system block, content blocks (text/image/tool_use/tool_result), max_tokens,
  thinking/thinking_budget, tools, tool_choice, stream
- Headers: `x-api-key`, `anthropic-version: 2023-06-01`, `anthropic-beta: ...`
- Stream events: `message_start|message_delta|message_stop`, `content_block_start|
  content_block_delta|content_block_stop`, delta types `text_delta|input_json_delta|
  thinking_delta|signature_delta`
- Compat skins (expose D2 over their own ops): xAI (`/v1`), Moonshot Kimi
  (`/anthropic` compat), MiniMax (anthropic-compat), Z.ai/GLM (anthropic-compat),
  Tencent Hunyuan (anthropic-compat), DeepSeek (anthropic-compat beta)
- Providers: Anthropic; Bedrock Converse API is D2-adjacent (see D4)

### D3 `gemini` — native Google REST family
- Endpoints: `{base}/v1beta/models/{model}:generateContent`,
  `...:streamGenerateContent` (alt=sse), `...:countTokens`, `...:embedContent|batchEmbedContents`,
  `/v1beta/models` (ListModels), `...:predict`
- Auth: `x-goog-api-key` header or `?key=`; Vertex AI uses OAuth2 service-account
  (D3 + GCP auth)
- Body: contents[p]/parts (text, inline_data, function_call, function_response,
  thought, code_execution), system_instruction, generationConfig (temperature,
  maxOutputTokens, responseMimeType/responseSchema, thinkingConfig, tools[
  functionDeclarations], toolConfig), stream via SSE `data:` JSON
- Modes: AI Studio (generativelanguage.googleapis.com), Vertex AI
  (*-aiplatform.googleapis.com)
- OpenAI-compat server mode exists on AI Studio endpoint (`/v1/chat/completions`) —
  configurable per-dialect, same key
- Providers: Google (Gemini family)

### D4 `bedrock` — AWS native
- Endpoints: `POST /model/{modelId}/invoke|invoke-with-response-stream` (legacy),
  `POST /model/.../converse|converse-stream` (Converse API, unified JSON),
  `list-foundation-models`
- Auth: AWS SigV4 (access key/secret/session, region, service=bedrock) — implemented
  in-core as an auth scheme, not a dialect
- Stream: `application/vnd.amazon.eventstream` (headers + JSON payload chunks),
  or NDJSON-ish lines (some runtimes)
- Body dialects inside invoke payloads are MODEL-specific (anthropic/claude, meta/*,
  mistral/mistral, ai21/*, cohere/*, amazon/*) → router exposes Converse (unified)
  or per-model body templates
- Bedrock Runtime also now serves an OpenAI-compatible endpoint
  (`bedrock-runtime.<region>.amazonaws.com` D1) — provider prefers D1 when advertised
- Providers: Amazon Bedrock (Claude, Llama, Mistral, Cohere, Nova, Titan,
  Command etc.), inference profiles (us. / eu.)

### D5 `responses` — OpenAI Responses API (stateful)
- Endpoints: `POST {base}/responses`, `GET {base}/responses/{id}`,
  `DELETE .../{id}/cancel` (and /items, /input_items), streaming `POST .../responses`
  with `stream:true` (SSE events: response.created, response.output_item.added,
  response.function_call_arguments.done, response.completed, response.failed)
- Body: input (messages or items incl. function_call_output, file_search results),
  tools[], tool_choice, reasoning{effort,summary}, instructions, previous_response_id,
  store, include, text{format}, output_audio etc.
- Providers: OpenAI; increasingly mirrored by gateways (MiniMax responses-compat,
  OpenRouter responses passthrough, agent gateways) — the router treats D5 as its
  own dialect so non-OpenAI mirrors can be configured individually

### D6 `voice-realtime` — duplex audio
- OpenAI Realtime: `wss {base}/v1/realtime?model=...` (WS JSON events:
  session.update, input_audio_buffer.append|commit, response.create,
  conversation.item.create, audio output deltas; client sends base64 audio in
  `input_audio_buffer.append`, server returns `response.audio.delta`)
- Gemini Live: `{base}/v1beta/models/{model}:bidiGenerateContent` over WS
  (protojson: setup / realtimeInput audio / serverContent audio+transcript+
  toolCall / toolResponse), or REST `generateContent` with `speech:` audio config
- ElevenLabs: turn-based WS `/v1/text-to-speech/{voice}/stream-input` (multipart
  text/audio messages) — no bidi semantics, classified `simple-stream`
- Cartesia/PlayHT/Deepgram/AssemblyAI: streaming STT or TTS over WS with their own
  message shapes → D7 templates
- Router surface: capability `voice` with sub-modes `realtime` (bidi), `live` (bidi),
  `turn-stream` (one-way streamed) — all bound to the core `VoiceSession` abstraction

### D7 `template` — generic vendor-native REST (path/payload templates)
- For hosts outside D1–D6: Stability (`/v2beta/stable-image/generate/*`), Ideogram
  (`/1/generation/*`), Deepgram (`/v1/listen` STT), OpenAI Whisper files, Azure
  Speech, IBM watsonx (`/ml/v1/text/generation`), Aleph Alpha, Writer
  Palmyra, AI21 (Jamba), Anthropic-compat-all (covered D2), Reka, Upstage,
  Perplexity sonar (D1), xAI (D1/D2), Snowflake Cortex (`/api/v2/cortex`),
  Nebius, Foundry (meta.llama.com), OpenRouter bypass modes
- Config: `endpointMap` path templates + per-operation request/response
  transformations (JSON-path mappers declared declaratively — no code)

### D8 `azure-openai` — D1 + Azure conventions
- D1 shapes + `api-version` query param, `api-key` header,
  deployment name instead of model id, Entra OAuth2 optional, `azure/` model prefix
- Treated as D1 with two config fields: `queryParams` and `headerMap`

## 3. Auth schemes (orthogonal to dialect)

| scheme | config | used by |
|---|---|---|
| `bearer` | apiKey | D1, D5, most gateways |
| `x-api-key` | apiKey + header template | D2, D3 (x-goog-api-key), Azure (api-key) |
| `query` | key name + value | D3 ?key=, some hosts | 
| `sigv4` | region/service/keys, session token | D4 Bedrock |
| `oauth2` | grant/client-id/secret or service-account | Vertex AI, Azure Entra |
| `header-template` | arbitrary `Header: {{key}}` map | vendor twists |
| `none` | — | local runtimes (ollama etc.), pollinations |

## 4. Streaming / transport formats

| format | dialect | notes |
|---|---|---|
| SSE `data: JSON` (+ optional `[DONE]`, ping lines) | D1, D2, D3-sse, D5 | must tolerate CRLF, multi-line data, keepalives |
| AWS event stream (header+payload frames) | D4 | binary framing with JSON/unmarshallable payloads |
| WS JSON + binary audio frames | D6 realtime/live | per-event schemas (realtime vs live differ, mapped in-core) |
| NDJSON | ollama native (D1 covers), some `template` hosts | newline-delimited records |
| Plain chunked HTTP | media hosts (STT/TTS) | proxy passthrough chunk-by-chunk |

## 5. Model catalog / capability discovery

Most providers: `GET {base}/models` (D1 shape: `{data:[{id,context_length,
max_output_length, input_modalities, output_modalities, supported_features}]}` —
freeinference model; OpenRouter shape richer). Anthropic/Gemini-native: no public
list or Google ListModels; Bedrock: `list-foundation-models`.

Router design: `catalog.mode = auto` (fetch live `/models` and cache, with
per-request TTL) | `static` (config list) | `merged` (static base + live overlay).
Capabilities inferred from catalog fields when present
(`input_modalities/supported_features`, e.g. freeinference); else from a
config-provided `capabilities` override. Reasoning fields and thinking support:
inferred from `supported_features/reasoning` or config.

## 6. Top-100 provider inventory → dialect assignment

OpenAI-compat (D1) — 70+: openai, openrouter, azure (D8), freeinference, deepseek,
qwen/dashscope, minimax, z-ai/glm, moonshot/kimi, mistral, x-ai, groq, together,
fireworks, cerebras, perplexity, smooth (nous), meta/llama.org api, bytedance-seed,
tencent hunyuan, stepfun, upstage, ibm-granite (D7 hybrid), cohere, nvidia nim,
inclusionai, amazon (openai-compat runtime), openrouter-routed, github models,
cloudflare workers ai, huggingface inference, replicate, baseten, deepinfra,
sambanova, lambda, novita, anyscale, modal, lepton, intel/gaudi?, pollinations,
cline, zen/opencode, text.pollinations.ai, ollama, vllm, sglang, tgi,
llama.cpp-server, lm studio, localai, aphrodite, jan, koboldcpp, tensorrt-llm,
msty, gpt4all, petals, nitrain?... (local runtimes consolidated), huggingface TEI
(embeddings), watsonx (D7), aleph alpha (D7), ai21 (D7+D1), reka (D1), writer (D7),
upstage (D1), snowflake cortex (D7), nebius (D1), foundry/meta (D1), xev (D1),
sanctum, kwaipilot, thedrummer, sakana (D1), poolside (D1), thinkingmachines (D1),
aion (D1), sao10k (D1), nex-agi (D1), inception (D1), xiaomi (D1)
Anthropic (D2) — 10+: anthropic, x-ai (dual), moonshot (dual), minimax (dual),
z-ai (dual), tencent (dual), bedrock-converse (D4), openrouter (routes to D2 as D1)
Gemini native (D3) — google ai studio, google vertex (+ D1 server-mode)
Bedrock native (D4) — amazon bedrock (converse/invoke; incl. anthropic/claude,
meta/llama, mistral, cohere, ai21, amazon.nova, amazon.titan), inference profiles
Responses (D5) — openai responses, openrouter responses passthrough, minimax,
codex cloud (openai)
Voice realtime (D6) — openai realtime (WS), gemini live (bidi WS), elevenlabs
(turn-stream), cartesia (turn-stream), deepgram (STT WS), assemblyai (STT WS),
playht (TTS WS), aura (TTS)
Vendor-native (D7) — stability, ideogram, deepgram, elevenlabs, watsonx,
writer, aleph alpha, snowflake cortex, midjourney (unofficial), flux/BFL, azure
speech, whisper (file), nomic (embeddings D1), voyage (D1 embeddings), jina
(D1 embeddings + rerank), cohere rerank (D1), mixedbread (D1)

## 7. Derived ProviderConfig schema (draft for llm-core)

```yaml
id: my-provider              # routing namespace: provider/model
enabled: true
dialect: openai-compat       # openai-compat | anthropic | gemini | bedrock |
                             # responses | voice-realtime | azure-openai | template
baseUrl: https://api.example.com/v1
auth:
  scheme: bearer             # bearer | x-api-key | query | sigv4 | oauth2 | none
  key: "sk-..."              # or env ref
  extraHeaders: {}           # e.g. {anthropic-version: 2023-06-01, X-Reasoning-Passthrough: false}
  queryParams: {}            # e.g. {api-version: 2024-10-21} (azure)
endpoints: {}                # per-operation path overrides: {chat: /chat/completions, ...}
capabilities:                # explicit; overrides catalog inference
  chat: true
  embeddings: true
  responses: true
  voice: {mode: realtime}    # realtime | live | turn-stream
  audio: {tts: true, stt: true}
  images: {generate: true, edit: false}
  rerank: true
  moderation: true
catalog:
  mode: auto                 # auto | static | merged
  ttl: 3600
  models: []                 # static list (mode: static|merged)
  path: /models              # catalog endpoint override
streaming: sse               # sse | eventstream | ws | ndjson
timeoutMs: 120000
aliases: {}                  # gateway-level model slug remap
```

Dialect definitions (built-in, themselves data): endpoint templates,
request/response transformers keyed per operation, SSE event parser, capability
flags. A new provider = one config entry; a new dialect = one data bundle.

## 8. Recommendations for llm-core

1. Implement dialects D1, D2, D5, D8 first (SSE core) — covers >90% of catalog
   traffic including freeinference and llama-server; D3 + D4 + D6 in v1.1;
   D7 (template) last as an escape hatch.
2. Keep auth as orthogonal pluggable layer (sigv4/oauth2 matter for Bedrock/Vertex).
3. Treat any `GET {base}/models` response as truth for capability inference when
   present; freeinference-style catalogs (context_length, max_output_length,
   input/output_modalities, supported_features) are the target shape.
4. Voice: implement the common `VoiceSession` first with gemini-live + openai
   realtime; the router treats them as D6 sub-modes, everything else is D7 streams.
5. Test matrix: one live-test provider per dialect (freeinference D1, llama-server
   D1-local, anthropic-skin x-ai D2, gemini D3, bedrock D4 if creds exist,
   openai D5).