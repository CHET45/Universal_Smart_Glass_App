# CyanBridge: Jetpack Compose + Material 3 Migration Plan

## Overview

Migrate the entire CyanBridge Android application from XML-based Views (AppCompat/Material Components) to Jetpack Compose with Material 3 design language. This is a full migration — all screens, all layouts, all interactions — with a complete cleanup of legacy View code at the end.

**Target:** Production-ready app with modern UI stack, maintained code quality, and clean architecture.

**Estimated total effort:** ~18-19 days (full-time)

---

## Phase 0 — Setup & Foundation (~2 days)

### 0.1 Add Compose Dependencies
- Add Compose BOM (latest stable release) to `build.gradle`
- Add Compose Compiler Plugin (matched to Kotlin version in `libs.versions.toml`)
- Add Material 3: `androidx.compose.material3`
- Add Navigation Compose: `androidx.navigation:navigation-compose`
- Add ViewModel Compose: `androidx.lifecycle:lifecycle-viewmodel-compose`
- Add Activity Compose: `androidx.activity:activity-compose`
- Add Hilt for Compose (keep existing Hilt setup if already present)
- Ensure Kotlin JVM target is 1.9+ (required for Compose compiler)

### 0.2 Create CyanBridgeTheme
Create `ui/theme/CyanBridgeTheme.kt`:
- Define brand colors: Cyan primary (`#00BCD4` or current brand), dark backgrounds, light/dark variants
- Create `ColorScheme` for light and dark themes (Material 3 `darkColorScheme`, `lightColorScheme`)
- Define `Typography` matching current text styles (headlines, body, captions)
- Wrap in `Theme` composable with `MaterialTheme`
- Support system dark/light mode toggle

### 0.3 Set Up Compose Navigation
Create `NavHost` in main activity:
- Mirror all existing Activity destinations as Compose routes:
  - `chat` — main chat screen
  - `history` — chat history / list
  - `settings` — settings screen
  - `pro` — Pro subscription
  - `welcome` — onboarding
  - etc.
- Bottom navigation bar with 4 tabs: Chat, History, Settings, Pro
- Animated transitions between routes (`AnimatedNavHost` if available)

### 0.4 Remove XML Theme Conflicts
- Audit `AndroidManifest.xml` for `<activity>` theme references using `AppCompat` themes
- Replace with Compose-compatible themes or remove explicit theme (let Compose handle it)
- Ensure `android:exported` and other manifest attributes are preserved
- Verify `fitsSystemWindows` behavior is replicated in Compose scaffold padding

---

## Phase 1 — Navigation Shell + Settings (~3 days)

### 1.1 Build MainScreen Scaffold
- Compose `Scaffold` with `BottomAppBar`
- 4 nav items with icons and labels: Chat, History, Settings, Pro
- `NavHost` routing to appropriate screens
- Proper back stack handling (pop up to correct destination)
- Handle deep links (`fersaiyan://`) matching existing `intent-filter` patterns

### 1.2 Migrate SettingsActivity → SettingsScreen
- Dark/light theme toggle using `DarkTheme` + `Switch`
- Model selection (calls existing `ProSubscriptionAiPrefs`)
- API key management
- Relay URL configuration
- Notification preferences
- Export/import data
- About/version info

### 1.3 Migrate AboutActivity → AboutScreen
- App version, credits, external links
- Open source licenses (`ABOUT_LIBS` or manual list)
- Can be a sub-screen within Settings or standalone

---

## Phase 2 — Chat Screen (~5 days)

**This is the most complex screen — allocate the most time here.**

### 2.1 Build ChatScreen Scaffold
- `Scaffold` with top app bar (model chip, overflow menu)
- `LazyColumn` for message list (reverse layout — newest at bottom)
- Input area: `OutlinedTextField` + voice mic button + send button

### 2.2 Message Bubble Components
- `UserMessageBubble` — right-aligned, primary color background, rounded corners
- `AssistantMessageBubble` — left-aligned, surface variant background
- Markdown rendering for assistant replies (use `Commonmark` library or `Markwon`)
- Code block rendering with monospace font and copy button
- Timestamp display (optional, toggleable)
- Loading indicator (`CircularProgressIndicator`) during generation

### 2.3 Voice Input Integration
- Voice input `IconButton` in bottom bar
- Trigger existing voice service (`SpeechRecognizer` or custom service)
- Animated mic indicator while recording
- Transcribed text auto-fills into input field
- Handle permission requests (`RECORD_AUDIO`)

### 2.4 Model Picker in Top Bar
- `SuggestionChip` or `FilterChip` showing current model name
- `ModalBottomSheet` with full model list:
  - Model name + server quota multiplier label (e.g. "gpt-5.4 (13x)")
  - Current model marked with checkmark
  - Plan-restricted models greyed out with tooltip "Upgrade to access"
- On selection: save to `ProSubscriptionAiPrefs`, update UI immediately

### 2.5 Relay-Down Toast UX
- `LaunchedEffect` observing `Throwable` error state from `ChatThreadRelayClient`
- `SnackbarHost` in scaffold showing: "Server unreachable. Try again later."
- Different severity for soft errors vs. hard connection failures
- Persist visibility across configuration changes

### 2.6 Daily Facts / Auto-Prompt
- Replicate existing daily facts review flow in Compose
- Card-based UI for fact summaries
- Accept/reject/dismiss interactions

### 2.7 Chat Thread Management
- New chat button in top bar
- Thread title display
- Continue conversation context

---

## Phase 3 — History Screen (~2 days)

### 3.1 Chat History List
- `LazyColumn` of chat sessions
- Each item: thread title, date (`SimpleDateFormat`), last message preview (truncated)
- Swipe-to-delete with `SwipeToDismissBox` (Material 3)
- Search bar at top (`SearchBar` composable) filtering by title/content

### 3.2 Thread Detail View
- Reuse `ChatScreen` with loaded message history
- Edit/delete individual messages via long-press context menu
- Pin important messages

### 3.3 Confirmation Dialogs
- Material 3 `AlertDialog` for delete confirmation
- `DismissibleDialog` or `BottomSheet` for other confirmations

---

## Phase 4 — Pro Subscription Screen (~2 days)

### 4.1 Plan Selection UI
- 4 plan cards: Trial, Cheap, Standard, Max
- Each card contains:
  - Plan name + icon/emoji
  - Price per month
  - Daily quota limit text
  - Feature list bullets
  - CTA button (primary for selected, outlined for others)
- Selected plan: elevated card with cyan border
- Plan descriptions from `/plans` endpoint (fetch on screen load)

### 4.2 Plan Change Logic
- **"Change Plan" button** in Plan & Account section:
  - Opens `AlertDialog` or `BottomSheet` with radio options for: Cheap, Standard, Max
  - Users cannot switch to Free Trial (one-time)
  - Selecting a plan triggers web checkout via `web-subscribe` URL
- Free Trial activation: calls `POST /pro/activate-trial` with Bearer token
- Paid plan switch: redirect to web checkout (`/web-subscribe?plan=X`)
- Handle success callback (`fersaiyan://pro-sub/callback`) updating local prefs

### 4.3 Subscription Status Display
- Current plan badge (e.g. "Standard", "Max")
- Renewal/expiry date
- Quota usage: `LinearProgressIndicator` showing tokens used vs. daily limit
- Last verified timestamp

---

## Phase 5 — Onboarding / Welcome Screen (~1 day)

### 5.1 WelcomeScreen
- Logo placeholder slot (existing drawable reference)
- Horizontal pager (`HorizontalPager`) with 3 slides:
  1. "AI on your glasses" — feature intro
  2. "Your data, your rules" — privacy + local models
  3. "Get started" — CTA to main screen
- `PageIndicator` dots at bottom
- Skip button (top-right)
- Next / Get Started buttons (bottom)
- On finish: write `onboarding_completed=true` to `SharedPreferences`

---

## Phase 6 — Remaining Screens (~1-2 days)

### 6.1 Migrate Any Remaining Simple Activities
- `LocalModelsConfigActivity` → `LocalModelsScreen`
- `CommunityPluginsActivity` → `CommunityPluginsScreen`
- `RecordingsListActivity` → `RecordingsScreen`
- `NotesListActivity` → `NotesScreen`

### 6.2 Deep Link Compatibility
- Map all existing `intent-filter` entries from XML Activities to Compose Navigation deep links:
  - `fersaiyan://chat`
  - `fersaiyan://settings`
  - `fersaiyan://pro`
  - `fersaiyan://pro-sub/callback`
  - etc.
- Test that external intents still resolve to correct screens

### 6.3 Widgets (if any)
- Migrate any home screen widgets to Glance (Compose-based widget API)

---

## Phase 7 — Cleanup (~2 days)

### 7.1 Remove Legacy View Code
- Delete all XML layouts in `res/layout/` (except `activity_main.xml` if needed for launcher)
- Delete all Fragment classes
- Delete old Activity classes (keep minimal stubs only if backward compatibility is critical)
- Remove `ViewBinding` and `DataBinding` plugin/dependencies
- Remove `androidx.appcompat` theme/style resources

### 7.2 Remove Unused Dependencies
Audit and remove from `build.gradle` / `libs.versions.toml`:
- `androidx.appcompat:appcompat` (if only used by old themes)
- `com.google.android.material:material` (replaced by Material 3)
- Old XML parsing libraries
- Any viewpager libraries replaced by Compose pager

### 7.3 Final Build Verification
- Run `./gradlew assembleDebug`
- Fix any remaining references to deleted layouts/views
- Ensure all Compose previews render correctly
- Verify light + dark themes on all screens

### 7.4 Testing Checklist
- [ ] Navigation: all bottom nav routes work
- [ ] Chat: send message, receive reply, voice input
- [ ] History: list, delete, open thread
- [ ] Settings: all toggles and preferences persist
- [ ] Pro Subscription: plan display, change plan, trial activation
- [ ] Welcome/Onboarding: completes and marks as done
- [ ] Deep links: all `fersaiyan://` routes resolve
- [ ] Relay-down UX: toast shows on connection failure
- [ ] Dark mode: all screens render correctly

---

## Effort Summary

| Phase | Tasks | Est. Time |
|---|---|---|
| 0 — Setup & Foundation | 0.1–0.4 | 2 days |
| 1 — Nav Shell + Settings | 1.1–1.3 | 3 days |
| 2 — Chat Screen | 2.1–2.7 | 5 days |
| 3 — History Screen | 3.1–3.3 | 2 days |
| 4 — Pro Subscription | 4.1–4.3 | 2 days |
| 5 — Onboarding | 5.1 | 1 day |
| 6 — Remaining Screens | 6.1–6.3 | 1-2 days |
| 7 — Cleanup | 7.1–7.4 | 2 days |
| **Total** | | **~18-19 days** |

---

## Technical Notes

### Dependency Compatibility
Ensure Kotlin version in `libs.versions.toml` is compatible with:
- Compose Compiler Plugin version (see [Compose-Kotlin compatibility map](https://developer.android.com/jetpack/androidx/releases/compose-kotlin))
- Hilt version if being used with Compose

### State Management
- Use `viewModel()` for screen-level state
- `StateFlow` for reactive UI state
- `rememberSaveable` for process-death surviving state
- Existing `SharedPreferences` classes (`ProSubscriptionPrefs`, etc.) can remain — wrap in `remember`/`produceState`

### Existing Business Logic
Keep existing logic in Kotlin classes untouched — only replace the UI layer:
- `ProSubscriptionRelayClient` — network calls, keep as-is
- `ProSubscriptionPrefs` — preferences, keep as-is
- `ProSubscriptionAiPrefs` — AI model prefs, keep as-is
- Backend integration (`ChatThreadRelayClient`, etc.) — keep as-is

### Performance
- Use `LazyColumn`/`LazyRow` for all lists
- Avoid recreating composables on every render — use `remember`
- Consider `derivedStateOf` for filtered/sorted lists
- Use `snapshotFlow` to bridge existing reactive streams to Compose

---

## File Reference: Existing Backend Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/plans` | GET | List all 4 plans with quota limits |
| `/pro/activate-trial` | POST | Activate free trial (auth required) |
| `/web-subscribe?plan=X` | GET | Redirect to Stripe checkout |
| `/pro/verify` | POST | Verify subscription |
| `/pro/quota?model=X` | GET | Get quota for model |
| `/models` | GET | List models with multipliers |
| `/chat` | POST | Send chat message |
| `/voice-query` | POST | Voice query |
| `/image-query` | POST | Image description |

---

*Last updated: 2026-03-22*
