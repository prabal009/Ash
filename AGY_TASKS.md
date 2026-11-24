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

## Phase 4+: Bluetooth & Future Backlog

- [x] Restore Android SDK 35 / JDK 21 and verify `testDebugUnitTest` and `lintDebug` baseline
- [ ] Remove dead legacy subsystems (`agent/FastCommandRouter.kt`, `agent/LocalServerManager.kt`, `memory/MemoryManager.kt`, `memory/JarvisDatabaseHelper.kt`, `claude-delivery/`)
- [ ] Build native Audio/Bluetooth coordinator (`setCommunicationDevice` SCO + BLE Audio, `AudioDeviceCallback`, per-turn `AudioFocusRequest`, `AudioRecord` routing) adapted cleanly from Yunto without Expo/RN
- [ ] Requalify default-assistant and music coexistence after Bluetooth route changes; no always-on `MediaSession`
- [ ] Revisit platform-level cleartext configuration separately from the provider-level HTTPS/local-HTTP enforcement already in Phase 3
