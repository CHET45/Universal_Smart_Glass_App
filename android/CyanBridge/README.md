# CyanBridge Manager (Android)

CyanBridge Manager is the Android app in this repository for smart-glasses pairing,
meeting capture, transcription, summarization, and privacy-first note export.

This app lives under:

- `android/<app-folder>/` (this repository's Android app module)

## What is implemented today

- Multi-thread chat UI backed by local Room persistence (`chat/`, `ui/ChatThreadActivity.kt`).
- Glasses manager with class detection + manual class override (`devices/`, `ui/MainActivity.kt`).
- Meeting capture foreground service with timer and metadata persistence (`audio/MeetingCaptureService.kt`).
- Pluggable transcription service and chunking pipeline (`ai/transcription/`).
- Structured meeting summarization and notes storage (`ai/summarization/`, `notes/`).
- Privacy toggles for transcript storage, redaction, and export behavior (`privacy/`, `ui/SettingsActivity.kt`).

## Module map

- `app/src/main/java/com/fersaiyan/cyanbridge/ui/` - activities, adapters, and UI glue.
- `app/src/main/java/com/fersaiyan/cyanbridge/devices/` - device classes, detection heuristics, gating, profile persistence.
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/` - capture source/timer/prefs and foreground capture service.
- `app/src/main/java/com/fersaiyan/cyanbridge/ai/` - transcription + summarization interfaces and implementations.
- `app/src/main/java/com/fersaiyan/cyanbridge/notes/` - notes repository and note creation from transcripts.
- `app/src/main/java/com/fersaiyan/cyanbridge/privacy/` - redaction and export policy helpers.
- `app/src/main/java/com/fersaiyan/cyanbridge/data/` - Room entities, DAOs, and repository.

## Build and test

Use the Android Studio bundled JDK (Java 17+):

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew assembleDebug
```

Useful test commands:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew testDebugUnitTest
JAVA_HOME=/opt/android-studio/jbr ./gradlew connectedDebugAndroidTest
```

## Privacy defaults (MVP)

- Transcript storage: OFF by default.
- Name redaction in exports: ON by default.
- Full transcript in exports: OFF by default.

These defaults are controlled by `privacy/PrivacyPrefs.kt` and applied through
`privacy/NoteExportFormatter.kt`.

## Future agent handoff

- Product scope and chapter-based acceptance gates are in root `AGENTS.md`.
- Working checklist and evidence tracking are in root `MVP_CHECKLIST.md`.
- Android-specific protocol notes for vendor behavior are in `android/AGENTS.md`.

When adding features, keep interfaces pluggable (especially in `ai/` and `audio/`) and
prefer local-first storage + explicit user control for any recording/transcription flow.
