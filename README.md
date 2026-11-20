# SANY IPTV

SANY IPTV is a single-APK Android IPTV player for user-provided M3U/M3U8 playlists.

## Device compatibility

- One application ID: `com.sany.iptv`
- One universal APK build (no separate mobile/TV application variants).
- Minimum Android version: Android 6.0 (API 23).
- The launcher supports both standard Android launchers and Android TV / Leanback launchers.
- The interface detects Android TV and enables TV-oriented sizing, focus styling, and remote-key handling.
- Intended for compatible Android phones, tablets, Android TV devices, and TV boxes. Actual playback and remote behavior depend on each device's Android version, hardware, codecs, and remote implementation; test on target devices before distribution.

## Features

- Import playlists from a URL or local file
- Stream playback with AndroidX Media3 ExoPlayer
- Search channels and filter by group/category
- Long-press channels to add or remove favorites
- Remembers the last playlist URL
- TV remote / D-pad focus support

## Build

Requires JDK 17, Android SDK 35, and Gradle 8.9 or newer (the GitHub Actions workflow uses Gradle 8.14.4).

Build the single universal APK:

- Debug: `gradle assembleDebug`
- Release: `gradle assembleRelease`

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds this universal APK on pushes to `main`. A signed release APK is published when the release workflow has the required signing secrets configured.

Use only playlists and streams you are authorized to access.
