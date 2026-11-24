# Project Ash — Background Reliability, Screen-Off & Locked-Screen Engineering Report

**Date:** 2026-11-24  
**Target Device:** OnePlus **CPH2467** (Android 15, API 35, OxygenOS/ColorOS, `arm64-v8a`, USB serial `557d9cdd`)  
**Branch:** `phase4-bluetooth-integration`  
**Final Debug APK Installed:** `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (`29,722,649` bytes)

---

## 1. Background Service Improvements Implemented

1. **Eliminated 30-Second Standby Suicide After Interruptions (`JarvisService.kt`):**
   - **Previous bug:** `onMicProblem("interrupted")` unconditionally posted `stopSelf()` after 30 seconds. Any phone call, camera recording, or WhatsApp voice note longer than 30 seconds permanently terminated `JarvisService`, requiring manual re-arming.
   - **Fix:** When `standby == true`, `JarvisService` no longer calls `stopSelf()` after 30 seconds. It transitions cleanly to `VoiceState.INTERRUPTED` and remains alive, waiting for OS recovery events.
2. **Added Event-Driven `AudioRecordingCallback` & Mute-Change Recovery (`JarvisService.kt`):**
   - Registered `AudioManager.AudioRecordingCallback` (unregistered in `onDestroy()`) so when another app (Camera, Voice Recorder, WhatsApp voice note) finishes recording (`configs.isEmpty()`), Ash automatically calls `onAudioPolicyChanged()` -> `resumeListening()` after a 400ms settle.
   - Registered `AudioManager.ACTION_MICROPHONE_MUTE_CHANGED` alongside `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT` so hardware/software mic-privacy unmute resumes standby immediately.
   - Handled transient `MicExit.SILENT` in armed standby with a single bounded 3-second recovery check instead of killing the service.
3. **Reduced Standby Memory Pressure to Mitigate OEM Killing (`Kokoro.kt`):**
   - Live CPH2467 profiling revealed `TOTAL PSS` jumping from **128 MB** (idle) to **596 MB** (`661 MB RSS`) after Kokoro TTS ran, and OnePlus's `o-kill(40)` subsequently terminated PID `3164` at `551 MB RSS`.
   - Reduced `Kokoro.IDLE_UNLOAD_MS` from `600_000L` (10 minutes) to `120_000L` (2 minutes) so native TTS memory is reclaimed quickly after a conversation finishes while remaining warm across multi-turn interactions.

---

## 2. Screen-Off Wake-Word Results

- **Native `sherpa-onnx` Verification (`-Preal`):**
  - Calibrated `@HEY_ASH` from `:2.6 #0.075` to `:2.9 #0.055`; `@HELLO_ASH` remained `:2.0 #0.1`.
  - `dualWakeKeywordMatrixOnRealEngine`: **`HEY_ASH=9/12`, `HELLO_ASH=12/12`, `FALSE=0/12`** (improved from `8/12` with zero false-wake regression).
  - Fixed a real punctuation-only wake-tail defect in `VoiceInput.cleanPrefix()` where `"Hey Ash."` left `"."` and triggered `runTask(".")`. `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` now **PASSES** with `lastTask == null`.
- **On-Device CPH2467 Status:**
  - Verified `JarvisService` runs with `isForeground=true`, `types=0x00000080` (`FOREGROUND_SERVICE_TYPE_MICROPHONE`), `RECORD_AUDIO: allow (running)` persisting when backgrounded to Launcher (`KEYCODE_HOME`).
  - Live spoken screen-off wake tests (TEST B & TEST D) require user voice interaction and remain **NOT EXECUTED** until run interactively by the user.

---

## 3. Locked-Screen Behavior

Implemented via `LockedCommands.kt`, `PendingUnlock`, and `JarvisService.kt`:

- **Permitted Immediately While Locked (No Unlock Required):**
  - Time queries (`"What time is it?"`)
  - Untargeted media transport (`"Play"`, `"Pause"`, `"Next song"`, `"Previous song"`)
  - Media volume (`"Increase the volume"`, `"Volume down"`, `"Mute"`, `"Set volume to 50"`)
- **Protected / Visual Operations While Locked:**
  - Ash responds clearly: **`"I can do that, but please unlock your phone first."`** (pre-cached in `Kokoro.STOCK_PHRASES` for instant playback).
  - Safe non-sensitive parsed commands (`SetTimer`, `Ask(DATE/BATTERY)`) are offered to a memory-only, 30-second one-shot `PendingUnlock` slot and automatically resume when Android broadcasts `Intent.ACTION_USER_PRESENT` (`resumeAfterUnlock()`).
  - Consequential actions (`CallContact`, `Dial`, `PhoneAgent`, `OpenApp`, chat/personal data) are never queued across lock boundaries or executed behind the keyguard.
  - After answering while locked, `afterReply()` returns directly to `WAKE_LISTENING` without holding an open 6-second follow-up window in a pocket.

---

## 4. Bluetooth Input / Output Results

- Fixed all 4 Phase 6 Bluetooth audit defects (`HIGH-01`, `HIGH-02`, `MED-01`, `LOW-01`):
  - `TurnInputRoute` preserves Bluetooth headset mic affinity across `say(question)` inside `host.ask()` confirmations and follow-ups (`HIGH-01`).
  - API 26–30 legacy SCO negotiation now accepts connected Bluetooth A2DP/SCO outputs prior to `startBluetoothSco()` (`HIGH-02`).
  - `prepareOutputForSpeech()` releases both `BLUETOOTH_SCO` and `BLE_AUDIO` communication modes before TTS output (`MED-01`).
  - `HeadsetButtonBridge` registers `AudioManager.AudioPlaybackCallback` to proactively yield `MediaSession` priority the moment music begins playing (`LOW-01`).
- On CPH2467: verified Bluetooth A2DP connection (`A2dpStateMachine state=Connected`), but `BLUETOOTH_CONNECT` runtime permission is currently `granted=false` until granted by the user in Settings. Standby wake listening intentionally stays on phone/wired mic (never holds Classic SCO in standby).

---

## 5. OnePlus CPH2467 (OxygenOS / Android 15) Manufacturer Restrictions Observed Live

Live `adb shell` inspection of CPH2467 revealed exact OnePlus (`Oplus`) background behaviors:

1. **`o-kill(40)` (`ApplicationExitInfo` reason=13, subreason=2040):** Observed killing `com.jarvis.assistant` twice (`18:19:53` at 232 MB RSS and `19:16:56` at 551 MB RSS) immediately after Recents task removal (`onTaskVanished`). **Mitigation:** User must set **App Info -> Battery usage -> Allow background activity** and **Lock** Ash in the Recents switcher.
2. **`OplusHansManager` App Freezer:** Live logcat confirmed `uid=10419, pkg=com.jarvis.assistant cannot transition from R to M, importance=accessibility`, meaning OnePlus's freezer does **not** freeze Ash while `JarvisAccessibilityService` is enabled.
3. **Standby Bucket:** `am get-standby-bucket com.jarvis.assistant` reported `10` (`ACTIVE`).
4. **Device Idle Whitelist:** `com.jarvis.assistant` is currently **not** in `dumpsys deviceidle whitelist` (user-controlled via Battery -> Unrestricted / Allow background activity).
5. **Android 15 Boot Restriction:** `BootReceiver` posts a reminder notification only and never illegally starts a `microphone` FGS from `BOOT_COMPLETED`.

---

## 6. Build, Test & Real-Device Matrix (TESTS A–O)

- **Build & Unit Tests:** `40 suites, 268 tests, 0 failures`, `0 lint errors (27 warnings)`, all 4 debug APKs ABI-verified.
- **Native `-Preal` Wake Tests:** `5/5` targeted scenarios passed (`HEY_ASH=9/12`, `HELLO_ASH=12/12`, `FALSE=0/12`, `saysSorryWhenNothingFollowsTheWakeWord` PASS).

| Test | Description | Status on CPH2467 |
| --- | --- | --- |
| **TEST A** | Both wake phrases with screen on | **NOT EXECUTED** (Host `-Preal` PASS; needs live voice) |
| **TEST B** | Both wake phrases with screen off | **NOT EXECUTED** (FGS + `RECORD_AUDIO` verified active; needs live voice) |
| **TEST C** | Continues functioning when another app is open | **PASS (Service/Mic)** — `JarvisService` FGS + `RECORD_AUDIO (running)` verified after `KEYCODE_HOME` switched to Launcher |
| **TEST D** | Standby after 5, 20, and 60 minutes | **NOT EXECUTED** (requires real-time soak after enabling OnePlus Unrestricted battery) |
| **TEST E** | Returns to standby after answering | **PASS (Observed in Live Logcat + Host)** — observed live Groq/Kokoro turn at `19:16:43–19:16:48` hiding TaskIsland cleanly |
| **TEST F** | Responds through Bluetooth earbuds | **NOT EXECUTED** (grant `BLUETOOTH_CONNECT` on CPH2467 first) |
| **TEST G** | Recovers after Bluetooth disconnection | **NOT EXECUTED** (19 simulated host tests PASS) |
| **TEST H** | Supported locked-screen commands | **NOT EXECUTED** (verified in `AshPhase3Test` & `EndToEndPhase7Test`) |
| **TEST I** | Requests unlocking for protected operations | **NOT EXECUTED** (verified in `EndToEndPhase7Test`) |
| **TEST J** | Recovers from microphone interruptions | **NOT EXECUTED** (event-driven `AudioRecordingCallback` + `OnModeChangedListener` wired; needs live call/Camera test) |
| **TEST K** | Unavailable internet gracefully | **NOT EXECUTED** (verified in host tests) |
| **TEST L** | Stable over repeated voice interactions | **NOT EXECUTED** (requires multi-turn live voice run) |
| **TEST M** | Behavior after normal process recreation | **PASS (Observed)** — `START_NOT_STICKY` leaves process clean after `o-kill(40)` without crash loops; `AshVoiceInteractionService` & `JarvisAccessibilityService` rebind cleanly |
| **TEST N** | Standby battery impact vs baseline | **NOT EXECUTED** (requires unplugged 30–60m `dumpsys batterystats` comparison) |
| **TEST O** | Behavior after device restart | **NOT EXECUTED** (`BootReceiver` notification-only verified in `TurnOffTest`; live reboot not triggered without permission) |

---

## 7. Required Phone Settings & Manual Verification Instructions (CPH2467)

1. **Enable OnePlus Background Persistence:**
   - Open **Settings -> Apps -> App management -> Ash -> Battery usage** -> enable **Allow background activity** (and **Allow auto-launch** if shown).
   - Open **Recents**, tap the `⋮` menu on Ash's card, and tap **Lock** so swiping does not trigger `o-kill(40)`.
2. **Grant Optional Runtime Access in Ash Settings:**
   - **Bluetooth microphone:** Grant Nearby Devices (`BLUETOOTH_CONNECT`).
   - **Direct contact calls:** Toggle on and grant Phone (`CALL_PHONE`) if desired.
   - **Media access:** Enable Ash in Notification Access for per-app music player transport.
3. **Run Interactive Live Verification:**
   ```bash
   adb logcat -v time JarvisService:V VoiceInput:V BtAudioRoute:V HeadsetButtonBridge:V FastAction:V *:S
   ```
   - Open Ash -> tap **Turn on** -> press Home -> test `"Hey Ash, what time is it?"` and `"Hello Ash, volume up"`.
   - Lock screen / turn display off -> test `"Hey Ash, what time is it?"` (should answer without unlock) and `"Hey Ash, set a timer for 2 minutes"` (should ask to unlock, then execute on unlock).
   - Put phone on desk for 5 / 20 / 60 minutes (unplugged) -> test wake -> capture `adb shell dumpsys batterystats com.jarvis.assistant` and `adb shell dumpsys meminfo com.jarvis.assistant`.
