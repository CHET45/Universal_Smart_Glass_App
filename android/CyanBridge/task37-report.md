# Task 37 — Chapter 6: Transcription POC (CyanBridge)

Worktree: `/home/fertroll10/Documents/ML/CyanBridgeSDK-wt/task37`

App: `/home/fertroll10/Documents/ML/CyanBridgeSDK-wt/task37/android/CyanBridge`

## Summary
Implemented Chapter 6 Transcription POC with a pluggable `TranscriptionService`, chunking + retry orchestration, fake + HTTP provider skeleton, progress/failure events, privacy-first transcript persistence (OFF by default) to a NEW transcription-specific Room table, plus a minimal debug Activity to run the flow manually.

No changes were made to `SettingsActivity` or Settings layouts. No changes were made to the `Note` entity/schema.

## Key Implementation (files)
### 1) Pluggable interface + events/models
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/TranscriptionService.kt`
- `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/TranscriptionModels.kt`

### 2) Chunking strategy
- Chunk model + helper:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/chunking/FileChunk.kt`
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/chunking/FileChunker.kt`

### 3) Retry policy + failure states + progress reporting
- Retry:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/retry/RetryPolicy.kt`
- Orchestration + progress/failure events:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/ChunkingTranscriptionService.kt`

### 4) Providers
- Fake provider (tests/manual offline):
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/backend/FakeTranscriptionBackend.kt`
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/FakeTranscriptionService.kt`
- HTTP provider skeleton (configurable endpoint):
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/backend/HttpTranscriptionBackend.kt`
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/HttpTranscriptionService.kt`
- Endpoint prefs (no Settings screen changes):
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/TranscriptionEndpointPrefs.kt`

### 5) Storage behavior (privacy-first, OFF by default)
- Privacy toggle (SharedPreferences; Chapter 8 will add real UI later):
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/privacy/PrivacyPrefs.kt`
- NEW Room entity/table for transcripts (NOT notes):
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/data/local/entity/CaptureTranscript.kt`
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/data/local/dao/CaptureTranscriptDao.kt`
- Persistence gate implementation:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ai/transcription/storage/RoomTranscriptStore.kt`

### 6) Minimal UI hook (debug activity)
- Activity:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/debug/TranscriptionDebugActivity.kt`
- Layout:
  - `android/CyanBridge/app/src/main/res/layout/activity_transcription_debug.xml`
- Manifest registration:
  - `android/CyanBridge/app/src/main/AndroidManifest.xml`

Launch (manual POC):
```bash
adb shell am start -n com.fersaiyan.cyanbridge/.ui.debug.TranscriptionDebugActivity
```

## DB changes
- `AppDatabase` bumped to v3 with migration 2→3 creating `capture_transcripts`.
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/data/local/AppDatabase.kt`
- `MyApplication` now registers the migration:
  - `android/CyanBridge/app/src/main/java/com/fersaiyan/cyanbridge/ui/MyApplication.kt`

## Tests
### Unit tests (run by `testDebugUnitTest`)
- Chunker tests:
  - `android/CyanBridge/app/src/test/java/com/fersaiyan/cyanbridge/ai/transcription/chunking/FileChunkerTest.kt`
- Retry policy tests:
  - `android/CyanBridge/app/src/test/java/com/fersaiyan/cyanbridge/ai/transcription/retry/RetryPolicyTest.kt`
- Service orchestration tests:
  - `android/CyanBridge/app/src/test/java/com/fersaiyan/cyanbridge/ai/transcription/ChunkingTranscriptionServiceTest.kt`

### Instrumentation test (optional)
- Room transcript persistence gated by privacy toggle:
  - `android/CyanBridge/app/src/androidTest/java/com/fersaiyan/cyanbridge/ai/transcription/storage/RoomTranscriptStoreAndroidTest.kt`

## Verification
Executed:
```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew testDebugUnitTest assembleDebug
```
Result: **BUILD SUCCESSFUL**

## Notes / Usage
- Transcript storage is **OFF by default** (privacy-first). Enable it from the debug screen checkbox to persist into `capture_transcripts`.
- HTTP backend is a skeleton: the server should accept multipart fields described in `HttpTranscriptionBackend` and return plain text or JSON with a `text` field.
