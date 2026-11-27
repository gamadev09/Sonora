# Sonora — Offline Forced Alignment Feasibility Report (Phase 7)

## Executive Summary

**Recommendation:** **Do NOT bundle an on-device neural forced-alignment model in Sonora's base APK.** Instead, keep authentic provider-sourced syllable/word timing as the primary path, and—if pursued in a future release—offer offline alignment strictly as an **opt-in downloadable module** (Play Feature Delivery / user-triggered model download) limited to cached/downloaded local audio.

---

## 1. Technical Approaches Evaluated

| Approach | Typical Model Size | On-Device Latency (3–4 min track, mid-range ARM64) | Word/Syllable Accuracy on Polyphonic Music | APK / Maintenance Impact |
| :--- | :--- | :--- | :--- | :--- |
| **Wav2Vec2 / CTC Forced Alignment** (e.g., MMS-300M / wav2vec2-base quantized INT8 via ONNX Runtime) | 95–315 MB | 8–25 s (CPU) | Poor without vocal isolation; moderate-to-good with stem separation | Very high (ONNX Runtime + JNI + huge model) |
| **Whisper-tiny / base + Cross-Attention / DTW** (`whisper.cpp` GGML Q5_1) | 31–57 MB | 12–35 s (CPU) | Moderate for clean vocals; drifts on choruses, reverb, backing vocals, rap | High (native C++ runtime + model) |
| **Vocal Isolation (Demucs/MDX) + CTC Alignment** | 150–400 MB combined | 45–120 s (CPU) | High (closest to Apple Music TTML word-level) | Prohibitive for mobile real-time playback |
| **DSP Energy / Onset Heuristics** (no ML) | < 100 KB | < 1 s | Low (syllable-level inaccurate; violates authentic timing goal) | Low, but produces fabricated/unreliable timing |

---

## 2. Key Engineering Constraints on Android

1. **Streaming vs. Local Audio Access:**
   - Forced alignment requires decoding the full audio waveform (or large look-ahead windows) into PCM.
   - For streamed tracks (YouTube Music / JioSaavn), downloading and decoding the entire stream just to align lyrics doubles bandwidth, delays first-word display, and competes with ExoPlayer's audio pipeline.
   - Alignment is only practical for **downloaded or local files** where the PCM can be decoded once and cached.

2. **Polyphonic Music Interference:**
   - Standard speech-trained aligners (Wav2Vec2, Whisper) degrade sharply when drums, guitars, synths, and backing harmonies overlap the lead vocal.
   - Reliable word boundaries in music require either a music-trained acoustic model or a preceding vocal-separation pass, both of which are memory- and battery-intensive on mobile CPUs (e.g., Snapdragon 6 Gen 1 / mid-range ARM64).

3. **Multilingual Coverage:**
   - Sonora users play English, Spanish, Japanese, Korean, Hindi, and mixed-language tracks.
   - A single English-only model would silently fail or misalign international tracks; multilingual models (MMS / Whisper multilingual) increase footprint and RAM usage significantly.

4. **APK Size & Battery Budget:**
   - Sonora's current APK is lean and fast to install/update. Bundling ONNX Runtime + an acoustic model would add 50–120 MB+ to every install and risk thermal throttling during playback.

---

## 3. Recommended Roadmap (If Implemented Later)

1. **Zero-fabrication policy (current):** Never synthesize fake word timestamps by evenly splitting line durations. Keep authentic provider-sourced syllable/word timing (`COMPLETE_SYLLABLE > COMPLETE_WORD > PARTIAL_WORD > LINE_SYNCED > PLAIN`) and persistent caching.
2. **Optional on-demand download (future):** If offline alignment is ever added:
   - Ship it as an opt-in downloadable model in Settings → Lyrics (not bundled in the APK).
   - Restrict execution to **local/downloaded tracks** with plain or line-synced lyrics only, running in a background WorkManager job with battery/thermal constraints.
   - Store aligned output in `LyricsCache` with a distinct confidence/tier tag so authentic provider word-sync always takes precedence whenever available online.
