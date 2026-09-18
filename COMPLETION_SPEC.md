# llm-core — Completion Spec

Steps to bring the config-driven LLM router to full completion. Scope is derived from
the design source (`research/provider-dialects.md` §7/§8), the borrow plan
(`research/borrow-patterns.md`), and a direct audit of the current implementation
(`openai-gateway-core/.../config/*` and `.../api/internal/*`).

Status legend: ✅ done · 🟡 partial · ❌ not started

## Scope boundary

**llm-core is a platform-agnostic backend/SDK.** It ingests `ProviderConfig`s, resolves the
correct provider engine per dialect, and exposes raw provider data/capabilities to callers.
That is the whole scope.

**Out of scope (separate gateway project):** provider CRUD/backing store, retry/failover,
health probes, observability/tracing, audit/webhooks, quotas/rate limiting, admin portal.
Those consume llm-core; they are not built here. Decisions in this repo are made to keep the
core thin and embeddable, not to grow an ops layer.

---

## 0. Current state (audited)

**Working, config-wired dialects** (via `OpenAIProvider.Companion.from(config)`):
- ✅ D1 `OPENAI_COMPAT` → `ConfigOpenAIProvider`
- ✅ D8 `AZURE_OPENAI` → `ConfigOpenAIProvider`
- ✅ D5 `RESPONSES` → `ResponsesOpenAIProvider`
- ✅ D7 `TEMPLATE` → `mediaProvider`

**Declared but not functional** (enum/field exists, factory throws or code path absent):
- ❌ D2 `ANTHROPIC`, D3 `GEMINI`, D4 `BEDROCK`, D6 `VOICE_REALTIME` → `from()` throws `IllegalArgumentException`
- ❌ Auth `SIGV4`, `OAUTH2` → no-op (comment says host transport attaches; nothing does)
- ❌ `StreamFormat.EVENTSTREAM|WS|NDJSON|CHUNKED` → only SSE has an engine

**Schema drift from spec §7:**
- ❌ `aliases: List<String>` — declared in `ProviderConfig`, **never read anywhere**; spec intends a slug→upstream **map**
- 🟡 `capabilities` flat booleans vs spec's nested objects (functionally complete, shape differs)
- 🟡 routing: doc comment claims `id/model-slug` namespace parsing, but `DefaultOpenAIGateway`
  matches on a separate `LLMProvider.name` and passes `request.model` through untouched

**Note:** code-based `AnthropicOpenAIProvider`, `GeminiOpenAIProvider`, `OllamaOpenAIProvider`
exist and are wired through legacy `initOpenAIGateway(...)` DI — but they are **not reachable
from `ProviderConfig`/`from(config)`**. "Completion" below means making every dialect
config-drivable, per the design goal "a new provider = one config entry."

---

## 1. Fix schema drift (blocking correctness) — DONE

- [x] **1.1 `aliases` → model remap.** `ProviderConfig.aliases` is now `Map<String, String>`.
      Applied via a `.remapped()` helper in `ConfigOpenAIProvider` on all four paths
      (chat/stream/completions/images); `ResponsesOpenAIProvider` inherits it through its
      `chatSurface` delegation.
- [x] **1.2 Corrected the routing doc comment** in `ProviderConfig` to describe provider-id/name
      matching and explicitly defer slug-namespace routing to the gateway project. No core code change.
- [x] **1.3 Capabilities shape** — kept flat booleans; drift documented and accepted. No serializer.
- [x] **1.4 Tests** — `ConfigOpenAIProviderAliasTest` (3 cases: remap applied, pass-through,
      empty-map no-op) all pass.

## 2. Wire the remaining chat/text dialects

- [x] **2.1 D2 `ANTHROPIC`** — `from(config)` now builds a config-driven Anthropic provider via
      `anthropicFrom(config)`, reusing `AnthropicOpenAIProvider`'s request/response adapters and
      content-block SSE streaming. `anthropic-version` is read from
      `auth.extraHeaders["anthropic-version"]` (case-insensitive) else the client default.
- [x] **2.2 D3 `GEMINI`** — `from(config)` builds a config-driven Gemini native provider via
      `geminiFrom(config)`, reusing `GeminiOpenAIProvider`'s contents/parts adapters and
      `streamGenerateContent` handling; key + baseUrl from config.
- [x] **2.3 Removed the throw fall-through** for ANTHROPIC/GEMINI; the `else` branch now only
      catches genuinely un-wired dialects (BEDROCK, VOICE_REALTIME).
- [x] **2.4 Tests** — `ConfigProviderFromDialectTest` (5 cases): builds Anthropic/Gemini
      providers, carries anthropic-version from extraHeaders, still throws for BEDROCK/
      VOICE_REALTIME, and multiple Gemini providers coexist with no global Koin. Full module
      suite: 86 tests, 0 failures (17 env-gated ITests skipped); all-module `jvmTest` green.
- [x] **2.5 Global-Koin flaw resolved (was flagged in prior turn).** Root cause: only
      `Gemini.create` leaked — it routed through `initGemini → startKoin` (global). `Anthropic.create`
      was already fully instance-scoped (builds requester + `DefaultMessagesApi` + `AnthropicApi`
      directly, no `startKoin`). Fix: added `Gemini.instance(config)` that constructs the
      `HttpRequester` + `DefaultTextGenerationApi` directly (mirroring Anthropic), and pointed
      `geminiFrom` at it. Verified by removing the test-only `@AfterEach stopKoin()` workaround and
      confirming the suite stays green + a regression test asserting `GlobalContext.getOrNull()` is
      null after building two Gemini providers. Config-driven providers of the same dialect now
      coexist, upholding the "any number of providers from config rows" guarantee.


## 3. Streaming engines beyond SSE

- [x] **3.1 SSE hardening** — `streamEventsFrom` (`common/.../ktor/api/Stream.kt`) now tolerates
      CRLF, `data:` with/without a following space, `[DONE]` with/without a space, SSE comment /
      keepalive lines (`: ping`), and blank separator lines. `endStreamResponse` kept as a
      backward-compatible helper (now space-insensitive). Tested in `StreamTest`
      (`tolerates CRLF, missing spaces, comments, keepalives and blank lines`).
- [x] **3.2 NDJSON** — bare-object records without a `data:` prefix (ollama-style) parse via
      `isJsonResponse`; explicit coverage added (`parses NDJSON bare-object records without data prefix`).
      Common module: 19 tests, 0 failures; gateway unaffected (86/0).
- [ ] **3.3 AWS `EVENTSTREAM`** (D4) — DEFERRED to v1.1 alongside D4 Bedrock (§5.1). Building the
      header+payload frame decoder now would be speculative ahead of its only consumer; it is
      gated on §4.1 SIGV4 + §5.1. No mature KMP impl (borrow-patterns §1) — build in `common` then.
- [ ] **3.4 WS binary frames** (D6) — DEFERRED to v1.1 alongside the `VoiceSession` abstraction
      (§5.2). Same rationale: no consumer until D6 lands.
- [ ] **3.5** Route `StreamFormat` selection through the provider — DEFERRED: only SSE + NDJSON
      have engines today and both are auto-detected line-by-line in `streamEventsFrom`, so no
      explicit switch is needed yet. Wire the switch when EVENTSTREAM/WS engines exist (3.3/3.4).

## 4. Auth: SIGV4 and OAUTH2 — DONE (pluggable-signer design)

Design decision: the platform-agnostic core does **not** embed AWS HMAC crypto or a live OAuth2
token exchange (no KMP crypto lib is present in commonMain; adding one is a host concern). Instead
SIGV4/OAUTH2 are handled by a **host-registered `RequestSigner` SPI** — the core resolves it per
request and attaches the returned headers/query params. This keeps the core thin while making both
schemes fully functional when a signer is installed, and degrades gracefully (unsigned) when not.

- [x] **4.1 SIGV4** — `ProviderAuth` gained `region/service/secretKey/sessionToken`. A registered
      `RequestSigner` for `AuthScheme.SIGV4` receives a `SigningContext(method, host, path, body)`
      and returns `SignedCredentials(headers, queryParams)`, applied in `ConfigOpenAIProvider`.
- [x] **4.2 OAUTH2** — `ProviderAuth` gained `tokenUrl/clientId/clientSecret/scopes`. A registered
      `RequestSigner` for `AuthScheme.OAUTH2` yields the bearer (or other) header; token
      acquisition/refresh lives in the host's signer implementation.
- [x] **4.3** Replaced the no-op `SIGV4/OAUTH2` branches in both `ConfigOpenAIProvider`
      (`signedFor()` on chat/completions/images) and `ProviderCatalogLoader` (`signedCatalogCredentials()`
      on the models GET).
- [x] **4.4 Tests** — `ConfigOpenAIProviderAuthTest` (3 cases): SIGV4 signer attaches headers +
      query params and receives the correct `SigningContext`; OAUTH2 signer attaches bearer; no
      registered signer → unsigned request proceeds.
- [x] **4.5 Concrete AWS SigV4 signer (crypto added).** Added the KMP crypto dependency
      (`org.kotlincrypto.macs:hmac-sha2` + `org.kotlincrypto.hash:sha2`, 0.8.0, Apache-2.0, pinned)
      to gateway-core commonMain. `AwsSigV4Signer` implements the full canonical-request → string-to-sign
      → derived-key → signature flow (HMAC-SHA256/SHA-256), emits `Authorization`, `X-Amz-Date`,
      `x-amz-content-sha256`, and `X-Amz-Security-Token` (temp creds). Timestamp via Ktor `GMTDate`
      (no expect/actual). Compiles for JVM **and** macOS/native. `ProviderAuth` extended with
      `region/service/secretKey/sessionToken`. `AwsSigV4SignerTest` (5 cases): AWS reference
      signing-key vector, **exact end-to-end signature** verified against an independent Python
      reference, session-token header, empty-keys guard, epoch→amzDate formatting. Gateway suite 95/0.

## 5. D4 Bedrock + D6 Voice (v1.1 per spec §8)

- [~] **5.1 D4 `BEDROCK`** — PARTIAL (functional). `from(config)` builds Bedrock via its
      **OpenAI-compatible runtime endpoint** (D1 surface), the spec §8 recommendation. AWS auth is
      now **concrete**: `AwsSigV4Signer` (§4.5) is auto-registered for SIGV4 when a Bedrock provider
      is built, so signed requests work out-of-the-box (host can still override). Tested: builds a
      D1-surface provider; signer verified against AWS reference vectors.
      REMAINING (v1.1): native Converse / `converse-stream` with
      `application/vnd.amazon.eventstream` binary framing — still gated on §3.3 (frame decoder).
      Functional Bedrock is available now via the D1 runtime.
- [ ] **5.2 D6 `VOICE_REALTIME`** — define the core `VoiceSession` abstraction first
      (borrow-patterns recommends Gemini Live's message set as canonical, OpenAI Realtime
      mapped into it), then bind `voice-client` module. `voice-client-core` module already
      exists in `settings.gradle.kts` — populate it. BLOCKED on §3.4 (WS binary frames).
- [ ] **5.3** Turn-stream (one-way TTS/STT) providers via D7 templates (ElevenLabs, Deepgram,
      Cartesia).

## 6. Catalog & capability discovery

- [ ] **6.1** `ProviderCatalogLoader` auto/static/merged is implemented — add the **host-side
      TTL cache** the `resolveCatalog(cached=...)` param anticipates (nothing caches yet).
- [ ] **6.2** Capability inference from catalog fields (`input_modalities`,
      `supported_features/reasoning`) with config override precedence (spec §5).
- [ ] **6.3** Catalog source: **fetch-on-demand only** (decided). Do **not** bundle a static
      OpenRouter/LiteLLM catalog asset into the repo — the core exposes live catalog data via
      `ProviderCatalogLoader`; any static/merged base is supplied by the caller as config
      (`Catalog.models`), never shipped in-tree. Resolves borrow-patterns §5 open question.

## 7. Gateway operational layer — OUT OF SCOPE

Provider CRUD, retry/failover, health probes, observability/tracing, audit/webhooks, quotas,
and admin portal belong to the **separate gateway project** that consumes llm-core (see Scope
boundary). They are intentionally **not** implemented in this KMP core. The only requirement
on llm-core is that its public API is sufficient for such a gateway to build on:

- [ ] **7.1** Confirm the provider/config API surface (`OpenAIGateway.create(configs)`,
      `OpenAIProvider.from(config)`, `getProviders`/`getProvider`, catalog access) is
      complete and stable enough for an external gateway to consume. No ops code added here.

## 8. Cross-platform + release

- [ ] **8.1** Ensure new dialect/stream/auth code lives in `commonMain`; provide
      `-darwin` and `-cio` actuals where platform HTTP/crypto differ (SigV4 signing,
      WS). `openai-gateway-darwin`, `voice-client-core` modules already declared.
- [ ] **8.2** `Package.swift` export surface updated for new public types.
- [ ] **8.3** Kover coverage ≥ 86% (repo threshold in `CLAUDE.md`) — `./gradlew koverVerify`.
- [ ] **8.4** `./gradlew clean build allTests check` green on JVM + darwin targets.
- [ ] **8.5** Docs: promote `research/provider-dialects.md` §7 to a stable
      `ProviderConfig` reference; document each dialect's config example.

---

## Definition of done

1. Every `Dialect` enum value is either config-drivable via `from(config)` or explicitly
   documented as out-of-scope (no silent `throw` for advertised dialects).
2. Every `AuthScheme` and `StreamFormat` enum value has a real engine behind it.
3. No dead config fields: `aliases` (and all `ProviderConfig` fields) are read and tested,
   or removed.
4. `ProviderConfig` doc/comments match runtime behavior (provider-id matching; no
   namespace parsing in core — that is the gateway project's job).
5. One live `*ITest` per implemented dialect (env-gated) + unit coverage ≥ 86%.
6. `clean build allTests check koverVerify` green across JVM and Apple targets.

## Suggested order (dependency-aware)

1 → 2 → 3 → 4 → 5 (5.1 depends on 4.1) → 6 → 8. Section 1 is blocking-correctness and
small; do it first. 4 (auth) gates 5.1 (Bedrock). 7 is out of scope (separate gateway
project). 8 (release) is last.


---

## Kilo note (2026-09-18) — D6 voice is NOT greenfield

§5.2/§3.4 state voice-client-core is an empty stub and WS binary frames are deferred —
**stale**. `voice-client-core` is already populated and live-tested (commits 2c84941,
16e41c7, and the Qwen TTS work):

- `voice-client/voice-client-core/src/commonMain/kotlin/com/tddworks/voice/api/`:
  `VoiceConfig(vendor: GEMINI_LIVE|OPENAI_REALTIME|QWEN_TTS, apiKey, baseUrl, model, voice,
  audioFormat, sampleRate)`, `VoiceSession` (sendText/sendAudio/sendToolResponse/endTurn/
  close), `VoiceEvent` (SessionReady/AudioDelta/Utterance/Error/Closed), `Voice` facade.
- Sessions: `internal/GeminiLiveSession.kt` (v1alpha bidiGenerateContent WS, binary
  frames, role'd turns, queued sends, generationComplete), `OpenAIRealtimeSession.kt`,
  `QwenTtsSession.kt` (DashScope `/api-ws/v1/inference` run-task/continue-task/finish-task
  — account-level ModelNotFound blocker documented).
- Live smoke tests: `voice-client-core/src/jvmTest/.../QwenTtsSmokeITest.kt` + Gemini
  bidi test; all pass on JVM (Qwen TTS surfaces the documented server error).
- `from(config)` binding D6 in gateway-core + `StreamFormat.WS` engine remain, plus
  turn-stream TTS/STT D7 templates (§5.3).

So §5.2's remaining work = binding + the gateway-side WS engine, not the abstraction.
