# Project Ash — Phase 4: Bluetooth Earbud Integration

**Date:** 2026-11-24  
**Branch:** `phase4-bluetooth-integration` (branched from `master` commit `73671c5`)  
**Application ID:** `com.jarvis.assistant` (`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`)  
**Next Handoff Target:** **Gemini Hydrogen** (Physical-Device & Integration Verification)

---

## 1. Executive Summary

Phase 4 integrates native Kotlin Bluetooth earbud, BLE Audio, and wired/USB headset support directly into Ash's existing single-owner audio pipeline (`AudioRouteController` -> `VoiceInput` -> `AudioOut` / `Speaker` -> `JarvisService`), with zero React Native, zero Expo, and zero new third-party Gradle dependencies.

Key architectural decisions verified in this phase:

1. **Standby wake listening stays hi-fi and low-power (Case A):** While waiting for `"Hey Ash"` or `"Hello Ash"`, Ash uses the built-in phone microphone (or wired/USB headset microphone when plugged in) and **never holds Classic Bluetooth SCO** while idle. Music over A2DP stays stereo hi-fi and earbud battery is preserved.
2. **Turn-scoped Bluetooth microphone negotiation (Case B):** When a turn is initiated from a headset button, assistant invocation, or Ash's Talk trigger (`preferHeadset = true`), `BluetoothAudioRouteController` negotiates BLE Audio (`TYPE_BLE_HEADSET`, API 31+) first or Classic HFP/SCO (`setCommunicationDevice` on API 31+, `startBluetoothSco` + `ACTION_SCO_AUDIO_STATE_UPDATED` on API 26–30) with bounded timeout, settle delay, actual routed-device verification, and verified fallback to wired/phone mic.
3. **Zero mid-sentence clipping on phone-mic wake hits:** When `"Hey Ash"` / `"Hello Ash"` is detected on the running phone/wired microphone (`preferHeadset = false`), Ash stays on that already-active microphone so pre-roll syllables are never dropped by a mid-sentence SCO switch.
4. **Hi-fi reply playback and private-output protection:** Before speaking (`JarvisService.say`), Ash closes any active headset `AudioRecord`, tears down turn-only Classic SCO, settles back into A2DP / BLE Audio / wired output, disables open-mic barge-in during Bluetooth output to prevent self-wake, and immediately pauses private playback if the headset disconnects mid-reply instead of spilling private audio to the phone speaker.
5. **Respectful, opt-in headset-button fallback:** `HeadsetButtonBridge` is **OFF by default**, requests zero idle audio focus, never claims `STATE_PLAYING`, yields its `MediaSession` whenever `AudioManager.isMusicActive` is true, debounces `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE` only when idle and opted in, and accepts `KEYCODE_VOICE_ASSIST` directly.

---

## 2. Actual Files Changed

### New Production & Legal Files

| File | Purpose |
| --- | --- |
| `app/src/main/java/com/jarvis/assistant/audio/AudioSystemGateway.kt` | Testable platform boundary (`AudioEndpoint`, `AudioSystemObserver`, `AudioSystemGateway`, `AndroidAudioSystemGateway`) wrapping `AudioManager`, `AudioDeviceCallback`, API 31+ `OnCommunicationDeviceChangedListener`, `BluetoothAdapter.ACTION_STATE_CHANGED`, legacy `ACTION_SCO_AUDIO_STATE_UPDATED`, and runtime `BLUETOOTH_CONNECT` checks. |
| `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt` | Production Phase 4 `AudioRouteController` implementing device detection, BLE Audio + Classic HFP/SCO negotiation (API 31+ and API 26–30), timeout & fallback, teardown grace suppression, disconnect/adapter-off recovery, and output selection. |
| `app/src/main/java/com/jarvis/assistant/audio/HeadsetButtonBridge.kt` | Opt-in, OFF-by-default `MediaSession` headset-button fallback that yields to active music players, debounces headset clicks, handles `KEYCODE_VOICE_ASSIST`, and holds zero idle audio focus. |
| `THIRD_PARTY_NOTICES.md` | MIT license attribution for `judejulius/jarvis` and the native Kotlin Bluetooth SCO/button concepts adapted from `lovisschmidt/yunto`. |

### Modified Production & Resource Files

| File | Summary of Changes |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Added `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` (`maxSdkVersion="30"`), and `BLUETOOTH_CONNECT`. Explicitly excluded `BLUETOOTH_SCAN`, location, `WAKE_LOCK`, `CALL_PHONE`, `READ_PHONE_STATE`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. |
| `app/src/main/java/com/jarvis/assistant/audio/AudioRouteController.kt` | Extended the Phase 3 seam with `InputRouteKind`, `OutputRouteKind`, `RouteDisconnectReason`, `ActiveRouteSnapshot`, `ownsCommunicationRoute`, `prepareCommandInput`, `prepareOutputForSpeech`, `updateSettings`, `onActiveInputLost`, and `onPrivateOutputLost` while preserving `BuiltInPhoneAudioRouteController` compatibility. |
| `app/src/main/java/com/jarvis/assistant/voice/VoiceInput.kt` | Updated `micBlocked(audio)` (internal for direct testability) so `MODE_IN_COMMUNICATION` only blocks capture when `!routeController.ownsCommunicationRoute`. External VoIP apps, `MODE_IN_CALL`, `MODE_RINGTONE`, and muted mic still interrupt capture immediately. |
| `app/src/main/java/com/jarvis/assistant/voice/AudioOut.kt` | Added `applyOutputPolicy(policy)` and updated `audioTrack()` so `setPreferredDevice(...)` is refreshed on both newly created and cached `AudioTrack` instances. |
| `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt` | Switched default `audioRouteControllerFactory` to `BluetoothAudioRouteController(ctx)`; wired settings updates; handled `onActiveInputLost`, `onPrivateOutputLost`, and `onRouteTrigger`; ensured active SCO/BLE `AudioRecord` is always stopped **before** SCO teardown in `say()`, `cancelVoiceTurn()`, `resumeListening()`, and `onDestroy()`; disabled open-mic barge-in on Bluetooth output; preserved zero-clip phone-mic wake capture. |
| `app/src/main/java/com/jarvis/assistant/Settings.kt` | Added persisted `bluetoothMicEnabled` (default `true`) and `headsetButtonFallback` (default `false`). |
| `app/src/main/java/com/jarvis/assistant/ui/SettingsFragment.kt` | Wired Bluetooth mic and opt-in headset-button switches, requesting `BLUETOOTH_CONNECT` on API 31+ only when the user enables Bluetooth mic without permission yet granted. |
| `app/src/main/res/layout/fragment_settings.xml` | Added the two Phase 4 switches (`bluetooth_mic`, `headset_button_fallback`) to Settings UI. |
| `app/src/main/res/values/ash_controls.xml` & `app/src/main/res/values-hi/ash_controls.xml` | Added English and Hindi labels/summaries for the two Bluetooth settings controls. |

### New & Updated Test Files

| File | Purpose |
| --- | --- |
| `app/src/test/java/com/jarvis/assistant/audio/BluetoothAudioRouteTest.kt` | 17 Robolectric unit tests covering Scenarios 1–6 and 9–14, manifest permission hygiene, `VoiceInput.micBlocked` call/VoIP vs. Ash-owned SCO behavior, and `JarvisService` destroy-time SCO cleanup. |
| `app/src/test/java/com/jarvis/assistant/audio/HeadsetButtonBridgeTest.kt` | 2 Robolectric unit tests covering Scenarios 7 & 8 (active music yield, paused opt-in debounce, `KEYCODE_VOICE_ASSIST`, release). |
| `app/src/test/java/com/jarvis/assistant/AshPhase3Test.kt` | Updated `tearDown()` to restore the previous `audioRouteControllerFactory` rather than hardcoding `BuiltInPhoneAudioRouteController`. |

---

## 3. Adapted Yunto Components & Explicit Fixes

The following native concepts were studied from `lovisschmidt/yunto` (`HeadphoneButtonModule.kt` and `HeadphoneButtonService.kt`, MIT License, Copyright (c) 2026 Lovis Schmidt) and rewritten cleanly in pure Kotlin without React Native or Expo:

- `AudioDeviceCallback` classification distinguishing full Bluetooth headset removal (A2DP/BLE output removed) from SCO-only drops while output remains connected.
- Teardown grace window (`TEARDOWN_GRACE_MS = 1500ms`) so intentional SCO release does not trigger false disconnect alarms, while real A2DP/BLE headset removal always bypasses the grace window.
- API 31+ `AudioManager.setCommunicationDevice` + `OnCommunicationDeviceChangedListener` with a 300ms settle delay (`SCO_SETTLE_MS`) before opening `AudioRecord`.
- Media-button click debouncing (`300ms`) for single/multi-press coalescing.

### Five Yunto Defects Explicitly Fixed in Ash

| # | Defect in Yunto Reference | Ash Phase 4 Fix |
| --- | --- | --- |
| 1 | **Ignored BLE Audio (`TYPE_BLE_HEADSET`):** only searched `TYPE_BLUETOOTH_SCO`, missing LE Audio / LC3 earbuds on Android 12+. | `BluetoothAudioRouteController` prioritizes `TYPE_BLE_HEADSET` (`26`) before `TYPE_BLUETOOTH_SCO` on API 31+ for both input negotiation and output selection. |
| 2 | **Broke below API 31:** lacked a working API 26–30 Classic SCO fallback while Ash supports `minSdk = 26`. | Implemented explicit API 26–30 path using `isBluetoothScoAvailableOffCall()`, `startBluetoothSco()`, `ACTION_SCO_AUDIO_STATE_UPDATED` (`SCO_AUDIO_STATE_CONNECTED` / `SCO_AUDIO_STATE_ERROR`), and `stopBluetoothSco()`. |
| 3 | **Uncancelled timer / stale callback races:** overlapping negotiations or teardowns could fire stale timeout/settle callbacks into a newer turn. | Every negotiation and grace timer is guarded by a monotonically increasing `routeGeneration` and cancelled immediately on abort, completion, or `releaseSession()`. |
| 4 | **Always-on `MediaSession` + idle audio focus claim:** hijacked play/pause from music players by claiming active session state continuously. | `HeadsetButtonBridge` is OFF by default, claims zero idle audio focus, never sets `STATE_PLAYING`, and deactivates its `MediaSession` whenever `AudioManager.isMusicActive` is true. |
| 5 | **Missing `BLUETOOTH_CONNECT` runtime permission guard on API 31+:** could fail or misclassify devices when Nearby Devices permission is denied. | `AudioSystemGateway.hasBluetoothConnectPermission()` checks `Manifest.permission.BLUETOOTH_CONNECT` on API 31+; denial cleanly falls back to phone/wired mic without crashing. |

---

## 4. Bluetooth APIs & Android Compatibility

| Android API | Microphone Negotiation | Route Verification | Teardown | Output Routing |
| --- | --- | --- | --- | --- |
| **API 31–35 (Android 12–15)** | `AudioManager.availableCommunicationDevices` (`TYPE_BLE_HEADSET` preferred over `TYPE_BLUETOOTH_SCO`) + `setCommunicationDevice(device)` + `MODE_IN_COMMUNICATION` | `OnCommunicationDeviceChangedListener` + immediate `communicationDevice` check + `SCO_SETTLE_MS` (300ms) + `AudioRecord.routedDevice` check in `VoiceInput` | `clearCommunicationDevice()` + restore `MODE_NORMAL` (unless cellular call/ringtone active) | `AudioOut.applyOutputPolicy(policy)` -> `AudioTrack.setPreferredDevice(preferred)` (BLE Audio -> A2DP -> Wired -> USB -> Speaker) |
| **API 26–30 (Android 8–11)** | `isBluetoothScoAvailableOffCall()` + `MODE_IN_COMMUNICATION` + `startBluetoothSco()` + `isBluetoothScoOn = true` | Dynamic `ACTION_SCO_AUDIO_STATE_UPDATED` receiver waiting for `SCO_AUDIO_STATE_CONNECTED` (or failing fast on `SCO_AUDIO_STATE_ERROR` / timeout) + `AudioRecord.routedDevice` check | `stopBluetoothSco()` + `isBluetoothScoOn = false` + restore `MODE_NORMAL` | `AudioOut.applyOutputPolicy(policy)` -> `AudioTrack.setPreferredDevice(preferred)` (A2DP -> Wired -> USB -> Speaker) |

**Device Priority:**
- **Standby Input (`WAKE_STANDBY`):** Wired headset mic -> USB headset mic -> Built-in phone mic. *(Classic SCO is intentionally never used in standby.)*
- **Headset Command Input (`COMMAND_CAPTURE`, `preferHeadset = true`):** BLE Audio mic (`TYPE_BLE_HEADSET`, API 31+) -> Classic HFP/SCO mic (`TYPE_BLUETOOTH_SCO`) -> Wired headset mic -> USB headset mic -> Built-in phone mic.
- **Speech Output (`SPEECH_PLAYBACK`):** BLE Audio (`TYPE_BLE_HEADSET`) -> Bluetooth A2DP (`TYPE_BLUETOOTH_A2DP`) -> Wired headset/headphones -> USB headset/device -> Built-in speaker.

---

## 5. Permissions

Declared in `app/src/main/AndroidManifest.xml` for Phase 4:
- `android.permission.MODIFY_AUDIO_SETTINGS` (normal permission, required for `AudioManager.setMode` / SCO / communication device routing)
- `android.permission.BLUETOOTH` (`android:maxSdkVersion="30"`, legacy Classic Bluetooth audio compatibility on API 26–30)
- `android.permission.BLUETOOTH_CONNECT` (runtime permission on API 31+, requested only when the user enables Bluetooth mic and permission is not yet granted; gracefully degrades to phone/wired mic if denied)

**Explicitly NOT requested** (verified by automated manifest test `manifestDeclaresMinimalBluetoothPermissionsWithoutScanOrLocation`):
- No `BLUETOOTH_SCAN`
- No `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- No `WAKE_LOCK`, `CALL_PHONE`, `READ_PHONE_STATE`, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

---

## 6. Audio Routing Behavior

1. **Standby Listening (`WAKE_STANDBY`):** `VoiceInput` captures continuously on the verified non-SCO input (`VOICE_RECOGNITION`) using `sherpa-onnx` KWS (`"Hey Ash"` / `"Hello Ash"`). Connected A2DP earbuds keep uninterrupted stereo music playback.
2. **Phone-Mic Wake Hit (`preferHeadset = false`):** `JarvisService.onWakeWord(fromKeyword = true)` transitions straight into command capture on the active phone/wired `AudioRecord` so no leading syllables after `"Hey Ash"` are clipped by SCO negotiation latency.
3. **Headset / Assistant / Talk Turn (`preferHeadset = true`):** `JarvisService.listen()` stops any standby `AudioRecord` first (`voice.stop`), calls `audioRoutes.prepareCommandInput(preferHeadset = true)`, verifies the Bluetooth communication route (or falls back cleanly on timeout, refusal, A2DP-only headset, or denied `BLUETOOTH_CONNECT`), and opens command capture on `VOICE_COMMUNICATION` with the verified headset `preferredDevice`.
4. **Safe Teardown Ordering:** Before tearing down SCO in `say()`, `cancelVoiceTurn()`, `resumeListening()`, or `onDestroy()`, `JarvisService` **always stops `VoiceInput` first** so `AudioRecord` never sees a spurious route-drop error during intentional teardown.
5. **Reply Playback & Music Restoration:** `prepareOutputForSpeech()` releases turn-only Classic SCO and waits `OUTPUT_SETTLE_MS` (250ms) so the reply plays over hi-fi A2DP rather than narrowband HFP. `Speaker` requests transient duck/pause audio focus per turn and abandons focus immediately on completion or stop, allowing Spotify / YouTube Music to resume.
6. **Self-Wake Prevention:** During Bluetooth output or communication mode, open-mic barge-in is disabled (`voice.muted = true`) and unmuting is delayed by 150ms after speech ends so Ash never triggers on its own reply.
7. **Private Output Disconnect Protection:** If Bluetooth or wired headset output disappears while `captureState == VoiceState.SPEAKING`, `onPrivateOutputLost` calls `cancelVoiceTurn("Headset disconnected. Reply paused.")` immediately so private replies do not spill onto the phone speaker.

---

## 7. Headset-Button Behavior

- **Primary recommended path:** Setting Ash as Android's Default Digital Assistant (`ACTION_ASSIST` / `AshRecognitionService`) lets earbuds whose long-press or voice gesture sends the system assistant trigger invoke Ash cleanly without interfering with media playback.
- **Secondary opt-in fallback (`HeadsetButtonBridge`):**
  - **OFF by default** (`Settings.headsetButtonFallback = false`).
  - When enabled in Settings, activates a passive `MediaSession` **only while `AudioManager.isMusicActive == false`**.
  - Whenever music is playing, `HeadsetButtonBridge` deactivates its session and returns `false` on media keys so active players retain Play/Pause/Next/Previous control.
  - When idle and enabled, debounces `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE` (`300ms`) into `RouteTrigger.HEADSET_BUTTON` and dispatches `KEYCODE_VOICE_ASSIST` immediately as `RouteTrigger.BLUETOOTH_VOICE_ACTIVATION`.
- **Honest hardware limitation:** Not every Bluetooth earbud sends standard Android media or voice-assist key events (some OEM buds map gestures exclusively to proprietary companion apps or hardwired OEM assistants). When hardware gestures cannot be intercepted, users can invoke Ash via the Default Assistant gesture, the phone's Talk button, or `"Hey Ash"` / `"Hello Ash"`.

---

## 8. Screen-Off Operation & Limitations

Ash explicitly distinguishes the three Android screen-off wake paths:

- **Case A — Phone / Wired Microphone Standby (Implemented & Active):** While the user-enabled microphone foreground service is armed, Ash listens for `"Hey Ash"` / `"Hello Ash"` using the phone microphone (or wired/USB headset mic if plugged in). Works with the screen off as long as Android does not suspend the FGS or silence the mic under OEM battery restrictions / Doze.
- **Case B — Bluetooth Headset Microphone for Active Commands (Implemented & Turn-Scoped):** Used for active command capture when triggered via headset button, assistant gesture, or Talk button (`preferHeadset = true`), provided a supported HFP/SCO or BLE Audio microphone is connected and `BLUETOOTH_CONNECT` is granted. **Not held open continuously during standby.**
- **Case C — Hardware-Supported Always-On Hotword DSP (Unavailable to Third-Party Apps):** Android's `AlwaysOnHotwordDetector` / SoundTrigger DSP path requires privileged system-signed permissions unavailable to third-party apps.

> **Important User-Facing Limitation:** Because Ash intentionally does not hold Classic Bluetooth SCO open during standby, **a wake phrase spoken softly into Bluetooth earbuds while the phone is in a pocket or bag may not be detected** when the earbud microphone is inactive. In that situation, use the earbud's assistant/headset trigger (where supported) or wake via the phone/wired microphone. Continuous standby Bluetooth headset mic access is **not** guaranteed across Android devices or OEM stacks.

---

## 9. Automated Tests Performed (14-Scenario Coverage)

Every one of the 14 required Bluetooth & audio scenarios is verified by deterministic host unit tests (with physical-device hardware gaps tracked separately in Section 12):

| # | Required Scenario | Automated Verification | Status |
| --- | --- | --- | --- |
| 1 | No headset | `BluetoothAudioRouteTest.noHeadsetUsesBuiltInPhoneMicAndSpeaker` | Verified (Host) |
| 2 | Wired headset | `BluetoothAudioRouteTest.wiredHeadsetDetectedPreferredAndRecoversOnUnplug` | Verified (Host) |
| 3 | Bluetooth Classic (HFP/SCO + A2DP) | `bluetoothClassicApi31NegotiatesScoAndReleasesForHiFiA2dp`, `bluetoothClassicLegacyApi29UsesStartBluetoothScoAndBroadcast`, `bluetoothScoTimeoutTearsDownAndFallsBackToPhoneMic`, `a2dpOnlyHeadsetFallsBackToPhoneMicForInputAndPlaysOverA2dp` | Verified (Host) |
| 4 | BLE Audio (`TYPE_BLE_HEADSET`) | `BluetoothAudioRouteTest.bleAudioPreferredOverClassicScoOnApi31` | Verified (Host) |
| 5 | Screen on (headset turn + destroy cleanup) | `bluetoothClassicApi31NegotiatesScoAndReleasesForHiFiA2dp`, `jarvisServiceReleasesBluetoothCommunicationRouteOnDestroy` | Verified (Host) |
| 6 | Screen off (standby phone mic, zero pre-roll clip) | `phoneMicWakeHitKeepsPhoneRouteWithoutDroppingPreRollEvenWithEarbudsConnected` + Phase 3 `AshPhase3Test` screen-off tests | Verified (Host) |
| 7 | Music playing (no hijack / focus yield) | `HeadsetButtonBridgeTest.musicPlayingPreventsHeadsetButtonHijackAndYieldsSessionPriority` + existing `SpeakerFocusTest` | Verified (Host) |
| 8 | Music paused (opt-in headset button + voice assist) | `HeadsetButtonBridgeTest.musicPausedAllowsOptInHeadsetButtonTriggerAndVoiceAssist` | Verified (Host) |
| 9 | Bluetooth reconnection | `BluetoothAudioRouteTest.bluetoothReconnectDuringStandbyAndAfterDisconnectRecoversCleanly` | Verified (Host) |
| 10 | Incoming calls & external VoIP | `incomingCallBlocksNegotiationAndNeverResetsCallAudioMode`, `voiceInputAllowsAshOwnedCommunicationModeAndBlocksExternalCalls` | Verified (Host) |
| 11 | Mic / Bluetooth permission denied | `BluetoothAudioRouteTest.permissionDenialsDegradeSafelyWithoutCrashing`, `manifestDeclaresMinimalBluetoothPermissionsWithoutScanOrLocation` | Verified (Host) |
| 12 | Interrupted speech / private output lost | `BluetoothAudioRouteTest.privateOutputRemovedMidSpeechSignalsStopInsteadOfSpeakerSpill` | Verified (Host) |
| 13 | Repeated wake / rapid activations | `BluetoothAudioRouteTest.rapidRepeatedNegotiationsInvalidateStaleTimersAndCallbacks` | Verified (Host) |
| 14 | Audio-route recovery (SCO drop, grace, BT OFF) | `BluetoothAudioRouteTest.scoDropVsFullDisconnectAndBluetoothAdapterOffRecovery` | Verified (Host) |

### Production Bug Caught & Fixed During Phase 4 Verification

During the initial `:app:testDebugUnitTest` run, 5 new `BluetoothAudioRouteTest` cases caught a real teardown bug in `BluetoothAudioRouteController.releaseSessionInternal()`: `communicationOwned = false` was cleared *before* checking `!isExternalCallActive()`, causing `isExternalCallActive()` to mistake Ash's own `MODE_IN_COMMUNICATION` for an external VoIP call and skip restoring `AudioManager.MODE_NORMAL`. Fixed by checking only cellular `MODE_IN_CALL` / `MODE_RINGTONE` when releasing an Ash-owned session, after which all 274 unit tests passed.

---

## 10. Actual Build & Verification Results

Commands executed against JDK 21 (`/home/prabal/.jdk/current`) and Android SDK 35 (`/home/prabal/Android/Sdk`):

```bash
JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk \
  bash ./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/check-apk-abis.py debug
```

| Check | Result | Details |
| --- | --- | --- |
| **Unit Tests (`:app:testDebugUnitTest`)** | **PASS** | **42 suites, 274 tests, 0 failures, 0 errors, 0 skipped** (all 255 Phase 3 tests + 19 new Phase 4 tests) |
| **Android Lint (`:app:lintDebug`)** | **PASS** | **0 errors, 33 warnings** (identical to Phase 3 baseline; **0 new warnings** in any Phase 4 file) |
| **Debug APK Assembly (`:app:assembleDebug`)** | **PASS** | Fresh APKs built on `phase4-bluetooth-integration` |
| **ABI Verification (`check-apk-abis.py debug`)** | **PASS** | `app-arm64-v8a-debug.apk` (29M), `app-armeabi-v7a-debug.apk` (28M), `app-x86_64-debug.apk` (31M), `app-universal-debug.apk` (54M) |

---

## 11. Known Issues & Limitations

1. **No physical Bluetooth hardware verified yet:** All Phase 4 tests ran under Robolectric SDK 35 with a deterministic `AudioSystemGateway` double. Real Bluetooth stacks (Qualcomm/MediaTek/Samsung/Pixel firmware, OEM HFP latency, LC3 BLE Audio dual-mode switching) MUST be qualified on physical devices.
2. **Standby wake phrase over earbuds in pocket:** Soft speech into earbuds while SCO is closed will not trigger `"Hey Ash"` / `"Hello Ash"` unless the phone mic hears it or the user invokes via earbud assistant/headset button.
3. **OEM headset button mapping variance:** Single-tap / long-press earbud gestures vary widely across AirPods, Galaxy Buds, Pixel Buds, Sony WF/WH, and budget TWS buds. Opt-in `HeadsetButtonBridge` cannot intercept proprietary gestures that Android never dispatches as `ACTION_MEDIA_BUTTON` or `KEYCODE_VOICE_ASSIST`.
4. **Follow-up turns stay on non-SCO unless re-triggered:** `listen(followUp = true)` intentionally avoids reopening Classic SCO so post-reply A2DP music stays hi-fi; if the user is wearing earbuds with the phone in a pocket, a quiet follow-up may need a fresh headset trigger.
5. **Pre-existing non-Phase-4 backlog items remain unchanged:** Legacy unused files (`agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, `memory/JarvisDatabaseHelper.kt`, `claude-delivery/`) and platform cleartext policy remain scoped to the future cleanup backlog.

---

## 12. Remaining Physical-Device Tests & Handoff to Gemini Hydrogen

**Gemini Hydrogen** should verify the fresh debug APK on at least one Android 12–15 phone (and ideally one Android 10–11 phone for the legacy API 26–30 SCO path).

### Install & Logcat Commands

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb logcat -v time BtAudioRoute:V HeadsetButtonBridge:V JarvisService:V VoiceInput:V Speaker:V AudioOut:V *:S
```

Permission toggle helpers for testing denial/grant flows on Android 12+:

```bash
adb shell pm revoke com.jarvis.assistant android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.jarvis.assistant android.permission.BLUETOOTH_CONNECT
adb shell pm revoke com.jarvis.assistant android.permission.RECORD_AUDIO
adb shell pm grant com.jarvis.assistant android.permission.RECORD_AUDIO
```

### Physical-Device Verification Matrix for Gemini Hydrogen

1. **Classic Bluetooth Earbuds (HFP/SCO + A2DP):**
   - Connect earbuds, play Spotify/YouTube Music, arm Ash standby -> verify music remains stereo hi-fi (no narrowband SCO drop in standby).
   - Trigger Ash via Talk button or Default Assistant gesture -> verify `BtAudioRoute` negotiates SCO, ASR captures speech spoken into earbuds (phone in another room/pocket), SCO releases before TTS reply, and reply plays cleanly over A2DP before music resumes.
   - Measure actual SCO negotiation latency (compare against `SCO_SETTLE_MS = 300ms` and `OUTPUT_SETTLE_MS = 250ms` for clipped first/last syllables).
2. **BLE Audio Earbuds (Android 13–15, LC3 `TYPE_BLE_HEADSET`):**
   - Verify `BtAudioRoute` selects `BLE_AUDIO` over `BLUETOOTH_SCO`, captures speech cleanly, and outputs reply without popping or dropping LE Audio link.
3. **A2DP-Only Headphones / Speaker (No Mic):**
   - Trigger Talk -> verify instant fallback to phone mic (`Listening (phone mic)…`) while spoken reply still plays through the A2DP headphones.
4. **Wired 3.5mm / USB-C Headset:**
   - Verify standby wake and commands use wired/USB mic; unplug mid-speech and confirm reply pauses immediately instead of spilling to speaker.
5. **Headset Button Coexistence:**
   - Leave `Headset button fallback` OFF -> verify single/double press controls Spotify/YouTube Music normally.
   - Toggle `Headset button fallback` ON while music is playing -> verify Play/Pause still controls music (no hijack).
   - Pause music -> press headset button -> verify Ash wakes and captures command through earbud mic.
6. **Mid-Turn Disconnect, Bluetooth Toggle OFF, & Reconnect:**
   - Place earbuds in charging case during command listening -> verify Ash cancels turn cleanly and restores `AudioManager.MODE_NORMAL` (`dumpsys audio` shows no stuck communication device).
   - Place earbuds in case mid-reply -> verify speech stops immediately (no speaker spill).
   - Take earbuds back out -> verify reconnect works on next turn without restarting Ash.
7. **Incoming Cellular & WhatsApp/Meet Call Interruption:**
   - Receive a call during standby, during active SCO capture, and during TTS reply -> verify Ash yields immediately without resetting call audio mode, and resumes standby automatically after the call ends (`MODE_NORMAL`).
8. **Screen-Off Pocket & Battery Run:**
   - Arm Ash with screen off (phone on desk and in pocket) for 30+ minutes with earbuds connected -> verify wake response on phone mic, zero stuck SCO locks (`dumpsys audio`), and measure real battery drain.
