# Project Ash — Phase 5: Testing, Build Verification & Optimization Report

**Date:** 2026-11-24  
**Tested Revision:** Branch `phase4-bluetooth-integration`, commit `92e984afca06acdb242c9cee881f28fec2761037` (branched from `master` `73671c5`)  
**Application ID:** `com.jarvis.assistant` (`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`, `versionName = "0.1.0"`)  
**Next Handoff Target:** **Gemini Argon**

---

## 1. Tested Source Revision & Mandatory Pre-Test Verification

- **Git Branch & HEAD:** `phase4-bluetooth-integration` at `92e984a` (`Phase 4: Integrate native Bluetooth earbud audio routing and headset fallback`).
- **Prior Reports Reviewed Chronologically:**
  - `docs/ash/00-repo-audit.md`
  - `docs/ash/01-architecture.md`
  - `docs/ash/02-implementation-status.md`
  - `docs/ash/03-bluetooth-integration.md`
- **Bluetooth Integration Confirmed:** Commit `92e984a` (19 files changed, +2283/-49 lines) is present on `phase4-bluetooth-integration` and includes `AudioSystemGateway.kt`, `BluetoothAudioRouteController.kt`, `HeadsetButtonBridge.kt`, `THIRD_PARTY_NOTICES.md`, `JarvisService.kt` lifecycle/teardown wiring, `VoiceInput.micBlocked` Ash-owned communication mode check, `AudioOut.applyOutputPolicy`, manifest permissions, Settings UI controls, and 19 host unit tests.

---

## 2. Build Environment

| Component | Verified Value |
| --- | --- |
| **OS / Filesystem** | Linux (`7.1.5+kali-amd64`), workspace on `vfat` (FAT32) — Gradle invoked via `bash ./gradlew` |
| **Java / JDK** | Eclipse Temurin OpenJDK `21.0.13+9-LTS` (`/home/prabal/.jdk/current`) |
| **Android SDK** | `/home/prabal/Android/Sdk` (`compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`) |
| **Gradle / AGP / Kotlin** | Gradle `8.11.1`, AGP `8.7.3`, Kotlin `2.0.21` |
| **Native Speech Engine** | `sherpa-onnx` `1.13.8` Android AAR + Linux x64 JNI & models in `test-native/` |
| **Connected ADB Devices** | **0 devices / 0 emulators** (`adb devices -l` returned empty list) |
| **Android Instrumentation Suite** | None (`app/src/androidTest/` does not exist) |

---

## 3. Commands Executed

| # | Command | Exit Code | Purpose |
| --- | --- | --- | --- |
| 1 | `/home/prabal/Android/Sdk/platform-tools/adb devices -l` | `0` | Check connected Android devices/emulators (none attached) |
| 2 | `JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk bash ./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` | `0` | Verify compilation, lint, and debug APK assembly |
| 3 | `JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk bash ./gradlew --offline --no-daemon :app:testDebugUnitTest -Preal --tests ...` (11 `RealEngineTest` wake-word & barge-in tests) | `1` | Execute native `sherpa-onnx` wake-word, matrix, and barge-in tests |
| 4 | `JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk bash ./gradlew --offline --no-daemon :app:testDebugUnitTest --rerun-tasks` | `0` | Fresh full run of all 42 host unit test suites (274 tests) with per-suite timings |
| 5 | `python3 scripts/check-apk-abis.py debug` | `0` | Verify ELF ABIs across all 4 debug APKs |

---

## 4. Build & Static Analysis Results

- **`:app:assembleDebug`:** **PASS** (4 fresh debug APKs built).
- **`:app:lintDebug`:** **PASS** (**0 errors, 33 warnings**; 0 new warnings in any Phase 4 file).
- **`scripts/check-apk-abis.py debug`:** **PASS** — all 4 APKs contain expected native libraries:
  - `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (29M) — `arm64-v8a`
  - `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` (28M) — `armeabi-v7a`
  - `app/build/outputs/apk/debug/app-x86_64-debug.apk` (31M) — `x86_64`
  - `app/build/outputs/apk/debug/app-universal-debug.apk` (54M) — `arm64-v8a, armeabi-v7a, x86_64`

---

## 5. Host Unit Test Results

**Result:** **PASS** — **42 suites, 274 tests, 0 failures, 0 errors, 0 skipped** (sum of test suite times: `36.75s`).

Slowest host suites (Robolectric SDK 35 + MockWebServer):
- `ui.ScreenshotTest`: `11.98s` (5 tests)
- `voice.CloudVoiceTest`: `6.11s` (11 tests)
- `AshPhase3Test`: `5.01s` (12 tests)
- `ui.TaskIslandTest`: `3.89s` (4 tests)
- `ui.AppFlowTest`: `3.62s` (6 tests)
- `agent.FastCommandRouterTest`: `2.04s` (7 tests)
- `TurnOffTest`: `0.72s` (5 tests)
- `audio.BluetoothAudioRouteTest`: `0.29s` (17 tests)

---

## 6. Instrumentation Results

- **Status:** **NOT EXECUTED** (`app/src/androidTest/` does not exist in this repository, and no physical Android device or emulator was attached via ADB).

---

## 7. Wake-Word Test Results (`RealEngineTest`, `-Preal`)

Executed 11 tests against the real `sherpa-onnx` `1.13.8` Linux x64 native engine, GigaSpeech 3.3M int8 KWS model, Kroko ASR, and Kokoro-82M TTS in `test-native/`:

**Result:** **10 passed, 1 failed** (total suite time: `441.31s`).

| Test Case | Result | Time | Notes |
| --- | --- | ---: | --- |
| `turningOffWhileModelsLoadKeepsTheMicClosed` | **PASS** | `19.02s` | Verifies Turn Off while models load keeps mic closed |
| `customPhraseStartsACommandWithoutBecomingPartOfIt` | **PASS** | `22.80s` | Custom wake phrase triggers and strips cleanly |
| `heyAshOneBreathCommandIsHeardAndStarted` | **PASS** | `28.25s` | Kokoro voice 6, one-breath command + wake prefix stripped |
| `helloAshOneBreathCommandIsHeardAndStarted` | **PASS** | `20.37s` | Kokoro voice 5, one-breath command + wake prefix stripped |
| `pauseAfterHeyAshStillGetsTheCommand` | **PASS** | `23.56s` | Kokoro voice 1, 2.5s pause between wake phrase & command |
| `pauseAfterHelloAshStillGetsTheCommand` | **PASS** | `33.41s` | Kokoro voice 5, 2.5s pause between wake phrase & command |
| `saysSorryWhenNothingFollowsTheWakeWord` | **FAIL** | `54.57s` | **Reproducible failure** at `RealEngineTest.kt:234` (`AssertionError`). Kokoro default voice 5 `"Hey Ash."` did not trigger KWS (`STATUS: Listening for Hey Ash \| task=null`). |
| `soundAlikesAndBareAshDoNotWakeAsh` | **PASS** | `41.85s` | `"Hey cash"`, `"Hey ask"`, `"trash"`, `"wash"`, `"Ash."`, `"Hey Jarvis"` all rejected (0 false wakes) |
| `dualWakeKeywordMatrixOnRealEngine` | **PASS** | `94.29s` | `KWS [production]: HEY_ASH=8/12 HELLO_ASH=12/12 FALSE=0/12 []` |
| `ownEchoNeverInterruptsButTheUserDoes` | **PASS** | `37.15s` | Echo comparator ignores own TTS and accepts user barge-in |
| `sayingStopOverJarvisStopsIt` | **PASS** | `66.04s` | `"Stop"` interrupts active TTS playback |

> **Honest Assessment:** Host `-Preal` tests use Kokoro-synthesized speech rather than live human microphones. `HELLO_ASH` is 12/12 reliable across the synthetic matrix, while `HEY_ASH` is 8/12 (missing Kokoro voice 5 in `saysSorryWhenNothingFollowsTheWakeWord`). Real human voice accuracy, noisy environments, and far-field/pocket wake latency remain **NOT EXECUTED** pending physical hardware.

---

## 8. Bluetooth & Audio Routing Test Results

All Bluetooth and headset-button tests ran under **Robolectric SDK 35** using `FakeGateway` (simulated `AudioSystemGateway`) — **NOT** real Bluetooth hardware:

- **`BluetoothAudioRouteTest` (17/17 PASS):**
  - Built-in phone mic/speaker fallback when no headset is connected.
  - Wired headset preference and unplug recovery (`WIRED_UNPLUGGED`, `PRIVATE_OUTPUT_LOST`).
  - Classic HFP/SCO negotiation on API 31+ (`setCommunicationDevice`) and API 26–30 (`startBluetoothSco` + `ACTION_SCO_AUDIO_STATE_UPDATED`).
  - BLE Audio (`TYPE_BLE_HEADSET`) preference over Classic SCO on API 31+.
  - Standby wake listening keeps phone/wired mic (never holds Classic SCO idle); phone-mic wake hit (`preferHeadset = false`) avoids mid-sentence SCO switch.
  - Negotiation timeout -> clean teardown -> `MODE_NORMAL` + phone mic fallback; A2DP-only headset fallback.
  - `prepareOutputForSpeech` tears down turn-only SCO before hi-fi A2DP playback.
  - Disconnect, Bluetooth adapter OFF, SCO-only drop, teardown grace suppression, and rapid repeated activation cancellation.
  - Incoming cellular call (`MODE_RINGTONE` / `MODE_IN_CALL`) and external VoIP (`MODE_IN_COMMUNICATION` when `!ownsCommunicationRoute`) block Ash capture without resetting call mode; Ash-owned `MODE_IN_COMMUNICATION` is permitted.
  - Denied `BLUETOOTH_CONNECT` falls back safely; denied `RECORD_AUDIO` refuses `JarvisService.start`; manifest excludes `BLUETOOTH_SCAN` and location permissions.
  - `JarvisService.onDestroy()` releases any owned Bluetooth communication route and restores `MODE_NORMAL`.
- **`HeadsetButtonBridgeTest` (2/2 PASS):**
  - Off by default; yields `MediaSession` whenever `isMusicActive` is true (never hijacks Play/Pause or HeadsetHook from music players).
  - Opt-in when music is paused debounces rapid presses (`300ms`) into one `HEADSET_BUTTON` trigger and fires `KEYCODE_VOICE_ASSIST` immediately.

---

## 9. Phone-Control, Lifecycle & Security Audit Results

- **Phone Commands (Simulated Host Tests PASS):** `FastCommandRouterTest`, `AppMatcherTest`, `YouTubeActionTest`, `ContactMatcherTest`, `SensitiveActionsTest`, `AppTipsTest`, `MemoryIntentParserTest`, `PhraseCorrectorTest`, and `AshPhase3Test` all pass. `SensitiveActions` + `OneUseApproval` enforce 10-second one-use spoken confirmation and protected-package/label blocking.
- **Lifecycle & Background (Simulated Host Tests PASS):** `TurnOffTest` (5/5), `CrashSafetyTest` (4/4), and `AshPhase3Test` (12/12) verify `ACTION_ARM`, `ACTION_SHUTDOWN`, `START_NOT_STICKY`, `BootReceiver` notification-only reminder (no illegal API 35 boot FGS launch), `AshVoiceInteractionService` / `AshRecognitionService` registration, and `TaskBoard` state transitions.
- **Security & Robustness Static Audit:**
  - **API Keys & Logging:** Keys are stored via `ProviderCredentials` (Keystore AES-GCM), `ModelProfile.toString()` / `ProviderConfig.toString()` redact credentials, and `grep` across `app/src/main/java/` found zero API keys, auth headers, or raw user transcripts logged via `android.util.Log`.
  - **Permissions:** Minimal Phase 4 additions (`MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` maxSdk 30, `BLUETOOTH_CONNECT`); no `BLUETOOTH_SCAN`, no location, no `WAKE_LOCK`, `CALL_PHONE`, `READ_PHONE_STATE`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  - **Listeners & Loops:** `AndroidAudioSystemGateway` unregisters `AudioDeviceCallback`, `OnCommunicationDeviceChangedListener`, and both `BroadcastReceiver`s cleanly in `unregisterObserver()`. `HeadsetButtonBridge` cancels pending debounce callbacks and releases `MediaSession` on `release()`. No `GlobalScope` or unbounded `while (true)` polling loops found.

---

## 10. Performance Measurements

- **Measured on Host (JDK 21 / Robolectric SDK 35):**
  - Full 42-suite host unit test execution: `36.75s` cumulative test time (`4m 11s` total Gradle `--rerun-tasks` wall time).
  - Native `RealEngineTest` (11 scenarios including model archive unpacking + Kokoro TTS + KWS + Kroko): `441.31s` wall time.
  - Configured Bluetooth timing constants in `BluetoothAudioRouteController.kt`: `SCO_SETTLE_MS = 300ms`, `OUTPUT_SETTLE_MS = 250ms`, `TEARDOWN_GRACE_MS = 1500ms`, `MAX_ROUTE_TIMEOUT_MS = 5000ms`; `HeadsetButtonBridge.DEBOUNCE_MS = 300ms`.
- **On-Device Performance (Startup, Wake Latency, Command Time, BT Routing Delay, RAM, CPU, Battery):** **NOT MEASURED** (no physical Android device connected).

---

## 11. Reproducible Bugs & Findings

1. **`RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` Fails (`AssertionError` at `RealEngineTest.kt:234`):**
   - **Repro:** Run `./gradlew :app:testDebugUnitTest -Preal --tests "com.jarvis.assistant.RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord"`.
   - **Cause:** The test feeds Kokoro default voice 5 `"Hey Ash."` followed by 14s silence. KWS (`HEY_ASH` tuned to `:2.6 #0.075`) misses voice 5 on `"Hey Ash."`, matching the matrix finding (`HEY_ASH=8/12`, `HELLO_ASH=12/12`, `FALSE=0/12`).
   - **Scope note:** Left unmasked intentionally (per Phase 5 rules: do not hide errors or weaken wake assertions). Requires acoustic/KWS threshold evaluation on real human voices before adjusting `HEY_ASH` tuning.
2. **Global Cleartext Flag in `network_security_config.xml` (Pre-existing Phase 1/3 Risk):**
   - `<base-config cleartextTrafficPermitted="true" />` allows cleartext HTTP at the OS level so local LM Studio/Ollama LAN hosts work, while `Endpoints` / `ProviderRequest` enforce HTTPS for remote hosts in Kotlin code.
3. **Unused Legacy Subsystems Retained (Pre-existing Backlog):**
   - `agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, and `memory/JarvisDatabaseHelper.kt` (`DATABASE_VERSION = 1` vs active `MemoryStore` `DB_VERSION = 3`) remain unused in production.

---

## 12. Fixes Applied in Phase 5

- **None.** All 274 host unit tests and `:app:lintDebug` passed cleanly on revision `92e984a` without requiring code patches, and the sole `RealEngineTest` failure (`saysSorryWhenNothingFollowsTheWakeWord` / `HEY_ASH` voice 5 recall) is an acoustic model tuning issue rather than a test defect, so it is reported transparently rather than masked.

---

## 13. Unexecuted Tests (No Physical Device Attached)

All hardware-dependent and on-device tests are **NOT EXECUTED**:
- All `androidTest` / UI instrumentation tests (no `androidTest` directory and 0 ADB devices).
- Physical Classic Bluetooth HFP/SCO earbuds, BLE Audio (LC3) earbuds, A2DP-only headphones, and wired/USB-C headset tests.
- Real Spotify / YouTube Music audio focus ducking, playback restoration, and `HeadsetButtonBridge` key interception.
- Real incoming cellular call & WhatsApp/Meet VoIP preemption and recovery.
- Locked screen, unlocked screen, screen-off, Doze (`deviceidle`), long idle, process kill, and OEM battery-policy tests.
- Live `PhoneAgent` Accessibility UI automation and API 34+ `takeScreenshotOfWindow` secure-window checks.
- On-device startup, wake latency, BT routing latency, CPU, RAM, and battery drain measurements.

---

## 14. Physical-Device ADB Test Procedure

```bash
# 1. Install and verify device ABI
adb devices -l
adb shell getprop ro.product.cpu.abilist
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk   # or app-universal-debug.apk

# 2. Stream live Ash + Bluetooth logs
adb logcat -v time BtAudioRoute:V HeadsetButtonBridge:V JarvisService:V VoiceInput:V Speaker:V AudioOut:V *:S
```

Execute and record Pass/Fail on a physical Android 12–15 phone (and Android 8–11 if testing legacy SCO):

1. **Unlocked Screen:** Launch Ash, download models, arm standby, test `"Hey Ash, what time is it?"` and `"Hello Ash, what time is it?"`, test Talk button, and test `Settings -> Default digital assistant`.
2. **Locked Screen:** Lock phone with PIN/biometric; verify time query & untargeted media transport work without unlock, while calling/personal/accessibility actions are blocked behind lock screen.
3. **Screen Off & Long Idle / Doze:** Arm standby, turn screen off for 15–60 min; also test forced Doze (`adb shell dumpsys deviceidle force-idle`, then `unforce`). Verify FGS status (`adb shell dumpsys activity services com.jarvis.assistant`).
4. **Bluetooth Earbuds + Music Playback:** Connect Classic (and BLE Audio if available) earbuds, play Spotify/YouTube Music, verify hi-fi stereo stays intact in standby, trigger Talk/assistant gesture, speak into earbuds from across room, verify SCO teardown before A2DP reply and music resumption, and test mid-turn case-close disconnect (`adb shell dumpsys audio` must show no stuck communication device).
5. **Incoming Calls:** Receive cellular call and VoIP call during standby, active command, and TTS reply; verify immediate yield and clean resume after hang-up.
6. **Airplane Mode / Offline:** Enable Airplane Mode; verify `"Hey Ash"` / `"Hello Ash"`, offline English ASR, Kokoro TTS, and fast local commands work with zero network.
7. **Permissions & Process Termination:** Test revoking/granting `RECORD_AUDIO` and `BLUETOOTH_CONNECT` via `adb shell pm revoke/grant com.jarvis.assistant ...`; test swipe-away from Recents, FGS Stop in Active Apps, and `adb shell am kill com.jarvis.assistant` (verify clean `START_NOT_STICKY` state).
8. **Battery, Memory & OEM Policies:** Capture `adb shell dumpsys meminfo com.jarvis.assistant`, `adb shell dumpsys batterystats com.jarvis.assistant`, and test OEM background/autostart settings (One UI / HyperOS / ColorOS) across a 30+ min armed idle vs Off baseline.

---

## 15. Remaining Risks

- **Physical Bluetooth stack & OEM variability:** HFP/SCO negotiation latency (`SCO_SETTLE_MS = 300ms`), BLE Audio dual-mode stability, and earbud media-button hardware events vary across phone/earbud chipsets and remain unverified on hardware.
- **`HEY_ASH` synthetic recall gap (8/12):** Some voices/pronunciations of `"Hey Ash"` miss at the current `:2.6 #0.075` tuning; human-voice testing is required before tuning further (to avoid raising false-wake rates).
- **Screen-off OEM killing & Doze:** Android OEMs frequently throttle or silence background `microphone` FGSs after extended screen-off idle.

---

## 16. Handoff to Gemini Argon

1. **Verified Artifacts Ready for Device Qualification:**
   - Branch `phase4-bluetooth-integration` at `92e984a` (not pushed).
   - Verified APKs in `app/build/outputs/apk/debug/` (`app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`, `app-x86_64-debug.apk`, `app-universal-debug.apk`).
2. **Priority Items for Gemini Argon:**
   - Execute Section 14 on a physical Android 12–15 device (and Bluetooth earbuds) and record actual latencies, battery stats, and pass/fail results.
   - Investigate `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` (`HEY_ASH` voice 5 miss) alongside human-voice wake recordings before adjusting KWS `:boost #threshold` values.
   - Scope future cleanup of legacy dead files (`agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, `memory/JarvisDatabaseHelper.kt`) and `network_security_config.xml` hardening once device testing is complete.
