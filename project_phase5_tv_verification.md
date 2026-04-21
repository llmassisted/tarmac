# Phase 5 TV Verification Tracker

Tracks what is implemented in code vs. what still needs real-hardware validation.
Updated: 2026-04-21

---

## Phase 5a — 4K HEVC + HDR10 static metadata  ✅ landed

| Item | Status | Notes |
|------|--------|-------|
| Lazy codec configure (buffer until SPS/SEI seen) | ✅ landed | `VideoPipeline.bufferForConfig()` / `MAX_PRECONFIG_SUBMITS=16` |
| HEVC SPS resolution parse | ✅ landed | `HevcBitstream.parse()` |
| HDR10 SEI 137/144 parse → `KEY_HDR_STATIC_INFO` | ✅ landed | `HevcBitstream.hdrStaticInfo` |
| Display HDR10 capability gate | ✅ landed | `DisplayCapabilities.supportsHdr10` |
| Display 4K mode gate for `KEY_MAX_WIDTH/HEIGHT` | ✅ landed | `DisplayCapabilities.supports4k` |
| **Needs hardware**: HDR10 tone-mapping on Shield/CCGTV | ⏳ pending | Verify display shows HDR badge; check `dumpsys SurfaceFlinger` for HDR layer |
| **Needs hardware**: 4K HEVC frame rate at 3840×2160 | ⏳ pending | Log `VideoPipeline` configured res; measure decode jank with systrace |

---

## Phase 5b — Bonjour 4K/HDR feature-bit advertisement  🟡 scaffolded, bits unverified

| Item | Status | Notes |
|------|--------|-------|
| `FeatureBits` composable class (replaces `FEATURES_DEFAULT` Long) | ✅ landed | `BonjourAdvertiser.kt` top-level class; named bits 7, 14, 27 verified |
| `DisplayCapabilities` data class + `probe()` helper | ✅ landed | `com.tarmac.media.DisplayCapabilities` |
| `DisplayCapabilities` threaded from `TarmacService` → `BonjourAdvertiser.start()` | ✅ landed | `displayCaps` named param in `start()` |
| Candidate 4K bit (`CANDIDATE_VIDEO_4K`, bit 17?) | 🟡 scaffolded | Commented out in `FeatureBits`; **needs Mac Wireshark capture to verify bit position** |
| Candidate HDR10 bit (`CANDIDATE_HDR10`, bit 38?) | 🟡 scaffolded | Commented out in `FeatureBits`; **needs Mac Wireshark capture to verify bit position** |
| **Needs hardware**: Wireshark capture of real Apple TV 4K mDNS TXT record | ⏳ pending | Compare `ft=` value; identify which bits flip when TV supports 4K/HDR |
| **Needs hardware**: Confirm Mac negotiates 4K resolution after advertising bits | ⏳ pending | After bit positions confirmed, un-comment ORs in `BonjourAdvertiser.start()` |

### How to verify candidate bits
1. Run Wireshark on the same LAN as a real Apple TV 4K (or Apple TV 4K simulator)
2. Filter `dns` and look for `_airplay._tcp` / `_raop._tcp` PTR/TXT records
3. Extract the `ft=` value; compare against a 1080p Apple TV (2nd/3rd gen)
4. The delta bits = candidate 4K/HDR advertisement bits
5. Set them in `FeatureBits` and un-comment the `+` lines in `start()`

---

## Phase 5b — Tunneled playback + audio session pairing  🟡 scaffolded

| Item | Status | Notes |
|------|--------|-------|
| `AudioPipeline.audioSessionId` property | ✅ landed | `track?.audioSessionId ?: 0` |
| `AirPlayJni.audioSessionId` global field | ✅ landed | Set by `TarmacService` after `AudioPipeline.start()` |
| `VideoPipeline` accepts `audioSessionId: Int` constructor param | ✅ landed | Threaded from `MirrorActivity` via `AirPlayJni.audioSessionId` |
| `MediaCodecList` probe for `FEATURE_TunneledPlayback` (HEVC only) | ✅ landed | `tryTunneledConfigure()` in `VideoPipeline` |
| `KEY_AUDIO_SESSION_ID` + `KEY_FEATURE_TUNNELED_PLAYBACK` set on HEVC format | ✅ landed | Inside `tryTunneledConfigure()` |
| Graceful fallback on `IllegalStateException` / `CodecException` | ✅ landed | Returns `null` → `configureStandard()` called |
| **Needs hardware**: NVIDIA Shield — tunneled HEVC active + A/V in sync | ⏳ pending | LogTag `VideoPipeline`: look for "Tunneled HEVC active" in logcat |
| **Needs hardware**: Chromecast with Google TV — tunneled probe result | ⏳ pending | CCGTV may report support but fail configure; verify fallback path is clean |
| **Needs hardware**: Fire TV Cube — tunneled probe result | ⏳ pending | Some Amlogic-based devices have partial tunneled support |
| **Needs hardware**: A/V sync quality vs. non-tunneled baseline | ⏳ pending | Use audio-tap app to measure drift before/after; target < 5ms average |

### Logcat signals to look for
```
VideoPipeline: Tunneled HEVC active (audioSessionId=NNN)     ← good path
VideoPipeline: No HEVC tunneled-playback decoder on this device   ← expected on H.264-only devices
VideoPipeline: Tunneled HEVC configure rejected (...); falling back to non-tunneled  ← fallback triggered
```

---

## CI / build

| Item | Status |
|------|--------|
| `./gradlew :app:assembleDebug` | Passes on CI; not runnable in this sandbox (Google Maven blocked) |
| `graphify update .` | `graphify` not installed in sandbox; run manually after next merge |
