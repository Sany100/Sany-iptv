# SANY IPTV

SANY IPTV is a native Android IPTV player for user-provided M3U/M3U8 playlists. An iPhone version is planned as a separate native iOS app; it is not implemented yet.

## Android device compatibility

- One Android application ID: `com.sany.iptv`
- One universal Android APK build for compatible Android phones, tablets, Android TV devices, and TV boxes.
- Minimum Android version: Android 6.0 (API 23).
- The launcher supports standard Android launchers and Android TV / Leanback launchers.
- The interface detects Android TV and enables TV-oriented sizing, focus styling, and remote-key handling.
- Actual playback and remote behavior depend on device hardware, codecs, Android version, and remote implementation; test on target devices before distribution.

## Planned iPhone support

- Target range: iPhone 6 through iPhone 18 Pro Max.
- Oldest-device baseline: iOS 12.5.8 / iPhone 6.
- The iPhone app will be a separate iOS build, not an Android APK.
- To support iPhone 6, the iOS client must use an iOS 12-compatible UIKit implementation rather than depending exclusively on SwiftUI (iOS 13+).
- Planned playback engine: AVPlayer, subject to stream/codec compatibility testing.
- iPad and Apple TV are not in scope.
- iPhone support is planned only; no iOS app is currently available.

See [the iPhone roadmap](docs/MULTI_PLATFORM_ROADMAP.md) for implementation and testing requirements.

## Android features

- Import playlists from a URL or local file
- Stream playback with AndroidX Media3 ExoPlayer
- Search channels and filter by group/category
- Long-press channels to add or remove favorites
- Remembers the last playlist URL
- TV remote / D-pad focus support

## Android build

Requires JDK 17, Android SDK 35, and Gradle 8.9 or newer (the GitHub Actions workflow uses Gradle 8.14.4).

Build the universal Android APK:

- Debug: `gradle assembleDebug`
- Release: `gradle assembleRelease`

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds this APK on pushes to `main`. A signed release APK is published when the release workflow has the required signing secrets configured.

Use only playlists and streams you are authorized to access.


## Update policy

- Mandatory updates are disabled by default and are enabled only when explicitly requested by the project owner.
- When the remote mandatory-update policy is disabled, the app clears any previously cached mandatory-update prompt.


## Android TV edition (separate build)

- The TV edition is built from the stable `v1.2.452` release on the isolated `tv/v1.2.452-base` branch.
- Build variant: `tvDebug` / `tvRelease`.
- Package ID: `com.sany.iptv.tv` (installs separately from the phone app).
- Minimum Android version: Android 10 (API 29).
- TV launcher entry requires Leanback; touchscreen is optional.
- Build locally with JDK 17: `gradle assembleTvDebug`.
- The TV workflow uploads a debug APK artifact for remote and low-memory device testing.
