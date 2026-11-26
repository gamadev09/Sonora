# Build BitChord Chords MVP on macOS

This is the complete source tree with the chords changes already applied.
You do not need to apply the earlier patch.

## App identity

| Variant | Installed package | Launcher name |
| --- | --- | --- |
| prodDebug (recommended) | `com.music.bitchord.chordsmvp` | BitChord Chords MVP |
| devDebug | `com.music.bitchord.chordsmvp.dev` | BitChord Chords MVP Dev |

These install separately from official BitChord and BitChord Dev. The Kotlin
namespace stays unchanged so native JNI bindings and class names keep working.
Provider authorities already use `${applicationId}`. The optional watch build
has matching IDs. Existing official-app data is not imported into this prototype.

## Requirements

- Android Studio, using its bundled JDK 21 (or another JDK 21).
- Android SDK Platform 37, as required by `app/build.gradle.kts`.
- CMake 3.22.1 and the NDK selected by the existing Android Gradle plugin.
- Internet for Gradle, SDK components and Maven dependencies on the first build.

The repository's older CONTRIBUTING setup section mentions SDK 36. Use the
current Gradle configuration (37 for the phone), which is authoritative.

## Build

1. Extract this archive into a normal folder.
2. Open the extracted BitChord-chords-mvp folder in Android Studio. Let Gradle
   sync and install the requested SDK/NDK tools. Do not upgrade dependencies.
3. In Terminal, inside that folder:

```sh
chmod +x gradlew build-chords-mvp.sh
./build-chords-mvp.sh
```

The script runs:

```sh
./gradlew :app:assembleProdDebug -Pbitchord.bundleWear=false
```

This skips the optional embedded watch APK and uses standard local debug
signing. No release keystore is required. APKs appear under:

```text
app/build/outputs/apk/prod/debug/
```

Choose the **arm64-v8a** APK for the Moto G Stylus 5G (2023), or the universal
APK. Install it over wireless debugging or transfer it to the phone and open it.

If Gradle says the Android SDK is missing, Android Studio should create a
`local.properties` file containing your actual SDK location. The typical macOS
location is:

```properties
sdk.dir=/Users/YOUR_USERNAME/Library/Android/sdk
```

Do not copy another person's absolute SDK path. If Terminal cannot locate Java,
select Android Studio's JDK for Gradle or set JAVA_HOME to your installed JDK 21.

## Use and validation

Play a downloaded/local or fully cached song, open lyrics and tap **Find chords**.
Chords appear above each timed lyric line and highlight when the audio reaches
each chord change. Show chords hides them; Simplify reduces sevenths to their
major/minor forms (Cmaj7 → C, Am7 → Am, G7 → G) without changing cached analysis.
The detector estimates major, minor, dominant seventh, major seventh and minor
seventh chords offline. Unsynchronised lyrics use a current-chord fallback rather
than invented line alignment. It is a basic chroma-based detector, not Moises-quality AI.

Twelve detector/display tests passed in the cloud. Android/Compose compilation, APK
installation and real-song accuracy have not been verified there. This ZIP is
source for you to compile, not a prebuilt or verified APK.

More details and on-device checks: `docs/CHORDS_MVP.md`.
