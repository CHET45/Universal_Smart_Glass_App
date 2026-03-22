# Local Models Implementation Note

## Stack detection

- Project type: native Android/Kotlin (Gradle app module, XML layouts, Activities, no React Native runtime).
- Existing provider architecture: Tasker / API models / Pro subscription routing via `AiProviderPrefs` and `AiAssistantRouter`.

## Chosen backend

- Local backend: **llama.cpp** through a small Android binding (`io.github.ljcamargo:llamacpp-kotlin`).
- Reason: this keeps inference in-app/on-device, avoids adding a localhost service, and keeps JNI complexity isolated in a maintained binding.

## Architecture decisions

- Added a dedicated local-model layer under `com.fersaiyan.cyanbridge.localmodels`:
  - catalog (curated GGUF metadata)
  - storage/import/download
  - prompt template registry
  - generation settings + profiles
  - llama.cpp engine wrapper
  - single-model session manager
  - provider bridge for existing chat router
- Extended `AiProviderType` with `LOCAL_MODELS` and mapped Settings "Local Models" provider to this route.
- Chat keeps the same contract (`messages -> reply`) while local provider additionally supports token streaming and cancellation.
- Models are stored in app-private storage under `files/local_models/models`.
