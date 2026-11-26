# Offline chords MVP — lyrics layout v2

The Android lyrics view shows estimated chords **above each corresponding lyric
line**, using the same lyric row container and its scrolling, blur and motion.
Translations remain below the original lyric. This replaces the earlier separate
horizontal chord strip. There is no chord-to-word positioning claim: chord names
wrap naturally above the line instead of attaching to exact lyric characters.

## Use

1. Build/install as described in `BUILD_CHORDS_MVP.md`. The standalone package
   remains `com.music.bitchord.chordsmvp` (prodDebug).
2. Play a local/downloaded or fully cached current rendition and open lyrics.
3. Tap **Find chords**. Progress and cancellation remain available.
4. Each lyric line shows the chord sounding at its start plus changes before the
   next distinct lyric timestamp. Duplicate timestamps (duets) share a window.
5. Chords highlight on the actual audio timeline. Backwards/forwards seeks are
   handled by stateless interval lookup. A lyric offset moves the line windows,
   not the chord event timestamps.
6. **Show chords** hides/shows annotations. **Simplify** reduces Cmaj7 to C,
   Am7 to Am and G7 to G. Adjacent equal simplified events are merged for display.
   This does not alter the original detection or its cached results.
7. **Analyse again** reruns detection. Version 2 caches are separate from the
   earlier triad-only detector, so old results are not mistaken for richer ones.

With untimed or missing lyrics, the controls provide a current-chord fallback.
Some outer player layouts use their own placeholder rather than composing the
lyrics panel when lyrics are absent; verify those layouts on a device. Instrumental
lyric-gap rows retain their original dot animation, rather than lyric annotations.
No alignment is invented for unsynchronised lyrics. Chords are hidden while
selecting lyrics for a share card.

## Analysis

- No server, microphone or model download. The existing Android MediaCodec
  streaming decoder feeds a bounded-memory, deterministic chroma/template engine.
- Major, minor, dominant seventh, major seventh and minor seventh templates in all
  12 roots (60 chord labels). N means silence/uncertainty and displays as a dash.
- Mono downmix, box-filter decimation to 11025 Hz, 8192-sample Hann FFT,
  pitch-class accumulation, template comparison and three-frame smoothing.
- This is a baseline, not a learned model or Moises-quality transcription. Dense
  mixes, overtones, detuning and stereo cancellation can cause wrong estimates.
  Diminished/augmented/suspended chords, richer extensions, slash chords, inversions,
  capo suggestions and chord diagrams are not implemented.
- Audio must be readable and supported by Android MediaExtractor/MediaCodec;
  duration is limited to 30 minutes. No new audio download is initiated.
- Only the current complete cached rendition is used, rather than a smaller
  potentially different cut. Playback source switches during preparation are not
  tracked; reanalyse after changing recordings.
- Atomic JSON results are retained in app-private cache, capped at 64 entries.
  URI/size/modification time where available or rendition key/length identify the
  source. Same-size replacements without modification metadata can need reanalysis.
- Chord polling is 100 ms but highlight precision also depends on the existing
  playback snapshots. UI labels are English and need resource localisation before
  a public release. Toggle state is per composed song and not a persisted setting.

## Architecture

`ChordAnalyzer`, `ChordEvent`, `ChordDisplay` and tests are in shared common code.
`ChordAnalysisSource` is an optional service on PlayerHost. AndroidChordAnalysis
owns decoding, cancellation and JSON caching. ChordLyricsPanel prepares shared
state and controls; LyricsContent maps events into line windows, then renders
LyricChordLine before the original PanelVoice inside the same animated Column.
The original playback/DSP pipeline is not changed.

## Validation

Actual Kotlin sources compiled with Kotlin/JVM 2.1.0 and JUnit: **12 tests passed**.
The tests include all 60 chord types/roots at 44.1 and 48 kHz (120 synthetic
combinations), silence, a C→Am progression, seeking/interval boundaries, chunk-size
invariance, short input, seventh simplification, preserved originals, carried
chords, multiple changes per lyric, duplicate duet timestamps and lyric offsets.
These synthetic tests do not establish real-song accuracy.

Full Android/Compose compilation and visual/device testing remain unverified.
The cloud has no Android SDK and Java could not download Gradle. No APK is claimed.
Run on your Mac:

```sh
./gradlew :shared:jvmTest :app:assembleProdDebug -Pbitchord.bundleWear=false
```

On-phone checks: portrait/landscape; translation enabled/disabled; long chord
sequences wrapping above lines; seventh simplification; Show chords; timed/untimed
lyrics; lyric offset changes; lyric selection; local/downloaded/cached audio;
partial-cache errors; cancel/change songs during analysis; cached replay;
backwards/forwards seeking; pause/resume; unsupported codecs. Compare several real
songs with known charts. This source MVP is not a tested release.
