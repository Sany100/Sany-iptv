# SANY IPTV Android ↔ iPhone Feature Parity Checklist

Branch: `ios-bootstrap`  
Comparison sources inspected:
- Android: `app/src/main/java/com/sany/iptv/MainActivity.kt`
- iOS: `ios/SANYIPTV/AppDelegate.swift`
- iOS build config: `ios/project.yml`

This is a source-code audit, not a runtime/device certification. “Partial” means some implementation exists but parity or behavior has not been verified.

## Core app and navigation

| Android capability | iOS status | Evidence / remaining work |
|---|---|---|
| Home / Live TV navigation | Partial | iOS has Live TV tab; Android has richer home sections and bottom navigation. |
| Categories and channel lists | Partial | iOS parses groups and filters; compare sorting, category semantics and all view modes. |
| Inline channel search | Implemented (basic) | Search exists; test matching, empty states and focus behavior. |
| Channel card/list view modes | Missing / not verified | Android has view-mode dropdown and card/list renderers; iOS uses a table list. |
| Movies / Anime / Series sections | Missing / partial | Android has movie/anime classification and Series “coming soon”; iOS currently focuses on live channels. |
| TV remote / D-pad focus behavior | Missing / not verified | Android contains TV focus/key handling; iOS target is iPhone-only. |

## Playlist management

| Android capability | iOS status | Evidence / remaining work |
|---|---|---|
| Built-in SANY playlist | Partial | iOS has a built-in playlist URL. |
| Add playlist URL | Partial | Playlist store accepts URLs; verify Settings action and persistence. |
| Import M3U file | Partial | iOS document picker/import path exists; test provider formats and errors. |
| M3U/M3U8 parsing | Partial | Basic EXTINF/group/logo parsing exists; compare attributes, quoted commas, URL schemes and edge cases. |
| Playlist manager / switch playlist | Partial | Multiple remote/imported playlists can be selected and removed; active-source persistence and fallback on removal are implemented. Rapid switching during an in-flight request still needs testing. |
| Reload active playlist | Partial | Refresh/load exists; test offline/cache behavior. |
| Playlist caching / offline recovery | Implemented in source; CI/device validation pending | Successful remote M3U downloads are cached per URL; network/HTTP failure falls back to the saved cache. Imported playlists are stored locally and added to the saved list. |

## Checker

| Android capability | iOS status | Evidence / remaining work |
|---|---|---|
| Start playlist stream check | Partial | iOS CheckerViewController exists; compare probing method and timeout. |
| Live progress and percentage | Partial | iOS progress UI exists; verify per-channel progress and percentage accuracy. |
| Working/failed channel results | Partial | iOS checker records results; compare result list and status persistence. |
| Recheck non-working channels | Implemented in source; CI/device validation pending | A dedicated action re-probes saved Offline/Restricted/Error channel URLs while retaining previous Working results. |
| Checker result persistence | Implemented in source; latest build pending | iOS now saves per-channel Online/Offline status, timestamp and URL in UserDefaults, displays saved results and supports clearing/re-running. Validate after the current CI build and on device. |
| Checker-to-Live-TV progressive channel reveal | Missing / not verified | Android-specific progressive reveal behavior must be ported if still desired. |

## Playback

| Android capability | iOS status | Evidence / remaining work |
|---|---|---|
| HLS/live playback | Partial | AVPlayerViewController is used; validate HLS, redirects, headers, codecs and DRM limitations. |
| Auto Play ON/OFF | Implemented (basic) | UserDefaults preference is read before playback. |
| Video quality Auto/480p/720p/1080p | Partial | preferredPeakBitRate is set; this is a bitrate hint, not guaranteed exact resolution. |
| Hardware Decoder ON/OFF | No direct iOS equivalent | The fake switch was removed. The Settings row explains that AVPlayer selects a supported decoder automatically; there is no public hardware/software decoder switch. |
| Buffer Control Low/Balanced/High | Partial | preferredForwardBufferDuration is set; validate actual behavior and avoid promising exact buffer duration. |
| Auto Retry on Failure | Partial | Retry observer exists with limited retry count; compare all error paths and retry policy. |
| Playback speed | Partial | Speed preference exists; test controls and rate changes during playback. |
| Background video playback | Partial / platform-limited | Setting row exists; verify AVAudioSession/background modes and App Store policy. |
| Picture-in-Picture | Implemented in source; CI/device validation pending | Added a Picture-in-Picture setting and enabled AVPlayerViewController PiP when ON. Device/iOS support still requires testing; iOS 12 does not support iPhone PiP. |
| Fullscreen / channel navigation | Partial / not verified | AVPlayerViewController is used; compare adjacent-channel and orientation behavior. |

## EPG

| Android capability | iOS status | Evidence / remaining work |
|---|---|---|
| Auto-detect playlist EPG URL | Partial | EPGRepository detects playlist EPG metadata. |
| Built-in EPG sources | Partial | Settings lists three sources; compare exact feeds and UI. |
| Manual EPG URL | Partial / not verified | Verify editable URL flow exists and persists. |
| Update EPG data | Partial | EPGRepository update operation exists; verify XMLTV parsing, timezone and channel matching. |
| Daily/background refresh | Partial | BGTaskScheduler path is guarded for iOS 13+; iOS does not guarantee exact daily execution. |
| EPG programme guide UI | Implemented in source; CI/device validation pending | Reads XMLTV channel display-name mappings and programme entries; supports text search across channel/programme names and All/Now/Upcoming filters. |

## Settings and administration

| Android setting / feature | iOS status | Evidence / remaining work |
|---|---|---|
| Day / Night / System theme | Partial | iOS has theme storage/styles; verify System option and apply-to-all-screens. |
| Accent colour | Partial | iOS style supports several accents; compare Android choices and live refresh. |
| Low internet mode | Partial | iOS low-data preference affects Auto quality bitrate; compare all Android effects. |
| Reduce UI animations | Implemented in source; CI/device validation pending | Toggle disables/enables UIKit animations. Verify visual behavior and accessibility interaction on device. |
| Hardware Decoder | Missing / unsupported direct equivalent | Do not leave a misleading nonfunctional switch. |
| Buffer Control | Partial | Player hint exists. |
| Auto Retry | Partial | Limited retry implementation. |
| Auto update check | Partial / platform-limited | App Store is the only supported public update channel; automatic install is controlled by iOS/App Store settings. |
| Manual update check | Implemented in source; App Store listing required | Queries Apple App Store Lookup API by bundle ID, compares versions and opens the official Store listing. No result is expected until published under this bundle ID. |
| Maintenance mode | Partial | iOS reads maintenance.json and has an allow-list screen. Verify server schema, refresh and admin workflow. |
| Copy Device ID | Partial | iOS generates installation UUID; it is not a hardware ID. |
| About/version | Partial | Basic iPhone Edition row exists; match version/build and author information. |
| Crash report/logging | Missing / not verified | Android installs crash logger; iOS equivalent not confirmed. |

## Platform and release

| Requirement | iOS status | Evidence / remaining work |
|---|---|---|
| iPhone-only target | Implemented in config | TARGETED_DEVICE_FAMILY is 1. |
| iPhone 6 / iOS 12.5.8 baseline | Deployment target set to iOS 12.0 | Earlier iOS 12 target build passed; newest source changes still need CI and iOS 12 simulator/device validation. |
| iPad / Apple TV excluded | Implemented in config | Device family 1 only. |
| App Store signing/distribution | Not done | Current artifact is unsigned; requires signing, App Store Connect metadata and review. |
| Real-device validation | Not done | No physical iPhone test evidence. |

## Immediate priority queue

1. Build the iOS target with deployment target iOS 12.0 and resolve any availability/compiler errors.
2. Port missing Android screen/navigation features; validate playlist cache/offline recovery and checker result persistence.
3. Verify all settings against real iPhone playback: playback speed, Auto Play, quality bitrate hints, buffer hints, retry, background audio and PiP. Hardware decoder is informational because AVPlayer does not expose an equivalent public switch.
4. Validate EPG channel mapping/search, retry behavior, PiP and App Store version-check workflow.
5. Add automated tests for M3U parsing, playlist cache fallback, settings persistence, checker classifications/rechecks, XMLTV channel mapping and App Store version comparison. Automated test coverage remains.
6. Compare Android and iOS feature-by-feature with screenshots and behavior tests; update this checklist with verified results.
7. Sign and distribute through TestFlight/App Store only after device and review checks.

## Important limitations

- This checklist does not claim 100% parity. Latest source changes require passing GitHub Actions and real-device testing. App Store update detection cannot return a listing until the iOS app is published with bundle ID `com.sany.iptv.ios`.
- iOS AVPlayer does not provide a public switch equivalent to Android's ExoPlayer hardware decoder preference.
- iOS background tasks are system-scheduled and cannot promise exact daily execution.
- App Store updates must follow Apple's distribution rules; an Android-style APK self-updater cannot be copied directly.
- Build success alone does not prove playback compatibility, feature parity, or support on every iPhone model.
