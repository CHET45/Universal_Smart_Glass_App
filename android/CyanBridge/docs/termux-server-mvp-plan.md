# Termux Server MVP Plan

## Current app server dependencies (inspected)

1. **AI relay endpoints (runtime app features)**
   - Used by `CliRelayClient` in `app/src/main/java/com/fersaiyan/cyanbridge/ai/router/CliRelayRouter.kt`.
   - Expected endpoints:
     - `POST /health`
     - `POST /chat`
     - `POST /voice-query`
     - `POST /image-query`

2. **OpenAI-compatible API model path (runtime app features)**
   - Used by `DirectApiClient` in `app/src/main/java/com/fersaiyan/cyanbridge/ai/router/CliRelayRouter.kt`.
   - Expected endpoints:
     - `GET /models` (connection test UI)
     - `POST /chat/completions` (or `/v1/chat/completions`, depending on configured base URL)

3. **Pro subscription verification (runtime app feature, optional)**
   - Used by `ProSubscriptionVerifier` in `app/src/main/java/com/fersaiyan/cyanbridge/agent/ProSubscriptionVerifier.kt`.
   - Expected endpoint:
     - `POST /pro/verify` (or configured override URL)
   - Existing behavior already falls back to local status when verifier is unavailable.

4. **HTTP transcription backend (debug/manual flow)**
   - Used by `HttpTranscriptionBackend` and `TranscriptionDebugActivity`.
   - Expected endpoint:
     - `POST /transcribe` (multipart upload)

5. **Not currently wired to real backend calls**
   - Memory vault cloud sync/search/enrollment contracts exist as stubs in
     `app/src/main/java/com/fersaiyan/cyanbridge/memoryvault/contracts/FutureBackendContracts.kt`.
   - These are intentionally not connected to network transport yet.

## MVP support decision

### Implemented in Termux MVP server

- `GET /health` + `POST /health`
- `GET /capabilities`
- `POST /chat`
- `POST /voice-query`
- `POST /image-query`
- `GET /models` and `GET /v1/models`
- `POST /v1/chat/completions`
- `POST /pro/verify`
- `POST /transcribe`

### Deliberately unsupported in MVP (capability=false)

- Cloud memory sync API
- Cloud memory search API
- Device enrollment API
- Web checkout backend orchestration

## Chosen stack

- **Python + FastAPI + Uvicorn + SQLite**
- Why:
  - Runs directly in Termux without Docker.
  - Small dependency footprint.
  - Easy endpoint parity with existing app contracts.
  - SQLite is sufficient for local MVP entitlement persistence.

## Deployment model on Termux

- Bundle in `_local_termux_server/` (kept git-untracked via `.git/info/exclude`).
- Shell scripts for install/start/stop/restart/status/logs/backup/restore/update.
- Optional Termux:Boot script to auto-start service on phone boot.

## Capability negotiation and honest fallback

1. Server exposes `GET /capabilities` with explicit booleans.
2. App now probes relay capabilities (`RelayServerCapabilitiesClient`) before chat/voice/image calls.
3. If capability is missing, app surfaces an explicit unavailable error instead of pretending success.
4. Pro verification now checks capabilities and falls back to local status with clear message.

## App wiring done for Termux profile

- Runtime relay settings support:
  - Relay base URL
  - Pro verify endpoint override
  - HTTP transcription endpoint
  - Optional shared token
