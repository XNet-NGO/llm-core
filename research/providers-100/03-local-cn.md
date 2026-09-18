# Top-100 → 03-local-runtimes.md (#052–#065)

All local runtimes: auth NONE by default (add middleware key in front), SSE streaming,
`GET /v1/models` supported. Choose one vendor with pinned ports; the router treats each
as an independent provider config (deploy the same runtime on X machines = X configs).

---

## #052 — Ollama 🟢(D1 path verified in upstream work; NDJSON native)
- dialect: OPENAI_COMPAT (server `/v1`) — native NDJSON listed for completeness
- baseUrl: `http://localhost:11434`  (D1 at `/v1/chat/completions`)
- auth: NONE
- capabilities: chat, embeddings (`/v1/embeddings`; native `/api/embeddings`), vision
  (llava/llama3.2-vision via image_url), tools
- Streaming: SSE on compat path; native `/api/chat` = NDJSON (`StreamFormat.NDJSON`)
- Knobs (compat): `options:{num_ctx, temperature, …}`? — compat path ignores `options`;
  native body: `{model, messages, stream, options{num_ctx,num_predict,temperature,top_k,
  top_p,repeat_penalty,seed,stop}, format:"json", keep_alive, tools}`; `raw:true` for
  prompt-only
- Catalog: `GET /api/tags` (native) or `/v1/models` (compat) — use compat path, AUTO.
- Quirks: model ids `llama3.3:70b` (name:tag); pull-on-demand `Ollama-Model` header?;
  compat path ignores `/api` knobs — pin templates if you need num_ctx.
```json
{"id":"ollama","name":"Ollama","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:11434",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},
 "catalog":{"mode":"AUTO","path":"/v1/models"},"streaming":"SSE"}
```

## #053 — vLLM (OpenAI server) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:8000/v1`
- auth: NONE (optional `Authorization` if served behind gateway)
- capabilities: chat, completions, embeddings (`/v1/embeddings`), batch (`/v1/batches`),
  scoring? (no)
- Knobs: `guided_json`,`guided_regex` via extra_body, `logprobs`,`top_logprobs`,
  `ignore_eos`, `frequency_penalty`,`presence_penalty`,`stop`, `seed`, `tool_choice`,
  `chat_template_kwargs`
- Quirks: `max_model_len` from served model; some builds lack `/v1/models` (fix by config
  STATIC); models served by `--served-model-name`.
```json
{"id":"vllm","name":"vLLM","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:8000/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #054 — SGLang 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:30000/v1`
- auth: NONE
- capabilities: chat, completions, embeddings (OpenAI embeddings format), chat templates
- Knobs: `structured_outputs` (json_schema), `extra_body:{reasoning_content:true}`,
  `top_logprobs`, multi-turn tooling; `/v1/chat/completions` mirrors.
- Quirks: llm.sglang path conventions; model ids match served.
```json
{"id":"sglang","name":"SGLang","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:30000/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #055 — TGI (HuggingFace) 🟡
- dialect: OPENAI_COMPAT (`/v1/chat/completions` on newer builds; legacy `/generate`) +
  native D7 (embeddings `/embed`, rerank `/rerank`)
- baseUrl: `http://localhost:8000` (D1 paths at `/v1/*`), native at `/generate`,
  `/generate_stream`, `/embed`, `/rerank`
- auth: NONE (optional `X-API-Key` if configured)
- capabilities: chat, embeddings, rerank, FIM (`/fim/completions`? verify)
- Knobs: `best_of`,`details:true`,`decoder_input_details`,`repetition_penalty`
  (native params), D1 `max_tokens`,`stream` (SSE `data:{}` w/o [DONE] — tolerate)
- Quirks: /v1/chat/completions added in recent TGI; embeddings model must be encoder.
```json
{"id":"tgi","name":"TGI","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:8000",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"rerank":true},"streaming":"SSE"}
```

## #056 — llama.cpp server 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:8080` (D1 at `/v1/chat/completions`, `/v1/completions`)
- auth: NONE (multi-user via `--api-key` → Authorization bearer)
- capabilities: chat, completions, embeddings (`/v1/embeddings`; legacy `/embedding`),
  rerank (`/rerank`), infill (`/infill`)
- Knobs: `cache_prompt:true`, `n_predict` (native `/completion` body: prompt, n_predict,
  temperature, top_k, top_p, repeat_penalty, stop, seed, n_keep, tfs_z, typical_p,
  presence_penalty, frequency_penalty, min_p, mirostat, …), `slot_id`, `grammar`,
  `json_schema` (server gen), `samplers[]`
- Quirks: `/v1` is the compat layer — full sampler control needs native `/completion`
  (D7 template if required); slots for concurrent requests.
```json
{"id":"llamacpp","name":"llama.cpp server","dialect":"OPENAI_COMPAT",
 "baseUrl":"http://localhost:8080","auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #057 — LM Studio 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:1234/v1`
- auth: NONE
- capabilities: chat, completions, embeddings (`/v1/embeddings`), tool calling
- Quirks: `max_tokens` not required; model ids from local catalog; `/api/v0` for model mgmt.
```json
{"id":"lm-studio","name":"LM Studio","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:1234/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #058 — LocalAI 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:8080/v1`
- auth: NONE (`API_KEY` env optional → bearer)
- capabilities: chat, completions, embeddings (`/v1/embeddings`), rerank (`/v1/rerank`),
  images (`/v1/images/generations`), audio TTS/STT, model gallery
- Knobs: `model` local name or `ggml`; model gallery via `GET /models/available`;
  `backend` hints in config.
```json
{"id":"localai","name":"LocalAI","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:8080/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true,"rerank":true,
   "imagesGenerate":true,"tts":true,"stt":true},
 "streaming":"SSE"}
```

## #059 — Aphrodite Engine 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `http://localhost:8000/v1`
- auth: NONE
- capabilities: chat, completions, embeddings, kv cache reuse (`use_prefix_cache`)
- Knobs: `include_stop_str_in_output`, `reasoning_content` on some models, guided decoding.
```json
{"id":"aphrodite","name":"Aphrodite","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:8000/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #060 — Jan (Jan Server) 🟡
- dialect: OPENAI_COMPAT
- baseUrl: `http://localhost:1337/v1`
- auth: NONE
- capabilities: chat only (local desktop runtime)
- Quirks: models stored in `~/.jan/models`; `/v1/models` present on local mode.
```json
{"id":"jan","name":"Jan","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:1337/v1",
 "auth":{"scheme":"NONE","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #061 — KoboldCpp 🟡
- dialect: OPENAI_COMPAT (compat layer) + native `/api/v1/generate` (D7)
- baseUrl: `http://localhost:5001`
- auth: NONE
- capabilities: chat, completions (native generate), vision? (llava via multimodal)
- Knobs (native): `prompt`, `max_context_length`, `max_length`, `temperature`, `top_p`,
  `top_k`, `rep_pen`, `rep_pen_range`, `typical`, `tfs`, `mirostat`, `sampler_order`,
  `stop_sequence[]`, `logit_bias`, `ban_tokens`, `soft_prompt`, `grammar`
- Quirks: compat `/v1/chat/completions` added 2024+; full control is native (D7 template).
```json
{"id":"koboldcpp","name":"KoboldCpp","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:5001",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true},"streaming":"SSE"}
```

## #062 — TensorRT-LLM (triton server) 🟡
- dialect: OPENAI_COMPAT (triton dyanmo openai) 
- baseUrl: `http://localhost:8000/v1`
- auth: NONE
- capabilities: chat, completions (`/v2/models/{model}/generate` native — D7 if needed)
- Quirks: openai adapter present on dynamo builds; model repo config `--model-repository`.
```json
{"id":"tensorrt-llm","name":"TensorRT-LLM","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:8000/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true},"streaming":"SSE"}
```

## #063 — GPT4All 🟡
- dialect: OPENAI_COMPAT
- baseUrl: `http://localhost:4891/v1`
- auth: NONE
- capabilities: chat, completions, embeddings (local)
- Quirks: desktop app local server; `/v1/models` returns local set.
```json
{"id":"gpt4all","name":"GPT4All","dialect":"OPENAI_COMPAT","baseUrl":"http://localhost:4891/v1",
 "auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true,"embeddings":true},"streaming":"SSE"}
```

## #064 — oobabooga text-generation-webui 🟡
- dialect: OPENAI_COMPAT (`/v1/chat/completions` via extension)
- baseUrl: `http://localhost:5000/v1`
- auth: NONE (add `--api-key` → bearer)
- capabilities: chat, completions
- Quirks: extension `openai` enables D1; legacy `/api/v1/chat` native (D7).
```json
{"id":"oobabooga","name":"text-generation-webui","dialect":"OPENAI_COMPAT",
 "baseUrl":"http://localhost:5000/v1","auth":{"scheme":"NONE","apiKey":""},
 "capabilities":{"chat":true,"completions":true},"streaming":"SSE"}
```

## #065 — Petals 🟡
- dialect: OPENAI_COMPAT? — actually h2o/Petals is a federated swarm, no official D1 HTTP;
  pin as TEMPLATE/BYO with community gateway endpoints (verify) — mostly consume via
  `petals.ml` python client; skip D1 routing.
```json
{"id":"petals","name":"Petals","dialect":"TEMPLATE","baseUrl":"https://petals.dev/api/v1",
 "auth":{"scheme":"NONE","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE",
 "catalog":{"mode":"STATIC","models":[]}}
```

---

# Top-100 → 04-china.md (#066–#081)

---

## #066 — Alibaba DashScope (Qwen) 🟢
- dialect: OPENAI_COMPAT (verified) + TEMPLATE native (images/video/omni/WS-TTS)
- baseUrl: `https://dashscope-intl.aliyuncs.com` (intl) / `https://dashscope.aliyuncs.com` (CN)
- auth: BEARER `sk-…` (DashScope key)
- endpoints (all VERIFIED live): chat=`/compatible-mode/v1/chat/completions`,
  models=`/compatible-mode/v1/models`, embeddings=`/compatible-mode/v1/embeddings`
- capabilities: chat (qwen3-flash/turbo/omni), embeddings (text-embedding-v4),
  imagesGenerate (qwen-image via native `multimodal-generation` — TEMPLATE),
  video (wan3.0-video — async submit/`X-DashScope-Async: enable`, poll `GET /tasks/{id}` —
  VERIFIED SUCCEEDED), omni (qwen-omni-turbo via native multimodal-generation),
  tts (WS native — ❌ NOT provisioned on workspace key: ModelNotFound for every id;
  see voice-client QwenTtsSession + QwenTtsSmokeITest), stt (qwen-audio)
- Native shapes (TEMPLATE, base + `/api/v1/services/aigc/multimodal-generation/generation`):
  `{model, input:{messages:[{role,content:[{text}|{image:{url}|{base64}}|{video:{url}}]}]},
  parameters:{temperature,top_p,max_tokens,seed,result_format:"message|text|url",
  video:{...}, image:{size,style}}}`; qwen-image style: `parameters.image.style:"qwen"` +
  `"url"` output (verified); auth same bearer.
- Catalog: AUTO `/compatible-mode/v1/models` (rich freeinference-style shape!)
- Quirks: image/video/text endpoints differ per model — each gets its own TEMPLATE config
  with `imageInput:"json"`, `imageOutput:"json"`, `imageModelInPath:false`;
  intl vs CN host matters (intl has no TTS SKU on this key — CN likely has).
```json
{"id":"qwen-dashscope","name":"Qwen DashScope","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://dashscope-intl.aliyuncs.com","auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"chat":"/compatible-mode/v1/chat/completions",
   "models":"/compatible-mode/v1/models","embeddings":"/compatible-mode/v1/embeddings"},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "catalog":{"mode":"AUTO","path":"/compatible-mode/v1/models"},"streaming":"SSE"}
```

## #067 — Zhipu AI (GLM / z.ai) 🟡 (intl base VERIFIED 2026-09: docs.z.ai)
- dialect: OPENAI_COMPAT (v4) + ANTHROPIC skin + D7 (cogview images, cogvideox video)
- baseUrl: `https://api.z.ai/api/paas/v4` (VERIFIED intl — OpenAI SDK base w/ trailing
  slash; endpoints under it: /chat/completions); CN mirror `https://open.bigmodel.cn/api/paas/v4`
- auth: BEARER (Zhipu key)
- capabilities: chat (glm-5.3, glm-5.3-flash multimodal coding), embeddings (glm-embedding,
  /embeddings), imagesGenerate (GLM-Image at /images/generations), video (CogVideoX-3 — D7
  async verify)
- Knobs: `thinking:true` (reasoning), `web_search:true` (builtin tool), `tools`,
  `response_format json_object`, `max_tokens`; GLM Coding Plan uses a SEPARATE dedicated
  endpoint (devpack tutorial) — portal should keep two provider entries.
- Quirks: anthropic-compat at `open.bigmodel.cn/api/anthropic` + z.ai mirror (D2 config,
  same key); SSE `data:` + [DONE]; cogview returns `data[0].url`.
```json
{"id":"zhipu-glm","name":"Zhipu GLM","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.z.ai/api/paas/v4","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "endpoints":{"imagesGenerations":"/images/generations"},"streaming":"SSE"}
```

## #068 — Moonshot (Kimi) 🟡 (VERIFIED 2026-09: platform.kimi.com)
- dialect: OPENAI_COMPAT + ANTHROPIC skin (Messages API) + RESPONSES mirror (all confirmed)
- baseUrl: `https://api.moonshot.cn/v1` (VERIFIED); platform renamed → platform.kimi.com
- auth: BEARER `sk-…`
- capabilities: chat (kimi-k3 flagship: 1M ctx, reasoning_effort low/high/max default max;
  kimi-k2.7-code / kimi-k2.7-code-highspeed; kimi-k2.6 256K), vision (images ≤4K +
  video input via file upload), embeddings (kimi-embedding — verify),
  responses API (OpenAI-compat /v1/responses?), context caching
- Knobs: `reasoning_effort:"low"|"high"|"max"` (k3), `thinking` (k2.x), `max_tokens`/
  `max_completion_tokens` per model params page, tools, JSON mode; vision via D1
  `image_url` parts; files for large video/images
- Cache: `$` suffix on model id (`kimi-k2-0711-preview$`), `cached_tokens` in usage,
  `/v1/files` uploads (persist) + `"fileid:…"` content parts
- Quirks: anthropic-compat Messages API at `api.moonshot.cn/anthropic` (x-api-key,
  Claude Code compatible); per-model param differences — consult
  platform.kimi.com/docs/api/models-overview.
```json
{"id":"moonshot-kimi","name":"Moonshot Kimi","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.moonshot.cn/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"responses":true},
 "endpoints":{"embeddings":"/embeddings","files":"/files"},"streaming":"SSE"}
```

## #069 — MiniMax 🟡 (ANTHROPIC skin VERIFIED 2026-09: platform.minimaxi.com)
- dialect: OPENAI_COMPAT (D1 mirrors) + ANTHROPIC (VERIFIED CN skin) + D7 native (video)
- baseUrl: `https://api.minimax.cn` (CN) · `https://api.minimax.io` (intl) —
  **Anthropic skin VERIFIED at `https://api.minimax.cn/anthropic`** (ANTHROPIC_BASE_URL;
  intl equivalent `https://api.minimax.io/anthropic` — verify)
- auth: BEARER
- capabilities: chat (MiniMax-M3: 1M ctx, native multimodal incl. image/video/tools/thinking
  blocks; M2.7/M2.7-highspeed/M2.5*/M2.1*/M2: 204.8K text+tools), imagesGenerate
  (image-01, image-01-live), video (H3: t2v/i2v/first-last-frame, 768P/2K, 4–15s; H3 Max
  via fal.ai; Hailuo 2.3/2.3 Fast/02 legacy), tts (speech-2.8/2.6/02/01 — see #100),
  music (music-3.0 PAID API discontinued for new users 2026-08-20; free stopped)
- Anthropic-skin knobs (VERIFIED): `thinking:{type:"adaptive"|"disabled"}` (M3; M2.x always
  on — disabled ignored), `service_tier:"standard"|"priority"` (priority = 1.5× price,
  priority admission), temperature [0,2] (recommend 1.0), top_p (M3 default 0.95);
  IGNORED: top_k, stop_sequences, mcp_servers, context_management, container;
  multimodal M3 only: image blocks (JPEG/PNG/GIF/WEBP ≤10MB), video (MP4/AVI/MOV/MKV ≤50MB
  inline, `mm_file://{file_id}` ≤512MB via Files API), request ≤64MB;
  `POST /anthropic/v1/messages/count_tokens` supported; multi-turn MUST echo full
  `response.content` (thinking/tool_use blocks) for tool loops.
- Quirks: OpenAI-compat paths `/v1/chat/completions` (same bearer) remain for non-M-series.
```json
{"id":"minimax","name":"MiniMax","dialect":"ANTHROPIC","baseUrl":"https://api.minimax.cn",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"x-api-key",
   "extraHeaders":{"anthropic-version":"2023-06-01"}},
 "capabilities":{"chat":true,"imagesGenerate":true},
 "endpoints":{"chat":"/anthropic/v1/messages"},"streaming":"SSE"}
```

## #070 — 01.AI (Yi) 🟡
- dialect: OPENAI_COMPAT
- baseUrl: `https://api.lingyiwanwu.com/v1`
- auth: BEARER
- capabilities: chat (yi-lightning, yi-large, yi-vision)
- Quirks: model `yi-lightning` flagship; standard SSE; vision via image_url parts.
```json
{"id":"01ai-yi","name":"01.AI Yi","dialect":"OPENAI_COMPAT","baseUrl":"https://api.lingyiwanwu.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #071 — StepFun (Step) 🟡
- dialect: OPENAI_COMPAT
- baseUrl: `https://api.stepfun.com/v1`
- auth: BEARER
- capabilities: chat (step-2-16k, step-1.5v-mini vision), voice (step-aso — realtime D6?),
  math (step-math-32k), embeddings?? (no)
- Knobs: `thinking:true` (step-2 reasoning), `max_tokens`, vision via image_url (JPEG/PNG)
- Quirks: reasoning output in `reasoning_content`; step-aso is voice model — D6 probe later.
```json
{"id":"stepfun","name":"StepFun Step","dialect":"OPENAI_COMPAT","baseUrl":"https://api.stepfun.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #072 — Baidu Qianfan (ERNIE) 🟡
- dialect: OPENAI_COMPAT (v2 — recommended) ; legacy v1 OAuth2 (`access_token` query)
- baseUrl: `https://qianfan.baidubce.com/v2`
- auth: BEARER (v2 API key: `Bearer <AK-SK base64>`? — verify: v2 uses `Authorization: Bearer
  <app key:secret>` derived? Docs: v2 requires `Authorization: Bearer <API_KEY>` where key
  from console; v1 needed `access_token` query via AK/SK OAuth)
- capabilities: chat (ernie-4.5-turbo, ernie-x1 reasoning), embeddings (ernie-embedding-v1?),
  vision (ernie-4.5-vl)
- Quirks: v1 legacy: `https://qianfan.baidubce.com/v1/chat/completions?access_token=` +
  `{messages, model:"ernie-3.5-8k"}`; v2 body `{model, messages, temperature, top_p,
  optional "reasoning_effort"}`; SSE [DONE].
```json
{"id":"baidu-qianfan","name":"Baidu Qianfan","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://qianfan.baidubce.com/v2","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},"streaming":"SSE"}
```

## #073 — Tencent Hunyuan 🟡
- dialect: OPENAI_COMPAT (+ ANTHROPIC skin)
- baseUrl: `https://api.hunyuan.cloud.tencent.com/v1`
- auth: BEARER (`Authorization: Bearer hunyuan-…` TC3-HMAC legacy for v1)
- capabilities: chat (hunyuan-turbo, hunyuan-large), imagesGenerate? (hunyuan-image — via
  `https://api.hunyuan.cloud.tencent.com/v1/images/generations` — verify), video (hunyuan-
  video — D7), embeddings?? (verify)
- Knobs: `extra_body` for modern knobs? standard D1 `temperature/top_p/max_tokens/stream` +
  `thinking` on hunyuan-turbolatest? (verify)
- Quirks: anthropic-compat at `/anthropic` (x-api-key); legacy v1 TC3 signing needed for
  some regions — prefer new API key admin.
```json
{"id":"tencent-hunyuan","name":"Tencent Hunyuan","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://api.hunyuan.cloud.tencent.com/v1","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #074 — Volcengine ARK (Doubao) 🟡
- dialect: OPENAI_COMPAT (chat/embeddings) + D7 (image/video async)
- baseUrl: `https://ark.cn-beijing.volces.com/api/v3`
- auth: BEARER (ARK API key)
- capabilities: chat (doubao-pro, deepseek-v3 on ark), embeddings (`/api/v3/embeddings`),
  imagesGenerate (doubao-seedream — `/api/v3/images/generations`? verify), video
  (seedance — `POST /api/v3/contents/generations/tasks` async, poll `/tasks/{id}` — D7),
  tts (doubao TTS — `/api/v3/tts`? verify; volc has `/api/v3/audio/…`?) 
- Quirks: model ids: `ep-…` (endpoint ids) or model names `doubao-pro-32k`; async tasks
  pattern: `{content:{model, prompt, ...}, id}` → `GET {base}/contents/generations/tasks/{id}`.
```json
{"id":"volcengine-ark","name":"Volcengine ARK","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://ark.cn-beijing.volces.com/api/v3","auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true,"imagesGenerate":true},
 "streaming":"SSE"}
```

## #075 — iFlytek Spark (Xinghuo) 🟡
- dialect: OPENAI_COMPAT (openai-compat gateway) — legacy WS native (D7) retired per docs
- baseUrl: `https://spark-api-open.xf-yun.com/v1`
- auth: BEARER — `Authorization: Bearer {APIKey}:{APISecret}` (colon-joined, NOT standard
  token; pin auth.extraHeaders `Authorization: "Bearer KEY:SECRET"` if apiKey holds pair)
- capabilities: chat (spark-4.0-ultra, spark-lite), embeddings (spark-embedding), tts
  (x-tts), stt (iflytek ASR via ws — D7)
- Quirks: free spark-lite for testing; standard D1 SSE; model ids `generalv3.5`, `4.0Ultra`.
```json
{"id":"iflytek-spark","name":"iFlytek Spark","dialect":"OPENAI_COMPAT",
 "baseUrl":"https://spark-api-open.xf-yun.com/v1",
 "auth":{"scheme":"BEARER","apiKey":"","extraHeaders":{"Authorization":"Bearer APIKEY:APISECRET"}},
 "capabilities":{"chat":true,"embeddings":true},"streaming":"SSE"}
```

## #076 — Baichuan 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.baichuan-ai.com/v1`
- auth: BEARER
- capabilities: chat (baichuan4-turbo), embeddings (baichuan-text-embedding at /v1/embeddings)
- Quirks: `max_tokens` standard; model `Baichuan4-Turbo` camelCase id — check catalog.
```json
{"id":"baichuan","name":"Baichuan","dialect":"OPENAI_COMPAT","baseUrl":"https://api.baichuan-ai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "capabilities":{"chat":true,"embeddings":true},"streaming":"SSE"}
```

## #077 — Xverse (Xiaomi-backed) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.xverse.cn/v1`
- auth: BEARER
- capabilities: chat (xverse-13b, xverse-moe)
- Quirks: minimal public docs — verify model ids; standard D1 body.
```json
{"id":"xverse","name":"Xverse","dialect":"OPENAI_COMPAT","baseUrl":"https://api.xverse.cn/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #078 — SenseTime SenseNova 🟡
- dialect: OPENAI_COMPAT (2024+)
- baseUrl: `https://api.sensenova.cn/v1`
- auth: X_API_KEY keyHeader `X-Access-Token` (SenseNova legacy auth also uses
  `client_id/client_secret` body — prefer new API key token)
- capabilities: chat (SenseChat-5.5), imagesGenerate (SenseNova image models — verify)
- Quirks: legacy: `/v6/chat/completions` with {app_key, app_secret} in body; new compat:
  `/v1/chat/completions` + `X-Access-Token: <token>` — verify both.
```json
{"id":"sensenova","name":"SenseNova","dialect":"OPENAI_COMPAT","baseUrl":"https://api.sensenova.cn/v1",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"X-Access-Token"},
 "capabilities":{"chat":true},"streaming":"SSE"}
```

## #079 — 360 Zhinao 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.ai.360.com/v1` (verify path — some consoles use /openapi/v1)
- auth: BEARER
- capabilities: chat (360guan-13b, 360zhinao2)
- Quirks: low public API adoption; verify model ids + path.
```json
{"id":"zhinao-360","name":"360 Zhinao","dialect":"OPENAI_COMPAT","baseUrl":"https://api.ai.360.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```

## #080 — Kuaishou Kling (video/image) 🟡
- dialect: TEMPLATE (async)
- baseUrl: `https://api.klingai.com/v1`
- auth: BEARER (X-API-Key header? — Kling uses `Authorization: Bearer` + `X-KLING-…`? verify;
  officially `Authorization: Bearer <ak/sk>`)
- endpoints: video=`/videos/text2video`, `/videos/image2video`, image=`/images/text2image`,
  tasks=`/videos/text2video/{id}` (GET poll)
- Body: `{model_name:"kling-v1-6"|"kling-v2", prompt, negative_prompt, cfg_scale, mode,
  duration, aspect_ratio, camera_control, image_url, ...}`
- capabilities: videosGenerate?? — maps to template `videos`+`tasks` endpoints; imagesGenerate
- Quirks: async: submit → `{data:{task_id}}` → poll GET until `status: succeeded` →
  `data.task_result.videos[].url`; CN vs intl (klingai.com global OK).
```json
{"id":"kling","name":"Kling","dialect":"TEMPLATE","baseUrl":"https://api.klingai.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"videos":"/videos/text2video","tasks":"/videos/text2video"},
 "capabilities":{"imagesGenerate":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #081 — Xiaomi MiMo (hosted) 🟡
- dialect: OPENAI_COMPAT; SSE
- baseUrl: `https://api.xiaomi.com/v1` (verify — Xiaomi's model API surfaced via
  aggregators mostly; direct API sparsely documented)
- auth: BEARER (Mi Home/OAuth)
- capabilities: chat (MiMo-8B/16B hosted), vision
- Quirks: pin verify; often consumed via openrouter/groq mirror instead.
```json
{"id":"xiaomi-mimo","name":"Xiaomi MiMo","dialect":"OPENAI_COMPAT","baseUrl":"https://api.xiaomi.com/v1",
 "auth":{"scheme":"BEARER","apiKey":""},"capabilities":{"chat":true},"streaming":"SSE"}
```