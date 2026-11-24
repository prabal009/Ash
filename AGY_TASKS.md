# Project Ash — Task Checklist

## Phase 1: Repository Audit & Technical Research

- [x] Inspect workspace and initialize Project Ash repository from `judejulius/jarvis` preserving Git history and MIT license
- [x] Clone `lovisschmidt/yunto` separately into scratch reference directory without merging
- [x] Audit Jarvis source architecture, Gradle/SDK config, manifest, services, voice/ASR/TTS, AI routing, tools, AccessibilityService, tests, and limitations
- [x] Audit Yunto native Kotlin headphone-button module, MediaSession, Bluetooth SCO/audio focus, manifests, compatibility docs, and license
- [x] Research Android 12–17 official APIs/restrictions and Google Play policies
- [x] Write comprehensive technical audit and GPT Astra handoff report at `docs/ash/00-repo-audit.md`
- [x] Verify report completeness, referenced file paths, and clean Git status (documentation-only diff)

## Phase 3: Crash Recovery & Core Ash Implementation

- [x] Recover workspace, Git state, docs (`00-repo-audit.md`, `01-architecture.md`), JDK 21, Android SDK 35, Gradle cache, and `test-native/` assets after PC crash
- [x] Audit all 13 Phase 3 components (A–M) and classify verified vs. partial vs. untested work
- [x] Recover Phase 3 repairs: island branding, bare `"Ash"` guard, global navigation, lock-screen query policy, call-end recovery, app launch fallback, and assistant-intent navigation
- [x] Migrate both native test fixtures to Ash; pass the eight selected `-Preal` scenarios, including paused and one-breath handoff and a sound-alike matrix (Hey 7/12, Hello 12/12, non-prefix negatives 0/12)
- [x] Cover contacts/dial approval, media command grammar, PhoneAgent validation, provider cancellation/sanitization, and `AshRecognitionService` registration in host tests
- [x] Run `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `check-apk-abis.py`: 255 unit tests passed, 0 lint errors (33 warnings), four fresh verified APKs
- [x] Write `docs/ash/02-implementation-status.md` with recovery evidence and Phase 4 handoff
- [ ] Qualify screen-off/Doze, human voices, assistant gesture and recognition lifecycle, actual player transport, restricted settings, and battery on physical phones

## Phase 4: Bluetooth Earbud Integration

- [x] Inspect repository, docs (`00`, `01`, `02`), and Yunto native Kotlin headset source (`HeadphoneButtonModule.kt`, `HeadphoneButtonService.kt`)
- [x] Create dedicated feature branch `phase4-bluetooth-integration`
- [x] Implement native Kotlin `BluetoothAudioRouteController`, `AudioSystemGateway`, and opt-in `HeadsetButtonBridge` (with MIT attribution in `THIRD_PARTY_NOTICES.md`)
- [x] Wire Phase 4 permissions (`MODIFY_AUDIO_SETTINGS`, `BLUETOOTH` maxSdk 30, `BLUETOOTH_CONNECT`), `VoiceInput` Ash-owned communication mode check, `AudioOut` live preferred output updates, `JarvisService` turn-scoped negotiation/teardown/private-output-stop, and Settings UI switches
- [x] Run comprehensive Phase 4 Bluetooth & audio routing unit tests (all 14 scenarios), full `:app:testDebugUnitTest` (42 suites, 274 tests passed), `:app:lintDebug` (0 errors, 33 warnings), `:app:assembleDebug`, and `check-apk-abis.py`
- [x] Write `docs/ash/03-bluetooth-integration.md` with Gemini Hydrogen testing handoff
- [ ] Qualify Classic HFP/SCO, BLE Audio, A2DP-only, wired/USB, headset-button coexistence, incoming calls, disconnect/reconnect, and screen-off pocket battery on physical Android devices (Gemini Hydrogen handoff)

## Phase 5: Testing, Build Verification & Optimization

- [x] Verify repository state, `phase4-bluetooth-integration` (`92e984a`), and all prior reports (`00`–`03`)
- [x] Verify build, static analysis, and APKs (`:app:lintDebug` 0 errors/33 warnings, `:app:assembleDebug` 4 APKs, `check-apk-abis.py debug` PASS)
- [x] Run full host unit test suite (`:app:testDebugUnitTest --rerun-tasks`: 42 suites, 274 tests, 0 failures, 0 errors, 0 skipped)
- [x] Run native `sherpa-onnx` wake-word & barge-in tests (`-Preal` 11 `RealEngineTest` scenarios: 10 passed, 1 failed — `saysSorryWhenNothingFollowsTheWakeWord` / `HEY_ASH` voice 5 recall)
- [x] Audit security, permissions, logging, listener cleanup, and lifecycle/concurrency safety
- [x] Write `docs/ash/04-test-report.md` with ADB physical-device procedure and Gemini Argon handoff
- [ ] Execute physical-device ADB test matrix (no Android device or instrumentation suite attached during Phase 5)

## Phase 6: Independent Final Security and Reliability Audit

- [x] Review all prior reports (`00`–`04`) chronologically and inspect git state on `phase4-bluetooth-integration`
- [x] Independently audit wake-word detection, Android background/FGS/assistant behavior, Bluetooth/audio routing, phone automation safety, credential/network security, code reliability, performance/privacy, and Phase 5 test evidence
- [x] Document verified findings (`HIGH-01` through `LOW-02`), verdicts, release-readiness decision (`READY FOR CONTROLLED DEVICE TESTING`), and prioritized correction plan for GPT Sol / GPT Astra in `docs/ash/05-security-review.md`

## Phase 7: End-to-End Engineering, Direct Calling, Background Reliability & CPH2467 Validation

- [x] Inspect connected OnePlus CPH2467 (Android 15, API 35) via ADB, recover half-applied uncommitted edits, and verify `AshVoiceInteractionService` + `JarvisAccessibilityService` bindings
- [x] Implement opt-in Direct Calling Mode (`DirectCallPolicy`, `CALL_PHONE`, `TelecomManager.placeCall`, duplicate cooldown, `ACTION_DIAL` fallback) and extended phone control (`userControl`, `OpenWebsite`, `YouTubeSearch`, `ClearText`, `SubmitSearch`)
- [x] Fix Phase 6 audit defects (`HIGH-01` Bluetooth `host.ask()`/`TurnInputRoute`, `HIGH-02` API 26–30 SCO, `HIGH-03` `@HEY_ASH` `:2.9 #0.055` + punctuation wake-tail bug, `MED-01` BLE teardown, `MED-04` dead legacy removal, `LOW-01` `AudioPlaybackCallback`, `LOW-02` Termux `dataDir`)
- [x] Harden screen-off/background reliability (`AudioRecordingCallback` + `ACTION_MICROPHONE_MUTE_CHANGED` recovery, remove 30s armed-standby `stopSelf()`, `LockedCommands` + `PendingUnlock`, reduce `Kokoro.IDLE_UNLOAD_MS` to 2m for OnePlus `o-kill(40)` mitigation)
- [x] Verify full build (`40 suites, 268 unit tests passed`, `0 lint errors / 27 warnings`, `-Preal` `HEY_ASH=9/12`, `HELLO_ASH=12/12`, `FALSE=0/12`, `saysSorryWhenNothingFollowsTheWakeWord` PASS, 4 ABI-verified APKs) and install fresh `app-arm64-v8a-debug.apk` onto OnePlus CPH2467
- [x] Fix live OnePlus CPH2467 calling & full-screen touch/device-control blockers (`DirectCallPolicy` prefix tier, `FastCommand.Dial` + fuzzy-confirm direct calling via `TelecomManager.placeCall` + `ACTION_CALL` fallback, `ScreenReader` OxygenOS overlay/node resilience, `JarvisAccessibilityService` clickable-ancestor + `dispatchGesture` tap/swipe + relaxed non-secret Edit/Tap with `OneUseApproval` on sensitive controls + `WindowContext.sameWindow`, `PhoneAgent` 15-step completion & `isPhoneAction` routing); verified `40 suites, 270 unit tests passed`, `0 lint errors`, 4 ABI-verified APKs, and installed via `adb install -r` on CPH2467
- [x] Write `docs/ash/06-final-validation.md` and `docs/ash/07-background-reliability.md` with actual CPH2467 telemetry and manual test checklist
- [ ] Complete interactive voice, Bluetooth earbud, authorized live call, and 60-minute unplugged screen-off soak tests on OnePlus CPH2467

## Future Backlog

- [x] Restore Android SDK 35 / JDK 21 and verify `testDebugUnitTest` and `lintDebug` baseline
- [ ] Remove dead legacy subsystems (`agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, `memory/JarvisDatabaseHelper.kt`, `claude-delivery/`)
- [x] Build native Audio/Bluetooth coordinator (`setCommunicationDevice` SCO + BLE Audio, `AudioDeviceCallback`, per-turn `AudioFocusRequest`, `AudioRecord` routing) adapted cleanly from Yunto without Expo/RN
- [ ] Requalify default-assistant and music coexistence on physical hardware after Bluetooth route changes; no always-on `MediaSession`
- [ ] Revisit platform-level cleartext configuration separately from the provider-level HTTPS/local-HTTP enforcement already in Phase 3
