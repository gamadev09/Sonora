# BitChord File & Module Guide

A structured guide to the modules, directories, and key files across the **BitChord** repository (1,504 tracked files).

## Repository Overview

| Top-Level Path | Tracked Files | Purpose |
| --- | ---: | --- |
| `app/` | 728 | Android phone/tablet application, native JNI/DSP/bit-perfect C++, resources, and tests |
| `web/` | 279 | Web client (`@bitchord/app`), browser extension (`@bitchord/extension`), shared bridge (`@bitchord/shared`), docs, and fixtures |
| `shared/` | 149 | Kotlin Multiplatform shared models, networking, Innertube, lyrics, Discord RPC, and Automix/Chords logic |
| `sharedUi/` | 142 | Compose Multiplatform shared UI, themes, components, Now Playing, lyrics, and localized resources |
| `tv/` | 55 | Android TV / Google TV application reusing `:app` data/playback/native layers with TV-specific UI |
| `wearos/` | 51 | Wear OS watch remote & "watch as speaker" app, plus `wearos/protocol/` shared with `:app` |
| `native/` | 38 | Shared C/C++ audio analyzer & loop-mix DSP, FFmpeg/ONNX Runtime build scripts, and vendored headers |
| `backend/` | 25 | Go "Listen Together" party sync & live presence server, tests, and Oracle/Render deploy configs |
| `.github/` | 10 | GitHub Actions CI/release/deploy workflows, funding config, and contributor SVG generator |
| `docs/` | 3 | Design and implementation docs for Chords MVP, Lyrics Translation, and the Web App |
| Root files | 24 | Gradle build config, wrapper, documentation, assets, and Chords MVP scripts/patch |

---

## Root Files

### Gradle & Build Configuration
- `settings.gradle.kts` — Configures repositories (`google`, `mavenCentral`, `jitpack.io`), includes `:shared` and `:sharedUi` unconditionally, and includes `:app`, `:tv`, and `:wearos` when an Android SDK is detected via `local.properties`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`.
- `build.gradle.kts` — Top-level build script; defines `bitchordVersionCode` (`42`), `bitchordVersionName` (`"1.10"`), `bitchordBetaSuffix`, plugin versions (AGP `8.10.1`, Kotlin `2.3.20`, Compose `1.10.3`), and overrides R8 to `8.13.23` to avoid a debug dexing register-allocation bug in `NowPlayingScreen`.
- `gradle.properties` — Project-wide Gradle/JVM/Kotlin/Android build flags.
- `gradle/wrapper/` — Gradle wrapper JAR and `gradle-wrapper.properties`, paired with `gradlew` (Unix) and `gradlew.bat` (Windows).
- `keystore.properties.example` — Template for Android release signing credentials.
- `.gitignore` / `.gitattributes` — Git ignore rules and line-ending/binary attributes.

### Documentation, Assets & Chords MVP Helpers
- `README.md` — Main project overview, feature list, download links, contributors badge, and disclaimers.
- `ADDITIONAL.md` — Documentation index linking `backend/README.md` and `docs/LYRICS_TRANSLATION.md`.
- `CONTRIBUTING.md` — Contribution workflow, setup instructions, build/test commands, and GPLv3 compliance notes.
- `CODE_OF_CONDUCT.md` — Community code of conduct.
- `MAINTAINERS.md` — Maintainer info (`@kushagrasinghx`) and auto-updated contributors list.
- `LICENSE` — Project license.
- `BUILD_CHORDS_MVP.md` — macOS build and validation guide for the Chords MVP (`com.music.bitchord.chordsmvp`).
- `START-HERE-WINDOWS.txt` — Windows PowerShell quickstart for building `:app` without Wear bundling.
- `build-chords-mvp.sh` — POSIX shell helper running `./gradlew :app:assembleProdDebug -Pbitchord.bundleWear=false`.
- `FORK_INSTRUCTIONS.md` & `APPLY_TO_FORK.patch` — Instructions and cumulative git patch for applying the Chords MVP onto an upstream fork.
- `Logo.png`, `Banner.png`, `upi_support.jpg` — Branding and support images used in documentation.

---

## `app/` — Android Phone & Tablet Application

### Build, Manifests, Assets & Profile
- `app/build.gradle.kts` — Configures `com.music.bitchord` (`applicationId = "com.music.bitchord.chordsmvp"`, `minSdk = 26`, `targetSdk = 36`, `compileSdk = 37`), `env` flavors (`dev` / `prod`), `debug` / `release` / `benchmark` build types, ABI splits (`armeabi-v7a`, `arm64-v8a`, `x86_64`, universal), CMake native build, `../wearos/protocol` source inclusion, `stripNewPipeExtractorUtils` task, and `BundleWearApk` task.
- `app/proguard-rules.pro` — R8/ProGuard keep rules for Rhino, NewPipe, InnerTubeX, QuickJS, ONNX Runtime, SMBJ, Ktor, `@Serializable`, `@JavascriptInterface`, `libadb-android`, `jmdns`, and `FfmpegAudioDecoder`.
- `app/libs/onnxruntime-android-1.28.0-automix.aar` — Custom operator-pruned ONNX Runtime `1.28.0` AAR for Automix.
- `app/src/main/AndroidManifest.xml` — Permissions, Android Auto metadata, `MainActivity` deep links (`music.youtube.com`, `youtu.be`, `bitchord://party`, invite links, audio intents), four adaptive Sonora launcher `<activity-alias>` entries (Default, Light, Dark, Monochrome) for runtime icon switching, `PlaybackService`, `WearCommandService`, `DownloadService`, and widgets/tile receivers.
- `app/src/dev/AndroidManifest.xml` — Dev flavor manifest override (`usesCleartextTraffic="true"`).
- `app/src/main/baseline-prof.txt` — AOT baseline profile rules for `com.music.bitchord.**` and `com.my.kizzy.**`.
- `app/src/main/assets/` — `Logo.svg`, `LogoTransparent.svg`, plus `beat_this_final0_q8.onnx` and `vocals_umxhq_int8.onnx` (excluded from APK packaging via `ignoreAssetsPattern` and downloaded on demand by `AutomixModels`).

### Java & Kotlin Sources (`app/src/main/java/`)
- `androidx/media3/decoder/ffmpeg/` — Vendored Media3 `1.11.0` FFmpeg extension (`FfmpegLibrary.java`, `FfmpegAudioDecoder.java`, `FfmpegAudioRenderer.java`, `FfmpegDecoderException.java`) loading `libffmpegJNI.so`.
- `org/schabi/newpipe/extractor/utils/Utils.java` — Patched NewPipeExtractor `Utils` fixing `URLEncoder`/`URLDecoder` crashes on Android API < 33.
- `com/music/bitchord/` — `BitChordApplication.kt` (startup & host wiring), `MainActivity.kt` (Compose root, deep links, DAC volume keys, refresh rate), `AppIcon.kt` (persistent launcher alias switching, legacy migration and package-update restore).
- `com/music/bitchord/auth/` — Encrypted credential/session storage (`AuthStore.kt`, `EncryptedPrefs.kt`, `AccountSessions.kt`, `SignedIn.kt`, `WebSession.kt`) and login WebViews (`YtMusicLoginScreen.kt`, `SpotifyLoginScreen.kt`, `DiscordLoginScreen.kt`).
- `com/music/bitchord/data/` — Repositories and integrations:
  - `AppUpdateChecker.kt`, `LocalGroups.kt`, `LocalMediaRepository.kt`, `OfflinePlaylists.kt`, `YouTubeThumbnailFallback.kt`
  - `backup/` — Google Drive backup/sync (`DriveApi.kt`, `DriveAuth.kt`, `DriveBackup.kt`, `ReplaySync.kt`).
  - `canvas/` — Motion artwork & artist signatures (`CanvasRepository.kt`, `AppleMusicCanvas.kt`, `SpotifyCanvas.kt`, `TidalCanvas.kt`, `CommunityCanvas.kt`, `SignatureInk.kt`).
  - `discord/` — `DiscordRPC.kt`, `DiscordAudioQuality.kt`, `SuperProperties.kt`.
  - `innertube/` — `AndroidStreamHooks.kt`, `PlaybackTracker.kt`, `potoken/PoTokenWebView.kt`.
  - `jiosaavn/` — `JioSaavnService.kt`.
  - `listentogether/` — `ListenTogether.kt`, `JamInviteLink.kt`.
  - `lyrics/` — `EmbeddedLyrics.kt`.
  - `presence/` — `AndroidPresence.kt`.
  - `remote/` — Remote artwork & song models (`EmbeddedArt.kt`, `RemoteArtworkStore.kt`, `RemoteSong.kt`).
  - `scrobbling/` — `ScrobbleManager.kt`, `LastFM.kt`, `ListenBrainzManager.kt`, `PrimaryArtist.kt`.
  - `settings/` — `AppSettings.kt`, `SearchHistory.kt`.
  - `smb/` — SMB network share browsing & playback (`SmbClient.kt`, `SmbConnection.kt`, `SmbRepository.kt`, `SmbCoverFetcher.kt`).
  - `sources/` — Pluggable music sources (`SourceRegistry.kt`, `SourceResolver.kt`, `YouTubeSource.kt`, `JioSaavnSource.kt`, `AddonSource.kt`, `ModuleSource.kt`, `addon/`, `module/` with `QuickJsExecutor.kt`).
  - `spotify/` — `LocalPlaylistStore.kt`.
  - `stats/` — Listening history & Replay stats (`ListeningRecorder.kt`, `ListeningStats.kt`, `ArtistFacts.kt`, `Backup.kt`).
  - `webdav/` — WebDAV client, uploads, and Coil auth (`WebDavClient.kt`, `WebDavRepository.kt`, `WebDavUploads.kt`).
- `com/music/bitchord/download/` — Offline download manager, DASH/HLS downloaders, and metadata/lyrics taggers (`DownloadService.kt`, `Downloader.kt`, `FlacTagger.kt`, `Mp4Tagger.kt`, `WebmTagger.kt`, `LyricsTag.kt`).
- `com/music/bitchord/playback/` — Core playback engine and DSP:
  - Service & queue: `PlaybackService.kt`, `PlayerConnection.kt`, `QueueCoordinator.kt`, `BoundedQueuePlanner.kt`, `CrossfadeController.kt`, `Autoplay.kt`, `PartySync.kt`, `SleepTimer.kt`, `EqualizerProcessor.kt`, `SpatialAudioProcessor.kt`, `LoudnessProcessor.kt`.
  - `audio/` — Precision PCM/float DSP chain (`DspChain.kt`, `PrecisionAudioSink.kt`, `OutputNegotiator.kt`), `bitperfect/` (Oboe exclusive output, DAC format conversion, `usb/` direct USB Audio Class transport & volume), and `bluetooth/BluetoothAudioTracker.kt`.
  - `cast/` — Google Cast integration (`CastController.kt`, `CastPlayback.kt`, `CastOptionsProvider.kt`, `RemoteSpeaker.kt`).
  - `chords/` — `AndroidChordAnalysis.kt` (offline chord analysis job runner).
  - `dsd/` — DSD/DST audio extractor and decoders (`DsdExtractor.kt`, `DsdDecoders.kt`, `DsdFormat.kt`).
  - `loopmix/` — `LoopMixRenderer.kt`, `LoopMixSession.kt`, `LoopMixPort.kt` (dual-deck Automix loop-mix wrapper).
  - `smart/` — `TrackAnalyzer.kt`, `AutomixModels.kt`, `AudioDecoder.kt`, `MixWaveform.kt`, `VersionAudioAligner.kt`.
- `com/music/bitchord/ui/` — Android-specific UI, `MainViewModel.kt`, `AndroidAppUiHost.kt`, `navigation/AppNavigator.kt`, `onboarding/OnboardingScreen.kt`, `player/` (`AndroidPlayerHost.kt`, `CanvasArtworkPlayer.kt`, `MusicVideoPlayer.kt`, `LyricsShareVideo.kt`), `replay/` & `replay/year/`, `screens/` (19 settings/library/backup/sources/Listen Together screens), `components/` (including vendored `backdrop/` glass effects and `floatingtabbar/`), `icons/`, `performance/`, `theme/`, `utils/`.
- `com/music/bitchord/wear/` — Phone-side Wear OS bridge (`WearBridge.kt`, `WatchInstaller.kt`, `WatchInstallTypes.kt`, `WatchOutputState.kt`).
- `com/music/bitchord/widget/` — Home screen media widgets (`MediaWidget.kt`, `MediaWidgetPill.kt`, `MediaWidgetActions.kt`) and Quick Settings `PlaybackTile.kt`.

### Native C/C++ (`app/src/main/cpp/` & `app/src/main/jniLibs/`)
- `app/src/main/cpp/CMakeLists.txt` — Builds 6 shared libraries with 16 KB page alignment:
  - `bitchord_analysis` (`jni/{analysis_jni,mel_jni,vocal_jni}.cpp` + `native/analyzer/*.cpp`)
  - `bitchord_loopmix` (`jni/loopmix_jni.cpp` + `native/loopmix/loop_mix_renderer.cpp`)
  - `bitchord_dsd` (`dsd/dsd_decimator.cpp`)
  - `bitchord_dst` (`dsd/dst/{decoder.c,jni.cpp}`, LGPL-2.1 DST decoder)
  - `bitchord_exclusive` (`bitperfect/exclusive_output.cpp` + static Oboe `1.11.0`)
  - `bitchord_usb` (`bitperfect/usb_transport.cpp` + static `libusb` `1.0.27`)
- `app/src/main/cpp/bitperfect/tests/usb_stream_test.cpp` — Standalone C++17 host test for `usb_stream.h`.
- `app/src/main/jniLibs/` — Prebuilt `libffmpegJNI.so` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

### Resources, Debug & Tests
- `app/src/main/res/` — Drawables, SF Pro Display `.otf` fonts, widget layouts, 22+ launcher icon variants (`mipmap-*`), localized `values-*/strings.xml` (19 locales), and `xml/` configs (`automotive_app_desc.xml`, `file_paths.xml`, `locales_config.xml`, widget providers).
- `app/src/debug/java/com/music/bitchord/ui/preview/BarPreviews.kt` — Compose `@Preview`s for top/bottom bars and mini-player.
- `app/src/test/java/com/music/bitchord/` — 126 JVM unit tests covering playback, DSP/bit-perfect audio, downloads, stats, and backup.
- `app/src/androidTest/java/com/music/bitchord/` — `AndroidAutoMediaLibraryTest.kt` and `ExampleInstrumentedTest.kt`.

---

## `shared/` — Kotlin Multiplatform Core

- `shared/build.gradle.kts` — Targets `androidTarget` and `jvm` (JVM 17). Defines `commonMain` (pure Kotlin + `kotlinx-serialization-json`) and `jvmSharedMain` (shared by Android & JVM desktop with Ktor `3.5.2`, `innertubex`, `jsoup`, `coroutines`, and `compileOnly` ONNX Runtime / NewPipeExtractor).
- `shared/src/commonMain/kotlin/com/music/bitchord/` (23 files):
  - `data/listentogether/` — Party wire models, jam invite parsing, queue reading, and server clock sync.
  - `data/model/` — Core domain models (`Models.kt`, `PlaylistOrder.kt`, `SpotifyMatch.kt`, `TopPicks.kt`, `VersionTimeMap.kt`).
  - `data/settings/` — `AutomixPerformanceMode.kt`, `TrackAnalysisState.kt`.
  - `playback/` — `AutoMixVisualState.kt`, `EqualizerCurve.kt`, `EqualizerPreset.kt`, `PeakLimiter.kt`.
  - `playback/chords/` — Pure-Kotlin FFT chroma + template chord detector (`ChordAnalysis.kt`, `ChordDisplay.kt`).
  - `playback/smart/` — Smart transition planner (`TransitionPlanner.kt`, `TransitionPolicy.kt`, `LoopMix.kt`, `TrackAnalysis.kt`).
- `shared/src/jvmSharedMain/kotlin/` (98 files):
  - `com/music/bitchord/auth/` — `DataSyncId.kt`.
  - `com/music/bitchord/data/` — `YtMusicRepository.kt`, `SongInfoRepository.kt`, `Http.kt`, `AppleCatalogue.kt`, `AppleArtistPhotos.kt`, `canvas/`, `discord/`, `innertube/` (`Innertube.kt`, `StreamResolver.kt`, `potoken/`), `lyrics/` (29 files: `LyricsRepository.kt` + providers like `LrcRed.kt`, `BetterLyrics.kt`, `BiniLyrics.kt`, `LrcLib.kt`, `KuGou.kt`, `Musixmatch.kt`, `Genius.kt`, TTML/LRC parsers, and translation), `presence/Presence.kt`, `settings/`, `sources/` (`TrackMatcher.kt`, `StreamFormat.kt`), `spotify/`, `webdav/`.
  - `com/music/bitchord/playback/` — `QueueWindow.kt`, `QueueTimeline.kt`, `QueueBuilder.kt`, `ContextRegistry.kt`, `TransitionFilter.kt`, and `smart/` (ONNX `BeatTracker.kt`, `VocalTracker.kt`, `MelSpectrogram.kt`, `AnalysisStore.kt`).
  - `com/my/kizzy/` (19 files) — Ported Kizzy Discord Gateway WebSocket & RPC client.
- `shared/src/commonTest/` (11 tests) & `shared/src/jvmTest/` (16 tests) — Unit tests for models, chords, Automix planning, Listen Together, Innertube, lyrics, sources, Spotify, and queue windowing.

---

## `sharedUi/` — Compose Multiplatform Shared UI

- `sharedUi/build.gradle.kts` — Compose Multiplatform (`1.10.3`) library targeting `androidTarget` and `jvm` with `commonMain` resources, `jvmSharedMain` shared UI, and `androidMain` / `jvmMain` platform actuals.
- `sharedUi/src/commonMain/composeResources/` (32 files) — Vector/bitmap drawables, `vazirmatn_regular.ttf` font, and `strings.xml` across default + 18 locales.
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/` (78 files):
  - `playback/PlaybackPosition.kt` — `@Stable` playhead state separated from `PlayerState` to avoid full-tree recomposition.
  - `ui/` — `AppUi.kt`, `ForegroundState.kt`, `LyricsProviderState.kt`.
  - `ui/components/` — Reusable shelves, search field, badges, skeletons, `OptimizedHaze.kt`, and `expect` `BlurredBackdrop` / `ContextClick`.
  - `ui/graphics/` — Color math, fading edges, and `expect` `ArtworkPixels`.
  - `ui/haptics/` — Semantic `Haptic` enum and `expect` `rememberHaptics()`.
  - `ui/icons/BitChordIcons.kt` — Custom stroke `ImageVector` icons.
  - `ui/player/` (42 files) — `PlayerHost.kt`, `PlayerState.kt`, `NowPlayingScreen.kt`, `LandscapePlayer.kt`, `SyncedLyricsPanel.kt`, `ChordLyricsPanel.kt`, `ChordControls.kt`, `AutoMix*`, `MeshGradient.kt`, `MusicVideoLayout.kt`, `ListenTogetherMembersSheet.kt`, and `expect` `PlayerPlatformUi`.
  - `ui/replay/ReplayCard.kt`, `ui/screens/` (`HomeScreen.kt`, `ExploreScreen.kt`, `LibraryScreen.kt`, `SearchScreen.kt`, `MixesScreen.kt`), `ui/theme/` (`ArtworkPalette.kt`, `ScreenOrientation.kt`, `StatusBarIcons.kt`), `ui/utils/SheetGestureGuard.kt`.
- `sharedUi/src/androidMain/` (7 files) & `sharedUi/src/jvmMain/` (8 files) — Platform `actual` implementations (including `PaletteQuantizer.kt` for desktop).
- `sharedUi/src/jvmTest/` (16 files) — Tests for lyric focus/clock/offsets, AutoMix UI state, queue keys, and artwork/status-bar contrast.

---

## `tv/` — Android TV / Google TV App

- `tv/README.md` & `tv/build.gradle.kts` — Remote-first Compose-for-TV app (`applicationId = "com.music.bitchord.tv"`) that compiles `../app/src/main/java` (excluding shadowed paths), `res`, `assets`, `jniLibs`, and `app/src/main/cpp/CMakeLists.txt` directly, adding `androidx.tv:tv-material` and `tv-foundation`.
- `tv/src/main/AndroidManifest.xml` & `tv/src/dev/AndroidManifest.xml` — Declares `.TvActivity` (`LEANBACK_LAUNCHER` via `.MainActivity` alias) and `TvStartup` initializer.
- `tv/src/main/java/com/music/bitchord/` — `TvActivity.kt`, `data/TvStartup.kt` (caps canvas FPS to 24), `data/TvUsbMusic.kt` (scans mounted USB drives), `data/settings/` (`TvSettings.kt`, `TvPinThrottle.kt`), `wear/` (no-op stubs excluding Wearable/ADB from TV APK), and `ui/` (`TvApp.kt`, `DeviceType.kt`, `TvSignIn.kt`, `tv/auth/` QR local HTTP auth server, `tv/audio/TvSpatialAudioEngine.kt`, `tv/display/` refresh-rate controller, `tv/player/`, `tv/screens/`, `tv/components/`, `tv/dialogs/`, `tv/onboarding/`, `tv/theme/`).
- `tv/src/main/res/` & `tv/src/test/` — TV banners, fonts, themes, and PIN throttle/settings unit tests.

---

## `wearos/` — Wear OS Watch App & Shared Protocol

- `wearos/README.md` & `wearos/build.gradle.kts` — Companion watch remote and standalone-streaming speaker (`minSdk = 30`), minified with R8 and optionally bundled into `:app` as `wear/BitChordWear.apk`.
- `wearos/protocol/com/music/bitchord/wear/WearProtocol.kt` — Shared by `:app` and `:wearos`; defines Wearable capabilities (`bitchord_phone`, `bitchord_watch`), DataItem/message/channel paths, remote commands, and speaker subcommands.
- `wearos/src/main/AndroidManifest.xml` & `WearActivity.kt` — Watch entry point with `AmbientLifecycleObserver` and self-update handling.
- `wearos/src/main/java/com/music/bitchord/wear/` — `data/` (`PhoneLink.kt`, `NowPlaying.kt`, `NowPlayingListener.kt`, `SelfUpdate.kt`), `playback/` (`Speaker.kt`, `WatchPlaybackService.kt`, `PhoneStreamDataSource.kt` streaming audio from the phone over `ChannelClient`), and `ui/` (`WearApp.kt`, `NowPlayingScreen.kt`, `ListScreens.kt`, `MeshBackground.kt`, `Ambient.kt`, `Theme.kt`).
- `wearos/src/main/res/` — Watch icons, SF Pro Display fonts, themes, and `values/wear.xml` capability advertisement.

---

## `native/` — Shared C/C++ DSP & Native Build Tooling

- `native/analyzer/` — Offline track analyzer (`audio_analysis.cpp`, `tempo_analysis.cpp`), windowed-sinc `resampler.cpp`, `mel_spectrogram.cpp` (Beat This! front end), and `vocal_spectrogram.cpp` (open-unmix STFT front end).
- `native/loopmix/` — `loop_mix_renderer.h` / `.cpp` using `SignalsmithStretch` for beat-locked bar looping, tempo ramping, and RBJ biquad EQ filters.
- `native/ffmpeg/` — `build.sh` (cross-compiles `librempeg` into `app/src/main/jniLibs/<abi>/libffmpegJNI.so`) and `ffmpeg_jni.cc` (Media3 FFmpeg JNI bridge).
- `native/onnxruntime/` — `opconfig.py`, `required_operators.config`, `build.sh` (builds pruned `libonnxruntime.so` and packages `app/libs/onnxruntime-android-1.28.0-automix.aar`), and `parity.c` / `parity.sh` (on-device ADB parity test).
- `native/third_party/` — `README.md` and vendored header-only `signalsmith-linear` (`0.6.6`) and `signalsmith-stretch` (`1.3.2`).

---

## `backend/` — Go Listen Together & Presence Server

- `backend/README.md`, `go.mod`, `go.sum` — Go `1.27.0` + `gorilla/websocket` server specification and dependencies.
- `backend/main.go` & `main_test.go` — HTTP/WebSocket server (`:8000` default) with CORS, per-IP rate limiting, `/healthz`, `/api/time`, `/api/parties/*`, `/api/presence`, `/api/stats/live` (+ `badge.svg`), `/invite/{code}`, and `/ws/parties/{code}`.
- `backend/clock/clock.go` — Monotonic millisecond clock anchored at startup.
- `backend/codes/` — 6-character unambiguous party code generator and normalizer.
- `backend/config/` — `PORT` and `JAM_*` environment configuration + origin checks.
- `backend/hub/hub.go` — Thread-safe WebSocket connection `Hub` and `SafeConn`.
- `backend/party/` — In-memory `PartyStore`, anchor-based playback sync, queue deltas, autoplay dedup, and host succession.
- `backend/presence/` — Active install counter (`android`, `pc`, `browser`).
- `backend/protocol/` — WebSocket frame types and control action definitions.
- `backend/render.yaml` & `backend/deploy/` — Render blueprint plus Oracle Cloud/Ubuntu provisioning (`setup.sh`, `Caddyfile`, `bitchord-jam.service`, `bitchord-deploy.sh`).
- `backend/tools/jam_probe.py` — Python WebSocket/REST probe for testing live party queue/autoplay behavior.

---

## `web/` — Web Client & Browser Extension Monorepo

- `web/package.json`, `pnpm-workspace.yaml`, `pnpm-lock.yaml`, `tsconfig.base.json`, `web/README.md` — pnpm workspace containing `app`, `extension`, and `shared`.
- `web/app/` (`@bitchord/app`) — Vite 8 + React 19 + TypeScript + Tailwind v4 web player (`vite.config.ts`, `src/routes.tsx`, `src/pageRegistry.tsx`, `src/components/`, `src/data/`, `src/extension/`, `src/lyrics/`, `src/party/`, `src/playback/` with Shaka/SABR YouTube playback and Essentia/ONNX/Signalsmith AutoMix, `src/shell/`, `src/state/`, `public/models/`).
- `web/extension/` (`@bitchord/extension`) — WXT Manifest V3 companion extension for Chromium and Firefox (`wxt.config.ts`, `site.config.ts`, `entrypoints/` for background, Firefox relay, popup, lyric permission grant, and Discord presence, `lib/`, `scripts/`, `dev-key/`).
- `web/shared/` (`@bitchord/shared`) — Typed bridge protocol (`src/bridge/protocol.ts`) and Chromium/Firefox page client (`src/bridge/client.ts`).
- `web/docs/` (8 `.md` files) — Architecture notes covering Apple Music DOM, AutoMix, lyrics, queue/player, Spotify/Discord, YouTube playback, and YouTube sign-in.
- `web/fixtures/` & `web/scripts/capture-fixtures.mjs` — Captured anonymous Innertube, lyrics, and Spotify JSON/TTML fixtures for parser tests.

---

## `docs/` & `.github/`

### `docs/`
- `docs/CHORDS_MVP.md` — Offline Android chords MVP design, DSP pipeline, UI behavior, and validation status.
- `docs/LYRICS_TRANSLATION.md` — On-demand Android lyrics translation batching, timing projection, animation, and GZIP LRU cache.
- `docs/web-app-build-prompt.md` — 12-section architecture and phased build specification for `web/`.

### `.github/`
- `.github/FUNDING.yml` — Ko-fi funding link (`kushagrasinghx`).
- `.github/scripts/contributors_svg.py` — Generates `contributors.svg` with base64-embedded contributor avatars.
- `.github/workflows/` — `android.yml` (signed `prod` phone + Wear release APKs), `check.yml` (Android `devDebug` unit tests/APK + Go `backend` vet/test), `tv.yml` (TV `devDebug` APK), `web.yml` (pnpm typecheck/test/build), `deploy-backend.yml` (Oracle VM deploy + `/healthz` check), `close-issue.yml` (`/close` issue command), `contributors-image.yml`, and `update-contributors.yml`.
