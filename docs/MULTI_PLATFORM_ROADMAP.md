# SANY IPTV iPhone support roadmap

## Scope

Build an iPhone version only. iPad and Apple TV are explicitly out of scope. Keep the existing Android app working and in the same GitHub repository.

## Target iPhone range

- Oldest target: iPhone 6 running iOS 12.5.8.
- Newest requested target: iPhone 18 Pro Max.
- Support must be verified by iOS version and device testing, not inferred from model names alone.
- iPhone 6 is limited to the iOS 12 generation; it cannot run apps that require iOS 13 or later. Apple lists iOS 12.5.8 updates for legacy devices including iPhone 6. See https://support.apple.com/en-us/118387.
- To retain iPhone 6 compatibility, start with a UIKit-based iOS app and set the deployment target to iOS 12. SwiftUI cannot be the only UI framework because it requires iOS 13 or later.
- Use AVPlayer for native HLS playback, while validating supported stream formats and codecs on the oldest device.

## Current implementation (audited)

- The existing app is a native Android app written in Kotlin.
- Android UI uses Android Views and AndroidX AppCompat.
- Video playback uses AndroidX Media3 / ExoPlayer.
- Android-specific integrations include Activity lifecycle, SharedPreferences, Android file picker, Picture-in-Picture, Android TV Leanback detection, and Android system APIs.
- There is currently no iOS/Swift project in this repository.
- The Android APK cannot be installed on an iPhone. The iPhone version needs its own iOS build (IPA distributed through Apple's supported signing, TestFlight, or App Store processes).

## Delivery model

One SANY IPTV repository and product, with separate native platform builds:

| Platform | Deliverable | Implementation |
| --- | --- | --- |
| Android phones/tablets/Android TV/TV boxes | APK / AAB | Existing Kotlin Android app |
| iPhone 6 through iPhone 18 Pro Max | iOS app | Swift + UIKit (iOS 12-compatible baseline) + AVPlayer |

A single repository and shared product experience are achievable; one installable binary for Android and iOS is not.

## iPhone feature parity

- [ ] SANY IPTV branding and matching visual design
- [ ] M3U/M3U8 playlist URL import
- [ ] Local playlist file import using iOS document picker
- [ ] Channel list, categories, and search
- [ ] Stream checking / channel status
- [ ] Favorites
- [ ] HLS playback and playback controls
- [ ] EPG
- [ ] Playback quality and buffering preferences where supported by the stream
- [ ] Maintenance mode and remote announcements
- [ ] Safe update/version notices
- [ ] Touch-first navigation for all supported iPhone screen sizes
- [ ] Memory/performance tuning for iPhone 6
- [ ] Verify playback and UI on iPhone 6 and representative newer devices through iPhone 18 Pro Max

## Engineering and release plan

1. Preserve the current Android project and its existing features.
2. Add a separate iOS project/target in this repository; do not try to package Android Kotlin code as an iPhone app.
3. Reimplement the iPhone UI and platform integrations in Swift/UIKit. Reuse the same design system, data formats, playlist parsing rules, and backend endpoints where practical.
4. Define shared data contracts for playlists, categories, favorites, EPG, playback preferences, maintenance status, and update announcements.
5. Use AVPlayer for HLS and test real-world playlist URLs, redirects, headers, codecs, and older-device performance.
6. Add iOS build automation on macOS runners. Apple signing, provisioning, TestFlight, or App Store distribution requires the appropriate Apple developer setup.
7. Test on physical devices and supported iOS versions. A successful compile alone does not establish compatibility.

## Release status

iPhone support is **planned, not implemented**. Android remains the only implemented client at this point. Do not advertise iPhone compatibility or distribute an iOS build until the iOS target has been implemented, signed, and tested.