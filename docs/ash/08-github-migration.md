# Project Ash — GitHub Migration, UI Polish & Premium README Report

**Report ID:** `08-github-migration`  
**Date:** 2026-11-24  
**Scope:** Safe migration to `https://github.com/prabal009/Ash.git`, security & secret audit, native Android UI polish, and original 30 FPS README motion graphics & documentation redesign.

---

## 1. Original Project Location & Filesystem Safety

- **Canonical Local Project Root:** `/run/media/prabal/SANDISK E/Ash`
- **Filesystem:** Removable SanDisk drive mounted as `vfat` (FAT32). No reformatting, destructive repairs, or symlink-dependent operations were performed; the original repository and Git history remain intact in place.
- **Gradle Invocation Note:** Because FAT32 does not preserve arbitrary POSIX execute bits, Gradle wrapper commands were executed via `bash ./gradlew`.

---

## 2. Destination GitHub Repository

- **Destination URL:** `https://github.com/prabal009/Ash.git` (`https://github.com/prabal009/Ash`)
- **Owner / Active Authenticated Account:** `prabal009` (verified via `gh auth status`, HTTPS protocol)
- **Pre-Push Remote State:** Verified empty (`isEmpty: true`, `defaultBranchRef: ""`, `git ls-remote` returned empty output).
- **Upstream Preservation:** Original upstream remote `origin` (`https://github.com/judejulius/jarvis.git`) was preserved untouched; destination was configured as remote `ash` (`https://github.com/prabal009/Ash.git`) and pushed without force push to `main`.

---

## 3. Original Git Status & History

Before this migration phase:

- **Active Branch:** `phase4-bluetooth-integration` at `8889919` (`Fix wake-word consistency, Groq English fast replies, and direct calling UI`)
- **Local `master`:** `73671c5` (`Complete Ash Phase 3 core assistant and recovery`)
- **Upstream `origin/master`:** `5dd9934` (`Translate the app into Hindi`)
- **Working Tree:** Clean prior to migration edits.
- **Full Development History Preserved:** All upstream Jarvis commits plus Ash Phase 3 (`73671c5`), Phase 4 (`92e984a`), Phase 5 (`e0ea3e0`), Phase 7 (`db4a306`), calling/accessibility fixes (`28f3b5f`), and wake-word/Groq fixes (`8889919`) were preserved intact without rewriting history.

---

## 4. Security & Secret Scanning Results

| Check | Scope | Result |
|---|---|---|
| Live API key / token scan (`gsk_`, `AIza`, `sk-`, `ghp_`, `github_pat_`, PEM private keys) | Working tree & tracked files | **0 exposed secrets** |
| Reachable Git history secret scan (`git log --all -p -S ...`) | Entire reachable commit graph | **0 exposed secrets** |
| Large binary / blob scan (`> 5 MB`) | Entire reachable commit graph | **0 blobs > 5 MB** (largest inherited blob: `1.53 MB`) |
| Sensitive artifact check (`*.apk`, `*.keystore`, `*.jks`, `local.properties`, `*.env`) | Tracked Git index (`git ls-files`) | **0 matches** |
| Personal device identifier hygiene | `docs/ash/06-final-validation.md`, `docs/ash/07-background-reliability.md` | Redacted OnePlus USB serial to `<redacted>` prior to push |

---

## 5. Files Intentionally Excluded (`.gitignore`)

Updated `.gitignore` removes erroneous `.github` / `.git` entries (ensuring CI workflows and templates stay tracked) and explicitly excludes:

- Build & intermediate outputs: `build/`, `.gradle/`, `.kotlin/`, `*.apk`, `*.aab`
- Local environment & IDE files: `local.properties`, `.env`, `.env.*`, `*.env`, `.idea/`, `*.iml`, `.DS_Store`, `captures/`, `*.log`, `*.bak`
- Signing keys & certificates: `*.jks`, `*.keystore`, `*.pem`, `*.p12`, `*.key`
- Large downloaded / locally compiled binaries: `app/libs/*.aar`, `app/libs/*.part`, `/test-native/*` (except `test-native/README.md`), `/app/src/main/jniLibs/*/libllama_server.so`

---

## 6. Actual Android Application UI Changes

Preserved Ash's existing Material 3 XML + ViewBinding architecture and all functional behavior while elevating visual coherence and state clarity:

- **Obsidian + Electric Cyan Palette (`app/src/main/res/values/colors.xml`, `themes.xml`):** Refined dark foundation (`#090C11` canvas, `#11161F` cards, `#192230` elevated surfaces, `#243043` `1dp` card/input hairline strokes, `#38BDF8` electric-cyan primary accent, `#F1F5F9` primary text, `#94A3B8` secondary text, `#34D399` / `#F59E0B` / `#F87171` semantic indicators).
- **Refined Controls (`bg_command.xml`, `bg_pill.xml`, `fragment_home.xml`, `ic_launcher_*.xml`):** Added hairline outlines to the command bar and active-model pill, and updated the adaptive launcher icon to Ash's obsidian concentric electric-cyan acoustic rings.
- **6-State Acoustic Orb (`ArcReactorView.kt`, `HomeFragment.kt`, `JarvisService.kt`):** Added `SPEAKING` (cyan-indigo harmonic pulse) and `ERROR` (coral ring) states alongside `OFF`, `IDLE`, `LISTENING`, and `WORKING`; exposed read-only `JarvisService.speakingNow` and `JarvisService.micInterrupted` flags; added reduced-motion support (`ValueAnimator.areAnimatorsEnabled()`) so animation respects system accessibility settings and avoids background rendering overhead.
- **Dynamic Task Island (`IslandViews.kt`, `TaskIsland.kt`):** Harmonized the speaking sweep halo gradient (`#38BDF8 → #E0F2FE → #818CF8`) and live audio bars (`#7DD3FC`) with Ash's visual system.

---

## 7. README Design Summary & Reference Research

Designed a cohesive, technically accurate editorial `README.md` informed by design principles studied across:

- `matiassingers/awesome-readme` — disciplined heading hierarchy, clear scannability, visual rhythm
- `ollama/ollama` — minimalist engineering branding, crisp quickstart & build-first structure
- `open-webui/open-webui` — product-focused screenshots, clean feature grouping, setup clarity
- `DenverCoder1/readme-typing-svg` & `kyechan99/capsule-render` — header geometry and motion pacing (implemented natively as local version-controlled 30 FPS GIFs rather than external third-party runtime dependencies)
- GitHub non-code file rendering rules — pure static SVG without `<script>` or CSS animation dependencies, optimized GIF89a assets well below GitHub's 10 MB limit, and local PNG fallbacks for every visual asset.

All feature claims in `README.md` strictly separate **implemented & verified**, **implemented & unit-tested (physical hardware pending)**, **experimental/platform-constrained**, and **planned** capabilities.

---

## 8. Generated Visual & Animation Asset Specifications

All motion graphics and architecture diagrams are reproducibly generated from source by [`scripts/generate-readme-assets.py`](../../scripts/generate-readme-assets.py) using `pycairo`, `Pillow`, `numpy`, and ImageMagick (`convert -layers OptimizeTransparency`), using GIF89a `[30, 30, 40]` ms frame cadence for an **exact 30.00 FPS source encoding rate** (*note: displayed browser/GitHub playback rate is controlled by the viewing client*).

| Asset Path | Type | Dimensions | Frames | Duration | Encoded Timing / Source FPS | File Size |
|---|---|---:|---:|---:|---|---:|
| `assets/readme/ash-hero.gif` | Animated GIF (seamless loop) | `1000 × 320` | 90 | 3.00 s | 33.33 ms avg (`30/30/40` ms) · **30.00 FPS** | **1,154.7 KB** |
| `assets/readme/ash-hero-static.png` | Static PNG fallback | `1000 × 320` | 1 | — | — | 127.1 KB |
| `assets/readme/ash-voice-flow.gif` | Animated GIF (illustrative flow, seamless loop) | `1000 × 310` | 150 | 5.00 s | 33.33 ms avg (`30/30/40` ms) · **30.00 FPS** | **298.1 KB** |
| `assets/readme/ash-voice-flow-static.png` | Static PNG fallback | `1000 × 310` | 1 | — | — | 86.2 KB |
| `assets/readme/ash-architecture.svg` | Static GitHub-safe SVG (no JS/CSS anim) | `1000 × 460` | 1 | — | — | 8.9 KB |
| `assets/readme/ash-architecture.png` | Static PNG fallback | `1000 × 460` | 1 | — | — | 204.3 KB |
| `assets/readme/ash-icon.png` | Rendered adaptive app icon | `432 × 432` | 1 | — | — | 32 KB |
| `assets/readme/screenshots/*.png` (7 files) | Authentic `ScreenshotTest` UI renders | `1233 × 2673` / island crops | — | — | Live Ash Android layouts, zero user data | ~1.2 MB total |

---

## 9. Build, Test & Verification Results

Executed after all UI polish and repository hygiene changes (JDK 21, Android SDK 35):

- **Command:** `JAVA_HOME=/home/prabal/.jdk/current ANDROID_HOME=/home/prabal/Android/Sdk bash ./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug && python3 scripts/check-apk-abis.py debug`
- **Result:** **EXIT 0 (`BUILD SUCCESSFUL in 2m 14s`)**
- **Host Unit Tests:** **40 suites, 271 tests passed, 0 failures, 0 errors, 0 skipped** (includes `LayoutSafetyTest`, `AppFlowTest`, `TaskIslandTest`, `ScreenshotTest`, `BluetoothAudioRouteTest`, `HeadsetButtonBridgeTest`, `AshPhase3Test`, `EndToEndPhase7Test`)
- **Android Lint (`:app:lintDebug`):** **0 errors, 27 pre-existing warnings**
- **Debug APK ABI Verification (`check-apk-abis.py debug`):** PASS across all 4 outputs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`)
- **README Asset Verification:** Every relative link and image path validated on disk; both animated GIFs decoded cleanly across all frames; SVG validated as clean XML with zero scripts or CSS animations; all generated images and updated UI screenshots visually inspected.

---

## 10. Licensing & Attribution

- **Project License:** MIT License ([`LICENSE`](../../LICENSE)), retaining `Copyright (c) 2026 Mohammad Joud Julius`.
- **Third-Party Notices:** [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md) preserves explicit MIT attribution for `judejulius/jarvis` and `lovisschmidt/yunto`.
- **Runtime & Model Licensing:** `sherpa-onnx`, `Kokoro-82M`, `OkHttp`, `Apache Commons Compress`, and `Material Components` (Apache-2.0), plus `Anthropic Java SDK` (MIT), are documented in `README.md`.

---

## 11. GitHub Push & Remote Verification

- **Destination Remote Configured:** `ash` → `https://github.com/prabal009/Ash.git` (`origin` preserved as `https://github.com/judejulius/jarvis.git`)
- **Target Branch:** `main` (normal non-force push from `phase4-bluetooth-integration`)
- **Pre-Push Remote Verification:** `git ls-remote ash` confirmed `https://github.com/prabal009/Ash.git` is empty (`isEmpty: true`).
- **Push Attempt & Exact Blocker:**
  - Command: `git push -u ash HEAD:main`
  - Result: **BLOCKED (HTTP 403)** — `remote: Permission to prabal009/Ash.git denied to prabal009.`
  - Root Cause: Active `gh` credential (`prabal009`, configured via `credential.https://github.com.helper = !/usr/bin/gh auth git-credential`) uses a fine-grained Personal Access Token (`github_pat_...`) that lacks write access (`Contents: Read and write` + `Workflows: Read and write`, or repository inclusion for `prabal009/Ash`).
- **Local Commits Ready to Push (Clean Working Tree):**
  - `a3471cb` — `Polish Ash obsidian-cyan UI theme, acoustic orb states, and repo hygiene`
  - `cdaf26e` — `Redesign README with 30 FPS motion graphics, architecture diagram, and Ash screenshots`
  - *(plus this migration report update commit)*
- **Resolution Steps (Official GitHub Auth):**
  1. Either update the `prabal009` fine-grained PAT in GitHub Settings → Developer settings → Personal access tokens to grant `prabal009/Ash` **Contents: Read and write** and **Workflows: Read and write**, **or** re-authenticate via OAuth with `gh auth login -h github.com -p https -w` (requesting `repo` + `workflow` scopes as `prabal009`).
  2. Run `git push -u ash HEAD:main` from `/run/media/prabal/SANDISK E/Ash`.

---

## 12. Remaining Limitations & Open Device Work

- Physical Bluetooth earbud qualification matrix (HFP/SCO, BLE Audio, A2DP-only, wired/USB, incoming calls, pocket disconnect/reconnect) remains pending physical hardware testing.
- Multi-hour (60+ minute) unplugged screen-off & Doze battery soak on OnePlus CPH2467 and additional OEMs remains to be executed.
- Debug APKs are intentionally excluded from Git and can be built locally or downloaded from GitHub Actions CI artifacts (`ash-debug-apk`).
