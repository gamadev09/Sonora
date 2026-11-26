# Sonora — Rust Core Migration & Performance Optimisation

**Principle:** *Kotlin for the experience. Rust for the computation.*  
**License:** GPL-3.0-or-later (zero external Rust crate dependencies — 100% standard library `std` only, fully GPL-compatible).

---

## 1. Architecture Overview

The Rust computational core is organized as a Cargo workspace under `native/`, strictly separated into a platform-independent core crate and a thin Android JNI bridge:

```text
native/
├── Cargo.toml                 # Workspace root (opt-level=3, LTO, strip=symbols, panic=abort)
├── sonora-core/               # Portable pure-Rust core (zero Android/platform deps)
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs
│       ├── dsp/mod.rs         # Separable multi-pass ARGB box blur
│       └── chords/mod.rs      # 8192-pt radix-2 FFT + Hann window + 12-bin chroma extraction
└── sonora-android/            # Thin Android JNI bridge -> libsonora_core.so
    ├── Cargo.toml
    └── src/lib.rs             # Zero-copy GetPrimitiveArrayCritical JNI exports
```

### Responsibilities
- **Kotlin (`app`, `shared`, `sharedUi`):** UI, Compose animations, Media3 playback, services, casting (AirPlay/DLNA/Chromecast), Listen Together, settings, and portable fallback implementations.
- **Rust (`sonora-core`):** Pure numerical/DSP kernels reusable across Android, Windows, macOS, and Linux.
- **Android JNI (`sonora-android`):** Thin `extern "C"` entry points compiled into `libsonora_core.so` (`510 KB` on `arm64-v8a`), using `GetPrimitiveArrayCritical` / `ReleasePrimitiveArrayCritical` to operate directly on JVM primitive arrays without intermediate heap copies.

---

## 2. Migration Progress & Justification

| Phase | Component | Status | Justification & Parity |
| --- | --- | --- | --- |
| **Phase A** | Rust Workspace + NDK `arm64-v8a` Packaging | **Completed** | Established `native/Cargo.toml`, `sonora-core`, and `sonora-android` → `app/src/main/jniLibs/arm64-v8a/libsonora_core.so` (`510 KB`). Zero external crates. |
| **Phase B** | Backdrop & Canvas Mesh Blur (`dsp::box_blur_argb`) | **Completed** | Replaces 6-pass Kotlin CPU box blur in [BlurredBackdrop.android.kt](file:///C:/Users/gamve/Projects/BitChord/sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/components/BlurredBackdrop.android.kt) and [ArtworkMeshBackdrop.kt](file:///C:/Users/gamve/Projects/BitChord/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt) (Home cards, Now Playing `128×128` backdrop, and `32×32` Canvas mesh). Live frosted bars (`Haze`) remain on GPU `RenderEffect`. |
| **Phase C** | Offline Chord FFT & Chroma Extraction (`chords::compute_chroma_frame`) | **Completed** | Replaces the 8192-point radix-2 double-precision FFT + Hann window + 12-bin pitch-class projection in [ChordAnalysis.kt](file:///C:/Users/gamve/Projects/BitChord/shared/src/commonMain/kotlin/com/music/bitchord/playback/chords/ChordAnalysis.kt) (~102M FMA ops per 4-minute song). Preserves exact algorithm, chord labels, guitar diagrams, capo, transposition, simplification, and Kotlin fallback. |
| **Phase D** | Smart Audio Alignment / Fingerprinting | **Planned** | Existing C++ native analyzer (`native/analyzer/*`, `native/loopmix/*`) already runs natively; evaluate profiling before migrating to avoid redundant rewrites. |
| **Phase E** | Additional ABIs (`armeabi-v7a`, `x86_64`) & Gradle Automation | **Planned** | Add `armv7-linux-androideabi` and `x86_64-linux-android` targets via `rustup target add` and wire a Gradle `Exec` task for automated multi-ABI builds. Currently non-`arm64-v8a` ABIs fall back seamlessly to Kotlin. |

---

## 3. JNI Interface Documentation

All exported symbols live in [native/sonora-android/src/lib.rs](file:///C:/Users/gamve/Projects/BitChord/native/sonora-android/src/lib.rs) and are packaged into `libsonora_core.so`:

1. **`Java_com_music_bitchord_ui_components_NativeBlur_boxBlur`**
   - **Kotlin caller:** [NativeBlur.kt](file:///C:/Users/gamve/Projects/BitChord/sharedUi/src/androidMain/kotlin/com/music/bitchord/ui/components/NativeBlur.kt) via [NativeBlurPlatform.kt](file:///C:/Users/gamve/Projects/BitChord/sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/NativeBlurPlatform.kt)
   - **Signature:** `(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int) -> Unit`
   - **Memory model:** In-place mutation via `GetPrimitiveArrayCritical` (`mode = 0` on release). Bounds-checked against `width * height`.
2. **`Java_com_music_bitchord_playback_chords_NativeChordDspAndroid_computeChroma`**
   - **Kotlin caller:** [NativeChordDsp.jvmShared.kt](file:///C:/Users/gamve/Projects/BitChord/shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/chords/NativeChordDsp.jvmShared.kt) via [NativeChordDsp.kt](file:///C:/Users/gamve/Projects/BitChord/shared/src/commonMain/kotlin/com/music/bitchord/playback/chords/NativeChordDsp.kt)
   - **Signature:** `(ring: DoubleArray, base: Int, outChroma: DoubleArray) -> Boolean`
   - **Memory model:** Read-only `ring` released with `JNI_ABORT` (`mode = 2`, zero copy-back); `outChroma` (length ≥ 12) released with `mode = 0`. Returns `false` on invalid input so Kotlin falls back safely.

---

## 4. Build Instructions

Requires stable Rust (`rustup target add aarch64-linux-android`) and Android NDK 27:

```powershell
Set-Location native
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$env:LOCALAPPDATA\Android\Sdk\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android24-clang.cmd"
cargo build -p sonora-android --target aarch64-linux-android --release
Copy-Item -Force "target\aarch64-linux-android\release\libsonora_core.so" "..\app\src\main\jniLibs\arm64-v8a\libsonora_core.so"
```

Then build the Android APK normally:

```powershell
.\gradlew.bat :app:assembleProdDebug "-Pbitchord.bundleWear=false"
```

---

## 5. Verification, Regression Tests & APK Impact

- **Rust compilation:** `cargo build -p sonora-android --target aarch64-linux-android --release` succeeded in `13.28s` with **0 warnings / 0 errors**.
- **APK binary impact:** `libsonora_core.so` adds **`510 KB`** uncompressed (`arm64-v8a`) and replaces `libsonora_blur.so`.
- **Unit & regression tests:**
  - `:shared:jvmTest` (including `ChordAnalysisTest`, `ChordDisplayTest`, `ChordHarmonyTest`): **PASSED 100%**.
  - `:sharedUi:jvmTest` (including `LyricClockTest`, `LyricFocusTest`, `LyricsOffsetTest`, `ArtworkPaletteTest`): **PASSED 100%**.
- **Physical device verification (Moto G Stylus 5G 2023, `arm64-v8a`):**
  - Installed `app-prod-arm64-v8a-debug.apk` cleanly (`Success`).
  - Cold start verified (`LaunchState: COLD`), live process running (`PID 24256`) with **0 crashes** in `logcat -b crash`.

### Remaining Limitations & Next Steps
- **Multi-ABI coverage:** `armeabi-v7a` and `x86_64` currently use the portable Kotlin fallback until those Rust targets are added and built in CI/Gradle.
- **Controlled song-level chord timing benchmark:** Direct A/B wall-clock timing of a full 4-minute offline song in `AndroidChordAnalysis` (Kotlin FFT vs. Rust `libsonora_core.so`) on `prodRelease` has not yet been recorded and should be measured on-device with a cached track before removing the Kotlin FFT fallback.
- **Host Rust unit tests on Windows:** Running `cargo test` on Windows requires MSVC `link.exe` (Visual Studio C++ Build Tools) or `x86_64-pc-windows-gnu`; cross-compilation for Android uses NDK clang and works out of the box.
