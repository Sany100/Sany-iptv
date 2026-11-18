# SANY IPTV

Android IPTV player for user-provided M3U/M3U8 playlists.

## Features
- Import playlists from a URL or local file
- Stream playback with AndroidX Media3 ExoPlayer
- Search channels and filter by group/category
- Long-press channels to add or remove favorites
- Remembers the last playlist URL
- GitHub Actions builds a debug APK on pushes to main

## Build
Requires JDK 17, Android SDK 35, and Gradle 8.9.

Run `gradle assembleDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Use only playlists and streams you are authorized to access.
