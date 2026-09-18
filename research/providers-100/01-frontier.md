# Top-100 → 01-frontier.md (#001–#030)

Legend: 🟢 = live-verified by llm-core smoke tests · 🟡 = documented upstream contract (untested)
· schemas are valid `ProviderConfig` JSON with dialect defaults elided (see 00-index §1).

---

## #001 — Openai (OpenAI Platform) 🟡
- dialect: OPENAI_COMPAT (+ second config for RESPONSES); streaming SSE
- baseUrl: `https://api.openai.com/v1`
- auth: BEARER `sk-…`
- capabilities: chat, completions, embeddings, imagesGenerate, imagesEdit, tts, stt,
  moderation, responses; batch + files via endpoints
- catalog: AUTO `/models` (rich shape: id, object, created, owned_by — NOT freeinference-style;
  context lengths are model-specific → prefer STATIC model list or portal-side map)
- Knobs: `max_completion_tokens` (o-series; `max_tokens` deprecated), `reasoning_effort`
  (low|medium|high), `stream_options.include_usage`, `seed`, `user`, `service_tier`,
  `parallel_tool_calls`, `response_format{type:json_object|json_schema}`; images:
  `size`, `quality` (low|medium|high), `style` (vivid|natural), `moderation`,
  `background` (gpt-image-1); audio speech: `voice`, `speed`, `response_format` (mp3|opus|aac|flac|wav|pcm),
  `model=tts-1|tts-1-hd|gpt-4o-mini-tts`; transcriptions: `whisper-1|gpt-4o-transcribe`
- Quirks: 429 → `retry-after-ms` header (not standard Retry-After); [DONE] sent; raw JSON error body.
- Config:
```json
{"id":"openai","name":"OpenAI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.openai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true,"tts":true,"stt":true,
   "imagesGenerate":true,"imagesEdit":true,"moderation":true,"responses":true},
 "catalog":{"mode":"AUTO"},"streaming":"SSE"}
```

## #002 — Openai-responses 🟡
- dialect: RESPONSES; baseUrl `https://api.openai.com/v1`; auth BEARER
- endpoints: responses=`/responses`; streaming SSE events `response.created,
  response.output_item.added, response.function_call_arguments.done, response.completed|failed`
- Knobs: `input` (messages | items[] incl. `function_call_output`, `file_search` results),
  `previous_response_id`, `instructions`, `tools[]`, `reasoning{effort,summary}`,
  `store`, `include[]`, `text{format}`, `output_audio{voice,format}`
- Capabilities: responses=true, chat=false.
```json
{"id":"openai-responses","name":"OpenAI Responses","dialect":"RESPONSES",
 "baseUrl":"https://api.openai.com/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":false,"responses":true},
 "endpoints":{"responses":"/responses"},"streaming":"SSE"}
```

## #003 — Google AI Studio (Gemini, OpenAI-compat mode) 🟢
- dialect: OPENAI_COMPAT on host-only baseUrl (the com.tddworks HttpRequester REPLACES the path —
  verified); streaming SSE
- baseUrl: `https://generativelanguage.googleapis.com` (HOST ONLY — no /v1beta)
- auth: X_API_KEY, `keyHeader: "x-goog-api-key"` (NO Bearer — verified; Bearer → 401)
- endpoints (all verified): chat=`/v1beta/openai/chat/completions`, models=`/v1beta/models`,
  embeddings=`/v1beta/embeddings` (POST, compat), batches=`/v1beta/openai/batches`,
  files=`/v1beta/files` (⚠ 404s on free-tier key — documented, not a blocker),
  interactions=`/v1beta/openai/interactions` (Interactions API — x-goog-api-key only)
- capabilities: chat, embeddings, imagesGenerate (nano-banana via compat), moderation?,
  voice (Gemini Live — separate VOICE_REALTIME config, ws + bidiGenerateContent v1alpha)
- Knobs: generationConfig passthrough via `generation_config`?? → use D1 fields; `thinking`
  maps to `reasoning_effort`; response_format json_object works.
- Quirks: streaming → SSE `data:{choices:[…]}` + `[DONE]`; keepalives absent; model ids
  `gemini-3.6-flash`, `gemini-3.8-*` (2026).
```json
{"id":"google-ai-studio","name":"Google AI Studio","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://generativelanguage.googleapis.com",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"x-goog-api-key"},
 "endpoints":{"chat":"/v1beta/openai/chat/completions","models":"/v1beta/models",
   "embeddings":"/v1beta/embeddings","batches":"/v1beta/openai/batches",
   "files":"/v1beta/files","interactions":"/v1beta/openai/interactions"},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true,"responses":false},
 "catalog":{"mode":"AUTO","path":"/v1beta/models"},"streaming":"SSE"}
```

## #004 — Google Vertex AI (Gemini native) 🟡
- dialect: GEMINI; streaming SSE (alt=sse)
- baseUrl: `https://{REGION}-aiplatform.googleapis.com`
- auth: OAUTH2 service-account (access token via metadata/JWT) → send as BEARER at runtime;
  config: scheme OAUTH2 (core must plug the token exchange), fallback `apiKey: ""` + portal hook
- endpoints: chat default `POST /v1beta/models/{model}:generateContent` (D3 default),
  models=`/v1beta/models` (list w/ publishers), embeddings=`/v1beta/models/{model}:embedContent`,
  imagesGenerations=`/v1beta/publishers/google/models/{model}:predict` (Imagen)
- capabilities: chat, embeddings, imagesGenerate, voice (Gemini Live via bidi WS)
- Knobs: `generationConfig{stopSequences,temperature,topP,topK,maxOutputTokens,
  responseMimeType:"application/json",responseSchema,candidateCount,thinkingConfig{thinkingBudget}}`,
  `safetySettings[{category,threshold}]`, `systemInstruction{parts[{text}]}`,
  `tools[{functionDeclarations}]`,`toolConfig`, `contents[{role,parts[{text|inline_data{mime_type,data}|file_data|functionCall|functionResponse}]}]`
- Stream: `data: {}` chunks; `safetyRating` blocks surfaced as `promptFeedback.blockReason`.
```json
{"id":"google-vertex","name":"Google Vertex AI","dialect":"GEMINI",
 "baseUrl":"https://us-central1-aiplatform.googleapis.com",
 "auth":{"scheme":"OAUTH2","tokenUrl":"https://oauth2.googleapis.com/token",
   "clientId":"","clientSecret":"","scopes":["https://www.googleapis.com/auth/cloud-platform"]},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "catalog":{"mode":"AUTO","path":"/v1beta/models"},"streaming":"SSE"}
```

## #005 — xAI (Grok) 🟡
- dialect: OPENAI_COMPAT; streaming SSE; also D2 skin at `/anthropic` (same key)
- baseUrl: `https://api.xai.com/v1`
- auth: BEARER `xai-…`
- capabilities: chat, imagesGenerate (grok-2-image/aurora via `grok-3-image`)
- Knobs: `reasoning_effort`, `stream_options.include_usage`, `max_completion_tokens`;
  image: `response_format:{type:"image_url"}`
- Quirks: model ids `grok-3`, `grok-4-fast`; D2 skin: `https://api.xai.com/anthropic`,
  `anthropic-version: 2023-06-01`, same x-api-key.
```json
{"id":"xai","name":"xAI Grok","dialect":"OPENAI_COMPAT","baseUrl":"https://api.xai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"imagesGenerate":true},"streaming":"SSE"}
```

## #006 — Mistral 🟡 (base VERIFIED via docs; models refreshed 2026)
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.mistral.ai/v1`
- auth: BEARER
- endpoints: chat default, embeddings=/embeddings, models=/models, rerank=/rerank,
  moderation=/moderations, completions=/fim/completions (FIM!), agents via `model: agent:…`
- capabilities: chat, embeddings, rerank, moderation, completions, stt (Voxtral)
- Knobs: `safe_prompt`, `random_seed`, `min_tokens`? (FIM `suffix`), `response_format json_schema`
- Quirks (docs.mistral.ai 2026-09): model ids now versioned full names —
  `mistral-medium-3-5-26-04`, `mistral-small-4-0-26-03`, `voxtral-mini-transcribe-26-02` (audio
  input), `ocr-4-1`; Mistral also hosts third-party open weights (Z.ai GLM 5.3, 1M ctx).
```json
{"id":"mistral","name":"Mistral","dialect":"OPENAI_COMPAT","baseUrl":"https://api.mistral.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true,"moderation":true,"completions":true,
   "stt":true},
 "streaming":"SSE"}
```

## #007 — Cohere 🟡
- dialect: OPENAI_COMPAT (compat endpoint) — native /v1/chat is D7; ship both entries
- baseUrl: `https://api.cohere.com` (compat: `https://api.cohere.com` + path /v1/chat/completions? —
  Cohere OpenAI-compat: `https://api.cohere.com/v1` exists; native chat = POST /v1/chat)
- auth: BEARER
- capabilities: chat(native/compat), embeddings(private networks?), rerank, moderation,
  generate (completions), classify, tokenize
- Knobs(native): `connectors`, `documents[]`, `return_prompt`, `safe_prompt`, `k`,`p`,
  `frequency_penalty`,`presence_penalty`, `tools[]`, `conversation_id`, `preamble`;
  embed: `input_type` (search_document|search_query|classification|clustering),
  `truncate`, `embedding_types` (int8|uint8|float|binary); rerank: `query`,`documents`,
  `top_n`,`rank_fields`,`return_documents`
- Quirks: native /v1/chat returns `{text, generation_id, citations, documents}` (NOT D1 choices!);
  streaming native = SSE w/ `event_type: text-generation|stream-end` (D7 template).
```json
{"id":"cohere","name":"Cohere","dialect":"OPENAI_COMPAT","baseUrl":"https://api.cohere.com",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"rerank":true,"moderation":true,"embeddings":false},
 "endpoints":{"chat":"/v1/chat/completions","rerank":"/v1/rerank","embeddings":"/v1/embed"},
 "streaming":"SSE"}
```

## #008 — DeepSeek 🟡 (VERIFIED 2026-09: docs)
- dialect: OPENAI_COMPAT; SSE (no usage in stream unless include_usage; pipes `reasoning_content`)
- baseUrl: `https://api.deepseek.com` (VERIFIED — no /v1 needed; both work)
- auth: BEARER sk-
- capabilities: chat, completions (FIM beta), vision (deepseek-vl), responses (D5 mirror),
  files, context caching; NO embeddings/images
- Knobs (VERIFIED): `thinking:{type:"enabled"}` (top-level), `reasoning_effort`,
  `max_tokens` (NOT max_completion_tokens), `stream_options`, `response_format json_object`;
  stream delta includes `reasoning_content` + `content`
- Models (VERIFIED): `deepseek-flash` (legacy `deepseek-v4-flash`, `deepseek-v4-flash-vision-exp`
  still accepted, retired — served by V4.1 Flash), `deepseek-v4-pro`
- Quirks: anthropic-compat at `https://api.deepseek.com/anthropic` (verified path, same key);
  Agent harness integration; vision guide exists.
```json
{"id":"deepseek","name":"DeepSeek","dialect":"OPENAI_COMPAT","baseUrl":"https://api.deepseek.com",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"responses":true},"streaming":"SSE"}
```

## #009 — AI21 (Jamba) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.ai21.com/v1`
- auth: BEARER
- capabilities: chat (jamba-1.5), embeddings, rerank, moderation?, summarization (separate)
- Quirks: model `jamba-1.5-mini/large`; `max_tokens` required-ish; rerank at /v1/rerank.
```json
{"id":"ai21","name":"AI21 Jamba","dialect":"OPENAI_COMPAT","baseUrl":"https://api.ai21.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true},"streaming":"SSE"}
```

## #010 — NVIDIA NIM 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://integrate.api.nvidia.com/v1`
- auth: BEARER `nvapi-…`
- capabilities: chat, embeddings (nvidia/nv-embed-qa), rerank (nvidia/nv-rerank-qa)
- Knobs: standard D1; some models need `stream_options`; `nvidia/nemotron-*`
- Quirks: model ids `nvidia/llama-3.1-nemotron-70b-instruct` style; hosted via build.nvidia.com.
```json
{"id":"nvidia-nim","name":"NVIDIA NIM","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://integrate.api.nvidia.com/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true},"streaming":"SSE"}
```

## #011 — IBM watsonx 🟡
- dialect: TEMPLATE native + D1-compat; two entries
- native baseUrl: `https://{region}.ml.cloud.ibm.com` ; auth: auth.type=iam oauth2 → BEARER
- native chat: POST `/ml/v1/text/generation` (D7: model_id, input, parameters{decoding_method,
  min/max_new_tokens, temperature, top_p, top_k, repetition_penalty, stop_sequences, time_limit})
- D1-compat: POST `/ml/v1/chat/completions` (OpenAI-compat on watsonx.ai; `model_id` in body)
- embeddings: `/ml/v1/embeddings`
- Capabilities: chat, embeddings.
```json
{"id":"watsonx","name":"IBM watsonx","dialect":"TEMPLATE",
 "baseUrl":"https://us-south.ml.cloud.ibm.com",
 "auth":{"scheme":"OAUTH2","queryParam":"access_token"},
 "endpoints":{"chat":"/ml/v1/chat/completions","embeddings":"/ml/v1/embeddings",
   "models":"/ml/v1/foundation_model_specs"},
 "capabilities":{"chat":true,"embeddings":true},
 "catalog":{"mode":"STATIC","models":[]},"streaming":"SSE"}
```

## #012 — Amazon Bedrock 🟡
- dialect: BEDROCK (native Converse) + D1-compat runtime (second config)
- baseUrl: `https://bedrock-runtime.{REGION}.amazonaws.com`
- auth: SIGV4 (access key/secret/session, region, service=bedrock) — core must implement
- endpoints: chat=POST `/model/{modelId}/converse|converse-stream` (D4 default),
  models=GET `/foundation-models` (list-foundation-models)
- Body (Converse): `{modelId, messages[{role,content:[{text|image{source{bytes}}|toolUse|
  toolResult}]}], system:[{text}], inferenceConfig{maxTokens,temperature,topP,stopSequences},
  toolConfig{tools:[{toolSpec}]}, additionalModelRequestFields}` (model-specific passthrough)
- Stream: `application/vnd.amazon.eventstream` (headers + payload chunks) — EVENTSTREAM
- D1-compat config: baseUrl `https://bedrock-runtime.{R}.amazonaws.com` + `/openai` path? —
  Bedrock OpenAI-compat runtime: `POST /model/{id}/invoke` legacy or compat endpoint
  `https://bedrock-runtime.{region}.amazonaws.com/openai/v1/chat/completions` (2025+; verify).
- Capabilities: chat, embeddings (Titan), imagesGenerate (Nova/Stable), video (Nova Reel), audio (TTS).
```json
{"id":"bedrock","name":"Amazon Bedrock","dialect":"BEDROCK",
 "baseUrl":"https://bedrock-runtime.us-east-1.amazonaws.com",
 "auth":{"scheme":"SIGV4","apiKey":"","region":"us-east-1","service":"bedrock",
   "secretKey":"","sessionToken":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "catalog":{"mode":"STATIC","path":"/foundation-models"},"streaming":"EVENTSTREAM",
 "aliases":{"claude-3.5-sonnet":"anthropic.claude-3-5-sonnet-20241022-v2:0"}}
```

## #013 — Azure OpenAI 🟡
- dialect: AZURE_OPENAI (=D1 + azure conventions)
- baseUrl: `https://{RESOURCE}.openai.azure.com/openai`
- auth: X_API_KEY keyHeader `api-key` (+ queryParams `{"api-version":"2024-10-21"}`); Entra
  OAUTH2 optional
- endpoints: chat=`/deployments/{deployment}/chat/completions` — deployment name replaces model
  (model field in body optional; if set it must match deployment) — portal hosts the alias map
- capabilities: chat, completions, embeddings, imagesGenerate (dall-e-3 compatible n/a; gpt-image),
  audio speech/transcriptions, batch (with api-version 2024-07+)
- Quirks: `azure/` model prefix convention; model→deployment alias via `aliases`; 429 has
  `retry-after-ms`.
```json
{"id":"azure-openai","name":"Azure OpenAI","dialect":"AZURE_OPENAI",
 "baseUrl":"https://my-resource.openai.azure.com/openai",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"api-key",
   "queryParams":{"api-version":"2024-10-21"}},
 "capabilities":{"chat":true,"completions":true,"embeddings":true,"tts":true,"stt":true},
 "streaming":"SSE"}
```

## #014 — Oracle OCI Generative AI 🟡
- dialect: TEMPLATE (sig — OCI Signing; port as custom auth)
- baseUrl: `https://generativeai.{REGION}.oci.oraclecloud.com`
- endpoints: chat=`/20231130/actions/chat`, text=`/20231130/actions/generateText`,
  summarization, embeddings=`/20231130/actions/embedText`
- Body: `{compartmentId (REQUIRED), servingMode:{modelId,modelType:"TEXT"}, messages:[{role,content}]}`
- Auth: OCI Signature v1 Authorization header (key fingerprint + RSA + host/date headers) —
  implement as SIGV4-style scheme with service=generativeai.
- Capabilities: chat, embeddings, completions. Model list via `GET /20231130/models`.
```json
{"id":"oci-genai","name":"Oracle OCI GenAI","dialect":"TEMPLATE",
 "baseUrl":"https://generativeai.us-ashburn-1.oci.oraclecloud.com",
 "auth":{"scheme":"OAUTH2","apiKey":""},
 "endpoints":{"chat":"/20231130/actions/chat","embeddings":"/20231130/actions/embedText",
   "models":"/20231130/models"},
 "capabilities":{"chat":true,"embeddings":true},"streaming":"SSE"}
```

## #015 — Perplexity 🟡 (VERIFIED 2026-09: platform overhaul)
- dialect: OPENAI_COMPAT (legacy sonar, DEPRECATED) + RESPONSES-style Agent API (new)
- baseUrl: `https://api.perplexity.ai` (+ `/v1` prefix on new endpoints)
- auth: BEARER `pplx-…`
- **Platform change (docs.perplexity.ai, 2026-09):** Sonar Chat Completions is being
  retired; migrate by **2026-09-27**. New surface:
  - **Agent API** — `POST https://api.perplexity.ai/v1/agent` (Responses-style body:
    `{model, input, tools:[{type:"web_search", filters:{search_domain_filter[],
    search_recency_filter:"month"}}], instructions, response_format:{type:"json_schema"…}}`);
    models routed from third parties (`openai/gpt-5.5`, plus sonar/preset ids). Maps to
    RESPONSES dialect with `endpoints.responses="/v1/agent"` + chat=false.
  - **Router API** — OpenAI-compatible (`/v1/chat/completions`?) for open-weight models
    hosted by Perplexity (verify exact path on /docs/router/quickstart).
  - **Search API** — `POST https://api.perplexity.ai/search` (query[] → ranked results) — TEMPLATE.
  - **Embeddings API** — NEW; capabilities.embeddings=true (was wrongly false).
- Knobs (agent): tools web_search + filters; legacy sonar knobs (`return_citations`,
  `web_search_options{search_context_size}`) still valid during migration window.
- no vision on sonar text models.
```json
{"id":"perplexity","name":"Perplexity","dialect":"OPENAI_COMPAT","baseUrl":"https://api.perplexity.ai",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},
 "endpoints":{"responses":"/v1/agent"},"streaming":"SSE"}
```

## #016 — OpenRouter 🟡
- dialect: OPENAI_COMPAT; SSE (routes per request to upstream dialect — D1 only for gateway)
- baseUrl: `https://openrouter.ai/api/v1`
- auth: BEARER `sk-or-…`
- capabilities: chat, completions?, imagesGenerate (some), embeddings (route models);
  D5 relay: POST /api/v1/responses (mirrors RESPONSES dialect; separate config or same with
  endpoints.responses=/responses)
- Knobs: top-level `provider:{order:[],allow_fallbacks,ignore_errors,data_collection,
  quantizations,only_allow_*}
  `, `route:"fallback"`, `transforms:{send_thinking,enable_reasoning,chain_of_thought}`,
  `models:"anthropic/claude-sonnet-4.5"` (vendor/model slug), `plugins`, `http_referer`/`X-Title`
  headers, `notify` webhook
- Quirks: chat completion metadata `{usage, provider, model, cost}` in response; `[DONE]` present;
  some upstreams send `data: ping` — tolerate. Rate limit: `X-RateLimit-*` headers.
```json
{"id":"openrouter","name":"OpenRouter","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://openrouter.ai/api/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"imagesGenerate":true,"embeddings":true},
 "catalog":{"mode":"AUTO"},"streaming":"SSE"}
```

## #017 — Groq 🟡 (base VERIFIED via docs)
- dialect: OPENAI_COMPAT; SSE (+ Responses API at /v1/responses — D5 mirror)
- baseUrl: `https://api.groq.com/openai/v1` (VERIFIED)
- auth: BEARER `gsk_…`
- capabilities: chat, stt (whisper at /audio/transcriptions), tts (kokoro/orpheus at
  /audio/speech), vision (llama-4-scout/maverick, OCR models), moderation (content
  moderation API), structured outputs, prompt caching, LoRA inference, batch
- Knobs: `reasoning_format:"hidden|visible"` (llama-3.3-70b reasoning), `max_tokens`,
  `temperature`,`top_p`,`stream_options`, built-in tools (web_search, code_execution,
  wolfram_aplha remotely), `service_tier`/flex/batch modes.
- Quirks: model `llama-3.3-70b-versatile` (docs example); 429 `X-Ratelimit-*` headers.
```json
{"id":"groq","name":"Groq","dialect":"OPENAI_COMPAT","baseUrl":"https://api.groq.com/openai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"stt":true,"tts":true,"moderation":true,"responses":true},
 "streaming":"SSE"}
```

## #018 — Together AI 🟡 (VERIFIED 2026-09: docs)
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.together.ai/v1` (VERIFIED)
- auth: BEARER
- capabilities: chat (serves MiniMax-M3, GLM, Qwen, Llama among 100+), embeddings
  (togethercomputer/m2-embed), imagesGenerate (FLUX at /images/generations), vision,
  fine-tuned/dedicated endpoints, batch
- Knobs: `max_tokens`, `stop`, `logprobs`, `response_format json_schema`, `seed`;
  **reasoning models return thinking in `delta.reasoning`** (NOT reasoning_content — pin!
  e.g. MiniMaxAI/MiniMax-M3)
- Quirks: model ids HF-style `MiniMaxAI/MiniMax-M3`; image via D1 image_url parts works.
```json
{"id":"together","name":"Together AI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.together.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},"streaming":"SSE"}
```

## #019 — Fireworks AI 🟡 (VERIFIED 2026-09: docs)
- dialect: OPENAI_COMPAT; SSE (+ Responses API — D5 mirror)
- baseUrl: `https://api.fireworks.ai/inference/v1` (VERIFIED)
- auth: BEARER
- capabilities: chat, vision, embeddings, imagesGenerate (SDXL/FLUX via /images/generations),
  responses, batch, fine-tuned + dedicated deployments
- Knobs (VERIFIED): default max_tokens 2048; `top_k`,`min_p`,`typical_p`,`frequency_penalty`,
  `presence_penalty`,`repetition_penalty`, `logprobs/top_logprobs`, `echo`, `return_token_ids`,
  `raw_output`, `ignore_eos`, `logit_bias`, `mirostat_target/mirostat_lr`, `n`,
  `perf_metrics_in_response` (streaming perf), service_tier "priority"/fast serving paths;
  usage always in final stream chunk (Fireworks extension)
- Quirks: model ids `accounts/fireworks/models/deepseek-v3p1`; deployments
  `accounts/{ACCOUNT_ID}/deployments/{DEPLOYMENT_ID}`.
```json
{"id":"fireworks","name":"Fireworks AI","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.fireworks.ai/inference/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true,"responses":true},
 "streaming":"SSE"}
```

## #020 — Cerebras 🟡 (base VERIFIED via docs; catalog moved to qwen-3.x 2026)
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.cerebras.ai/v1` (VERIFIED: /chat/completions, Bearer)
- auth: BEARER
- capabilities: chat; tool calling, streaming, structured outputs, reasoning — all
  documented; no embeddings/images
- Knobs: standard D1; docs examples use plain `max_tokens` (accepts both).
- Quirks: model `qwen-3.8-27b` (docs example 2026-09); 404 "model not found" if deprecated;
  free tier low RPM.
```json
{"id":"cerebras","name":"Cerebras","dialect":"OPENAI_COMPAT","baseUrl":"https://api.cerebras.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #021 — SambaNova Cloud 🟡
- dialect: OPENAI_COMPAT (primary) + ANTHROPIC skin (`/anthropic`?)
- baseUrl: `https://api.sambanova.ai/v1`
- auth: BEARER
- capabilities: chat only (llama-4-*); tool calls via openai schema
- Quirks: model ids `Meta-Llama-3.3-70B-Instruct` (SambaNova naming without vendor prefix? —
  uses `Meta-Llama-…` style); `stream_options`.
```json
{"id":"sambanova","name":"SambaNova","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.sambanova.ai/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #022 — Lambda (Lambda Chat) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.lambdalabs.com/v1`
- auth: BEARER (Lambda API key)
- capabilities: chat (hermes-3, llama-4); no embeddings/images.
```json
{"id":"lambda","name":"Lambda Chat","dialect":"OPENAI_COMPAT","baseUrl":"https://api.lambdalabs.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #023 — Novita AI 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.novita.ai/v3/openai` (the /v3/openai IS the v1 prefix — paths append /v1)
- endpoints: chat=`/v1/chat/completions` (→ full /v3/openai/v1/chat/completions),
  models=`/v1/models`, imagesGenerations=`/v1/images/generations`
- auth: BEARER
- capabilities: chat, imagesGenerate (flux, sd), video (async — template)
- Knobs: image `height/width`,`num_inference_steps`,`guidance_scale`,`style_preset`,
  `negative_prompt`; chat standard.
```json
{"id":"novita","name":"Novita AI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.novita.ai/v3/openai",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"imagesGenerate":true},
 "endpoints":{"chat":"/v1/chat/completions","models":"/v1/models",
   "imagesGenerations":"/v1/images/generations"},
 "streaming":"SSE"}
```

## #024 — DeepInfra 🟡 (base + shape VERIFIED via docs)
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.deepinfra.com/v1/openai` (VERIFIED — includes /v1/openai suffix)
- auth: BEARER
- capabilities: chat (100+ open models incl. DeepSeek-V4-Flash-0731), embeddings, RERANK
  (now first-class), vision, stt (Whisper at /v1/audio/transcriptions), tts, image gen
  (FLUX/SD at /v1/images/generations), video gen, private deployments + GPU rental
- Knobs: `deepinfra_source:"api"`, `stop`; model ids `deepseek-ai/DeepSeek-V4-Flash-0731`
  (HF-style)
- Quirks: most models on OpenRouter; SSE [DONE] present.
```json
{"id":"deepinfra","name":"DeepInfra","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.deepinfra.com/v1/openai","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true,"stt":true,"tts":true,
   "imagesGenerate":true},
 "streaming":"SSE"}
```

## #025 — Baseten 🟡
- dialect: OPENAI_COMPAT (per-deployment)
- baseUrl: `https://app.baseten.co` (per-model: `https://model-{id}.api.baseten.co`)
- auth: BEARER `–` (Baseten API key)
- endpoints: chat=`/v1/chat/completions` (per deployment), predict=`/v1/predict` (D7 async
  for multimodal deps)
- capabilities: chat per deployed model; vision/image models as BYO
- Quirks: `model` in body must be NOT set or the deployed model name; tasks via poll
  `/v1/tasks/{task_id}` for image/video (async — D7).
```json
{"id":"baseten","name":"Baseten","dialect":"OPENAI_COMPAT","baseUrl":"https://app.baseten.co",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #026 — Anyscale Endpoints 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.endpoints.anyscale.com/v1`
- auth: BEARER (anyscale key; legacy `es_` prefix — gone 2025)
- capabilities: chat, completions, embeddings
- Quirks: model ids `meta-llama/…` style; sunset advisory — legacy Anyscale Endpoints EOL'd 2025,
  migrated to Anyscale Serverless; keep for legacy configs.
```json
{"id":"anyscale","name":"Anyscale","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.endpoints.anyscale.com/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #027 — Lepton AI 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.lepton.ai` (workspace base `https://{workspace}.lepton.run`? — verify)
- auth: BEARER
- capabilities: chat, imagesGenerate (lepton-hosted flux/sd at /v1/images/generations? verify)
- Quirks: model ids `lepton/…`, tight /v1 paths.
```json
{"id":"lepton","name":"Lepton AI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.lepton.ai",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #028 — Replicate 🟡
- dialect: TEMPLATE (async predictions) — primary; OpenAI-compat for select chat models
- baseUrl: `https://api.replicate.com/v1`
- auth: BEARER `r8_…`
- endpoints: create=`POST /predictions`, get=`GET /predictions/{id}`, cancel=
  `POST /predictions/{id}/cancel`, stream via `output.urls.stream` (binary chunks),
  models list `GET /models`
- Body: `{version (model hash), input:{knobs per model}, webhook}` — knob shapes are
  model-specific (declare per-model templates)
- Capabilities: virtually all model types (chat, image, video, audio) — each a template.
- Quirks: no unified chat body; predictions return immediately with `status: starting` →
  poll `GET /predictions/{id}` until succeeded. 2026: Replicate exposes OpenAI-compat endpoint
  `/v1/chat/completions` for select models (verify before enabling).
```json
{"id":"replicate","name":"Replicate","dialect":"TEMPLATE","baseUrl":"https://api.replicate.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"tasks":"/predictions","files":"/predictions"},
 "capabilities":{"chat":true,"imagesGenerate":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #029 — HuggingFace Inference Providers 🟡
- dialect: OPENAI_COMPAT on the unified router + TEI (embeddings/rerank, D7/D1)
- baseUrl: `https://router.huggingface.co/v1` (unified — 2025+; live)
- auth: BEARER `hf_…` (+ optional `X-Use-Cache:false`, `X-Wait-For-Model:true`)
- capabilities: chat (all provider-models via router: `openai/gpt-4o-mini`,
  `together/…`, `cohere/…`), embeddings (via router? verify), TEI routes
- Dedicated Inference Endpoints: baseUrl `https://{endpoint-id}.{region}.{cloud}.endpoints.hf.co`,
  D1 at `/v1/chat/completions`, or native `/generate|/generate_stream`, `/embed`, `/rerank`
- Quirks: router model slugs `{provider}/{model}`; ratelimit `x-ratelimit-*`.
```json
{"id":"hf-inference","name":"HuggingFace Inference","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://router.huggingface.co/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},"streaming":"SSE"}
```

## #030 — Cloudflare Workers AI 🟢
- dialect: OPENAI_COMPAT (chat) + TEMPLATE (media) — two configs share the account base
- baseUrl: `https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}/ai/v1`
- auth: BEARER CF API token (needs Workers AI permission)
- endpoints (verified): chat=`/chat/completions` (openai-compat), models=
  `/models/search` (GET; `?per_page=&page=`), embeddings (native `/run/{model}` D7),
  images: POST `/images/generations`? — verified path used in tests: template-driven
  `/run/{model}` for flux-2-klein (multipart input) & SDXL (json input) — pin imageInput/
  imageOutput per model (our TemplateMediaProvider: flux-2-klein multipart, SDXL json)
- capabilities: chat, embeddings (bge), imagesGenerate, tts (via /run/{model} — template)
- Knobs: `stream:true`; model ids `@cf/meta/llama-3.3-70b-instruct-fp8-fast`,
  `@cf/black-forest-labs/flux-1-schnell`, `@cf/bytedance/stable-diffusion-xl-lightning`;
  free tier aggressive 429.
- Quirks: chat returns `{result:{response}}` vs openai `choices[]` on `stream:false` (the
  compat endpoint maps; verify per model); gate streaming via `stream:true`.
```json
{"id":"cloudflare-workers-ai","name":"Cloudflare Workers AI","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"chat":"/chat/completions","models":"/models/search"},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "catalog":{"mode":"STATIC","path":"/models/search"},"streaming":"SSE"}
```