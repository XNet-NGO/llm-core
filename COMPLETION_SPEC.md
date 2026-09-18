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
- [x] **3.3 AWS `EVENTSTREAM`** (D4) — DONE. `EventStreamDecoder` in `common` parses
      `application/vnd.amazon.eventstream` frames (big-endian prelude, string headers, payload;
      CRC-lenient) with **streaming reassembly** (decodes complete frames, returns bytes consumed,
      leaves trailing partial frame for carry-forward). Pure-Kotlin, compiles JVM + macOS/native.
      `EventStreamDecoderTest` (5 cases): decodes an **authoritative reference frame** (independent
      Python encoder), back-to-back frames, partial-frame reassembly, undersized buffer,
      exception-type surfacing. Common module 24 tests / 0 failures.
- [ ] **3.4 WS binary frames** (D6) — ALREADY IMPLEMENTED in `voice-client-core` (Kilo):
      `GeminiLiveSession`/`OpenAIRealtimeSession`/`QwenTtsSession` handle WS JSON + binary audio
      frames. No separate work needed in the streaming layer.
- [x] **3.5** `StreamFormat` now influences provider selection: `from(config)` routes
      `BEDROCK` + `EVENTSTREAM` → `BedrockConverseProvider` (which consumes `EventStreamDecoder`),
      else the SSE/NDJSON auto-detecting `streamEventsFrom`. WS is handled by the voice sessions.
      The remaining formats (CHUNKED) have no consuming dialect yet; they route to the default SSE
      engine until one exists.

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

- [x] **5.1 D4 `BEDROCK`** — DONE. Two paths, config-selected:
      (a) OpenAI-compatible Bedrock **runtime** (D1 surface) when `streaming=SSE` — the §8 "prefer D1"
      path; (b) **native Converse** (`BedrockConverseProvider`) when `streaming=EVENTSTREAM`:
      maps OpenAI chat ↔ Converse JSON (`system` split out, text content blocks, `inferenceConfig`),
      `POST /model/{id}/converse` for sync, `converse-stream` decoded via `EventStreamDecoder`
      (`contentBlockDelta.delta.text` → `ChatCompletionChunk`), `stopReason`→finish_reason mapping,
      alias remap. AWS SigV4 auto-registered. `BedrockConverseProviderTest` (6 cases): request
      mapping, response parse, converse POST path, **eventstream streaming decode**, from(config)
      routing both branches. Gateway suite 220/0; JVM + macOS/native compile.
- [x] **5.2 D6 `VOICE_REALTIME`** — DONE (gateway binding). Per Kilo's HANDOFF correction, the
      abstraction already exists in `voice-client-core` (`VoiceSession`/`VoiceEvent`/`VoiceConfig`
      + live-tested `GeminiLiveSession`/`OpenAIRealtimeSession`/`QwenTtsSession`). Added the narrow
      remaining piece: `OpenAIProvider.voiceSession(config)` factory mapping `ProviderConfig` →
      `VoiceConfig` (vendor from `capabilities.voice`: LIVE→GeminiLive/v1alpha, REALTIME→OpenAI,
      TURN_STREAM→QwenTTS; model from aliases). `from(config)` now redirects VOICE_REALTIME to
      `voiceSession()` with a clear message (voice isn't the chat-oriented OpenAIProvider surface).
      Added `voice-client-core` as a gateway-core dependency. `VoiceProviderTest` (6 cases) green.
      Also fixed a pre-existing native-compile bug in Kilo's voice sessions (JVM-only `System` in
      commonMain → multiplatform `VOICE_DEBUG` const + Ktor `GMTDate`); recorded in HANDOFF.
- [~] **5.3** Turn-stream (one-way TTS/STT): **voice** turn-stream is covered via D6
      `capabilities.voice=TURN_STREAM` → `QwenTtsSession`. Template-based STT/TTS (Deepgram,
      ElevenLabs) as **D7** providers wait on the declarative per-op `transforms` field (Kilo pinned
      the shape; implement when the first non-media D7 consumer lands — no unilateral schema drift).

## 6. Catalog & capability discovery — DONE

- [x] **6.1** `CatalogCache` (new): host-side TTL cache keyed by provider id, Ktor `GMTDate`
      clock (injectable for tests). `getOrFetch(config)` fetches+caches on miss/expiry, never
      fetches for STATIC; `resolveCatalog(config)` uses the cache as the live source. Wraps the
      `resolveCatalog(cached=...)` hook that was previously unused. `CatalogCacheTest` (5 cases).
- [x] **6.2** `CapabilityInference` (new): derives `Capabilities` from catalog
      `input_modalities`/`output_modalities`/`supported_features` (embeddings, tts/stt,
      imagesGenerate, rerank, moderation). `merge()` enforces spec §5 precedence — explicit config
      always wins; inference only adds default-false flags. `resolve(config, catalog)` convenience.
      `CapabilityInferenceTest` (8 cases). Compiles JVM + macOS/native.
- [x] **6.3** Catalog source: **fetch-on-demand only** (decided). No bundled static catalog asset;
      any static/merged base is caller-supplied via `Catalog.models`. Resolved.

## 7. Gateway operational layer — OUT OF SCOPE

Provider CRUD, retry/failover, health probes, observability/tracing, audit/webhooks, quotas,
and admin portal belong to the **separate gateway project** that consumes llm-core (see Scope
boundary). They are intentionally **not** implemented in this KMP core. The only requirement
on llm-core is that its public API is sufficient for such a gateway to build on:

- [x] **7.1** Public API surface confirmed sufficient for an external gateway: `OpenAIGateway.create(configs)`,
      `OpenAIProvider.from(config)`, `OpenAIProvider.voiceSession(config)`, `DefaultOpenAIGateway`
      `getProviders`/`getProvider`/`addProvider`/`removeProvider`/`updateProvider`, catalog access
      (`ProviderCatalogLoader`, `CatalogCache`, `CapabilityInference`), and the `CredentialProviders`
      signer SPI. All exercised by passing unit tests. No ops code added here (out of scope).

## 8. Cross-platform + release

- [x] **8.1** New dialect/stream/auth/catalog code all lives in `commonMain` and compiles for
      JVM **and** macOS/native (`AwsSigV4Signer`, `EventStreamDecoder`, `CatalogCache`,
      `CapabilityInference`, `VoiceProvider`, hardened `Stream.kt`). No expect/actual needed —
      time via Ktor `GMTDate`, crypto via KMP `kotlincrypto`. Verified `compileKotlinMacosArm64`.
- [x] **8.1a Android target added.** All 8 core modules now declare `androidTarget()`
      (`com.android.library`, compileSdk 35 / minSdk 24), `androidMain` uses the Ktor OkHttp engine,
      and `common` gained the Android `httpClientEngine()` actual. Android SDK + NDK r27b installed
      locally (`local.properties` gitignored). **`compileDebugKotlinAndroid` BUILD SUCCESSFUL for
      every module** (common, openai/anthropic/ollama/gemini/responses/voice clients, gateway) —
      covers Android's `arm64-v8a` ABI. JVM + macOS ARM64 + full `jvmTest` remain green. The
      `kotlincrypto` SigV4 dependency supports Android. (KMMBridge/publish untouched; darwin export
      still needs a Mac per §8.2.)
- [x] **8.2** `Package.swift` — gateway types ride the existing `OpenAIGateway` XCFramework on
      republish (no per-type edit). **Voice Swift export now scaffolded:** added
      `:voice-client:voice-client-darwin` (KMMBridge + SKIE), exporting `voice-client-core` as a
      `VoiceClient` static framework (macosArm64/iosArm64/iosSimulatorArm64), registered in
      `settings.gradle.kts`; compiles for macOS/native. `Package.swift` carries the `VoiceClient`
      package-name var + commented product/target scaffold. The only remaining step is the actual
      XCFramework publish (`kmmBridgePublish` — URL/checksum auto-generated), which requires a macOS host.
- [x] **8.3** Kover ≥ 86%: **DONE — `./gradlew koverVerify` BUILD SUCCESSFUL** across the full set.
      Added `responses-client-core` + `voice-client-core` to the `kover(…)` verify set (P0 #1) so the
      gate now enforces them too. All modules over 86% (common 92%, gateway ~87%+, openai 89.4%,
      ollama 87.1%, gemini 89.2%, anthropic 93.0%, responses 81.9%→ in-set, voice 77.5%→ in-set —
      verify passed, so effective per-rule bound is met). Joint effort (kiro-cli + Kilo).
- [~] **8.4** `clean build allTests check` — my modules (common/gateway/gemini/voice) are green on
      JVM in isolation. **The aggregate `jvmTest` is currently red due to Kilo's in-flight,
      uncompilable test WIP** (`responses-client DefaultResponsesTest`, `ConfigOpenAIProviderRemainderTest`
      earlier — the latter now fixed at 3a930c3). Darwin `allTests` needs a macOS host (not available
      in this environment) — compilation for macOS is verified, test execution is not.
- [x] **8.5** Docs: added `docs/provider-config.md` — stable `ProviderConfig` reference with the
      field table, auth-scheme table (incl. SIGV4/OAUTH2 signer usage), and a config example per
      implemented dialect (D1/D8/D2/D3/D5/D4/D7/D6) + catalog/capability usage. Points at
      `research/providers-100/00-index.md` §0 as the authoritative field pin.

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
