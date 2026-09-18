# Top-100 → 05-media-voice.md (#082–#100)

Media/voice providers are TEMPLATE-dialect configs: each needs explicit path overrides and,
in the build agent's template engine, request/response transformations. The blocks below pin
the upstream protocol (method, path, headers, body, response) so the transformations become
mechanical.

---

## #082 — ElevenLabs 🟡 (auth/model headers VERIFIED 2026-09: elevenlabs.io/docs)
- dialect: TEMPLATE (+ D6 TURN_STREAM WS for stream-input TTS)
- REST baseUrl: `https://api.elevenlabs.io`; WS: `wss://api.elevenlabs.io`
- auth: X_API_KEY — `keyHeader: "xi-api-key"` (legacy name; `x-api-key` also accepted)
- endpoints (VERIFIED): audioSpeech=`/v1/text-to-speech/{voice_id}` (multipart form:
  `text=…&model_id=…&voice_settings={stability,similarity_boost,style,use_speaker_boost}`;
  `Accept: audio/mpeg` → binary body; streaming = CHUNKED; `output_format:` query for PCM),
  ttsStream=`/v1/text-to-speech/{voice}/stream-input` (WS frames:
  `{"text":"<fragment>","try_trigger_generation":true}` → text+audio binary frames —
  D6 TURN_STREAM), stt=`/v1/speech-to-text` (multipart; `model_id=scribe_v1`), voices=
  `/v1/voices` (GET), sound effects=`/v1/sound-generation`
- capabilities: tts, stt, voice TURN_STREAM, model list via `/v1/models`
- Knobs TTS: voice_settings{stability 0–1, similarity_boost 0–1, style 0–1,
  use_speaker_boost bool}; models 2026: `eleven_v3` flagship (docs example),
  `eleven_multilingual_v2`, `eleven_turbo_v2_5`
- Quirks: cost/observability via response headers — `character-cost`, `request-id`,
  `x-trace-id` (VERIFIED — log them host-side).
```json
{"id":"elevenlabs","name":"ElevenLabs","dialect":"TEMPLATE","baseUrl":"https://api.elevenlabs.io",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"xi-api-key"},
 "endpoints":{"audioSpeech":"/v1/text-to-speech","audioTranscriptions":"/v1/speech-to-text",
   "models":"/v1/models"},
 "capabilities":{"tts":true,"stt":true,"voice":"TURN_STREAM"},
 "streaming":"CHUNKED","catalog":{"mode":"AUTO","path":"/v1/models"}}
```

## #083 — Cartesia Sonic 🟡
- dialect: TEMPLATE (+ D6 TURN_STREAM WS)
- REST baseUrl: `https://api.cartesia.ai`; WS `wss://api.cartesia.ai/tts/websocket`
- auth: BEARER (`X-API-Key` accepted legacy — pin bearer)
- endpoints: audioSpeech=`/v1/tts` (POST JSON:
  `{model_id:"sonic-2", transcript, voice:{mode:"id"|"embedding",id|embedding},
  output_format:{container:"raw"|"wav"|"mp3",encoding:"pcm_s16le",sample_rate:16000|22050|
  24000|44100,bit_rate?}, language:"en", duration?}`; streaming SSE by default — set
  `Accept: audio/*` for chunked binary? — docs: SSE JSON events with `audio` base64 +
  `context_id`), voices=`/v1/voices/list` (GET), voice embedding = POST `/v1/voices/embed`
- capabilities: tts (REST + WS), voices list; no STT
- WS shape (turn-stream): connect → send `{model_id, transcript, voice, output_format,
  context_id, continue:true}` → server streams `text` deltas then audio chunks (binary in
  `type:"chunk"`), end with `type:"done"`? — pin verify; context_id continues a voice
  session across requests (voice continuity).
- Knobs: `control-level` knobs? sonic-2: `emotion[]`, `language`, `voice speed` via
  transcript SSML-ish tags `[laughs]` — model handles; `previous_context_id`.
```json
{"id":"cartesia","name":"Cartesia","dialect":"TEMPLATE","baseUrl":"https://api.cartesia.ai",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioSpeech":"/v1/tts","models":"/v1/voices/list"},
 "capabilities":{"tts":true,"voice":"TURN_STREAM"},"streaming":"SSE",
 "catalog":{"mode":"STATIC","path":"/v1/voices/list","models":[]}}
```

## #084 — PlayHT (Play.ht) ⚠️ DOCS UNREACHABLE — verify on first live call
- dialect: TEMPLATE; CHUNKED streaming for TTS
- baseUrl: `https://api.play.ht/api/v2`
- auth: BEARER + `X-User-Id` (BOTH required; `Authorization: Bearer <key>`,
  `X-User-Id: <user id>` — pin in extraHeaders)
- endpoints: audioSpeech=`/tts/stream` (POST JSON `{text, voice, speed, quality:"draft|low|
  medium|high|premium", format:"mp3|wav|flac|ogg", voice_engine:"PlayHT2.0-turbo"|
  "PlayDialog"}` → audio body; job variant `POST /tts` + `GET /tts/{id}` poll), stt=
  `/speech-to-text`? (verify), voices=`/voices` (GET)
- capabilities: tts; voice list
- **Unreachable this pass:** docs.play.ai and api.play.ht both fail from the build host
  (transport/network block; raw.githubusercontent playht SDK repo 404). Claims above are
  from historical docs (2023–2024, X-User-Id requirement is long-standing).
- **Probe procedure (do before shipping):** with a PlayHT key — (1) `GET /api/v2/voices`
  with both auth headers → expect 200 + voice list; on 401/403 check bearer vs
  X-User-Id mismatch (most common failure); (2) `POST /api/v2/tts/stream` with a
  `playht2.0-turbo` model voice → expect binary mp3; (3) if both fail, audit current
  docs from a browser and update this entry.
```json
{"id":"playht","name":"PlayHT","dialect":"TEMPLATE","baseUrl":"https://api.play.ht/api/v2",
 "auth":{"scheme":"BEARER","apiKey":"","extraHeaders":{"X-User-Id":"USER_ID"}},
 "endpoints":{"audioSpeech":"/tts/stream","models":"/voices"},
 "capabilities":{"tts":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","path":"/voices","models":[]}}
```

## #085 — Deepgram 🟡
- dialect: TEMPLATE (STT REST + WS; TTS REST)
- baseUrl: `https://api.deepgram.com`
- auth: BEARER (`Authorization: Token <key>`? — officially `Authorization: Token`... pin:
  Deepgram accepts `Authorization: Bearer` AND legacy `Token`; prefer Bearer)
- endpoints: stt=`/v1/listen` (POST audio body; query knobs: `model=nova-3`, `language`,
  `smart_format=true`, `punctuate=true`, `diarize=true`, `endpointing`, `keywords`,
  `filler_words`, `redact`; response JSON `{results:{channels:[{alternatives:[{
  transcript, confidence, words[{word,start,end,confidence}]}]}]}}`), sttStream=
  `wss://api.deepgram.com/v1/listen?model=nova-3&…` (binary audio frames in,
  JSON results out — D6), tts=`/v1/speak` (body `{text}` + query `model=aura-2-thalia-en`,
  `encoding=mp3`; `Accept: audio/mpeg` → binary; streaming `/v1/speak?` chunked),
  ttsWS=`wss://api.deepgram.com/v1/speak?model=…` (bidirectional: text JSON in, audio out)
- capabilities: stt, tts, voice TURN_STREAM
- Knobs: STT `language:"en"`, `summarize:"v2"`, `detect_language`, `multichannel`,
  `utterances`; TTS `speed`, `container`, `sample_rate`, `encoding` (`linear16`).
```json
{"id":"deepgram","name":"Deepgram","dialect":"TEMPLATE","baseUrl":"https://api.deepgram.com",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioTranscriptions":"/v1/listen","audioSpeech":"/v1/speak"},
 "capabilities":{"stt":true,"tts":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #086 — AssemblyAI 🟡
- dialect: TEMPLATE (async)
- baseUrl: `https://api.assemblyai.com`
- auth: BEARER
- endpoints: stt=`/v2/transcript` (POST JSON `{audio_url, speaker_labels, …}` → `{id}`;
  GET `/v2/transcript/{id}` poll; when `status:completed` → `{text, utterances, words,
  summary, …}`; streaming `wss://api.assemblyai.com/v2/realtime/ws?sample_rate=16000` →
  `{"audio_start":...}` frames, results `message_type:"FinalTranscript"`), lemur=
  `/v2/lemur/task` (`{transcript_ids, prompt, context}`), file upload=`/v2/upload`
- capabilities: stt, moderation? (content safety), summarization (lemur)
- Knobs: `speaker_labels`,`auto_highlights`,`content_safety`,`iab_categories`, `summarization`,
  `entity_detection`,`language_detection`,`custom_spelling`, `punctuate`,`format_text`
- Quirks: async poll pattern; realtime WS uses `message_type` frames with `audio_start`/
  `audio_end`; lemur context — check `FinalTranscript` + `PartialTranscript`.
```json
{"id":"assemblyai","name":"AssemblyAI","dialect":"TEMPLATE","baseUrl":"https://api.assemblyai.com",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioTranscriptions":"/v2/transcript","files":"/v2/upload"},
 "capabilities":{"stt":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #087 — Speechmatics 🟡
- dialect: TEMPLATE (REST + WS realtime)
- baseUrl: `https://rt.spectrum.speechmatics.com` (realtime WS) / `https://api.speechmatics.com` (batch)
- auth: BEARER (in WS: `Authorization` header upgrade; batch: bearer)
- endpoints: stt=batch `POST /v2/jobs` (audio upload, `{config:{type:"transcription",
  transcription_config:{language:"en", operating_point, diarization:"speaker",
  additional_vocab[]}}}` → job, poll `GET /v2/jobs/{id}` → `output`), realtime=
  `wss://rt.spectrum.speechmatics.com/v2?…` (frames `{message:"AddAudio",
  audio:{data:<b64>,format:"pcm_s16le",sample_rate:16000}}` → `message:"AddTranscript"` + token frames)
- capabilities: stt (batch + realtime)
- Quirks: WS uses protobuf or JSON (`?message_format=json`); token timings matter for UI.
```json
{"id":"speechmatics","name":"Speechmatics","dialect":"TEMPLATE",
 "baseUrl":"https://api.speechmatics.com","auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioTranscriptions":"/v2/jobs"},"capabilities":{"stt":true},
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #088 — Hume AI (EVI + TTS) 🟡
- dialect: TEMPLATE (+ D6 realtime EVI WS)
- baseUrl: `https://api.hume.ai`
- auth: BEARER (`X-Hume-Api-Key` legacy — prefer bearer)
- endpoints: eviWS=`wss://api.hume.ai/v0/evi/chat` (+ `config_id`, `resumed_chat_id`,
  `session_id` query; frames: `{type:"user_input", text}` in, `{type:"assistant_message",
  message:{role, content}}` + `{type:"audio_output", data:<b64>}` out), tts=`/v0/tts`
  (POST `{utterance, voice:{name:"alloy"|"ito"|…}}` → JSON `{generated_audio}` b64),
  voices=`/v0/voices` (GET)
- capabilities: voice REALTIME (EVI), tts
- Knobs EVI: `config_id` (persona/settings from dashboard), `context` (system prompt),
  `mute_audio`, `language`; tts `model_id`, `speed`, `pitch`.
```json
{"id":"hume","name":"Hume AI","dialect":"TEMPLATE","baseUrl":"https://api.hume.ai",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioSpeech":"/v0/tts","models":"/v0/voices"},
 "capabilities":{"tts":true,"voice":"REALTIME"},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","path":"/v0/voices","models":[]}}
```

## #089 — Resemble AI 🟡
- dialect: TEMPLATE (REST + WS)
- baseUrl: `https://app.resemble.ai`
- auth: BEARER
- endpoints: tts=`/api/v2/projects/{project_id}/tts` (POST `{text, data:{voice_uuid, 
  sample_rate, output_format:"wav|mp3"}}` → JSON `{item:{audio_src}}`), streams add
  `skip_processing:true`; voices=`/api/v2/projects/{project_id}/voices` (GET)
- capabilities: tts; voice cloning (video avatars out of scope)
```json
{"id":"resemble","name":"Resemble AI","dialect":"TEMPLATE","baseUrl":"https://app.resemble.ai",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioSpeech":"/api/v2/projects"},"capabilities":{"tts":true},
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #090 — Fish Audio (OpenAudio) 🟡
- dialect: TEMPLATE
- baseUrl: `https://api.fish.audio`
- auth: BEARER (X-API-Key also accepted? — docs use `Authorization: Bearer <key>`)
- endpoints: tts=`/v1/tts` (POST `{text, reference_id, format:"wav|mp3|flac|opus",
  sample_rate, language, chunk_length, normalized, latency, streaming}` → JSON `{audio:
  <b64>}` or binary with `Accept: audio/*`), ttsStream=`/v1/tts/stream` (POST same →
  chunked audio), voices=`/v1/voices` (GET, `page_size`, `page`, `query`),
  voicesInfo=`/v1/voices/{id}`
- capabilities: tts, voice list
- Quirks: reference_id = voice id; `latency:"normal"|"streaming"` tunes; Chinese-first UI
  but docs are EN.
```json
{"id":"fish-audio","name":"Fish Audio","dialect":"TEMPLATE","baseUrl":"https://api.fish.audio",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioSpeech":"/v1/tts/stream","models":"/v1/voices"},
 "capabilities":{"tts":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","path":"/v1/voices","models":[]}}
```

## #091 — Stability AI ⚠️ DOCS UNREACHABLE — SPA-only, verify on first live call
- dialect: TEMPLATE
- baseUrl: `https://api.stability.ai/v2beta`
- auth: BEARER (`Authorization: Bearer sk-st-…`)
- endpoints: imagesGenerate=`/stable-image/generate/{model}` (model in path: `flux1-k2`,
  `flux1-schnell`, `sd3.5-large`; POST `multipart/form-data` — `{prompt, aspect_ratio,
  output_format:"png|jpeg|webp", seed, negative_prompt, strength}`; `Accept: image/*` →
  BINARY body (imageOutput:"raw"); `n` via `["n"]`? — one image per call default;
  `mode` for control), imagesEdit=`/stable-image/edit/erase|inpaint|outpaint|search-and-replace`
  (multipart: `image` file + prompt), upscale=`/stable-image/upscale` (multipart image,
  `output_format`, `creativity`), audio=`/audio/stable-audio-2.0/generation` (POST JSON
  `{prompt, seconds_total, steps?, cfg_scale?, seed, audio_start?}` → JSON `{audio:[b64]}`),
  tts=`/audio/stable-tts` (verify path)
- capabilities: imagesGenerate, imagesEdit, tts?
- Knobs: flux `aspect_ratio:"21:9|16:9|3:2|4:3|1:1|3:4|2:3|9:16|9:21"`, `terminate_on_end`,
  `width/height` (fixed ratio only), `seed`; sd3 `style_preset`.
- **Unreachable this pass:** platform.stability.ai is a client-side SPA — the doc pages and
  openapi.json return an empty shell to non-browser fetchers; api.stability.ai/openapi.json
  empty. The official `stability-sdk` repo now wraps the LEGACY gRPC API (grpc.stability.ai)
  — NOT the v2beta REST. So the v2beta REST shapes above stand on long-term stability and
  the CF-flux precedent, but are NOT re-verified this pass.
- **Probe procedure (do before shipping):** with a Stability key —
  (1) `POST /v2beta/stable-image/generate/flux1-k2` multipart w/ one prompt, header
  `Accept: image/*` → expect 200 + binary PNG (if 400, check multipart shape);
  (2) `GET /v2beta/stable-image/models` (if 404, it's `GET /stable-image/models` on v2beta —
  noted in some SDKs) for the live model list; (3) update this entry with observed shapes.
```json
{"id":"stability","name":"Stability AI","dialect":"TEMPLATE","baseUrl":"https://api.stability.ai/v2beta",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"imagesGenerations":"/stable-image/generate","imagesEdits":"/stable-image/edit"},
 "capabilities":{"imagesGenerate":true,"imagesEdit":true,"tts":true},
 "imageInput":"multipart","imageOutput":"raw","imageModelInPath":true,
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #092 — Ideogram 🟡
- dialect: TEMPLATE
- baseUrl: `https://api.ideogram.ai/v1`
- auth: BEARER (X-API-Key accepted? — docs use `Authorization: Bearer`)
- endpoints: imagesGenerate=`/generation` (POST JSON `{image_request:{prompt, aspect_ratio:
  "1:1|16:9|9:16"|"4:3"|"3:4"|"2:3"|"3:2", model:"V_3"|"V_3_TURBO"|"V_2.5", magic_prompt_option:
  "auto"|"on"|"off", style_type:"auto|photographic|anime|3D-render|...", scheduler,
  negative_prompt, seed, n}}` → `{created, data:[{url, resolution, is_image_generated}]}` —
  imageOutput:"json" (url, not b64!) — pin url-join in transform), upscale=
  `/generations/{id}/upscale` (GET? POST with image_path — verify), edit=`/edit` (multipart:
  image_file + prompt)
- capabilities: imagesGenerate, imagesEdit
- Quirks: returns URLs (hosted 24h?) — download or redirect; `magic_prompt` cheaper on V_3_TURBO.
```json
{"id":"ideogram","name":"Ideogram","dialect":"TEMPLATE","baseUrl":"https://api.ideogram.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"imagesGenerations":"/generation","imagesEdits":"/edit"},
 "capabilities":{"imagesGenerate":true,"imagesEdit":true},
 "imageInput":"json","imageOutput":"json","imageModelInPath":false,
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #093 — BFL (Black Forest Labs, official Flux) 🟡 (VERIFIED 2026-09: docs.bfl.ml)
- dialect: TEMPLATE (async)
- baseUrl: `https://api.bfl.ai/v1`
- auth: BEARER (`Authorization: Bearer <key>`)
- endpoints: imagesGenerate=`/{model}` (POST JSON `{prompt, image, width, height,
  aspect_ratio, steps, guidance_scale, interval, safety_tolerance, output_format, webhook}`
  → `{id}` — ASYNC; poll `GET /get_result?id={id}` → `{status:"Ready", result:
  {sample:"https://…"}}`; statuses incl. Started/Pending/Request Moderated/Error/Content
  Moderated)
- capabilities: imagesGenerate, imagesEdit (control via `image` + `start_step`)
- **Docs moved (VERIFIED):** full docs now at **docs.bfl.ml** (llms.txt:
  docs.bfl.ml/llms.txt). Product line 2026:
  - **FLUX 3** — one model, one API: text-to-video (audio included), image-to-video
    (keyframes), audio rendering — new family;
  - **FLUX.2 [klein]/[dev]** — still supported for production image gen/editing; open weights;
  - **FLUX Tools** — outpainting, erase, deblur, virtual try-on;
  - MCP integration + playground.bfl.ai; open-weights helm via HF black-forest-labs.
- Quirks: async poll pattern maps to template `videos`/`tasks` ops; results are URLs.
```json
{"id":"bfl-flux","name":"BFL Flux","dialect":"TEMPLATE","baseUrl":"https://api.bfl.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"imagesGenerations":"/flux-pro-1.1","tasks":"/get_result"},
 "capabilities":{"imagesGenerate":true},
 "imageInput":"json","imageOutput":"json","imageModelInPath":true,
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #094 — Recraft 🟡
- dialect: TEMPLATE (+ OpenAI-style compat for some endpoints)
- baseUrl: `https://api.recraft.ai/v1`
- auth: BEARER
- endpoints: imagesGenerate=`/images/generations` (POST JSON `{prompt, model:"recraft-v3",
  style:"any|realistic_image|digital_illustration|vector_illustration|icon|...", size:
  "1024x1024|1365x1024|1024x1365|1536x1024|..."|"square_hd|portrait_4_5|...",
  response_format:"url|b64_json", colors?, contrast?}` → `{data:[{url|b64_json}]}`),
  edit=`/images/edits` (multipart: image + prompt), multiple styles list `GET /styles`
- capabilities: imagesGenerate, imagesEdit
- Quirks: style taxonomy is Recraft-specific; response_format b64 supported (good).
```json
{"id":"recraft","name":"Recraft","dialect":"TEMPLATE","baseUrl":"https://api.recraft.ai/v1",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"imagesGenerations":"/images/generations","imagesEdits":"/images/edits"},
 "capabilities":{"imagesGenerate":true,"imagesEdit":true},
 "imageInput":"json","imageOutput":"json","imageModelInPath":false,
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #095 — Runway (Runway Dev) 🟡 (VERIFIED 2026-09: docs.dev.runwayml.com)
- dialect: TEMPLATE (async tasks)
- baseUrl: `https://api.dev.runwayml.com/v1`
- auth: BEARER + required header `X-Runway-Version: 2024-11-06` (VERIFIED — versioned
  API, changelog + versions page on docs)
- endpoints (VERIFIED): image_to_video=`POST /v1/image_to_video`
  `{model:"gen4.5", promptText, ratio:"1280:720"|"1280:768"|…, duration:5|10}` →
  `{id}`, text_to_video=`POST /v1/text_to_video` (same body), video_to_video=`POST /v1/
  video_to_video` (Aleph 2.0), text_to_image=`POST /v1/text_to_image` (GPT Image 2 routed),
  upscale=`/v1/upscale`, tasks=`GET /v1/tasks/{id}` → `{status, output:[urls]}` (poll;
  SDK `.waitForTaskOutput()` — task.output[0] = video URL)
- capabilities: video (template videos+tasks), image (text_to_image), image2video
- Models (VERIFIED 2026): **Seedance 2.5** (1080p, up to 30s, large reference budget for
  image/video/audio), **Gen 4.5**, **Aleph 2.0** (video-to-video), **GPT Image 2**
  (text-to-image via Runway routing)
- Quirks: docs re-platformed (docs.dev.runwayml.com, astro); MCP for Cursor/Claude/Codex;
  Characters API for avatars separate product (D6/TURN_STREAM later); version header now
  documented under api-details/versioning.
```json
{"id":"runway","name":"Runway","dialect":"TEMPLATE","baseUrl":"https://api.dev.runwayml.com/v1",
 "auth":{"scheme":"BEARER","apiKey":"","extraHeaders":{"X-Runway-Version":"2024-11-06"}},
 "endpoints":{"videos":"/text_to_video","tasks":"/tasks"},
 "capabilities":{"imagesEdit":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #096 — Luma (Dream Machine) ✅ (VERIFIED 2026-09: docs.lumalabs.ai OpenAPI v1.1.0)
- dialect: TEMPLATE (async)
- baseUrl: `https://api.lumalabs.ai/dream-machine/v1` (VERIFIED OpenAPI server)
- auth: BEARER (JWT-format key)
- endpoints (VERIFIED): videos=`POST /generations/video`, tasks=`GET /generations/{id}`
  (also GET /generations list w/ filters, DELETE, /generations/credits, /ping),
  image=`POST /generations/image`, reframe=`POST /generations/reframe_image|reframe_video`,
  modify=`POST /generations/modify_video`, upscale=`POST /generations/upscale_video`
- Video body (VERIFIED schema): `{generation_type:"video", prompt, aspect_ratio,
  loop:bool, keyframes:{frame0/frame1:{type:"image"|"generation", url|id}},
  callback_url (POST Generation on dreaming/completed/failed), model:"ray-2"|"ray-flash-2",
  resolution:"540p"|"720p"|"1080p"|"4k", duration:"5s"|"9s", concepts:[{key}]}`
  — aspect_ratio enum: `1:1|16:9|9:16|4:3|3:4|21:9|9:21` (default 16:9)
- Response: `{id, generation_type:"video"|"image", state:"queued"|"dreaming"|"completed"|
  "failed", failure_reason, created_at, assets:{video|image|progress_video (urls)}, model,
  request}` — poll GET until completed; assets.video = mp4 URL
- Image body: `{generation_type:"image", model:"photon-1"|"photon-flash-1", prompt,
  aspect_ratio, format:"jpg"|"png", image_ref[] (url+weight), style_ref[], character_ref,
  modify_image_ref, sync:bool (default false), sync_timeout (default 60), callback_url}`
- Extras: modify_video modes `adhere_1..3|flex_1..3|reimagine_1..3`; upscale_video
  `{resolution}`; add_audio `{prompt, negative_prompt}`.
- Quirks: **Dream Machine API is being superseded** — docs banner: new API platform at
  platform.lumalabs.ai, current docs at docs.agents.lumalabs.ai (Agents API) — portal may
  add a second `luma-agents` entry later; callback_url to avoid polling.
```json
{"id":"luma","name":"Luma Dream Machine","dialect":"TEMPLATE",
 "baseUrl":"https://api.lumalabs.ai/dream-machine/v1","auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"videos":"/generations/video","tasks":"/generations"},
 "capabilities":{"imagesGenerate":true},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","models":[]}}
```

## #097 — Pika (video) 🟡
- dialect: TEMPLATE (async; API v2)
- baseUrl: `https://api.pika.art/v2`
- auth: X-API-Key? — Pika v2 uses `Authorization: Bearer` + optional `X-User-Id` — VERIFY
- endpoints: generate=`/generate` (POST `{model:"pika-2.2", prompt, flow:"text_to_video"|
  "image_to_video"|"text_to_image"|"image_to_image", image_url, aspect_ratio, duration?
  }` → `{id}`), poll `GET /generate/{id}` → `{status:"succeeded", result:{outputs?}}` —
  verify path (docs changed)
- capabilities: video, image
- Quirks: evolving public API; mark verify before wiring.
```json
{"id":"pika","name":"Pika","dialect":"TEMPLATE","baseUrl":"https://api.pika.art/v2",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"videos":"/generate","tasks":"/generate"},
 "capabilities":{},"streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #098 — Adobe Firefly Services 🟡
- dialect: TEMPLATE
- baseUrl: `https://firefly-api.adobe.io` (also `https://firefly-api.adobe.io/v2`)
- auth: OAUTH2 (client-credentials `X-Adobe-Provider` headers — access token via
  `https://ims-na1.adobelogin.com/ims/token/v3` client_id+client_secret → `Authorization:
  Bearer <token>`)
- endpoints: imagesGenerate=`/v2/images/generate` (POST JSON `{numVariations, size:
  {width,height}, prompt, negativePrompt, stylePreset, seed, contentClass, structure:
  {referenceImage:{source:{url|base64}, strength}}}` → `{outputs:[{storage: {type,
  signedUrl}}]}` — results are SIGNED URLs), edit=`/v2/images/{endpoint}` (expand/fill/
  remove — upload + poll), models list `/v2/models`? (GET)
- capabilities: imagesGenerate, imagesEdit (generative fill/expand/remove)
- Quirks: signed-URL outputs (download via transform); `X-API-Key` header sometimes
  required alongside bearer; rate limits by entitlement.
```json
{"id":"adobe-firefly","name":"Adobe Firefly","dialect":"TEMPLATE",
 "baseUrl":"https://firefly-api.adobe.io","auth":{"scheme":"OAUTH2","apiKey":""},
 "endpoints":{"imagesGenerations":"/v2/images/generate","models":"/v2/models"},
 "capabilities":{"imagesGenerate":true,"imagesEdit":true},
 "imageInput":"json","imageOutput":"json","imageModelInPath":false,
 "streaming":"CHUNKED","catalog":{"mode":"STATIC","models":[]}}
```

## #099 — Azure Speech (TTS/STT) 🟡
- dialect: TEMPLATE (+ WS)
- baseUrl: `https://{REGION}.tts.speech.microsoft.com` (TTS) /
  `https://{REGION}.stt.speech.microsoft.com` (STT)
- auth: X_API_KEY `keyHeader:"Ocp-Apim-Subscription-Key"` (+ `X-Microsoft-OutputFormat:
  audio-24khz-48kbitrate-mono-mp3` header; Entra OAUTH2 optional)
- endpoints: tts=`/cognitiveservices/v1` (POST SSML body `<?xml…<speak><voice name=
  "en-US-AvaMultilingualNeural">…</voice></speak>`; `Accept: audio/mpeg` → binary;
  non-streaming legacy), ttsWS=`wss://{r}.tts.speech.microsoft.com/cognitiveservices/
  websocket/v1` (bidi: `speech.config` + `synthesis.context` + `ssml` JSON frames →
  audio frames incl. WordBoundary events — D6 TURN_STREAM), stt=`https://{r}.stt.speech
  .microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US`
  (POST audio; `Ocp-Apim-Subscription-Key`), sttWS=`wss://{r}.stt.speech.microsoft.com/
  speech/recognition/conversation/cognitiveservices/v1?language=en-US` (binary audio in,
  `RecognitionResult` JSON out)
- capabilities: tts, stt (batch+streaming), voice TURN_STREAM
- Quirks: SSML is mandatory for TTS (voice name, prosody{rate,pitch,volume}, break);
  output formats via X-Microsoft-OutputFormat header values (mp3/ogg/pcm16k);
  neural voices list via `GET /cognitiveservices/voices/list`.
```json
{"id":"azure-speech","name":"Azure Speech","dialect":"TEMPLATE",
 "baseUrl":"https://eastus.tts.speech.microsoft.com",
 "auth":{"scheme":"X_API_KEY","apiKey":"","keyHeader":"Ocp-Apim-Subscription-Key"},
 "endpoints":{"audioSpeech":"/cognitiveservices/v1","models":"/cognitiveservices/voices/list"},
 "capabilities":{"tts":true,"stt":true,"voice":"TURN_STREAM"},"streaming":"CHUNKED",
 "catalog":{"mode":"STATIC","path":"/cognitiveservices/voices/list","models":[]}}
```

## #100 — MiniMax Audio (t2a_v2 TTS) ✅ (VERIFIED 2026-09: platform.minimaxi.com OpenAPI)
- dialect: TEMPLATE (native)
- baseUrl: `https://api.minimax.cn` (CN — VERIFIED OpenAPI server) /
  `https://api.minimax.io` (intl); alt host `https://api-bj.minimaxi.com`
- auth: BEARER (account API key)
- endpoints: audioSpeech=`/v1/t2a_v2` (POST JSON, VERIFIED)
- Body (VERIFIED schema): `{model (REQUIRED), text (REQUIRED, ≤10000 chars),
  stream:false, stream_options:{exclude_aggregated_audio}, voice_setting:{voice_id
  (REQUIRED; mixer → leave empty + timbre_weights), speed [0.5,2], vol (0,10], pitch
  [-12,12], emotion:"happy|sad|angry|fearful|disgusted|surprised|calm|fluent|whisper",
  text_normalization, latex_read}, audio_setting:{sample_rate [8000..44100], bitrate
  [32000..256000] (mp3 only), format:"mp3|pcm|flac|wav|pcmu_raw|pcmu_wav|opus" (default
  mp3), channel 1|2, force_cbr}, pronunciation_dict:{tone:["原/替"…]}, timbre_weights:
  [{voice_id, weight 1–100}] (max 4 voices), language_boost (≈40 langs + auto),
  voice_modify:{pitch,intensity,timbre [-100,100], sound_effects:"spacious_echo|
  auditorium_echo|lofi_telephone|robotic"}, subtitle_enable, subtitle_type:"sentence"|
  "word"|"word_streaming", output_format:"hex"|"url" (url valid 24h, non-stream only),
  aigc_watermark}`
- Models (VERIFIED enum): `speech-2.8-hd`, `speech-2.8-turbo`, `speech-2.6-hd`,
  `speech-2.6-turbo`, `speech-02-hd`, `speech-02-turbo`, `speech-01-hd`, `speech-01-turbo`
- Response (VERIFIED): non-stream JSON `{data:{audio: <HEX (not base64!)>, subtitle_file,
  status:1|2}, extra_info:{audio_length,audio_sample_rate,audio_size,bitrate,
  audio_format,audio_channel,word_count,usage_characters}, trace_id, base_resp{
  status_code (0 ok · 1004 auth fail · 1002 rate-limited · 1039 TPM · 2013 bad params),
  status_msg}}`; stream → SSE chunks `data.audio` hex with status 1→2 (last chunk may
  include aggregated audio).
- Quirks: audio payload is HEX-encoded regardless of format; subtitle JSON link for word
  timestamps; trace_id in header for support; text tags `(laughs)` etc. only on 2.8 models;
  pause tag `<#x#>` secs; inline pronunciation `(pin1)(yin2)` / IPA / Jyutping.
```json
{"id":"minimax-audio","name":"MiniMax Audio","dialect":"TEMPLATE","baseUrl":"https://api.minimax.cn",
 "auth":{"scheme":"BEARER","apiKey":""},
 "endpoints":{"audioSpeech":"/v1/t2a_v2"},
 "capabilities":{"tts":true},"streaming":"SSE",
 "catalog":{"mode":"STATIC","models":[]}}
```