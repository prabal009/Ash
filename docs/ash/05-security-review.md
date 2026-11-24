# Project Ash — Phase 6: Independent Final Security and Reliability Audit

**Date:** 2026-11-24  
**Audited Revision:** Branch `phase4-bluetooth-integration`, commit `e0ea3e0` (code commit `92e984afca06acdb242c9cee881f28fec2761037`, branched from `master` `73671c5`)  
**Application ID:** `com.jarvis.assistant` (`minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`, `versionName = "0.1.0"`)  
**Final Audit Verdict:** **`READY FOR CONTROLLED DEVICE TESTING`** *(Not yet `READY FOR PERSONAL-USE RELEASE` — see Sections 3, 13, and 15)*  
**Handoff Target:** **GPT Sol / GPT Astra** (for targeted code corrections prior to personal-use release)

---

## 1. Overall Assessment

An independent, read-only source-code and test-evidence audit was conducted across the entire Project Ash repository (`phase4-bluetooth-integration` at `e0ea3e0`) and all five prior engineering reports (`00-repo-audit.md` through `04-test-report.md`).

### High-Level Conclusions

1. **Strong Architectural Security Foundation (Phase 3 & Phase 4 Claims Verified):**
   - **Consequential action boundaries are enforced in Kotlin architecture, not delegated to LLM prompts:** `PhoneAgent` (`PhoneAgent.kt:136–158`) accepts at most one typed tool call tied to the exact current `observation_id`, strips coordinate/arbitrary-intent tools, blocks protected packages/labels (`SensitiveActions.kt:8–13`) and password/sensitive nodes (`ScreenReader.kt:170–179`), verifies non-secure windows via Android 14+ `takeScreenshotOfWindow` (`JarvisAccessibilityService.kt:117–146`), and enforces a 10-second, single-use, context-fingerprinted spoken confirmation via `OneUseApproval` (`SensitiveActions.kt:54–70`, `JarvisAccessibilityService.kt:195–221`).
   - **No autonomous phone calling:** `CALL_PHONE` and `READ_PHONE_STATE` were removed from `AndroidManifest.xml`. Contact calling resolves numbers locally, requires spoken confirmation within 10 seconds, re-verifies the contact still owns the number, and dispatches `Intent.ACTION_DIAL` (`FastActionExecutor.kt:588–620`) so the user places the call manually.
   - **Credential & model integrity hardened:** API keys are encrypted with AES-256-GCM backed by `AndroidKeyStore` (`ProviderCredentials.kt:14–79`) with slot-bound AAD; error messages across provider boundaries are scrubbed (`ProviderRequest.kt:18–31`); remote providers require HTTPS while local HTTP endpoints are pinned to `Proxy.NO_PROXY` and private/loopback DNS (`Endpoints.kt:30–78`); and `sherpa-onnx` AAR, KWS, Kroko ASR, Kokoro TTS, and GGUF downloads all verify SHA-256 digests (`app/build.gradle.kts:100–118`, `ModelDownloader.kt:17–75`, `GgufDownloader.kt:140–225`).
2. **Why the Verdict Is `READY FOR CONTROLLED DEVICE TESTING` (and Not Yet `READY FOR PERSONAL-USE RELEASE`):**
   - Independent code inspection uncovered **three verified `HIGH`-severity functional defects** and **four `MEDIUM`-severity reliability/privacy issues** (documented in Sections 3 and 4) that were not caught by the existing 274 host unit tests:
     - **`HIGH-01`:** Spoken confirmation prompts (`host.ask()`) and follow-up turns (`listen(followUp = true)`) during a Bluetooth headset session tear down SCO to speak the prompt over A2DP and **never re-negotiate the Bluetooth headset microphone** to hear the user's answer, falling back silently to the phone microphone.
     - **`HIGH-02`:** On Android 8.0–11 (API 26–30), `BluetoothAudioRouteController.findPreferredBluetoothCommEndpoint()` requires `TYPE_BLUETOOTH_SCO` to already appear in `GET_DEVICES_INPUTS` before calling `startBluetoothSco()`, breaking headsets that only expose `TYPE_BLUETOOTH_A2DP` until SCO is connected.
     - **`HIGH-03`:** Native `sherpa-onnx` wake-word recall for `"Hey Ash"` (`HEY_ASH` `:2.6 #0.075`) is `8/12` (`66.7%`) across synthetic voices and reproducibly fails `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` (`RealEngineTest.kt:234`), with zero live human-voice or noisy-environment calibration performed yet.
   - Furthermore, **zero tests have been executed on a physical Android handset or physical Bluetooth earbuds** (`04-test-report.md` §6, §13).

---

## 2. Critical Findings

**Zero (`0`) verified `CRITICAL` security or data-loss defects** were found in the current codebase. All critical vulnerabilities identified in the Phase 1 Jarvis baseline audit (`00-repo-audit.md` §9) — plaintext API keys in `SharedPreferences`, unverified model archive downloads, `BootReceiver` Android 15 `ForegroundServiceStartNotAllowedException` crash, unconfirmed `ACTION_CALL` execution, and coordinate-tap `SensitiveActions` bypasses — were verified as remediated in source code.

---

## 3. High-Priority Findings

### `HIGH-01` — Bluetooth Microphone Route Is Torn Down for `host.ask()` Confirmation Prompts and Follow-Up Turns Without Re-Negotiating Headset Input

- **Severity:** `HIGH`
- **Classification:** Verified Code Defect
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:483–489` (`say()`)
  - `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt:329–352` (`prepareOutputForSpeech()`)
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:1202–1220` (`hostFor(id).ask()`)
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:582–589, 1118` (`listen()` & `afterReply()`)
- **Evidence:**
  1. When a turn starts from a Bluetooth headset (`preferHeadset = true`), `BluetoothAudioRouteController` negotiates `BLUETOOTH_SCO` (or `BLE_AUDIO`) and sets `communicationOwned = true`.
  2. When Ash needs user confirmation (e.g., `FastActionExecutor.confirmDial` calling `asker("Open the dialer for ...")`, contact/player disambiguation, or `PhoneAgent` calling `host.ask("Replace ...? Say yes to type it.")`), `hostFor(id).ask(question)` invokes `say(question) { ... }` (`JarvisService.kt:1202`).
  3. `say()` stops `VoiceInput` (`if (audioRoutes.ownsCommunicationRoute && voice.isListening) voice.stop { prepareAndSpeak() }`, lines 483–485) and calls `audioRoutes.prepareOutputForSpeech`, which calls `releaseSessionInternal(clearMode = true)` (`BluetoothAudioRouteController.kt:334`), tearing down SCO and resetting `activeInputPolicy` to `selectNonBluetoothInputPolicy()` (`BUILT_IN_MIC` / `WIRED_HEADSET`).
  4. When `say(question)` finishes, the completion lambda inside `hostFor(id).ask()` (`JarvisService.kt:1209–1211`) immediately calls:
     ```kotlin
     if (useOfflineAsr) {
         voice.listenForCommand(10_000, ::finishAnswer)
     }
     ```
     It **never** calls `audioRoutes.prepareCommandInput(preferHeadset = true)`!
  5. Similarly, after any normal spoken reply finishes, `afterReply()` (`JarvisService.kt:1118`) calls `listen(promptIfQuiet = false, followUp = true)`, where line 582 explicitly sets `val tryHeadset = preferHeadset && !followUp && !interrupted && ...`, forcing follow-up capture onto the phone microphone.
- **Reproduction Procedure:**
  1. Connect Bluetooth Classic earbuds, put the phone in a pocket or another room, and trigger Ash via headset button or Talk (`preferHeadset = true`).
  2. Say `"Call Mom"` (or any command that triggers `host.ask()`).
  3. Ash speaks `"Open the dialer for Mom, mobile ...? You will place the call yourself"` through the earbuds over A2DP.
  4. Speak `"Yes"` into the earbud microphone.
- **Expected Behavior:** Ash re-acquires the Bluetooth headset microphone route before opening the 10-second `listenForCommand` window in `host.ask()` (and for headset-originated follow-up turns, or disables follow-up when SCO teardown occurred).
- **Observed / Demonstrated Problem:** Because SCO was released before `say(question)` and never re-negotiated in `host.ask()`, `VoiceInput.runMic()` calls `routeController.prepareInput(COMMAND_CAPTURE)` which returns `BUILT_IN_MIC`. The user's `"Yes"` spoken into the earbuds is not captured when the phone is in a pocket/bag, causing confirmation timeout and failed hands-free dialing/disambiguation.
- **Recommended Correction:** Track whether the current turn/task originated on a Bluetooth headset route (e.g., `headsetTurn: Boolean`). In `hostFor(id).ask()`, after `say(question)` completes, if `headsetTurn` is true and `audioRoutes.currentSnapshot().bluetoothMicAvailable`, negotiate `audioRoutes.prepareCommandInput(preferHeadset = true)` before calling `voice.listenForCommand(10_000, ::finishAnswer)` (and start the 10-second approval timer after the route is ready).
- **Responsible Development Agent:** **GPT Sol** (or **GPT Astra**).

---

### `HIGH-02` — Legacy API 26–30 Bluetooth Classic SCO Path Refuses Negotiation When `TYPE_BLUETOOTH_SCO` Input Is Not Enumerated Prior to `startBluetoothSco()`

- **Severity:** `HIGH`
- **Classification:** Verified Code Defect (Android 8.0–11 / API 26–30 Compatibility)
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt:237, 293–301, 513–529`
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:582–583`
- **Evidence:**
  - In `BluetoothAudioRouteController.kt:524–529`:
    ```kotlin
    // API 26–30: Classic SCO only, gated on off-call availability + visible SCO input or BT output.
    if (!gateway.isBluetoothScoAvailableOffCall()) return null
    val scoInput = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    if (scoInput != null) return InputRouteKind.BLUETOOTH_SCO to scoInput
    return null
    ```
  - Despite the comment stating *"visible SCO input or BT output"*, the implementation only checks `inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }` and never checks `gateway.getOutputEndpoints()` for `TYPE_BLUETOOTH_A2DP` / `TYPE_BLUETOOTH_SCO`.
  - On many Android 8–11 builds, `AudioManager.getDevices(GET_DEVICES_INPUTS)` does not list a `TYPE_BLUETOOTH_SCO` source until after `startBluetoothSco()` has brought up the HFP SCO link.
- **Reproduction Procedure:**
  1. On an API 26–30 device (or a `BluetoothAudioRouteTest` case with `sdkInt = 29` where `gw.outputs` contains `TYPE_BLUETOOTH_A2DP`, `gw.scoAvailableOffCall = true`, and `gw.inputs` only lists `TYPE_BUILTIN_MIC` prior to `startLegacyBluetoothSco()`), inspect `ctrl.currentSnapshot().bluetoothMicAvailable` and call `ctrl.prepareCommandInput(preferHeadset = true)`.
- **Expected Behavior:** When `sdkInt < 31` and `isBluetoothScoAvailableOffCall()` is true and a Bluetooth headset (`TYPE_BLUETOOTH_SCO` input OR `TYPE_BLUETOOTH_A2DP`/`TYPE_BLUETOOTH_SCO` output) is connected, Ash should attempt `startLegacyBluetoothSco()` and verify the resulting `SCO_AUDIO_STATE_CONNECTED` broadcast (falling back on timeout/error if the device is A2DP-only).
- **Observed / Demonstrated Problem:** `findPreferredBluetoothCommEndpoint()` returns `null`, `currentSnapshot().bluetoothMicAvailable` returns `false`, and `JarvisService.listen()` skips SCO negotiation completely.
- **Recommended Correction:** In `findPreferredBluetoothCommEndpoint()`, on API 26–30 when `scoInput == null`, allow `InputRouteKind.BLUETOOTH_SCO to null` if `gateway.isBluetoothScoAvailableOffCall()` and `gateway.getOutputEndpoints().any { isBluetoothOutputType(it.type) }`, relying on the existing `SCO_AUDIO_STATE_ERROR` / timeout fallback if the sink is A2DP-only.
- **Responsible Development Agent:** **GPT Sol**.

---

### `HIGH-03` — `"Hey Ash"` (`HEY_ASH`) Keyword Spotter Recall Is 8/12 (66.7%) on Synthetic Voices and Reproducibly Fails `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord`

- **Severity:** `HIGH`
- **Classification:** Verified Reproducible Test Failure & Uncalibrated Acoustic Threshold
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/voice/VoiceInput.kt:47` (`WakeModel.KEYWORDS`)
  - `app/src/test/java/com/jarvis/assistant/RealEngineTest.kt:231–236` (`saysSorryWhenNothingFollowsTheWakeWord`)
- **Evidence:**
  - `WakeModel.KEYWORDS` defines:
    ```text
    ▁HE Y ▁A SH :2.6 #0.075 @HEY_ASH
    ▁HE LL O ▁A SH :2.0 #0.1 @HELLO_ASH
    ```
  - In Phase 5 (`04-test-report.md` §7), running `:app:testDebugUnitTest -Preal` against the real `sherpa-onnx` 1.13.8 native engine produced:
    - `KWS [production]: HEY_ASH=8/12 HELLO_ASH=12/12 FALSE=0/12 []`
    - `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` **FAILED** (`java.lang.AssertionError` at `RealEngineTest.kt:234`) because `say("Hey Ash.")` using default Kokoro voice `5` never triggered `@HEY_ASH` (`STATUS: Listening for Hey Ash | task=null`).
  - Conversely, prefix collisions `"Hey Ashley"` (`9/12`–`10/12`) and `"Hello Ashley"` (`16/16`) trigger early because the streaming Zipformer transducer commits before the `-ley` suffix arrives (`02-implementation-status.md` §7–8).
- **Reproduction Procedure:**
  ```bash
  JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk \
    bash ./gradlew --offline --no-daemon :app:testDebugUnitTest -Preal \
    --tests "com.jarvis.assistant.RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord"
  ```
- **Expected Behavior:** Both `"Hey Ash"` and `"Hello Ash"` reliably wake the assistant across supported voices, and `saysSorryWhenNothingFollowsTheWakeWord` passes without inflating false-positive wakes.
- **Observed / Demonstrated Problem:** `"Hey Ash"` (2 syllables, short vowel transition) misses 4 of 12 synthetic renders including Kokoro voice 5 in `saysSorryWhenNothingFollowsTheWakeWord`, while `"Hello Ash"` achieves 12/12. No real human-voice corpus has been recorded or evaluated yet.
- **Recommended Correction:** Record a small multi-speaker human-voice test set on target phone hardware (quiet room, pocket, and background noise) to jointly calibrate `@HEY_ASH` `:boost #threshold` and verify `saysSorryWhenNothingFollowsTheWakeWord` passes without regressing the `0/12` false-wake baseline.
- **Responsible Development Agent:** **GPT Astra** (Wake-Word & Voice Pipeline).

---

## 4. Medium and Low-Priority Findings

### `MED-01` — BLE Audio (`TYPE_BLE_HEADSET`) Communication Mode Is Not Released Before Reply Playback (`prepareOutputForSpeech`)

- **Severity:** `MEDIUM`
- **Classification:** Verified Code Defect
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt:329–352`
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:450–489`
- **Evidence:**
  - In `JarvisService.say()`, lines 483–485 stop `VoiceInput` whenever `audioRoutes.ownsCommunicationRoute && voice.isListening` (which is true for both `BLUETOOTH_SCO` and `BLE_AUDIO`).
  - However, `BluetoothAudioRouteController.prepareOutputForSpeech()` (line 331) checks:
    ```kotlin
    val needsScoTeardown = communicationOwned && activeCommunicationKind == InputRouteKind.BLUETOOTH_SCO
    ```
  - When `activeCommunicationKind == InputRouteKind.BLE_AUDIO`, `prepareOutputForSpeech()` does **not** release the communication session, leaving `communicationOwned = true`, `AudioManager.mode = MODE_IN_COMMUNICATION`, and `communicationDevice` pinned to the BLE conversational endpoint during TTS playback even though `VoiceInput` was stopped.
- **Reproduction Procedure:** Run a `BluetoothAudioRouteTest` scenario with `TYPE_BLE_HEADSET_COMPAT` (Scenario 4), then call `ctrl.prepareOutputForSpeech { ... }` and inspect `ctrl.ownsCommunicationRoute` and `gw.audioMode`.
- **Expected Behavior:** Once command capture on `BLE_AUDIO` finishes and `VoiceInput` stops for reply playback, `prepareOutputForSpeech()` should clear the communication device and restore `AudioManager.MODE_NORMAL` (or keep duplex mode intentionally without stopping `VoiceInput`).
- **Observed / Demonstrated Problem:** `VoiceInput` is stopped, yet `ownsCommunicationRoute` remains `true` and `AudioManager.mode` remains `MODE_IN_COMMUNICATION` throughout TTS playback until `resumeListening()` or `cancelVoiceTurn()` runs.
- **Recommended Correction:** In `BluetoothAudioRouteController.prepareOutputForSpeech()`, release the turn-scoped communication session when `communicationOwned` is true for both `BLUETOOTH_SCO` and `BLE_AUDIO` (using `teardownSettleMs` for Classic SCO and immediate/short settle for BLE Audio).
- **Responsible Development Agent:** **GPT Sol**.

---

### `MED-02` — Platform `SystemVoice` Fallback Bypasses `AudioOut` Preferred-Device Routing and Can Speak Sensitive Prompts on Unverified Outputs

- **Severity:** `MEDIUM`
- **Classification:** Verified Code Defect / Privacy Gap
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/voice/SystemVoice.kt:20–69`
  - `app/src/main/java/com/jarvis/assistant/voice/Voice.kt:118–130`
  - `app/src/main/java/com/jarvis/assistant/service/JarvisService.kt:450–478`
- **Evidence:**
  - `AudioOut.applyOutputPolicy(policy)` applies `AudioTrack.setPreferredDevice(policy.preferredDevice)` only to `AudioOut`'s internal 24 kHz `AudioTrack` (`Kokoro` and `CloudVoice`).
  - When `Kokoro` is not installed or `"system"` voice is selected (`Voice.kt:120–129`), `SystemVoice` uses Android's `android.speech.tts.TextToSpeech` (`SystemVoice.kt:68`), which renders audio in an external TTS service without `setPreferredDevice` or `AudioTrack.routedDevice` verification.
  - Furthermore, `01-architecture.md` §5 and `02-implementation-status.md` §12 item 5 specified: *"System TTS engine routing is not necessarily observable; suppress sensitive speech on unverified output."* Currently, `JarvisService.say()` speaks all text (including contact phone numbers in `confirmDial` and `ScreenAction.Read` text) identically through `SystemVoice`.
- **Reproduction Procedure:** Select `"Android system voice"` in Settings (or do not install Kokoro) and trigger a contact dial confirmation or screen read while multiple output devices are present.
- **Expected Behavior:** Route hints or privacy checks should distinguish verified `AudioOut` playback from unverified external `SystemVoice` playback, or pass `Bundle` stream/device parameters where supported and redact/display sensitive details on screen when output privacy cannot be verified.
- **Observed / Demonstrated Problem:** `SystemVoice` ignores `OutputRoutePolicy.preferredDevice` and speaks all prompts without checking whether output routing is verified.
- **Recommended Correction:** Have `Speaker` check `AudioOut.routeController.prepareOutput()` and expose whether output routing is verified (`usingKokoro || cloudConfig() != null`); when unverified `SystemVoice` is used in locked/headset contexts, avoid reading raw phone numbers or screen payloads aloud without explicit confirmation.
- **Responsible Development Agent:** **GPT Sol**.

---

### `MED-03` — Global `cleartextTrafficPermitted="true"` in `network_security_config.xml` Weakens Process-Wide OS Network Policy

- **Severity:** `MEDIUM`
- **Classification:** Architectural Defense-in-Depth Risk
- **Affected File & Line Range:**
  - `app/src/main/res/xml/network_security_config.xml:3–5`
- **Evidence:**
  - `<base-config cleartextTrafficPermitted="true" />` enables cleartext HTTP across the entire application process so users can configure arbitrary LAN IP addresses for local Ollama / LM Studio servers (`http://192.168.x.x:11434/v1`).
  - While `Endpoints.requireHttpUrl()` (`Endpoints.kt:30–32`), `ProviderRequest` (`ProviderRequest.kt:34`), `CloudVoice` (`CloudVoice.kt:129`), `ModelDownloader` (`ModelDownloader.kt:23–39`), and `GgufDownloader` (`GgufDownloader.kt:162`) enforce HTTPS for non-local hosts in Kotlin code, Android's OS-level `NetworkSecurityPolicy` will not block accidental cleartext connections from any future library or component.
- **Reproduction Procedure:** Inspect `app/src/main/res/xml/network_security_config.xml`.
- **Expected Behavior:** Minimize cleartext scope or clearly document and test that every network client in the APK routes through `Endpoints.requireHttpUrl()`.
- **Observed / Demonstrated Problem:** Platform-level cleartext protection is globally disabled.
- **Recommended Correction:** Add a unit test asserting that every `OkHttpClient` call site in `com.jarvis.assistant` validates URLs through `Endpoints.requireHttpUrl()` or pinned `https://` constants, and document the LAN HTTP requirement in user-facing Settings.
- **Responsible Development Agent:** **GPT Astra**.

---

### `MED-04` — Four Dead Legacy Files Including Conflicting SQLite Schema v1 (`JarvisDatabaseHelper.kt`) Ship in Production Source Tree

- **Severity:** `MEDIUM`
- **Classification:** Verified Maintainability & Latent Database-Downgrade Defect
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/memory/JarvisDatabaseHelper.kt:11, 64–65` (`DATABASE_NAME = "jarvis_memory.db"`, `DATABASE_VERSION = 1`)
  - `app/src/main/java/com/jarvis/assistant/memory/MemoryStore.kt:372–373` (`DB_NAME = "jarvis_memory.db"`, `DB_VERSION = 3`)
  - `app/src/main/java/com/jarvis/assistant/memory/MemoryManager.kt:1–180`
  - `app/src/main/java/com/jarvis/assistant/agent/FastCommandRouter.kt:1–600`
  - `app/src/main/java/com/jarvis/assistant/agent/LocalServerManager.kt:1–170`
- **Evidence:**
  - `JarvisDatabaseHelper` targets `"jarvis_memory.db"` with `DATABASE_VERSION = 1`, whereas production `MemoryStore` opens the same `"jarvis_memory.db"` file with `DB_VERSION = 3`.
  - Although no production call site currently instantiates `JarvisDatabaseHelper` or `MemoryManager`, keeping them in `app/src/main/java/` means any accidental reference will throw `android.database.sqlite.SQLiteDowngradeException: Can't downgrade database from version 3 to 1`.
- **Reproduction Procedure:** In a test or activity, open `MemoryStore.get(context)` (creates v3 DB), then call `JarvisDatabaseHelper(context).readableDatabase`.
- **Expected Behavior:** Unused duplicate classes and conflicting SQLite helpers should not exist in `app/src/main/java/`.
- **Observed / Demonstrated Problem:** Dead classes bloat the codebase, confuse maintenance, and carry a latent v3->v1 SQLite crash.
- **Recommended Correction:** Delete `memory/JarvisDatabaseHelper.kt`, `memory/MemoryManager.kt`, `agent/FastCommandRouter.kt`, and `agent/LocalServerManager.kt` (along with their 3 legacy-only unit test files) in a dedicated cleanup commit.
- **Responsible Development Agent:** **GPT Astra**.

---

### `LOW-01` — `HeadsetButtonBridge` Does Not Proactively Deactivate Its `MediaSession` via `AudioPlaybackCallback` When Music Starts

- **Severity:** `LOW`
- **Classification:** Verified Edge-Case Defect (Only When Opt-In `headsetButtonFallback` Is Enabled)
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/audio/HeadsetButtonBridge.kt:67–77, 108–122`
  - `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt:84, 113, 132, 190, 357, 500–502`
- **Evidence:**
  - `HeadsetButtonBridge` is **OFF by default** (`Settings.headsetButtonFallback = false`), so default users are unaffected.
  - When a user enables `headsetButtonFallback` while music is paused, `HeadsetButtonBridge` sets `session.isActive = true`. If the user then starts music in Spotify, `refreshPriority()` is not called until a device topology change occurs or a media key is pressed.
  - When the first Play/Pause key event arrives, `handleKeyEvent()` sees `isMusicActive() == true`, calls `refreshPriority()` (`session.isActive = false`), and returns `false`. On Android builds that do not re-route a media button event to the next `MediaSession` after the top active session returns `false`, that single press is lost.
- **Reproduction Procedure:** Enable `Headset button fallback`, start music playback while `HeadsetButtonBridge.isSessionActive` is `true`, and observe that `isSessionActive` remains `true` until the first key event or device change.
- **Expected Behavior:** `HeadsetButtonBridge` should register `AudioManager.AudioPlaybackCallback` while enabled so `refreshPriority()` deactivates the session immediately when another app begins audio playback.
- **Observed / Demonstrated Problem:** Reactive rather than proactive `MediaSession` deactivation on music start.
- **Recommended Correction:** Register an `AudioManager.AudioPlaybackCallback` inside `HeadsetButtonBridge.ensureSession()` (and unregister in `release()`) that calls `refreshPriority()` on `onPlaybackConfigChanged`.
- **Responsible Development Agent:** **GPT Sol**.

---

### `LOW-02` — Hardcoded `/data/data/com.termux/...` Path Breaks Multi-User / Work-Profile Termux Execution

- **Severity:** `LOW`
- **Classification:** Verified Minor Defect (Flagged by Android Lint `SdCardPath`)
- **Affected Files & Line Ranges:**
  - `app/src/main/java/com/jarvis/assistant/Settings.kt:435`
  - `app/src/main/java/com/jarvis/assistant/ai/LocalServerManager.kt:71`
- **Evidence:**
  - Hardcodes `/data/data/com.termux/files/usr/bin/bash` and `/data/data/com.termux/files/home/.jarvis/start-llama.sh`, which fails on secondary Android user profiles (`/data/user/<id>/com.termux/...`).
- **Recommended Correction:** Resolve Termux's `ApplicationInfo.dataDir` via `PackageManager.getApplicationInfo("com.termux", 0).dataDir` with fallback to `/data/data/com.termux`.
- **Responsible Development Agent:** **GPT Astra**.

---

## 5. Confirmed Implementation Capabilities (Verified in Code & Host Tests)

| Audit Area | Verified Capability | Source Evidence |
| --- | --- | --- |
| **Audit 1: Wake-Word Detection** | Offline `"Hey Ash"` (`@HEY_ASH`) and `"Hello Ash"` (`@HELLO_ASH`) via `sherpa-onnx` GigaSpeech 3.3M Zipformer; bare `"Ash"` explicitly blocked; 1.5s pre-roll handoff into Kroko ASR on a single `MicrophoneOwner` lease; timed + regex wake-prefix stripping; self-wake prevention via `voice.muted = true` + 150ms post-speech tail + `isBargeIn` echo comparator; clean model-missing error reporting. | `VoiceInput.kt:40–68, 126–205, 549–837, 918–992`; `WakePhrase.kt:7–63`; `RealEngineTest.kt:162–325` |
| **Audit 2: Android Background Behavior** | Explicit user consent (`ACTION_ARM`); `START_NOT_STICKY`; prompt `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` in `onCreate()` with exception catch & `failStart()`; notification-only `BootReceiver` (no API 34/35 boot FGS crash); `AshVoiceInteractionService` + `AshSessionService` + local `AshRecognitionService` properly guarded by `BIND_VOICE_INTERACTION` and `RECORD_AUDIO`; keyguard turn cancellation on `ACTION_SCREEN_OFF`. | `JarvisService.kt:154–314, 388–422, 1352–1405`; `BootReceiver.kt:14–34`; `AshVoiceInteractionService.kt:19–75`; `AshRecognitionService.kt:18–105`; `AndroidManifest.xml:85–143` |
| **Audit 3: Bluetooth & Audio** | Minimal permissions (`MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` maxSdk 30, `BLUETOOTH_CONNECT`; no scan/location); standby wake stays on phone/wired mic (zero idle Classic SCO); turn-scoped BLE Audio (`TYPE_BLE_HEADSET`) and Classic SCO (`setCommunicationDevice` API 31+, `startBluetoothSco` API 26–30); generation-scoped timeout/settle/grace timers; `VoiceInput` stopped before SCO teardown; per-utterance `AudioFocusRequest` (`Speaker`); private output stops on headset disconnect; opt-in `HeadsetButtonBridge` OFF by default. | `BluetoothAudioRouteController.kt:36–628`; `AudioSystemGateway.kt:70–225`; `HeadsetButtonBridge.kt:30–159`; `Voice.kt:64–154`; `AudioOut.kt:30–130` |
| **Audit 4: Phone Control** | Deterministic bilingual fast router (`router/FastCommandRouter.kt`); contact disambiguation + 10s spoken confirmation + number re-check + `ACTION_DIAL` (never `ACTION_CALL`); `MediaNotificationListener` active-session transport controls + `ACTION_PLAY_FROM_SEARCH` + global media key fallback; lock-screen gate permitting only time queries and untargeted media transport; `PhoneAgent` single-step observation-bound proposals with `OneUseApproval`. | `FastActionExecutor.kt:97–120, 282–373, 498–620`; `PhoneAgent.kt:92–308`; `JarvisAccessibilityService.kt:74–355`; `ScreenReader.kt:68–192` |
| **Audit 5: Security** | Keystore AES-256-GCM credentials (`ProviderCredentials.kt`); redacted `toString()`; scrubbed provider error bodies (`ProviderRequest.kt:18–31`); HTTPS required for remote hosts + `NO_PROXY`/`LOCAL_DNS` for local HTTP (`Endpoints.kt:30–78`); SHA-256 verification on all model downloads and `sherpa-onnx` AAR; zip-slip canonical path check in `ModelDownloader.unpack()` (`ModelDownloader.kt:96`); zero sensitive data in `Log.*` calls. | `ProviderCredentials.kt:14–79`; `ProviderRequest.kt:18–101`; `Endpoints.kt:18–85`; `ModelDownloader.kt:17–109`; `SensitiveFilter.kt` |
| **Audit 6 & 7: Reliability, Performance & Privacy** | Single `MicrophoneOwner` arbiter; bounded mic retry schedule (`500ms`, `2000ms`); reusable pre-allocated `PcmHistory` buffers (no per-read heap churn in steady state); zero LLM/ASR inference during wake standby (single-thread int8 KWS only); `onTrimMemory(TRIM_RUNNING_CRITICAL)` unloads Kokoro (~200 MB) and local runtime (~1 GB); explicit `Turn off` in app and notification. | `VoiceInput.kt:62–67, 385–415, 549–580`; `JarvisService.kt:316–324, 275–279` |

---

## 6. Unverified Capabilities (Require Physical Device & Hardware)

The following capabilities exist in code and pass simulated host tests, but have **zero physical-device verification** (`04-test-report.md` §6, §13):

1. **Physical Screen-Off & Doze Survival:** Whether Android 12–15 OEM power managers (Samsung One UI, Xiaomi HyperOS, OnePlus/Realme ColorOS, Pixel) keep `JarvisService`'s `microphone` FGS streaming non-zero PCM after 15–60+ minutes of screen-off idle and forced Doze.
2. **Real Human Voice Wake Accuracy:** Recall and false-accept rates per hour for `"Hey Ash"` and `"Hello Ash"` across male/female speakers, Indian/global English accents, far-field distance, and pocket muffler conditions.
3. **Physical Bluetooth Earbud & Headphone Interop:** Real Classic HFP/SCO negotiation latency vs. `SCO_SETTLE_MS = 300ms`, SCO->A2DP transition vs. `OUTPUT_SETTLE_MS = 250ms`, BLE Audio (`TYPE_BLE_HEADSET`) LC3 routing on Android 13–15, and actual earbud hardware button/gesture delivery.
4. **Live Third-Party Media & Telecom Coexistence:** Actual `MediaController.TransportControls` interaction with installed Spotify/YouTube Music, and live cellular/WhatsApp/Meet call preemption and post-call resume.
5. **Android 14+ `takeScreenshotOfWindow` Latency & Behavior:** Live execution of `JarvisAccessibilityService.canCaptureWindow()` against real foreground apps and `FLAG_SECURE` banking/password windows.
6. **Measured Battery, Thermal, RAM, and Startup Benchmarks:** No on-device `dumpsys batterystats`, `meminfo`, or startup timings have been collected.

---

## 7. Screen-Off Feasibility Verdict (Audit 1 & Audit 2)

- **Verdict:** **Architecturally Sound for User-Armed Foreground Service (Case A); Subject to OEM Battery Policies and Pocket Acoustics.**
- **What Actually Works in Code:**
  - When armed from a visible activity (`ACTION_ARM`) with `RECORD_AUDIO` and `POST_NOTIFICATIONS` granted, `JarvisService` promotes immediately to `FOREGROUND_SERVICE_TYPE_MICROPHONE` (`JarvisService.kt:185–194`) and keeps a single 16 kHz `AudioRecord` feeding local `sherpa-onnx` KWS (`VoiceInput.kt:439–522`).
  - When the screen turns off (`Intent.ACTION_SCREEN_OFF`), `screenReceiver` (`JarvisService.kt:154–161`) cancels any active command or speech turn (`"Screen locked. Repeat the request if needed."`) and returns to `WAKE_LISTENING` standby.
  - Time queries (`"What time is it?"`) and untargeted media transport (`"Pause music"`, `"Next song"`) are the **only** commands permitted while the keyguard is locked (`JarvisService.kt:885, 984–988`; `FastActionExecutor.kt:103–104`).
- **Hard Platform & Acoustic Limits (Honestly Documented):**
  - Third-party apps cannot access Android's privileged `AlwaysOnHotwordDetector` / SoundTrigger DSP (`CAPTURE_AUDIO_HOTWORD` is `signature|privileged`).
  - Because Classic Bluetooth SCO is not held open during standby, **speaking `"Hey Ash"` softly into Bluetooth earbuds while the phone is muffled inside a pocket will not be heard by the inactive earbud mic** unless the phone's built-in mic picks up the user's voice.

---

## 8. Bluetooth Reliability Verdict (Audit 3)

- **Verdict:** **Clean Native State Machine for Single-Turn Commands, Blocked from Full Release by `HIGH-01` (Multi-Turn `host.ask()` Loses BT Mic), `HIGH-02` (API 26–30 Input Check), and `MED-01` (BLE Audio Output Teardown).**
- **Strengths Confirmed:**
  - Zero Expo/React Native code; all 5 Yunto defects identified in Phase 1 were addressed; `VoiceInput` is stopped before SCO teardown (`JarvisService.kt:376–383, 483–485, 1140–1146`); and private headset removal mid-speech immediately pauses output (`JarvisService.kt:233–239`).
- **Defects Requiring Fix Before Personal Release:**
  - `HIGH-01` breaks hands-free Bluetooth confirmation (`"Yes"` after `"Call Mom?"`) when the phone is in a pocket.
  - `HIGH-02` breaks legacy API 26–30 Bluetooth headsets that don't expose a `TYPE_BLUETOOTH_SCO` input prior to `startBluetoothSco()`.
  - `MED-01` leaves `MODE_IN_COMMUNICATION` active during TTS reply on `BLE_AUDIO` headsets.

---

## 9. Phone Automation Safety Verdict (Audit 4)

- **Verdict:** **High Safety — Strong Deterministic Verification and Fail-Closed Architecture.**
- **Verified Safeguards:**
  1. **No LLM self-authorization:** `PhoneAgent` (`PhoneAgent.kt:233–278`) rejects batched tool calls, rejects stale `observation_id`s, forbids raw coordinate taps/swipes or Enter submission, and routes all actions through `JarvisAccessibilityService.perform()`.
  2. **Protected surface isolation:** `ScreenReader.foreground()` (`ScreenReader.kt:69–99`) rejects any window covered by an overlay, non-application window, protected package (`bank`, `wallet`, `paytm`, `phonepe`, `authenticator`, `permissioncontroller`, `packageinstaller`), or tree containing a password / `isAccessibilityDataSensitive` node (`ScreenReader.kt:114–119, 170–179`).
  3. **Android 14+ secure-window probe:** `canCaptureWindow()` (`JarvisAccessibilityService.kt:117–146`) uses `takeScreenshotOfWindow` to verify the window does not have `FLAG_SECURE`, immediately closing the hardware buffer without storing pixels. *(Note: this means `PhoneAgent` screen observation intentionally requires Android 14 / API 34+, failing closed on API 26–33 while deterministic global navigation still works.)*
  4. **Bound one-use approvals:** `OneUseApproval` binds `(turn, ScreenContext, Plan)` with a 10-second expiry and re-verifies the live window and node stamps before and after asking (`JarvisAccessibilityService.kt:193–221`).
  5. **Honest outcome reporting:** Neither `FastActionExecutor` nor `PhoneAgent` claims an unverified action succeeded (`"I asked Android to open the dialer. Tap Call there to place the call."`, `"I sent the play request to ..., but couldn't verify playback."`).

---

## 10. Security and Privacy Assessment (Audit 5, Audit 6 & Audit 7)

- **Credentials & Storage:** Encrypted at rest using `AndroidKeyStore` AES-256-GCM with preference-slot AAD (`ProviderCredentials.kt`). `android:allowBackup="false"` prevents cloud backup extraction.
- **Permissions:** Minimum necessary set (`INTERNET`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` maxSdk 30, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`, `SET_ALARM`, `com.termux.permission.RUN_COMMAND`, `READ_CONTACTS`). Dangerous/intrusive permissions (`CALL_PHONE`, `READ_PHONE_STATE`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) are absent.
- **Privacy & Cloud Data Transmission:**
  - Wake standby runs 100% offline on-device (no audio leaves the device).
  - Personal memories are filtered by `SensitiveFilter` (blocks passwords, OTPs, card numbers, SSNs, API keys) and are **only** sent to remote cloud LLMs if the user explicitly enables `shareMemoryWithCloud` (`Settings.kt:371–373`, default `false`).
  - Raw `PcmHistory` buffers are zero-filled (`wipe()` / `clear()`) on turn completion and capture close (`VoiceInput.kt:571–579, 863`).

---

## 11. Validation of Previous Tests & Testing Gaps (Audit 8)

Review of `docs/ash/04-test-report.md` against actual test files and execution logs confirms:

- **Accurately Reported in `04-test-report.md`:**
  - **42 host unit test suites (274 tests) ran and passed** (`36.75s` cumulative time) under Robolectric SDK 35 + MockWebServer.
  - **11 native `RealEngineTest` (`-Preal`) tests ran** against real `sherpa-onnx` Linux JNI and models: **10 passed, 1 failed** (`saysSorryWhenNothingFollowsTheWakeWord` failed on `"Hey Ash."` with Kokoro voice 5).
  - **All 19 Phase 4 Bluetooth tests (`BluetoothAudioRouteTest` + `HeadsetButtonBridgeTest`) were simulated** via in-memory `FakeGateway` and Robolectric.
  - **Zero instrumentation or physical-device tests ran** (`0` ADB devices attached).
- **Major Missing Automated Test Scenarios Identified by This Audit:**
  1. **Multi-turn Bluetooth `host.ask()` confirmation test:** No test exercises `JarvisService` starting a headset turn (`preferHeadset = true`), calling `say(question)` inside `host.ask()`, and verifying which microphone route is active when `listenForCommand` opens for the `"Yes"` answer (`HIGH-01`).
  2. **API 26–30 Bluetooth Classic headset without pre-enumerated `TYPE_BLUETOOTH_SCO` input:** `BluetoothAudioRouteTest.bluetoothClassicLegacyApi29UsesStartBluetoothScoAndBroadcast` pre-populated `gw.inputs += AudioEndpoint(20, TYPE_BLUETOOTH_SCO, ...)` before `startBluetoothSco()`, masking `HIGH-02`.
  3. **BLE Audio `prepareOutputForSpeech` teardown test:** `BluetoothAudioRouteTest.bleAudioPreferredOverClassicScoOnApi31` tested `prepareOutput()` instead of `prepareOutputForSpeech()`, masking `MED-01`.

---

## 12. Android Compatibility Concerns

| API Level / Platform | Compatibility Status & Concern |
| --- | --- |
| **API 26–30 (Android 8.0–11)** | Affected by `HIGH-02` (legacy Bluetooth SCO input pre-check). Also note that `PhoneAgent` UI tree observation is intentionally disabled below API 34 (`JarvisAccessibilityService.kt:75–77`) because per-window secure-surface detection (`takeScreenshotOfWindow`) was added in API 34. Fast commands and global navigation work on API 26+. |
| **API 31–33 (Android 12–13)** | Modern `setCommunicationDevice` and `BLUETOOTH_CONNECT` supported; `PhoneAgent` screen observation remains disabled below API 34 by design. Android 13 sideloaded APK requires user to enable "Allow restricted settings" in App Info before toggling Accessibility / Notification Listener. |
| **API 34–35 (Android 14–15)** | Primary target tier (`compileSdk = 35`, `targetSdk = 35`). Full `FOREGROUND_SERVICE_TYPE_MICROPHONE` WIU compliance, `BootReceiver` notification-only compliance, and `takeScreenshotOfWindow` secure-window verification active. |
| **Android 16–17 (Future OS Compatibility)** | Per-utterance `AudioFocusRequest` on a WIU-capable FGS and absence of background boot FGS launches align with Android 16/17 background audio hardening. |

---

## 13. Required Fixes Before Personal-Use Release

In priority order for **GPT Sol** and **GPT Astra**:

1. **[MUST FIX — `HIGH-01`] Re-negotiate Bluetooth headset mic for `host.ask()` and headset follow-ups (`JarvisService.kt:483–489, 582–589, 1202–1220`):** Preserve headset-turn affinity across `say(question)` inside `hostFor(id).ask()` so spoken `"Yes"`/`"No"` confirmations and disambiguations use the earbud microphone when triggered from earbuds.
2. **[MUST FIX — `HIGH-02`] Allow API 26–30 legacy SCO negotiation when Bluetooth A2DP output is connected (`BluetoothAudioRouteController.kt:524–529`):** Do not require `TYPE_BLUETOOTH_SCO` to appear in `getInputEndpoints()` before calling `startLegacyBluetoothSco()`.
3. **[MUST FIX — `HIGH-03`] Calibrate `@HEY_ASH` wake threshold and resolve `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` (`VoiceInput.kt:47`, `RealEngineTest.kt:231–236`):** Tune `@HEY_ASH` against both synthetic voice 5 and real human voice recordings on target hardware so `"Hey Ash"` recall matches `"Hello Ash"` without introducing false wakes.
4. **[SHOULD FIX — `MED-01`] Release `BLE_AUDIO` communication session in `prepareOutputForSpeech()` (`BluetoothAudioRouteController.kt:331`):** Clear `communicationOwned` and restore `MODE_NORMAL` after BLE command capture stops.
5. **[SHOULD FIX — `MED-02` & `MED-04`] Guard unverified `SystemVoice` sensitive readouts and delete the 4 dead legacy files (`JarvisDatabaseHelper.kt`, `MemoryManager.kt`, `agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`).**
6. **[OPTIONAL — `LOW-01` & `LOW-02`] Add `AudioPlaybackCallback` to `HeadsetButtonBridge` and resolve Termux `dataDir` via `PackageManager`.**

---

## 14. Physical-Device Testing Requirements

Before promoting Ash from `READY FOR CONTROLLED DEVICE TESTING` to `READY FOR PERSONAL-USE RELEASE`, execute and record on a physical Android 14/15 handset with Bluetooth earbuds:

1. **Multi-turn Bluetooth Earbud Confirmation Test (Post-`HIGH-01` Fix):** Trigger Ash from earbuds with phone in another room -> `"Call <Contact>"` -> hear confirmation prompt in earbuds -> reply `"Yes"` into earbuds -> verify dialer opens with contact's number.
2. **Human Wake-Word & False-Wake Matrix (Post-`HIGH-03` Fix):** 20 `"Hey Ash"` + 20 `"Hello Ash"` utterances across quiet room, 2m distance, and pocket; plus 1 hour of background speech/music with zero false wakes.
3. **Screen-Off & Doze 60-Minute Soak:** Arm FGS, lock screen, test wake at 5 min, 30 min, 60 min, and under `adb shell dumpsys deviceidle force-idle`.
4. **Spotify/YouTube Music + Incoming Call Coexistence:** Verify stereo A2DP preservation in standby, duck/pause and resume around TTS replies, and clean yield/resume across cellular and VoIP calls.
5. **Android 14/15 `PhoneAgent` Secure-Window & Approval Test:** Verify `PhoneAgent` refuses banking/password screens and requires spoken `"Yes"` within 10 seconds before typing or searching.

---

## 15. Release-Readiness Decision

### Chosen Final Status: **`READY FOR CONTROLLED DEVICE TESTING`**

- **Personal-Use Sideload Readiness:** The APK (`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`) is safe to install on a personal test phone for controlled qualification today: it will not place unconfirmed calls, expose API keys, hijacks music buttons by default, or act on banking/password screens. Fixing `HIGH-01`, `HIGH-02`, `HIGH-03`, and `MED-01` and completing the physical-device test matrix in Section 14 will qualify it for **`READY FOR PERSONAL-USE RELEASE`**.
- **Public Google Play Store Readiness (`NOT READY` / Policy-Restricted):** As documented since Phase 1 (`00-repo-audit.md` §1, §10), Google Play's AccessibilityService policy prohibits general-purpose AI assistants from using `AccessibilityService` to autonomously observe and act across third-party apps unless `JarvisAccessibilityService` is stripped or disabled in a dedicated Play Store build flavor. Keep Ash as a personal-use sideloaded APK (or split a non-Accessibility flavor if public Play Store distribution is ever desired).

---

## 16. Handoff to GPT Sol or GPT Astra

1. **Branch & Revision to Base Fixes On:** `phase4-bluetooth-integration` at `e0ea3e0` (code at `92e984a`).
2. **Immediate Action Plan for GPT Sol / GPT Astra:**
   - **Step 1 (Bluetooth & Audio — GPT Sol):** Fix `HIGH-01` (`JarvisService.kt:1202–1220` `host.ask()` Bluetooth mic re-negotiation), `HIGH-02` (`BluetoothAudioRouteController.kt:524–529` API 26–30 SCO input check), `MED-01` (`BluetoothAudioRouteController.kt:331` BLE Audio teardown in `prepareOutputForSpeech`), and `LOW-01` (`HeadsetButtonBridge` `AudioPlaybackCallback`). Add unit tests for all three in `BluetoothAudioRouteTest.kt`.
   - **Step 2 (Wake-Word & Hygiene — GPT Astra):** Fix `HIGH-03` (`VoiceInput.kt:47` `@HEY_ASH` recall so `RealEngineTest.saysSorryWhenNothingFollowsTheWakeWord` passes without false wakes), `MED-02` (`SystemVoice` sensitive-output check), `MED-04` (delete the 4 dead legacy files including `JarvisDatabaseHelper.kt`), and `LOW-02` (Termux `dataDir` lookup).
   - **Step 3 (Device Qualification):** Re-run `:app:testDebugUnitTest`, `:app:testDebugUnitTest -Preal`, `:app:lintDebug`, and `:app:assembleDebug`, then execute the physical-device test matrix in Section 14.
