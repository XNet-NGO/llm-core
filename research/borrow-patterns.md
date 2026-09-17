# llm-core — Borrow Candidates & Pattern Sources

KMP projects to borrow code from, plus non-KMP projects for architecture patterns.
All candidates verified on GitHub (2026-09-17): stars, license, activity.
Nothing here is vendored yet — each item notes what to take and the license.

## 1. KMP projects (code-level borrow)

| Project | Stars | License | What to borrow |
|---|---|---|---|
| **aallam/openai-kotlin** | 1843 | MIT | The reference KMP OpenAI client: request/response DTOs, `Flow`-based streaming, engine-agnostic HTTP layer, error model. Best source for D1 chat/embeddings/models shapes. Older (last push 2026-02) but API surface is still the canonical shape. |
| **tddworks/openai-kotlin** | 56 | Apache-2.0 | Actively maintained multi-provider KMP SDK: OpenAI + Anthropic + Azure + Gemini + Ollama dialects in ONE library — the closest existing analog to llm-core. Borrow: dialect-per-module layout, provider interface, streaming implementations. Consider as upstream base rather than reimplementing. |
| **joreilly/GeminiKMP** | 246 | Apache-2.0 | Google native (D3) patterns: generateContent/streamGenerateContent, generationConfig, function calling, multimodal parts — and a real Compose Multiplatform app wiring it (Android/iOS/Desktop). |
| **aallam/ktoken** | 43 | MIT | KMP BPE tokenizer — token counting for history/rate-limit features without JVM-only libs. |
| **xef** (xebia-functional/xef) | 192 | Apache-2.0 | Higher-level Kotlin LLM framework (agents, tools, memory, ops integrations). Borrow: agent/tool abstractions and AIOps patterns if llm-core grows an agent layer; core remains a router. |
| **Librechat-Mobile** (garfiec) | 95 | MIT | KMP + Compose mobile client talking to a self-hosted gateway (LibreChat) — the template for the gateway's mobile admin/chat surface. |
| **ChatGemini / ChaKt-KMP / Gemini-AI-KMP-App** | 149/185/114 | Apache-2.0/MIT | Compose Multiplatform app structure (Ktor client + kotlinx-serialization + DI wiring) for chat UIs — portal screen patterns. |
| **kotlin-mcp-server** (normaltusker) | 30 | — | KMP MCP server using OpenAI/Gemini/OpenRouter shapes — MCP bridging if the gateway wants MCP tool plumbing (llama-server already speaks MCP on :3333). |

RunanywhereAI/runanywhere-sdks (10k★) — **caution**: license is NOASSERTION (custom); inspect terms before borrowing anything. Has KMP-ish AI client layers worth reading only.

Greenfield in KMP: **streaming SSE client plugin** (Ktor `sse`/`content-negotiation`), **AWS event-stream** (D4) and **WS binary frames** (D6) — no mature KMP implementations found; those get built in llm-core (patterns below).

## 2. Non-KMP pattern sources (architecture, not code)

| Project | What pattern to take |
|---|---|
| **Vercel AI SDK** (TS, @ai-sdk) | The best reference for **data-driven provider abstraction**: provider namespaces, `providerOptions` passthrough per provider, unified stream protocol with typed events, `embeddingModel`/`languageModel` categorization, transformRequestBody per provider. Mirrors llm-core's dialect model — read their openai-compatible provider implementation before designing ours. |
| **LiteLLM** (Python) | Config schema (`provider + model → base_url/api_key` routing), **model_prices_and_context_window.json** — the largest public model data asset (context windows, limits; re-verify each entry; usable after linting against live catalogs), cost tracking model. |
| **one-api / new-api** (Go) | DB-backed provider/channel CRUD, model-channel routing, token quotas, retry/failover middleware — the gateway admin HAL to copy conceptually (their provider table is exactly our `ProviderConfig` rows). |
| **litellm-rust / GoModel / maximhq/bifrost** | Gateway performance patterns: zero-copy streaming, adaptive load balancing, health probes. Also our earlier research: GoModel + baffle... bifrost's <100µs overhead claim informs where NOT to add logic in the hot path. |
| **Portkey / Helicone** | Observability middleware: request tracing (sessions, latency, cost), fallbacks, timeout+cache semantics — patterns for the gateway's history/audit/webhook module. |
| **OpenRouter model catalog JSON** | Already fetched (444 models) — usable as a **static merged catalog** source: capabilities, context lengths, pricing. Refresh policy: daily/weekly. |
| **OpenAI Realtime + Gemini Live protocol docs** | Voice D6 wire specs: WS event sets, audio chunking, tool-call lifecycle — the source of truth for `VoiceSession` event mapping (no usable KMP impl exists; we define the abstraction). |
| **Deepgram/AssemblyAI/ElevenLabs WS docs** | Turn-stream (D7) voice/STT/TTS event shapes for the template dialect examples. |
| **freeinference /v1/models** | Exact catalog shape (context_length, max_output_length, input/output_modalities, supported_features, quantization) — the target shape llm-core's capability inference parses; one AI Studio/Vertex doc for ListModels D3 variant. |

## 3. Recommended borrowing plan (in build order)

1. `tddworks/openai-kotlin` (Apache-2.0) — evaluate as the **core base**: its dialect modules map onto llm-core's D1/D2/D3; fork-extend with config-driven provider loading instead of fixed clients. Biggest ROI.
2. `aallam/openai-kotlin` (MIT) — cherry-pick DTO shapes + streaming `Flow` API for anything tddworks lacks.
3. `joreilly/GeminiKMP` (Apache-2.0) — port D3 request/stream code into the gemini dialect module.
4. `aallam/ktoken` (MIT) — token counting in commonMain.
5. Gateway admin: model CRUD/history/audit on **one-api** channel semantics + **Helicone** tracing event shape; UI patterns from **Librechat-Mobile**; portal UI on **Compose Multiplatform**.

## 4. License/attribution rules

- MIT/Apache-2.0 = safe to borrow with attribution headers; keep NOTICE/third-party-licenses file in the repo.
- RunanywhereAI (NOASSERTION) = read-only reference, no code copied.
- Gateways above (litellm, one-api, bifrost, Portkey) are reference-architecture only — no code.

## 5. Open questions for design review

- Fork `tddworks/openai-kotlin` vs reimplement from its patterns? (Fork = faster start, their structure may fight config-driven loading; reimplement = cleaner dialect-as-data, slower.)
- Include the OpenRouter catalog JSON as default merged catalog (adds ~4MB to the repo) or fetch-on-demand only?
- Voice `VoiceSession` abstraction: model on Gemini Live's message set (bidi, setup+toolCall) as the canonical shape with OpenAI Realtime mapped into it, or dual canonical sets?