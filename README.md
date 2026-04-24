# Tarmac

High-quality **AirPlay receiver for Android TV**. Point your MacBook's Screen Mirroring at Tarmac and get:

- 1080p60 and 4K (HEVC) mirroring
- HDR10 passthrough
- Hardware-tunneled HEVC decode for drift-free A/V sync
- **Extended-display mode** — "Use As Separate Display" in macOS's AirPlay menu uses the TV as a second monitor
- AirPlay Video streaming (Photos, Apple TV app, etc.)

Built for the Android TV boxes you actually use — Chromecast with Google TV, NVIDIA Shield, Fire TV Cube — without the stutter and sync problems of `AirScreen`.

**Latest release:** [v1.0.0](https://github.com/llmassisted/tarmac/releases/tag/v1.0.0) — signed APK available on the release page.

## Architecture

```
Android TV app (Kotlin + Leanback)
  └─ TarmacService (foreground)
       └─ JNI → native AirPlay server (UxPlay lib/)
            └─ MediaCodec (tunneled) + AudioTrack
```

The AirPlay protocol stack is [UxPlay](https://github.com/FDH2/UxPlay)'s `lib/` directory (RTSP + RAOP + FairPlay SAP + pair-setup), pulled in as a git subtree. UxPlay's GStreamer-based renderers are replaced with Android `MediaCodec` and `AudioTrack` to get hardware A/V sync, low latency, and HDR passthrough.

## Build

Requires Android Studio Hedgehog (2023.1.1) or newer, NDK 26.3.11579264, CMake 3.22. The native RAOP/RTSP protocol stack (UxPlay's `lib/`) and libplist are vendored under `native/`, and OpenSSL ships via a prefab AAR — no subtree pull is needed.

```sh
# Debug build (Android Studio debug keystore; fine for local install):
./gradlew :app:assembleDebug

# Install on a connected Android TV device:
./gradlew :app:installDebug
```

### Release build

A release build is signed with a keystore referenced from `~/.gradle/gradle.properties` (or `-P` flags). Missing properties cleanly degrade to an unsigned APK.

```properties
# ~/.gradle/gradle.properties
TARMAC_RELEASE_STORE_FILE=/absolute/path/to/tarmac-release.jks
TARMAC_RELEASE_STORE_PASSWORD=...
TARMAC_RELEASE_KEY_ALIAS=tarmac
TARMAC_RELEASE_KEY_PASSWORD=...
```

```sh
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk (signed, APK Signature Scheme v2)
```

## Install / sideload

Grab the signed APK from the [latest release](https://github.com/llmassisted/tarmac/releases/latest) and sideload it:

```sh
adb install tarmac-v1.0.0.apk
```

Or, for a local debug build:

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open Tarmac on the TV. It advertises itself via Bonjour as `Tarmac` by default (configurable under Settings). On your Mac, open **Control Center → Screen Mirroring**, pick Tarmac, enter the on-screen PIN.

For extended desktop, open the Screen Mirroring submenu again while mirroring and choose **Use As Separate Display**.

## Diagnostics

Debug builds expose runtime state two ways:

```sh
# Full diagnostics report (sessions, codec stats, wake-lock state, network callback):
adb shell dumpsys activity service com.tarmac/.service.TarmacService

# Same report mirrored to logcat via a NOT_EXPORTED broadcast:
adb shell am broadcast -p com.tarmac -a com.tarmac.action.DUMP_STATS
```

StrictMode thread + VM policies are installed on debuggable builds so disk/network on main, leaked closables, and activity leaks show up in logcat.

## License

**GPL-3.0-only.** UxPlay is GPL-3.0, so any build that includes the subtree must also be distributed under GPL-3.0. The UI/service layers (Kotlin code in `app/`) are GPL-3.0 for consistency.

See [LICENSE](LICENSE) for the full text.

## Status

- [x] Phase 1 — Android TV scaffolding + native build skeleton
- [x] Phase 2 — JNI bridge, MediaCodec video, AudioTrack audio, Bonjour
- [x] Phase 3 — Leanback UX (pairing PIN, status, settings)
- [x] Phase 4 — Extended display + AirPlay Video (ExoPlayer)
- [x] Phase 5a — 4K HEVC + HDR10 static metadata + tunneled playback
- [x] Phase 5b — Endurance hardening (session-scoped wake-lock, Wi-Fi roam recovery, pipeline fault self-healing, JNI callback lifecycle safety, dumpsys + adb diagnostics)

Shipped as **v1.0.0**.
