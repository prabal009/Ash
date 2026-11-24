# Third-Party Notices

Project Ash builds on and adapts concepts from the following open-source projects:

## Jarvis (`judejulius/jarvis`)
- **License:** MIT License
- **Copyright:** Copyright (c) 2026 Mohammad Joud Julius
- **Usage:** Primary native Kotlin Android foundation (see repository `LICENSE`).

## Yunto (`lovisschmidt/yunto`)
- **License:** MIT License
- **Copyright:** Copyright (c) 2026 Lovis Schmidt
- **Usage:** Native Kotlin Bluetooth HFP/SCO routing state-machine concepts, `AudioDeviceCallback` SCO-vs-output disconnect classification, and debounced headset-button handling concepts adapted in `app/src/main/java/com/jarvis/assistant/audio/BluetoothAudioRouteController.kt` and `app/src/main/java/com/jarvis/assistant/audio/HeadsetButtonBridge.kt` (rewritten natively without React Native or Expo).
