# Contributing to Sonora

Thank you for contributing to Sonora. Follow these guidelines to keep the codebase clean, maintainable and verifiable.

## Proposing Contributions

- Discuss substantial features, architectural changes, or UI modifications in an issue before implementing them.
- Focused bug fixes, verified performance improvements, translations and documentation updates can be submitted directly as a Pull Request.

## Pull Request Guidelines

- Branch from the current active development branch using a descriptive name (e.g., `fix/lyrics-offset`, `perf/chord-cache`).
- Keep pull requests focused on a single concern. Avoid mixing unrelated refactors or formatting churn with functional changes.
- Include reproduction steps, test results, and before/after measurements for any performance-related change.
- Do not add external dependencies unless clearly justified by necessity, binary size and GPLv3 compatibility.

## Development Setup

- **JDK:** Java Development Kit 21.
- **Android SDK:** `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`.
- **C/C++ NDK & CMake:** CMake 3.22.1+ and Android NDK 27 (for native audio DSP under `app/src/main/cpp` and `native/`).
- **Rust (Optional):** Stable Rust with `aarch64-linux-android` target if modifying `native/sonora-core` or `native/sonora-android`.
- **Listen Together Backend (Optional):** Go 1.22+ for `backend/`.

## Build and Test Commands

Run Gradle commands from the repository root:

```bash
./gradlew :app:assembleProdDebug -Pbitchord.bundleWear=false
./gradlew :shared:jvmTest :sharedUi:jvmTest :app:testProdDebugUnitTest -Pbitchord.bundleWear=false
```

Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleProdDebug "-Pbitchord.bundleWear=false"
.\gradlew.bat :shared:jvmTest :sharedUi:jvmTest :app:testProdDebugUnitTest "-Pbitchord.bundleWear=false"
```

## Audio, Performance and Telemetry Standards

- Do not present application telemetry as proof of physical hardware behavior unless verified on hardware.
- Performance changes must include reproducible measurements on physical devices using production-equivalent builds where applicable, and must never sacrifice user-facing features or visual fidelity.

## Licensing & Attribution

Sonora is licensed under the **GNU General Public License v3.0 (GPLv3)** and is derived from **BitChord**. All contributed code and dependencies must remain strictly compatible with GPLv3 and preserve upstream copyright and attribution notices.
