# Tarmac

High-quality **AirPlay receiver for Android TV**. Point your MacBook's Screen Mirroring at Tarmac and get:

- 1080p60 and 4K (HEVC) mirroring
- HDR10 passthrough
- **Extended-display mode** — "Use As Separate Display" in macOS's AirPlay menu uses the TV as a second monitor
- AirPlay Video streaming (Photos, Apple TV app, etc.)

Built for the Android TV boxes you actually use — Chromecast with Google TV, NVIDIA Shield, Fire TV Cube — without the stutter and sync problems of `AirScreen`.

## Architecture

```
Android TV app (Kotlin + Leanback)
  └─ TarmacService (foreground)
       └─ JNI → native AirPlay server (UxPlay lib/)
            └─ MediaCodec (tunneled) + AudioTrack
```

The AirPlay protocol stack is [UxPlay](https://github.com/FDH2/UxPlay)'s `lib/` directory (RTSP + RAOP + FairPlay SAP + pair-setup), pulled in as a git subtree. UxPlay's GStreamer-based renderers are replaced with Android `MediaCodec` and `AudioTrack` to get hardware A/V sync, low latency, and HDR passthrough.

## Build

Requires Android Studio Hedgehog (2023.1.1) or newer, NDK 26.3.11579264, CMake 3.22.

```sh
# From repo root, one-time subtree pull of the UxPlay protocol library:
git subtree add --prefix=native/libairplay \
    https://github.com/FDH2/UxPlay.git v1.73 --squash

# Then build:
./gradlew :app:assembleDebug

# Install on a connected Android TV device:
./gradlew :app:installDebug
```

The Phase-1 build compiles without the subtree (the native lib becomes a stub). CI uses that path so the build stays green before the subtree lands.

## Install / sideload

`adb install app/build/outputs/apk/debug/app-debug.apk`

Open Tarmac on the TV. It advertises itself via Bonjour as `Tarmac` by default (configurable). On your Mac, open **Control Center → Screen Mirroring**; pick Tarmac; enter the on-screen PIN.

For extended desktop, open the Screen Mirroring submenu again while mirroring and choose **Use As Separate Display**.

## License

**GPL-3.0-only.** UxPlay is GPL-3.0, so any build that includes the subtree must also be distributed under GPL-3.0. The UI/service layers (Kotlin code in `app/`) are GPL-3.0 for consistency.

See [LICENSE](LICENSE) for the full text.

## Status

- [x] Phase 1 — Android TV scaffolding + native build skeleton
- [ ] Phase 2 — JNI bridge, MediaCodec video, AudioTrack audio, Bonjour
- [ ] Phase 3 — Leanback UX (pairing PIN, status, settings)
- [ ] Phase 4 — Extended display + AirPlay Video (ExoPlayer)
- [ ] Phase 5 — 4K HEVC + HDR10 tunneling, endurance hardening
