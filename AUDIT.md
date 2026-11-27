# Sonora — Independent Technical Debt & Anti-Slop Audit

**Audit mode:** Strictly read-only with respect to application source code. No Kotlin, Rust, C++, Gradle or Android XML source files were modified during this audit.  
**Core principle:** *Preserve every feature. Reduce complexity. Prove every improvement.*

---

## Executive Summary

Sonora inherits a capable but monolithic Android/KMP codebase from BitChord, and recent rapid iteration has introduced a second layer of **Sonora-specific technical debt** — most notably an unbenchmarked Rust JNI layer using raw hand-indexed JNI vtable offsets, 31+ dead Liquid Glass / `backdrop` files left in the APK after the UI toggle was removed, and English strings copied verbatim into 19 non-English locale files to satisfy unit tests.

Further Rust migration and speculative micro-optimisations should remain **suspended** until the findings below are resolved and validated on a minified `prodRelease` build.

---

## Part 1 — Newly Introduced Sonora Technical Debt

### S-1. Unsafe Raw JNI Vtable Indexing & Missing Panic Guard
- **Files:** `native/sonora-android/src/lib.rs:14-16, 40-44, 79-83, 106`
- **Finding:** `sonora-android` invokes JNI functions (`GetArrayLength`, `GetPrimitiveArrayCritical`, `ReleasePrimitiveArrayCritical`) by manually indexing raw `*const *const c_void` vtable slots (`171`, `222`, `223`) via `std::mem::transmute`. In addition, while `Cargo.toml` sets `panic = "abort"`, calling `GetPrimitiveArrayCritical` holds a GC critical section across the entire 8192-point FFT (`~100K+` floating-point ops in `compute_chroma_frame`), which can stall ART's garbage collector on other threads (including audio/UI threads!).
- **Severity:** **High** | **Regression Risk:** **Medium** | **Complexity:** Low–Medium
- **Recommended Solution:** Replace `GetPrimitiveArrayCritical` with `GetDoubleArrayRegion` / `GetIntArrayElements` (or a direct `ByteBuffer`) so ART GC is never blocked during FFT execution, and use the `jni` crate (or a minimal C header wrapper via `cc`) instead of raw vtable slot offsets.

### S-2. Unvalidated Performance Claims & Debug-Only Benchmarks
- **Files:** `PERFORMANCE.md`, `RUST_MIGRATION.md`, `shared/src/commonMain/kotlin/com/music/bitchord/playback/chords/ChordAnalysis.kt:91-97`, `sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/components/BlurredBackdrop.android.kt:53-58`
- **Finding:** All on-device benchmarks were collected on `prodDebug` (unminified, no R8, no baseline profiles, device at `42–44°C`). Steady-state scroll jank actually moved from `14.68%` (`p90: 38ms`) to `16.04%` (`p90: 40ms`) between runs — within thermal/debug noise, not a proven improvement. Neither `box_blur_argb` nor `compute_chroma_frame` has a controlled A/B wall-clock or energy benchmark comparing Kotlin vs. Rust on identical `prodRelease` workloads.
- **Severity:** **High** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Build a minified `prodRelease` (or `benchmark`) APK, cool the test device below `35°C` unplugged, and run controlled A/B timing of: (a) 1,000 iterations of `BlurredBackdrop` / `ArtworkMeshBackdrop` blur, (b) full-song `ChordAnalyzer` analysis (Kotlin vs. Rust), and (c) Macrobenchmark/Perfetto scroll traces. Delete any Rust path that does not show a reproducible win.

### S-3. Prebuilt Native Binary Committed Without Gradle Build Automation or Multi-ABI Coverage
- **Files:** `app/src/main/jniLibs/arm64-v8a/libsonora_core.so` (`510 KB`), `native/Cargo.toml`
- **Finding:** `libsonora_core.so` is manually cross-compiled and copied into `app/src/main/jniLibs/arm64-v8a/` only. There is no Gradle task rebuilding it when `native/sonora-core` changes, `armeabi-v7a` and `x86_64` have no `.so` (silently falling back to Kotlin), and `510 KB` is large for ~200 lines of math because `std` formatting/panicking machinery is linked in.
- **Severity:** **Medium** | **Regression Risk:** **Low** | **Complexity:** Medium
- **Recommended Solution:** Either automate Rust compilation via Gradle for all 3 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) and shrink binary size (or move `box_blur` / `fft` into the existing CMake/NDK C++ library in `app/src/main/cpp/CMakeLists.txt` if Rust does not justify a second native toolchain).

### S-4. Incomplete Liquid Glass Removal Leaving 31+ Dead Files & Unreachable State
- **Files:** `app/src/main/java/com/music/bitchord/MainActivity.kt:465-476`, `app/src/main/java/com/music/bitchord/ui/components/LiquidGlass.kt` (272 lines), `app/src/main/java/com/music/bitchord/ui/components/GlassNavBar.kt`, `app/src/main/java/com/music/bitchord/ui/components/floatingtabbar/GlassSelectionPill.kt`, `app/src/main/java/com/music/bitchord/ui/components/backdrop/*` (**31 files**), `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt:543-558, 1138, 1665-1666`, `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:256-259`
- **Finding:** `MainActivity.kt:474` hardcodes `LocalLiquidGlassEnabled provides false`, yet `MainActivity.kt:471` still allocates `rememberLayerBackdrop(onDraw = paintBackdrop)` and provides `LocalAppBackdrop`, `SettingsSheet.kt:256` still collects `AppSettings.liquidGlass`, and all 31 vendored `backdrop/` files + `LiquidGlass.kt` remain compiled into the app as unreachable dead code.
- **Severity:** **Medium** | **Regression Risk:** **Low** | **Complexity:** Medium
- **Recommended Solution:** After confirming Liquid Glass is permanently retired, delete `ui/components/backdrop/`, `LiquidGlass.kt`, `GlassNavBar.kt`, `GlassSelectionPill.kt`, and remove the dead `rememberLayerBackdrop` allocation in `MainActivity.kt` (keeping `AppSettings` preference key migration-safe so old prefs never crash).

### S-5. Duplicate Refresh Rate Selector in Settings UI
- **Files:** `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt:1184-1208` (Appearance) and `SettingsSheet.kt:1498-1518` (Performance)
- **Finding:** Adding the refresh-rate `SegmentedControl` to Appearance left the original refresh-rate `SegmentedControl` inside Performance (`if (highPerformanceMode)`), creating two separate controls that mutate the same `AppSettings.performanceRefreshRate` state. Furthermore, selecting a rate in Appearance implicitly flips `AppSettings.setHighPerformanceMode(true)` without showing the `showPerformanceWarning` dialog that Performance shows.
- **Severity:** **Medium** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Keep a single canonical Refresh Rate selector in Appearance and remove the duplicate block from Performance (or reconcile `highPerformanceMode` semantics cleanly).

### S-6. Deferred Startup Race Conditions in `BitChordApplication.onCreate`
- **Files:** `app/src/main/java/com/music/bitchord/BitChordApplication.kt:173-189`
- **Finding:** Deferring `PlaylistSync.init`, `DriveBackup.init`, `CanvasCache.init`, `ListeningStats.init`, `ArtistFacts.init`, and `initLastfm()` asynchronously off `onCreate` creates a startup race window: if `PlaybackService` starts immediately from a media button / widget / deep link before `Dispatchers.IO` completes `CanvasCache.init` or `ListeningStats.init`, callers can observe uninitialized state.
- **Severity:** **Medium** | **Regression Risk:** **Medium** | **Complexity:** Low
- **Recommended Solution:** Ensure every deferred singleton (`CanvasCache`, `ListeningStats`, `ArtistFacts`, `LastFM`) either lazily initializes on first access or guards against uninitialized reads with a lightweight `CompletableDeferred` / `AtomicBoolean`.

### S-7. Un-translated English Strings Backfilled into 19 Non-English Locales
- **Files:** `app/src/main/res/values-*/strings.xml` (19 locale files)
- **Finding:** ~44 missing Replay/AutoMix strings and 5 `<plurals>` blocks were copied in English into all 19 non-English `strings.xml` files to make `LocaleStringsTest` pass. While this fixes the build test, it masks untranslated strings from translators.
- **Severity:** **Low** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Track these keys for proper localization and ensure Android's native `values/strings.xml` fallback is used or translations are contributed per locale.

### S-8. Branding Cleanup & Intentionally Retained Compatibility Identifiers
- **Updated to Sonora:**
  - **Android & TV app:** Update notification strings (`update_available_body`, `update_downloading_body`, `update_ready_body`, `update_failed_body`) across all 20 locales; Discord Rich Presence default button (`Visit Sonora`) and `PROJECT_URL` (`https://github.com/gamadev09/Sonora`); user-visible backup import error messages in `Backup.kt`; `LastFM` and Coil `User-Agent` headers; DLNA stream headers (`Server: Sonora-DLNA/1.0`, `ICY-NAME: Sonora Stream`); `AppUpdateChecker` release API (`https://api.github.com/repos/gamadev09/Sonora/releases/latest`), cached APK filename (`sonora-${info.version}.apk`), and TV update prefix (`SonoraTV-`, matching phone `Sonora-`).
  - **Web & Browser Extension:** Extension manifest name/description, popup/connect/grant pages, `SpotifyPage` / `SpotifyPlaylistPage` user-facing copy, web Discord RPC (`APP_NAME = 'Sonora'`, `Visit Sonora`, `https://github.com/gamadev09/Sonora`), `androidAppUrl` pointing to `https://github.com/gamadev09/Sonora`, and packaged extension archives/folders (`sonora-extension-chrome` / `sonora-extension-firefox`).
  - **Listen Together & Wear OS:** Invite landing page title, subtitle, join button, download link (`https://github.com/gamadev09/Sonora`), startup log in `backend/main.go`, systemd unit description, `backend/README.md`, `wearos/README.md`, and Wireless ADB companion `getDeviceName()` (`"Sonora"`).
- **Intentionally Retained for Compatibility & Upstream Attribution:**
  - Application IDs and Kotlin/Java namespaces (`com.music.bitchord*`, `com.dev.bitchord*`, `com.gamadev09.sonora`), `@bitchord/*` workspace package names, and Go module path.
  - Deep-link schemes and Wearable Data Layer paths (`bitchord://`, `bitchord-wear://`, `/bitchord/*`, `bitchord_watch` capability, `wear/BitChordWear.apk` asset names, and ADB pairing certificate `CN=BitChord`).
  - Backup schema tag (`APP_TAG = "bitchord"`) and backup filename (`bitchord-backup-YYYY-MM-DD.json`) so existing backups import cleanly.
  - SharedPreferences, DataStore, database, widget (`widget_open_bitchord`), IndexedDB (`bitchord-automix`), and web `localStorage` (`bitchord:*`) keys.
  - Listen Together WebSocket subprotocols (`bitchord.v1`, `bitchord.token.*`), service health identifier (`bitchord-listen-together`), addon auth headers/proof identifiers, Discord `APPLICATION_ID`, Firefox add-on ID (`extension@bitchord.kushagrasingh.in`), and existing deployed server domains/service users/origin allowlists.
  - Upstream BitChord attribution in `README.md` and copyright/license headers across source files.
- **Migration Work Required Before Replacing Retained Identifiers:**
  - **Application IDs & Kotlin Namespace (`com.music.bitchord*`, `com.dev.bitchord*`):** Requires coordinated upgrade/migration planning across existing installed TV/Wear apps, Wearable Data Layer companion pairing (which requires matching package IDs and signing certificates), AndroidManifest authorities (`FileProvider`, receivers, services), build flavors, JNI symbol exports (`Java_com_music_bitchord_*`), and ProGuard/R8 rules.
  - **Deep-Link & Wearable Schemes (`bitchord://`, `bitchord-wear://`):** Introduce `sonora://` / `sonora-wear://` alongside the legacy schemes and continue accepting both during a compatibility transition period so shared links and watch handoffs keep working.
  - **Backups & Stored Data (`APP_TAG`, `bitchord-backup-*.json`, SharedPreferences, DataStore, IndexedDB, `localStorage`):** Write new Sonora identifiers only once a dual-read migration layer is in place that transparently reads/migrates existing `bitchord` preferences, caches, widget snapshots, and backup files without data loss.
  - **Protocols & Integrations (`bitchord.v1`, `bitchord.token.*`, addon auth headers/proofs, Wear paths):** Coordinate app, Go backend, web client, and compatible addons using version negotiation or dual-protocol support rather than an abrupt rename.
  - **Hosting & Infrastructure (`bitchord.in`, `api.bitchord.*`, addon auth service, systemd units):** Stand up and verify replacement Sonora domains, TLS, CORS/origin allowlists, and Linux service units before switching default client endpoints.
- **Remaining Manual / Release Actions:**
  - **Release Updater:** `gamadev09/Sonora` currently has no published GitHub Releases, so `AppUpdateChecker` will safely remain idle until a release with `Sonora-*.apk` and `SonoraTV-*.apk` assets is published (it never falls back to upstream BitChord releases).
  - **Android TV Banner Artwork:** Raster TV banners (`tv/src/main/res/drawable*/tv_banner.png`) still require visual verification and should only be replaced once finalized Sonora TV banner artwork is ready.

---

## Part 2 — Inherited BitChord Technical Debt

### B-1. God-Class Monoliths Driving High Compile Times & Recomposition Blast Radius
- **Files:**
  - `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt` (**11,114 lines**)
  - `app/src/main/java/com/music/bitchord/MainActivity.kt` (**6,670 lines**)
  - `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt` (**4,852 lines**)
  - `app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt` (**4,439 lines**)
  - `app/src/main/java/com/music/bitchord/ui/MainViewModel.kt` (**3,825 lines**)
  - `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt` (**3,628 lines**)
  - `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt` (**3,261 lines**)
  - `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt` (**2,562 lines**)
- **Finding:** Core application logic and UI are concentrated in a handful of 3,000–11,000-line files. In Compose (`MainActivity.kt`, `NowPlayingScreen.kt`, `SettingsSheet.kt`), dozens of `collectAsStateWithLifecycle()` calls sit at top-level composables, widening recomposition scopes and making incremental Kotlin compilation slow (~1–3 min per edit).
- **Severity:** **High** | **Regression Risk:** **High (if refactored blindly)** | **Complexity:** High
- **Recommended Solution:** Do **not** rewrite wholesale. Extract cohesive, stateless sub-composables and narrow state-reading scopes only when profiling (`Compose Compiler Metrics` / `Perfetto`) proves a hot recomposition path.

### B-2. Fragile Cross-Module Source Sharing (`tv/` Compiling `app/src/main/java`)
- **Files:** `tv/build.gradle.kts:135-145, 518-532`
- **Finding:** `:tv` points `java.srcDir("../app/src/main/java")` directly at `:app`'s source tree and uses a custom `KotlinCompile` exclusion filter to shadow colliding classes. Whenever `:app` adds a dependency (like `jmdns` or `bouncycastle`), `:tv` breaks at compile time unless manually mirrored.
- **Severity:** **Medium** | **Regression Risk:** **Medium** | **Complexity:** High
- **Recommended Solution:** Gradually move phone/TV-shared playback & repository code into `:shared` rather than sharing raw source directories.

### B-3. Dual-Cache Duplication & Unbounded Background Save Timer
- **Files:** `app/src/main/java/com/music/bitchord/data/stats/ArtistFacts.kt:114-119`, `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt:580-582, 651-653`
- **Finding:** `ArtistFacts.init` spawns an infinite `while (true) { delay(SAVE_INTERVAL_MS); save() }` loop for the lifetime of the process even when nothing is dirty. `ArtworkMeshBackdrop` maintains two separate `LinkedHashMap` caches (`fullBlurCache`, `meshCache`) alongside Coil's own `MemoryCache`.
- **Severity:** **Low** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Trigger `ArtistFacts` persistence via a debounced `Channel`/`MutableSharedFlow` on actual writes instead of a permanent polling loop.

### B-4. Obsolete Root Fork/Patch Development Artefacts
- **Files:** `APPLY_TO_FORK.patch`, `FORK_INSTRUCTIONS.md`, `BUILD_CHORDS_MVP.md`, `START-HERE-WINDOWS.txt`, `FILE_GUIDE.md`, `build-chords-mvp.sh`, `upi_support.jpg`, `Banner.png`, `Logo.png`
- **Finding:** One-off prototype/fork handoff files and upstream donation QR code (`upi_support.jpg`) sit in the repository root.
- **Severity:** **Low** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Archive or remove obsolete handoff scripts/patches and `upi_support.jpg` after confirming nothing in CI or Gradle references them.

### B-5. Configuration-Time Dependency Resolution & AGP/SDK Mismatch Warning
- **Files:** `app/build.gradle.kts`, `tv/build.gradle.kts`, `gradle.properties`
- **Finding:** `Configuration 'newPipeExtractorRaw' was resolved during configuration time` runs on every Gradle invocation, and AGP `8.10.1` warns on `compileSdk = 37.0`.
- **Severity:** **Low** | **Regression Risk:** **Low** | **Complexity:** Low
- **Recommended Solution:** Defer `newPipeExtractorRaw` resolution to task execution time and align AGP version with `compileSdk`.

---

## Proposed Execution Order (Post-Approval)

1. **Validate on `prodRelease` first (S-2):** Run controlled A/B benchmarks (Kotlin vs. Rust blur & chord FFT, plus `prodRelease` scroll `gfxinfo`/Perfetto) on a cooled device. Keep only what measurably wins.
2. **Fix JNI safety & GC blocking (S-1):** Replace `GetPrimitiveArrayCritical` during FFT with non-GC-blocking array access and remove raw vtable slot indexing.
3. **Eliminate dead Liquid Glass code & duplicate Refresh Rate UI (S-4, S-5):** Delete the 31+ unreachable `backdrop/` files, remove `rememberLayerBackdrop` from `MainActivity.kt`, and deduplicate the Refresh Rate selector in `SettingsSheet.kt`.
4. **Harden deferred startup singletons (S-6) & replace `ArtistFacts` timer (B-3):** Eliminate startup race windows and idle timer wakeups.
5. **Clean up obsolete root artefacts & multi-ABI packaging (S-3, B-4, S-8):** Remove `upi_support.jpg` / old fork patches and automate native builds or consolidate into CMake.
