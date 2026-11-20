# SANY IPTV multi-platform roadmap

## Goal

Deliver SANY IPTV across Android phones/tablets, Android TV/TV boxes, iPhone, iPad, and Apple TV with a consistent visual identity, shared playlist/account data, and equivalent core IPTV features.

## Current implementation (audited)

- The existing application is a native Android app written in Kotlin.
- Android UI is built with Android Views and AndroidX AppCompat.
- Video playback uses AndroidX Media3 / ExoPlayer.
- Android-specific integrations include Android Activity lifecycle, SharedPreferences, Android file picker, Android Picture-in-Picture, Android TV Leanback detection, and Android system APIs.
- There is currently no Flutter, Kotlin Multiplatform, Swift, iOS, or tvOS project in the repository.
- The current Android APK cannot be installed on iOS or Apple TV.

## Target delivery model

Keep one GitHub repository and one SANY IPTV product, with platform-specific installable builds:

| Platform | Deliverable | UI / playback technology |
| --- | --- | --- |
| Android phone / tablet | APK / AAB | Existing Kotlin app initially |
| Android TV / TV box | Android APK / AAB | Existing Kotlin app with D-pad navigation |
| iPhone / iPad | iOS app (IPA via Xcode signing / TestFlight / App Store) | SwiftUI and AVPlayer |
| Apple TV | tvOS app | SwiftUI tvOS interface and AVPlayer |

An APK, iOS IPA, and tvOS app are different platform binaries. A single repository and shared product experience are achievable; one binary for every operating system is not.

## Recommended implementation architecture

1. Preserve the working Android application while the Apple versions are developed.
2. Define platform-neutral product behavior and data contracts for playlists, channel groups, favorites, EPG, playback preferences, maintenance status, and app update announcements.
3. Implement iOS and iPadOS with SwiftUI, AVPlayer, native document import, and touch-first navigation.
4. Implement tvOS as a dedicated target using SwiftUI focus navigation and the Apple TV remote. Reuse the same data and playback services where supported, but adapt layouts for television.
5. Move shared remote configuration and user data to a documented backend/API so settings and favorites can synchronize across platforms. Do not embed private admin credentials or signing secrets in client apps.
6. Add separate CI build jobs: Android APK/AAB on Android runners and iOS/tvOS builds on macOS runners. Apple distribution requires Apple signing credentials and provisioning.
7. Test playlist import, HLS playback, EPG, search, favorites, maintenance mode, update notices, remote/touch navigation, and background/PiP behavior on real devices.

## Feature parity checklist

- [ ] Shared SANY IPTV branding and visual design
- [ ] M3U/M3U8 URL and file import
- [ ] Channel list, categories, and search
- [ ] Stream checking / channel status
- [ ] Favorites
- [ ] HLS playback and playback controls
- [ ] EPG
- [ ] Playback quality and buffering preferences
- [ ] Maintenance mode and remote announcements
- [ ] Cross-device account/settings synchronization
- [ ] Touch navigation for iPhone/iPad
- [ ] Siri Remote / focus navigation for Apple TV
- [ ] Platform-specific build and release automation

## Release status

Apple platform support is **planned, not implemented**. The existing Android app remains the only implemented client until the iOS and tvOS targets are built and tested. Do not advertise iPhone, iPad, or Apple TV compatibility before those builds pass device testing and are distributed through Apple's supported channels.
