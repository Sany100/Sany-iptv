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

The project builds two separate APK variants:

- Mobile: `gradle assembleMobileDebug` or `gradle assembleMobileRelease`
- Android TV: `gradle assembleTvDebug` or `gradle assembleTvRelease`

The TV edition has its own application ID (`com.sany.iptv.tv`), a Leanback launcher entry, and a two-column channel-card layout with remote-control focus navigation. The existing mobile application ID (`com.sany.iptv`) and phone layout remain unchanged.

GitHub Actions builds both variants and publishes both signed APKs when the Android release workflow succeeds.

Use only playlists and streams you are authorized to access.
