# Ash Phase 3 — Implementation status

## 1. Work recovered after the PC crash

Continued the uncommitted Phase 3 tree on Jarvis `5dd9934`; no reset, re-clone, or upstream merge. Restored Android Platform 35 (rev. 2), Build-Tools 34.0.0 and platform-tools under `~/Android/Sdk`. Restored the checksum-verified Gradle 8.11.1 wrapper distribution after its direct download timed out. Reused Temurin 21.0.13, cached dependencies and sherpa-onnx 1.13.8 Android AAR/Linux JNI. The AAR and the KWS, Kroko and Kokoro archives matched their repository SHA-256 pins.

## 2. Last verified milestone before recovery

The previous full host run reported 40 suites, 255 tests (no failures, errors or skips), and lint reported 0 errors and 33 warnings. The prior APKs predated the wake-tail fix. Kotlin app and unit-test compilation passed with that fix, but its final real-engine run had not completed. See [audit](00-repo-audit.md) and [architecture](01-architecture.md) for the original baseline and design, not current verification.

## 3. Previously completed features

- Ash branding, Dynamic Island, English/Hindi UI resources, persona and crash reporting. Package identity remains compatible with Jarvis.
- Local `Hey Ash` and `Hello Ash`, optional custom phrase, no bare `Ash`. `VoiceInput` uses one microphone lease; records with route verification, client-silence/call-mode checks and bounded retries. English handoff replays volatile PCM into Kroko on the same capture.
- Explicit microphone standby consent (`ACTION_ARM`), `START_NOT_STICKY`, screen-off turn cancellation, event-driven call-mode recovery on API 31+, and notification-only reboot reminder. Under keyguard, only time queries and untargeted media transport may bypass unlock.
- Default-assistant service/session registration and an English local `AshRecognitionService` that returns text to its caller, never commands. Platform speech fallback excludes Ash's own provider.
- Per-utterance focus in `Speaker`; optional `MediaNotificationListener` with active session transport, supported play-from-search intent and untargeted global media-key fallback. Ash does not claim media buttons.
- Contact resolution with disambiguation, strict dialable numbers, spoken approval with 10-second expiry and target recheck before `ACTION_DIAL`; the user places the call in the dialer.
- Typed phone actions with foreground/window and node-fingerprint checks, one-use approvals and protection for sensitive UI. Deterministic global navigation works without the API 34 screenshot capability.
- Keystore AES-GCM provider credentials with legacy migration, HTTPS for remote provider endpoints, constrained local HTTP (`LOCAL_DNS`, `NO_PROXY`), canceled/sanitized provider requests and SHA-256-pinned voice models. The Android network XML still permits cleartext at platform level so configured LAN addresses work; provider enforcement is in code, not a global OS prohibition.

## 4. Partially implemented features

- Screen-off capture and assistant entry points are implemented but not handset-qualified. Assistant session launch from keyguard is disabled. The assistant role is optional and does not implement the wake words.
- `AudioRouteController` is wired, but the production implementation validates only the built-in microphone. No Bluetooth selection, earbud mic capture or headset-trigger compatibility layer ships.
- Host coverage verifies routing/validation, dial confirmation, provider behavior, assistant registration and activity navigation. It does not prove third-party player transport or a real client recognition session.

## 5. Files repaired after the crash

| File or area | Repair |
|---|---|
| `AshPhase3Test.kt` | SDK 35 hang was in the `ActivityScenario.close()` teardown path in `mainActivitySwitchesToHomeOnAssistantIntent`; use `Robolectric.buildActivity(...).setup()` with explicit pause/stop/destroy, preserving the navigation assertion. |
| `VoiceInput.kt`, `CleanTranscriptTest.kt`, `RealEngineTest.kt` | Replayed wake PCM could produce `As`, `Hello asked`, or variable forms such as `Hey us` and `KOS`. Treat wake-only segments as empty and use native KWS/ASR timing for unrecognized spellings. Assert that the one-breath Hey fixture starts with the command, not a leaked wake prefix. |
| `JarvisService.kt`, `MainActivity.kt`, `SettingsFragment.kt`, `JarvisAccessibilityService.kt`, `FastActionExecutor.kt`, island resources | Recovered lock-screen policy, call-end resume, assistant-intent Home navigation, bare-Ash guard, global navigation, app launch fallback and branding. |

KWS timestamps can restart on native silence resets. The capture worker maps the final wake token back to the replay timeline using the pinned model's chunk size and confirmation blanks; Kroko token times identify words already underway in that span. An empty wake-only endpoint resets ASR without sending a command or closing the turn. This preserves the normal anchored phrase handling and does not log transcripts.

## 6. New implementation changes

- Tuned `HEY_ASH` to `:2.6 #0.075`; `HELLO_ASH` remains `:2.0 #0.1`. Kept the same tokenization and a single spotter. The Gradle measurement now uses the same one-thread KWS configuration as production and reports the negative denominator.
- Added a timed fallback at the wake handoff, not a growing spelling list. Manual input and non-wake turns do not use that fallback. Kept follow-on payload assertions, including the first command words. Updated the changelog.

## 7. Build and test results

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | 40 suites, 255 tests; 0 failures, 0 errors, 0 skipped |
| Eight selected `RealEngineTest` scenarios, `-Preal` | 8 passed; 0 failures, 0 errors, 0 skipped. Both phrases in one breath and with a pause; sound-alikes/bare Ash; Off during loading; custom phrase; matrix. |
| Final real-engine matrix | **Hey Ash 7/12**, **Hello Ash 12/12**, **0/12** non-prefix negative wakes. Four Kokoro voices for positives, two for negatives. |
| Standalone calibration (two renders, not the Gradle verdict) | Hey baseline 7/12 in each; tuned 10/12 and 9/12. Hello unchanged at 12/12 in each. Original negatives 0/12 per render. Ashley prefix tests: Hey 9/12 and 10/12, Hello 16/16 in both tuned renders. |
| `:app:lintDebug` | 0 errors, 33 warnings; `app/build/reports/lint-results-debug.html` |
| `:app:assembleDebug` and ABI checker | Four fresh APKs; expected ELF architectures and Sherpa/ONNX libraries verified. No embedded `libllama_server.so` present in this local build. |

All four APK manifests report application ID `com.jarvis.assistant`, version `0.1.0` (code 1). Fresh build timestamps were 14:28:38–14:28:40 local time, newer than the replaced 18:28 outputs from the prior build.

| APK path | Bytes |
|---|---:|
| `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 28,674,038 |
| `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` | 27,286,882 |
| `app/build/outputs/apk/debug/app-x86_64-debug.apk` | 30,501,678 |
| `app/build/outputs/apk/debug/app-universal-debug.apk` | 54,960,922 |

```bash
export JAVA_HOME="$HOME/.jdk/current" ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
bash ./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/check-apk-abis.py debug
adb devices -l
adb shell getprop ro.product.cpu.abilist
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb shell am start -n com.jarvis.assistant/.ui.MainActivity
adb shell dumpsys activity services com.jarvis.assistant
adb shell dumpsys audio
adb shell dumpsys deviceidle
```

For a test phone only, force Doze with `adb shell dumpsys deviceidle force-idle` after arming, then restore with `adb shell dumpsys deviceidle unforce`. Install and arm through the UI before disabling networking or locking the phone. No handset was connected during closeout; the ADB device checks above are instructions, not measured results.

## 8. Remaining bugs and blockers

- `HEY_ASH` recall varies materially across synthetic renders; it still trails `HELLO_ASH`. A small, synthesized corpus cannot estimate human recall or a false-wake rate per hour. `Hey Ashley` and `Hello Ashley` can trigger because the streaming spotter can commit before the suffix arrives. These are not included in the non-prefix negative count.
- `PhoneAgent` requires API 34+ per-window screenshot verification (`takeScreenshotOfWindow`) as a local secure-window check. Protected or unverifiable windows fail closed. Back/Home/Recents/Notifications/Quick Settings do not require this check.
- Legacy `agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, and `memory/JarvisDatabaseHelper.kt` remain, unused by the production paths; `claude-delivery/` is also retained. Do not reuse the legacy v1 helper against the active v3 database.

## 9. Screen-off wake-word status

Physical-device verification is still required. Host tests prove software handoff, not that an armed microphone foreground service maintains live audio after lock, under Doze, or on a specific OEM build. Boot, role binding and accessibility enablement do not silently arm listening. Wake recognition is not hardware DSP or authorization to unlock the phone.

## 10. Features needing real-device verification

1. Install and provision wake/English ASR models. Arm visibly, go offline, lock, and issue an offline time query with each phrase, immediately, after 15/60 minutes and in forced Doze. Confirm the display stays off. Verify Turn off, process stop/reboot, permission/mic privacy changes, incoming calls and recovery after hang-up.
2. Measure held-out human voices, both phrase styles, varied accents, distance/noise, ordinary conversation and media. Record hits/attempts, duplicate or clipped commands, false wakes per listening hour and uptime.
3. Select Ash under Default digital assistant. Trigger the OS gesture unlocked; verify one turn and cancel on hide/role loss. Test locked behavior separately. Verify local recognition-client results and release without dispatching commands.
4. Enable and revoke notification access; test `pause`, `resume`, `next`, `previous`, app targeting and search on actual players, and duck/restore during Ash speech. Test confirmed system dialing using a controlled contact.
5. Sideload on Android 13–15 and exercise Restricted Settings for Accessibility and Notification Listener. Verify deterministic navigation with access on, safe refusal with access off, and API 34+ window checks against secure surfaces.
6. Compare matched unplugged Off, Talk-only and armed runs; inspect `adb shell dumpsys batterystats` and `dumpsys audio`, thermal state and observed mic availability. Verify no idle focus and no mic after Off.

## 11. Remaining Phase 3 work

Host closeout complete for the requested suite and APKs. The device matrix above remains the release-qualification gate; no supported list for screen-off/Doze or human wake accuracy is asserted. Full optional native tuning/TTS tests beyond the eight requested scenarios were not rerun here.

## 12. Phase 4 Bluetooth integration instructions

1. Add a native Kotlin `AudioRouteController` implementation under `audio/`. Supply it at `JarvisService.audioRouteControllerFactory`; service creation passes the same instance to `VoiceInput` and sets `AudioOut.routeController`. `start(listener)` registers observers; `stop()` unregisters and tears down. Use `prepareInput`, `acceptInputDevice`, `prepareOutput`, and `releaseSession`; extend lifecycle reporting where required rather than introducing a second recorder/owner.
2. Keep built-in phone-mic wake standby by default. An explicit or system headset gesture may negotiate a command route before a ready cue. A keyword detected on the phone mic must keep the same input through pre-roll and command. On API 31+, request `BLUETOOTH_CONNECT` where required and `MODIFY_AUDIO_SETTINGS`; do not request discovery permissions merely to select a route. Missing permission or A2DP-only output means handset input.
3. Adapt Classic SCO concepts from [Yunto's pinned native module](https://github.com/lovisschmidt/yunto/blob/8bb110a6a11738601ae639dd4bd32eb446326f25/modules/headphone-button/android/src/main/java/expo/modules/headphonebutton/HeadphoneButtonModule.kt), with MIT attribution if adapting code. On API 31+ select a compatible communication sink via `availableCommunicationDevices` → `setCommunicationDevice`: prefer exposed `TYPE_BLE_HEADSET` when suitable, otherwise `TYPE_BLUETOOTH_SCO`. Observe communication callbacks and the running `AudioRecord.routedDevice` before reporting readiness. On 26–30, check off-call SCO support and register for `ACTION_SCO_AUDIO_STATE_UPDATED` before `startBluetoothSco`; await connected with a bounded deadline and pair every request with `stopBluetoothSco`.
4. Serialize route ownership by turn. Current `VoiceInput.micBlocked` treats all `MODE_IN_COMMUNICATION` as an interruption: distinguish a route owned by Ash from phone/VoIP preemption before enabling SCO; never set `MODE_IN_CALL`. `prepareInput` runs only when a recorder opens; a headset-initiated switch needs explicit stop/release acknowledgement before opening the new route. Reject late callbacks and partial commands, distinguish SCO-only drop from headset loss, and return to a verified phone route. Call `releaseSession()` on all turn exits; currently the service's explicit call is on destroy, not a turn-finalization guarantee.
5. Release turn-only SCO before hi-fi reply playback, observe route transition, and apply/verify preferred output through `AudioOut.routeController`; cover platform TTS too since it does not use Ash's `AudioTrack` directly. Stop private playback if headset output drops. Keep existing per-utterance focus ownership. Prefer the system assistant gesture, retain normal player button ownership, and do not copy Yunto's always-active `MediaSession`, idle focus or any Expo/React Native layer.
6. Qualify actual Classic and LE Audio pairs (including legacy SCO), A2DP-only fallback, denial, disconnect, call preemption, music coexistence and battery. Isolate handset and earbud acoustically to confirm which mic supplied the command. Do not offer earbud standby until separately measured.
