### Chapter 5 / Task 36 — Audio Capture Pipeline (Meeting Mode)

**Summary**
Implemented a phone-side meeting audio capture pipeline with:
- Start/Stop capture controls
- Bluetooth mic (SCO / call-mode) preferred with automatic fallback to phone mic
- Optional timer (15m / 1h / 3h / none) with auto-stop
- Visible recording indicator (in-app banner) + persistent foreground notification
- Session metadata persisted locally

---

## What was implemented

### 1) Meeting capture start/stop + source selection
- Added `MeetingCaptureService` (foreground service) to manage audio recording lifecycle.
- Recording uses `MediaRecorder`:
  - Tries Bluetooth SCO / call-mode when available.
  - Uses `AudioSource.VOICE_COMMUNICATION` when Bluetooth mic is active.
  - Falls back to `AudioSource.MIC` (phone mic) when Bluetooth SCO cannot be established.
- Saves audio to:
  - `Android/data/<package>/files/recordings/meeting_yyyyMMdd_HHmmss.m4a`

### 2) Timer option (auto-stop)
- UI provides a timer dropdown (No timer / 15 min / 1 hour / 3 hours).
- Service schedules an auto-stop when a duration is selected.

### 3) Visible recording indicator
- **In-app banner** at the top of `MainActivity` with a Stop button.
- **Persistent notification** via foreground service (includes Stop action).

### 4) Session metadata persisted
- Added Room entity/table `capture_sessions` and DAO.
- Saved fields include:
  - startedAt / endedAt / durationSec
  - deviceClass (from last selected device profile)
  - captureSource (BLUETOOTH_MIC vs PHONE_MIC)
  - audioPath
  - timerDurationSec, stopReason, error

### 5) Permissions + Manifest
- Manifest additions:
  - `RECORD_AUDIO`
  - `POST_NOTIFICATIONS`
  - `FOREGROUND_SERVICE_MICROPHONE`
  - Foreground service declaration: `.audio.MeetingCaptureService` with `foregroundServiceType="microphone"`
- `MainActivity` requests runtime permissions for mic (and notifications on Android 13+).

---

## Key files changed/added
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/MeetingCaptureService.kt` (new)
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/CaptureSource.kt` (new)
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/MeetingCapturePrefs.kt` (new)
- `app/src/main/java/com/fersaiyan/cyanbridge/audio/CaptureTimer.kt` (new; pure helper for unit tests)
- `app/src/main/java/com/fersaiyan/cyanbridge/data/local/entity/CaptureSession.kt` (new)
- `app/src/main/java/com/fersaiyan/cyanbridge/data/local/dao/CaptureSessionDao.kt` (new)
- `app/src/main/java/com/fersaiyan/cyanbridge/data/local/AppDatabase.kt` (version bump to 2 + migration 1→2)
- `app/src/main/java/com/fersaiyan/cyanbridge/ui/MyApplication.kt` (adds migration)
- `app/src/main/java/com/fersaiyan/cyanbridge/data/repository/CyanBridgeRepository.kt` (session insert/query)
- `app/src/main/res/layout/acitivyt_main.xml` (meeting controls + banner)
- `app/src/main/AndroidManifest.xml` (permissions + service)
- `app/src/test/java/com/fersaiyan/cyanbridge/audio/CaptureTimerTest.kt` (new)

---

## Build / verification
Ran:
```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew testDebugUnitTest assembleDebug
```
Result: **BUILD SUCCESSFUL**

---

## Manual checklist (suggested)
- Start meeting capture with Bluetooth headset/glasses connected → banner shows "Bluetooth mic".
- Start meeting capture with no BT mic available → banner shows "Phone mic".
- Lock/unlock screen while recording → notification persists, recording continues.
- Set timer to 15 minutes (or 1h/3h) → auto-stops and UI updates.
- Verify output `.m4a` exists and is playable.
