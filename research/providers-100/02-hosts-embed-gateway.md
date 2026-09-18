# Top-100 → 02-hosts-embed-gateway.md (#031–#051)

---

## #031 — GitHub Models 🔴 RETIRED (2026-07-30) — do not configure
- VERIFIED via docs.github.com/github-models: GitHub Models (playground, model catalog,
  inference API, BYOK) was **fully retired July 30, 2026** — no longer available to any
  customer. Any portal entries must be migrated.
- successor: Azure AI Foundry (ai.azure.com) — broad model catalog, OpenAI-compat
  endpoints; config pattern = Azure OpenAI (D8) or D1 per endpoint.
- Config below kept for historical reference ONLY (`enabled:false`):
```json
{"id":"github-models","name":"GitHub Models (RETIRED)","enabled":false,
 "dialect":"OPENAI_COMPAT","baseUrl":"https://models.inference.ai.azure.com",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"moderation":true},"streaming":"SSE"}
```

## #032 — Nebius AI Studio 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.studio.nebius.ai/v1`
- auth: BEARER
- capabilities: chat, embeddings, imagesGenerate (FLUX hosted at /images/generations? verify —
  also `/v1/text-to-image`? use D1 images path), rerank?? (verify)
- Quirks: model ids `meta-llama/…`, `mistralai/…`, `neversleep/…`; EU/GDPR positioning.
```json
{"id":"nebius","name":"Nebius AI Studio","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.studio.nebius.ai/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},"streaming":"SSE"}
```

## #033 — SiliconFlow 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.siliconflow.cn/v1` (CN) · `https://api.siliconflow.com/v1` (intl)
- auth: BEARER `sk-…`
- capabilities: chat, embeddings (BAAI/bge, Qwen), imagesGenerate (flux/sd at
  /images/generations), tts (fishaudio/fish-speech via /audio/speech — verify model naming),
  stt (via /audio/transcriptions — Qwen2-Audio, sensevoice)
- Knobs: standard D1; image `image_size`,`batch_size`,`num_inference_steps`,`guidance_scale`;
  TTS `voice` (fish-speech voice ids), `language`.
- Quirks: model ids both `Qwen/Qwen2.5-72B-Instruct` (HF-style) and `deepseek-ai/…`;
  free tier rate-limited hard.
```json
{"id":"siliconflow","name":"SiliconFlow","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.siliconflow.cn/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true,"tts":true,"stt":true},
 "streaming":"SSE"}
```

## #034 — freeinference 🟢 (catalog-shape reference provider)
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://global.freeinference.ai` (verify exact host from portal; catalog rich shape)
- auth: BEARER
- capabilities: chat, embeddings, imagesGenerate; responses passthrough
- Catalog: the TARGET catalog shape — `GET {base}/models` returns
  `[{id, context_length, max_output_length, input_modalities[], output_modalities[],
  supported_features[]}]` → maps 1:1 onto `CatalogModel`. `catalog.mode=AUTO`,
  `capabilities` left default and OVERLAID by inference.
- Knobs: standard D1; reasoning models surface `reasoning_content`.
- Quirks: `/v1` prefix may be optional — pin `endpoints.chat` if host differs.
```json
{"id":"freeinference","name":"freeinference","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://global.freeinference.ai/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},
 "catalog":{"mode":"AUTO","path":"/models","ttlSeconds":3600},"streaming":"SSE"}
```

## #035 — Pollinations 🟡
- dialect: OPENAI_COMPAT (text) — auth NONE
- baseUrl: `https://text.pollinations.ai`
- endpoints: chat=`/openai` (POST D1 body; NO /chat/completions suffix), GET `/{prompt}`
  (query style, `?model=&system=&temperature=`)
- auth: NONE (public; optional `Authorization` for tiers? — docs say no key needed for base)
- capabilities: chat (many open models), vision (via model id); images at separate host
  `image.pollinations.ai/{prompt}` (GET binary — TEMPLATE config)
- Quirks: model ids `openai`, `mistral`, `llama`, `qwen-coder` aliased; streaming via
  `stream:true` SSE; some endpoints return markdown — set `referrer`.
```json
{"id":"pollinations-text","name":"Pollinations Text","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://text.pollinations.ai","auth":{"scheme":"NONE","apiKey":""},
 "endpoints":{"chat":"/openai"},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #036 — Cline API 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.cline.bot`
- auth: BEARER `sk-ant-cline-…`? — Cline API uses Anthropic-compat `/v1/messages`
  with `x-api-key` (D2) — VERIFY current shape; historically it mirrors Anthropic.
- capabilities: chat (claude sonnet/opus via cline router)
- Quirks: model ids `claude-sonnet-4`, `claude-opus-4`; billing per Cline account.
```json
{"id":"cline-api","name":"Cline API","dialect":"ANTHROPIC","baseUrl":"https://api.cline.bot",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"x-api-key",
   "extraHeaders":{"anthropic-version":"2023-06-01"}},
 "capabilities":{"chat":true},"catalog":{"mode":"STATIC","models":[]},"streaming":"SSE"}
```

## #037 — Zen (opencode.ai) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.opencode.ai`
- auth: BEARER
- capabilities: chat (anthropic models), embeddings?? (no)
- Quirks: model ids `anthropic/claude-sonnet-4.5` via zen router; verify paths
  (`/v1/chat/completions`).
```json
{"id":"zen","name":"Zen (opencode.ai)","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.opencode.ai","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #038 — Snowflake Cortex 🟡
- dialect: TEMPLATE; SSE
- baseUrl: `https://{ORG}-{ACCOUNT}.snowflakecomputing.com`
- auth: BEARER (Snowflake OAuth/JWT token) + `X-Snowflake-Authorization-Token-Type: KEYPAIR_JWT`
- endpoints: chat=`/api/v2/cortex/inference:complete` (2025) — body
  `{model, messages:[{role,content}], temperature, top_p, max_tokens}`
- capabilities: chat (reality: cortex-llama, mistral-large, claude via contract), embeddings?
  (cortex embed), search.
- Quirks: model ids `snowflake-arctic-embed` (embeddings), `cortex-llama-3.3-70b`;
  gateway often prefers SQL/warehouse integration; HTTP API young — mark verify.
```json
{"id":"snowflake-cortex","name":"Snowflake Cortex","dialect":"TEMPLATE",
 "baseUrl":"https://ORG-ACCOUNT.snowflakecomputing.com","auth":{"scheme":"OAUTH2","apiKey":""},
 "endpoints":{"chat":"/api/v2/cortex/inference:complete"},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #039 — Writer 🟡
- dialect: OPENAI_COMPAT (chat endpoint shaped /v1/chat) — pin as D1 with path override
- baseUrl: `https://api.writer.com/v1`
- auth: BEARER (+ `X-Request-Id` recommended; some endpoints require `application_id` in body)
- endpoints: chat=`/chat`, completions=`/completions`, embeddings=`/embeddings`
- capabilities: chat (palmyra), embeddings, completions
- Quirks: `/v1/chat` NOT `/chat/completions`; body `{model, messages, temperature, top_p,
  max_tokens(=2048 default), stop, tools[]}`; streaming SSE `data:` with `[DONE]`.
```json
{"id":"writer","name":"Writer","dialect":"OPENAI_COMPAT","baseUrl":"https://api.writer.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"chat":"/chat","completions":"/completions","embeddings":"/embeddings"},
 "capabilities":{"chat":true,"embeddings":true,"completions":true},"streaming":"SSE"}
```

## #040 — Upstage 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.upstage.ai/v1`
- auth: BEARER
- capabilities: chat (solar-pro), embeddings (solar-embedding-1-large), rerank
  (solar-rerank-1-large at /v1/rerank), document AI (`/document-ai/layout` — D7)
- Knobs: doc-ai `base64`, `model: layout`; chat standard.
```json
{"id":"upstage","name":"Upstage","dialect":"OPENAI_COMPAT","baseUrl":"https://api.upstage.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true},"streaming":"SSE"}
```

## #041 — Reka 🟡
- dialect: OPENAI_COMPAT (2024 migration; legacy X-API-Key header on some hosts — VERIFY)
- baseUrl: `https://api.reka.ai/v1`
- auth: BEARER (fallback `X-API-Key` legacy: set `keyHeader:"X-API-Key"` + scheme X_API_KEY if
  bearer 401s)
- capabilities: chat (reka-flash, reka-core — vision-native)
- Quirks: multimodal through D1 image_url parts; models `reka-flash`, `reka-core`.
```json
{"id":"reka","name":"Reka","dialect":"OPENAI_COMPAT","baseUrl":"https://api.reka.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #042 — Hyperbolic 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.hyperbolic.xyz/v1`
- auth: BEARER
- capabilities: chat, imagesGenerate (flux at /v1/images/generations? verify)
- Quirks: model ids `meta-llama/…` style; gpu rental also offered (out of scope).
```json
{"id":"hyperbolic","name":"Hyperbolic","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.hyperbolic.xyz/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"imagesGenerate":true},"streaming":"SSE"}
```

## #043 — FriendliAI 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.friendli.ai/server/v1`
- auth: BEARER
- capabilities: chat, imagesGenerate (flux via /v1/images/generations — verify)
- Quirks: model ids `meta-llama-3.3-70b`, `flux.1-schnell` HF-style.
```json
{"id":"friendliai","name":"FriendliAI","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.friendli.ai/server/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"imagesGenerate":true},"streaming":"SSE"}
```

## #044 — Akash Network (Console/MLaaS) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://console.akash.network/api/v1` (verify; provider-provided gateways vary)
- auth: BEARER (interchain wallet/Akash key)
- capabilities: chat via hosted open models (llama, qwen, deepseek)
- Quirks: per-deployment base URLs — treat like Baseten (config per deployment id).
```json
{"id":"akash","name":"Akash MLaaS","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://console.akash.network/api/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #045 — Modal 🟡
- dialect: OPENAI_COMPAT (BYO endpoint)
- baseUrl: user-supplied (per deployment, e.g. `https://{user}--{app}-serve.modal.run`)
- auth: Modal token OR none (public deployments)
- capabilities: chat/whatever the deployed function exposes (BYO)
- Quirks: not a model vendor — a compute host; config = endpoint per deployment; streaming
  depends on the app (usually SSE).
```json
{"id":"modal","name":"Modal (BYO)","dialect":"OPENAI_COMPAT","baseUrl":"https://USER--APP.modal.run",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #046 — Nomic (Atlas / embeddings) 🟡
- dialect: OPENAI_COMPAT (embeddings + chat?)
- baseUrl: `https://api-atlas.nomic.ai/v1`
- auth: BEARER `nk-…`
- capabilities: embeddings (nomic-embed-text-v1.5 — 768 dims, matryoshka), chat (nomic-point?
  verify)
- Knobs: `task_type:"search_document|search_query|clustering|classification"`,
  `dimensions` (64/128/512/768), `truncate:true`
```json
{"id":"nomic","name":"Nomic Atlas","dialect":"OPENAI_COMPAT","baseUrl":"https://api-atlas.nomic.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":false,"embeddings":true},
 "endpoints":{"embeddings":"/embeddings"},"streaming":"SSE"}
```

## #047 — Voyage AI 🟡 (VERIFIED 2026-09: docs.voyageai.com)
- dialect: OPENAI_COMPAT (embeddings + rerank); SSE n/a
- baseUrl: `https://api.voyageai.com/v1`
- auth: BEARER `pa-…`
- endpoints: embeddings=`/v1/embeddings`, rerank=`/v1/rerank` (VERIFIED paths)
- capabilities: embeddings, rerank (chat=false); **multimodal embeddings** (text, image,
  audio, video, tabular) — voyage-3-series; state-of-the-art retrieval claim
- Knobs: `input_type:"document|query"` (recommended pairing), `output_dimension`
  (voyage-3-lite: 512 default), `truncation:"true|false|left|right"`; rerank: `query`,
  `documents`, `top_k`, `return_documents`, `model:"voyage-rerank-2"`
- Quirks: input as list; batch limits apply; docs llms.txt at docs.voyageai.com/llms.txt.
```json
{"id":"voyage","name":"Voyage AI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.voyageai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":false,"embeddings":true,"rerank":true},
 "endpoints":{"embeddings":"/embeddings","rerank":"/rerank"},"streaming":"SSE",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #048 — Jina AI 🟡 (VERIFIED 2026-09: jina.ai/embeddings)
- dialect: OPENAI_COMPAT (embeddings/rerank) + D7 (reader r.jina.ai, search s.jina.ai)
- baseUrl: `https://api.jina.ai/v1`
- auth: BEARER `jina_…` (verified: Authorization: Bearer)
- capabilities: embeddings (v5-text small/nano — 32K/8K ctx, multilingual), rerank
  (jina-reranker-v3.5), **multimodal embeddings** (v5-omni: text/image/audio/video/PDF in one
  space — input typed objects `{image:{url}}` etc., one PDF per request), reader,
  search (s.jina.ai — token-costed)
- Knobs (VERIFIED): `normalized:true` (L2), `embedding_type:"float"|"binary"|"base64"`
  (API also exposes `encoding_format`/`output_dtype`/`embedding_types` variants),
  `truncate:true`, task-specific LoRA adapters (v5-text), Matryoshka dims;
  rerank `query`,`documents`,`top_n`,`return_documents`
- Quirks: rate limits RPM/TPM per key tier (free 100 RPM/100k TPM; per-IP without key);
  OpenAPI at api.jina.ai/openapi.json, interactive docs at api.jina.ai/scalar; v5-omni
  embeddings are byte-compatible with v5-text (no reindexing).
```json
{"id":"jina","name":"Jina AI","dialect":"OPENAI_COMPAT","baseUrl":"https://api.jina.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":false,"embeddings":true,"rerank":true},
 "endpoints":{"embeddings":"/embeddings","rerank":"/rerank"},"streaming":"SSE"}
```

## #049 — mixedbread.ai 🟡
- dialect: OPENAI_COMPAT; SSE n/a
- baseUrl: `https://api.mixedbread.ai/v1`
- auth: BEARER (`X-MB-Api-Key` legacy on some endpoints — verify; keep extraHeaders fallback)
- capabilities: embeddings (mxbai-embed-large-v1 — 1024), rerank (mxbai-rerank-v1)
- Quirks: model `mxbai-embed-large-v1`; rerank bodies like Voyage.
```json
{"id":"mixedbread","name":"mixedbread.ai","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.mixedbread.ai/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":false,"embeddings":true,"rerank":true},
 "endpoints":{"embeddings":"/embeddings","rerank":"/rerank"},"streaming":"SSE"}
```

## #050 — Vercel AI Gateway 🟡
- dialect: OPENAI_COMPAT (proxy/passthrough); SSE
- baseUrl: `https://ai-gateway.vercel.sh/v1`
- auth: BEARER (Vercel token) or none
- capabilities: chat (proxies any provider — route target chosen in portal/create usage)
- Quirks: provider chosen via `X-Vercel-AI-Provider`? historically portal-only route config —
  treat as D1 transparent proxy with per-team URL subdomains? pin verify; model ids upstream.
```json
{"id":"vercel-ai-gateway","name":"Vercel AI Gateway","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://ai-gateway.vercel.sh/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #051 — Clarifai 🟡
- dialect: OPENAI_COMPAT (2024+ openai-compat layer)
- baseUrl: `https://api.clarifai.com/v2/openai`
- auth: BEARER (Clarifai PAT) — fallback `X-API-Key`? verify; PAT standard
- capabilities: chat (hosted llama/qwen via their openai-compat), workflows (D7)
- Quirks: model ids `meta/llama-3.1-70b-instruct` style; full Clarifai platform (workflows,
  inputs, models search) is D7.
```json
{"id":"clarifai","name":"Clarifai","dialect":"OPENAI_COMPAT","baseUrl":"https://api.clarifai.com/v2/openai",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```