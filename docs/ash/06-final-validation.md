# Project Ash — Final End-to-End Validation & Release Report

**Date:** 2026-11-24  
**Branch:** `phase4-bluetooth-integration` (base commit `e0ea3e0`, plus Phase 7 end-to-end fixes & CPH2467 validation)  
**Application ID:** `com.jarvis.assistant` (`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`, `versionName = "0.1.0"`, `versionCode = 1`)  
**Connected Test Device:** OnePlus **CPH2467** (Android 15, API 35, `arm64-v8a`, USB serial `<redacted>`)  
**Final Release-Readiness Verdict:** **`READY FOR CONTROLLED DEVICE TESTING`** *(Safe and ready for personal testing on CPH2467 today; full `READY FOR PERSONAL USE` awaits completion of the manual spoken/screen-off/calling/idle checklist in Section 23 on physical hardware.)*

---

## 1. Final Application Overview

Project Ash is a native Kotlin Android voice assistant built on `judejulius/jarvis` with native Kotlin Bluetooth routing concepts adapted from `lovisschmidt/yunto` (zero React Native/Expo). It provides:

- Offline `"Hey Ash"` (`@HEY_ASH`) and `"Hello Ash"` (`@HELLO_ASH`) wake detection via `sherpa-onnx` GigaSpeech 3.3M int8 Zipformer + Kroko streaming English ASR + Kokoro-82M paced TTS (with cloud TTS and Android TTS fallbacks).
- Persistent user-armed foreground microphone service (`JarvisService`, `FOREGROUND_SERVICE_TYPE_MICROPHONE`), Default Digital Assistant registration (`AshVoiceInteractionService` / `AshSessionService` / `AshRecognitionService`), and event-driven audio-interruption recovery (`OnModeChangedListener`, `ACTION_MICROPHONE_MUTE_CHANGED`, `AudioRecordingCallback`).
- Native Kotlin Bluetooth Classic (HFP/SCO API 26–35) and BLE Audio (`TYPE_BLE_HEADSET` API 31+) audio routing (`BluetoothAudioRouteController`, `TurnInputRoute`, `HeadsetButtonBridge` with `AudioPlaybackCallback`).
- Opt-in Direct Calling Mode (`DirectCallPolicy`, `CALL_PHONE` + `TelecomManager.placeCall` with `ACTION_DIAL` confirmation fallback).
- Deterministic local fast-command routing (apps, navigation, scrolling/swiping, tapping, typing, clearing text, search submission, YouTube search, website opening, volume, flashlight, alarms/timers, media playback, contacts/calling, time/date/battery) plus `PhoneAgent` multi-step AI automation behind Android 14+ secure-window and `OneUseApproval` boundaries.

---

## 2. Git Revision & Reports Reviewed

- **Inspected Branch:** `phase4-bluetooth-integration` (branched from `master` `73671c5`, prior HEAD `e0ea3e0`).
- **All Prior Reports Reviewed Chronologically:**
  - `docs/ash/00-repo-audit.md`
  - `docs/ash/01-architecture.md`
  - `docs/ash/02-implementation-status.md`
  - `docs/ash/03-bluetooth-integration.md`
  - `docs/ash/04-test-report.md`
  - `docs/ash/05-security-review.md`

---

## 3. Actual Modified, Added & Removed Files (Since Commit `e0ea3e0`)

### Added Production & Test Files
- `app/src/main/java/com/jarvis/assistant/contacts/DirectCallPolicy.kt` — Opt-in Direct Calling Mode policy gate (opt-in check, `CALL_PHONE` check, live spoken request guard, high-confidence match tier, emergency/short-code block, 10s duplicate-call cooldown).
- `app/src/main/java/com/jarvis/assistant/service/LockedCommands.kt` — Unified locked-screen command gate (`LockedCommands`) and bounded 30s one-shot `PendingUnlock` queue.
- `app/src/main/java/com/jarvis/assistant/service/TurnInputRoute.kt` — Main-thread Bluetooth/phone input negotiation coordinator for initial commands, `host.ask()` confirmations, and headset follow-ups.
- `app/src/test/java/com/jarvis/assistant/EndToEndPhase7Test.kt` — Unit tests verifying `DirectCallPolicy`, `LockedCommands`, `PendingUnlock`, `OpenWebsite`, `HIGH-01`, `HIGH-02`, and `MED-01`.

### Modified Production, Resource & Test Files
- `app/src/main/AndroidManifest.xml` — Declared `CALL_PHONE` strictly for opt-in Direct Calling Mode.
- `app/src/main/java/com/jarvis/assistant/Settings.kt`, `ui/SettingsFragment.kt`, `res/layout/fragment_settings.xml`, `res/values/strings.xml`, `res/values-hi/strings.xml` — Added explicit consent-gated `Direct contact calls` toggle (`directContactCalls`, off by default) and `termuxScriptOverride`.
- `app/src/main/java/com/jarvis/assistant/contacts/ContactResolver.kt` — Added `hasCallPermission()`.
- `app/src/main/java/com/jarvis/assistant/router/Commands.kt`, `FastCommandRouter.kt`, `FastActionExecutor.kt`, `accessibility/JarvisAccessibilityService.kt` — Wired Direct Calling Mode (`placeDirectCall` via `TelecomManager.placeCall` + cooldown + `ACTION_DIAL` fallback), `userControl()` (`Scroll`, `TapText`, `TypeText`, `ClearText`, `SubmitSearch`, `YouTubeSearch`), `OpenWebsite` with safe `https`/`http` URI validation, and per-camera `TorchCallback` cleanup.
- `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt` — Wired `TurnInputRoute` (`listen` + `host.ask()` fixing `HIGH-01`), `LockedCommands` + `PendingUnlock` (`ACTION_USER_PRESENT`), `AudioManager.ACTION_MICROPHONE_MUTE_CHANGED`, `AudioManager.AudioRecordingCallback` recovery, removed the 30-second `stopSelf()` kill during armed standby interruptions, and skipped open follow-ups while locked.
- `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt` — Fixed API 26–30 SCO discovery (`HIGH-02`), BLE Audio teardown in `prepareOutputForSpeech()` (`MED-01`), and post-settle active communication device verification.
- `app/src/main/java/com/jarvis/assistant/audio/HeadsetButtonBridge.kt` — Added proactive `AudioManager.AudioPlaybackCallback` deactivation when music starts (`LOW-01`).
- `app/src/main/java/com/jarvis/assistant/voice/VoiceInput.kt` — Calibrated `HEY_ASH` to `:2.9 #0.055` (`9/12` vs `8/12`, `0/12` false wakes) and fixed punctuation-only wake-tail bug in `cleanPrefix()` (`"."` -> `""`).
- `app/src/main/java/com/jarvis/assistant/voice/Kokoro.kt` — Reduced `IDLE_UNLOAD_MS` from `600_000L` (10 min) to `120_000L` (2 min) to shrink standby RSS on 8 GB OEM devices and added the unlock prompt to `STOCK_PHRASES`.
- `app/src/main/java/com/jarvis/assistant/ai/LocalServerManager.kt` — Resolved Termux `dataDir` via `PackageManager` (`LOW-02`).
- `app/build.gradle.kts`, `CrashReporter.kt`, `agent/Llm.kt`, `memory/MemoryStore.kt`, `ui/ModelEditActivity.kt`, `voice/ModelDownloader.kt` — Preserved and verified hardening edits (SHA-256 verification on cached AAR, redacted crash locations, atomic model-swap recovery).
- `app/src/test/java/com/jarvis/assistant/RealEngineTest.kt`, `router/FastCommandRouterTest.kt`, `voice/CleanTranscriptTest.kt`, `ui/BackgroundStepTest.kt`, `CrashSafetyTest.kt`, `voice/ModelDownloaderTest.kt` — Updated & expanded automated test coverage.

### Removed Dead Legacy Files (`MED-04`)
- Removed unused `agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/JarvisDatabaseHelper.kt` (conflicting SQLite v1 schema), `memory/MemoryManager.kt`, and their 3 legacy-only test files.

---

## 4. Confirmed Defects Discovered & Fixed

| ID | Defect Discovered | Root Cause | Fix Applied & Verification |
| --- | --- | --- | --- |
| **Half-applied working tree** | Uncommitted edits left `DirectCallPolicy`, `userControl`, `browserIntent`, `resumeAfterUnlock`, `onAudioPolicyChanged`, and `termuxScriptPath` unresolved. | Prior session interrupted mid-refactor. | Completed all missing implementations cleanly; `:app:compileDebugKotlin` & 268 unit tests pass. |
| **`HIGH-01`** | Bluetooth mic lost during `host.ask()` confirmation prompts and follow-ups. | `say()` released SCO before prompt; `host.ask()` never re-negotiated BT mic. | Wired `TurnInputRoute` + `headsetTurn` tracking in `JarvisService.listen()`, `hostFor(id).ask()`, and `afterReply()`. Verified in `EndToEndPhase7Test`. |
| **`HIGH-02`** | Legacy API 26–30 Bluetooth headsets failed SCO check when `TYPE_BLUETOOTH_SCO` wasn't listed prior to `startBluetoothSco()`. | `findPreferredBluetoothCommEndpoint` only checked `inputs`. | Checked `outputs` (`TYPE_BLUETOOTH_SCO`/`TYPE_BLUETOOTH_A2DP`) on API < 31. Verified in `EndToEndPhase7Test`. |
| **`HIGH-03` + Punctuation Wake-Tail Bug** | `HEY_ASH` missed Kokoro voice 5 (`8/12`) AND after calibration, `"Hey Ash."` left `"."` and dispatched `runTask(".")`. | `HEY_ASH` threshold `:2.6 #0.075` too strict; `cleanPrefix()` didn't strip punctuation-only tails. | Calibrated `HEY_ASH` to `:2.9 #0.055` (`9/12`, `0/12` false wakes), fixed `cleanPrefix()` to return `""` when no letter/digit remains. `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` now **PASSES**. |
| **30s Standby Suicide on Interruption** | Armed `JarvisService` called `stopSelf()` 30s after any phone call or other app recording interruption. | `onMicProblem("interrupted")` unconditionally scheduled `stopSelf()` after 30s and had no `AudioRecordingCallback` recovery. | Removed 30s `stopSelf()` when `standby == true`; added event-driven `AudioManager.AudioRecordingCallback` + `ACTION_MICROPHONE_MUTE_CHANGED` recovery. |
| **OnePlus `o-kill(40)` High Standby RSS** | Live CPH2467 `ApplicationExitInfo` showed `o-kill(40)` killing PID `3164` at `551 MB RSS` after Kokoro speech. | `Kokoro.IDLE_UNLOAD_MS` held ~350 MB native ONNX heap for 10 full minutes after speaking. | Lowered `Kokoro.IDLE_UNLOAD_MS` to 2 minutes (`120_000L`) and documented OnePlus battery/Recents-lock requirements. |
| **`MED-01` / `MED-04` / `LOW-01` / `LOW-02`** | BLE Audio stayed in `MODE_IN_COMMUNICATION` during reply; dead SQLite v1 helper; reactive `MediaSession` deactivation; hardcoded `/data/data/com.termux`. | Missing BLE teardown, unused files, missing `AudioPlaybackCallback`, literal path. | All 4 fixed and verified in unit tests + lint (warnings reduced from 33 to 27). |

---

## 5. Remaining Defects & Limitations

1. **OnePlus OxygenOS `o-kill(40)` on Recents Swipe:** Verified live on CPH2467 (`ApplicationExitInfo #0`, `reason=13`, `description=o-kill(40)`): swiping Ash away from Recents terminates the process even with an active `microphone` FGS unless the user locks Ash in Recents and sets Battery usage -> **Allow background activity**.
2. **`HEY_ASH` Synthetic Recall `9/12` vs `HELLO_ASH` `12/12`:** Improved from `8/12` -> `9/12` with `0/12` non-prefix false wakes; still needs live human-voice confirmation on CPH2467.
3. **`SystemVoice` Unverified Output (`MED-02`):** Android's external `TextToSpeech` fallback (used only when Kokoro and cloud TTS are disabled) does not expose `AudioTrack.setPreferredDevice`.
4. **Platform Cleartext Config (`MED-03`):** `network_security_config.xml` allows cleartext HTTP for LAN Ollama/LM Studio; remote HTTPS is enforced in Kotlin (`Endpoints.requireHttpUrl`).
5. **Android 14+ Requirement for `PhoneAgent` Observation:** `JarvisAccessibilityService.observe()` intentionally requires API 34+ (`takeScreenshotOfWindow`) to fail closed on secure windows; satisfied on CPH2467 (API 35).

---

## 6. Direct Calling Implementation & Test Status

- **Modes:**
  - **Confirm Before Calling (Default, `directContactCalls = false`):** Resolves contact, disambiguates duplicates/multiple numbers, asks spoken confirmation with 10s expiry, re-verifies contact, opens system dialer via `ACTION_DIAL`.
  - **Direct Calling Mode (Opt-In, Settings -> `Direct contact calls`):** Requires explicit consent dialog + `READ_CONTACTS` + `CALL_PHONE`. For a live spoken command (`fromVoice = true`) matching a single high-confidence (`exact`/`words`/`kin`) contact with a single ordinary number (>= 7 digits, non-emergency, outside 10s duplicate cooldown), initiates the call directly via `TelecomManager.placeCall` (`ACTION_CALL` fallback), sets `irreversible = true`, reserves cooldown before dispatch (never redials on uncertain failure), and announces `"Calling <Name>."`. Ambiguous names/numbers still clarify by voice first.
- **Test Status:** Policy & dial fallback verified in unit tests (`EndToEndPhase7Test`, `AshPhase3Test`). **No live phone call was placed on CPH2467** (per strict authorization rule).

---

## 7. Build, Automated Tests & Live CPH2467 Telemetry

### Verified Commands & Results
- `JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk bash ./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` -> **EXIT 0 (`BUILD SUCCESSFUL in 1m 47s`)**
- `python3 scripts/check-apk-abis.py debug` -> **EXIT 0** (all 4 APKs verified)
- **Host Unit Tests:** **40 suites, 268 tests, 0 failures, 0 errors, 0 skipped**
- **Android Lint:** **0 errors, 27 warnings** (down from 33)
- **Native `sherpa-onnx` (`-Preal`) Wake Verification:**
  - `dualWakeKeywordMatrixOnRealEngine`: **PASS** (`HEY_ASH=9/12`, `HELLO_ASH=12/12`, `FALSE=0/12`)
  - `heyAshOneBreathCommandIsHeardAndStarted`: **PASS**
  - `helloAshOneBreathCommandIsHeardAndStarted`: **PASS**
  - `soundAlikesAndBareAshDoNotWakeAsh`: **PASS**
  - `saysSorryWhenNothingFollowsTheWakeWord`: **PASS** (after punctuation wake-tail fix)

### Live OnePlus CPH2467 (Android 15 / API 35) Measurements
- **APK Installed (`adb install -r`):** `Success` (data and all 3 downloaded models — `kws/`, `asr/`, `kokoro/` — preserved intact).
- **Warm Activity Startup (`am start -W`):** `TotalTime: 1903ms`, `WaitTime: 1973ms`, `0` crashes.
- **Idle / Armed Memory (`dumpsys meminfo com.jarvis.assistant`):**
  - App open / VIS + A11y bound: `TOTAL PSS: 128 MB`, `TOTAL RSS: 232 MB`.
  - Armed with KWS + Kroko + active Kokoro speech: `TOTAL PSS: 596 MB`, `TOTAL RSS: 661 MB` (now auto-unloads Kokoro after 2m idle).
- **Foreground Service & Background Survival:** Verified `JarvisService` running with `isForeground=true`, `types=0x00000080` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`), `RECORD_AUDIO: allow (running)` persisting after `KEYCODE_HOME` switched focus to `com.android.launcher`.
- **OEM Policy Observation:** `OplusHansManager` reported `cannot transition from R to M, importance=accessibility` (app freezer bypassed while Accessibility enabled); `ApplicationExitInfo` confirmed `o-kill(40)` on Recents swipe when not battery-whitelisted / locked in Recents.

---

## 8. Real-World Acceptance Tests (TESTS 1–18)

| Test | Scenario | Status | Evidence / Blocker |
| --- | --- | --- | --- |
| **TEST 1** | Say `"Hey Ash"` screen on | **NOT EXECUTED** (Host `-Preal` PASS) | Requires live spoken voice on CPH2467 (`RealEngineTest` passed `9/12` + one-breath). |
| **TEST 2** | Say `"Hello Ash"` screen on | **NOT EXECUTED** (Host `-Preal` PASS) | Requires live spoken voice on CPH2467 (`RealEngineTest` passed `12/12` + one-breath). |
| **TEST 3** | Say `"Hey Ash"` screen off | **NOT EXECUTED** (FGS armed verified) | `JarvisService` FGS + `RECORD_AUDIO` verified running in background on CPH2467; live spoken screen-off test pending user voice. |
| **TEST 4** | Say `"Hello Ash"` screen off | **NOT EXECUTED** | Pending user spoken verification with display off on CPH2467. |
| **TEST 5** | Bluetooth earbuds command + response | **NOT EXECUTED** (Simulated PASS) | A2DP device connected on CPH2467, but `BLUETOOTH_CONNECT` is `granted=false` until granted in Settings; 19 host BT tests pass. |
| **TEST 6** | `"Hey Ash, call Rahul"` Direct Calling Mode | **NOT EXECUTED** (Unit PASS) | `DirectCallPolicy` verified in `EndToEndPhase7Test`; live call intentionally NOT placed without user authorization (`CALL_PHONE` currently `granted=false`). |
| **TEST 7** | `"Hey Ash, open YouTube"` | **NOT EXECUTED** (Router/Host PASS) | Pending live voice run on CPH2467. |
| **TEST 8** | `"Hey Ash, scroll down"` | **NOT EXECUTED** (Host PASS) | `JarvisAccessibilityService` verified bound on CPH2467; pending live voice run. |
| **TEST 9** | `"Hey Ash, go back"` | **NOT EXECUTED** (Host PASS) | Pending live voice run on CPH2467. |
| **TEST 10** | `"Hey Ash, play music"` | **NOT EXECUTED** (Host PASS) | Note: `MediaNotificationListener` is not yet enabled in Android Notification Access on CPH2467 (global media-key fallback works). |
| **TEST 11** | `"Hey Ash, next song"` | **NOT EXECUTED** (Host PASS) | Pending live voice run on CPH2467. |
| **TEST 12** | Multi-step workflow (`open YouTube and search for Kotlin tutorials`) | **NOT EXECUTED** (Router/Host PASS) | `FastCommand.YouTubeSearch` + `PhoneAgent` verified in host tests; pending live voice run on CPH2467. |
| **TEST 13** | Disconnect Bluetooth mid-interaction | **NOT EXECUTED** (Simulated PASS) | Verified in `BluetoothAudioRouteTest`; pending live earbud case-close test. |
| **TEST 14** | Interrupt speech recognition / barge-in | **NOT EXECUTED** (Host `-Preal` PASS) | Verified in `RealEngineTest.ownEchoNeverInterruptsButTheUserDoes` & `sayingStopOverJarvisStopsIt`. |
| **TEST 15** | Unavailable internet & AI provider failures | **NOT EXECUTED** (Host PASS) | Verified in `OpenAiCompatibleSession`, `AiRouter`, `CloudVoiceTest`; pending Airplane Mode test on CPH2467. |
| **TEST 16** | Missing mic / Contacts / Accessibility permissions | **PASS (Partial)** | Verified on CPH2467 (`CALL_PHONE` & `BLUETOOTH_CONNECT` denied cleanly without crashing) + host tests (`BluetoothAudioRouteTest`, `AshPhase3Test`). |
| **TEST 17** | Background service after extended idle (5/20/60m) | **DEVICE-DEPENDENT / NOT EXECUTED** | Short background persistence verified after `KEYCODE_HOME`; 60m soak & Doze test requires user-monitored unplugged run with OnePlus battery set to Unrestricted. |
| **TEST 18** | Lock-screen & authentication restrictions | **NOT EXECUTED** (Host PASS) | Verified in `AshPhase3Test` & `EndToEndPhase7Test` (`LockedCommands`, `PendingUnlock`); pending locked-phone spoken check on CPH2467. |

---

## 9. Verified Final Debug APK Paths

- **Primary (Installed on CPH2467):** `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (`29,722,649` bytes, `arm64-v8a`)
- **Universal:** `app/build/outputs/apk/debug/app-universal-debug.apk` (`56,009,533` bytes, `arm64-v8a, armeabi-v7a, x86_64`)
- **ARMv7:** `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` (`28,335,493` bytes)
- **x86_64:** `app/build/outputs/apk/debug/app-x86_64-debug.apk` (`31,550,289` bytes)

---

## 10. Installation, Setup & Remaining Manual Tests on CPH2467

Already installed on CPH2467 (`com.jarvis.assistant`). To unlock every feature on CPH2467:

1. **Settings -> Battery -> App battery management -> Ash -> Allow background activity** (and **Lock** Ash in Recents) to prevent OnePlus `o-kill(40)`.
2. **Ash Settings -> Bluetooth microphone** (toggle off/on or open Android App Permissions -> Nearby devices -> Allow) to grant `BLUETOOTH_CONNECT`.
3. **Ash Settings -> Direct contact calls** -> tap Enable and grant Phone (`CALL_PHONE`) if you want hands-free direct calling.
4. **Ash Settings -> Media access** -> enable Ash in Notification Access for per-app Spotify/YouTube Music transport control.
5. Tap **Turn on** in Ash Home, then speak through TESTS 1–15 and 17–18 above while streaming `adb logcat -v time JarvisService:V VoiceInput:V BtAudioRoute:V FastAction:V *:S`.
