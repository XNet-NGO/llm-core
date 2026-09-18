# AGENTS.md — llm-core multi-agent protocol

Two agents work in this repository concurrently: **Kilo** (research/configs/verification)
and **kiro-cli** (build). Read `HANDOFF.md` FIRST at session start — it is the
cross-agent mailbox and holds the current status of shared contracts.

Rules:
1. `HANDOFF.md` is authoritative for handoff state; append to its MESSAGE LOG
   (dated entries) when starting/finishing milestones that touch shared contracts.
2. Commit prefix `[handoff]` for messages that carry handoff state.
3. Shared contracts live in `research/providers-100/00-index.md` (§0 schema pin) —
   do not rename `ProviderConfig` fields silently.
4. `ProviderConfig` JSON blocks in `research/providers-100/*.md` are copies of truth;
   portal/static config generation may consume them directly.
5. Live smoke tests gate provider enablement: 🟢 verified → safe; 🟡 doc-pinned but
   untested → add smoke test before shipping; probe procedures exist in-file for the
   unreachable pair (PlayHT, Stability).