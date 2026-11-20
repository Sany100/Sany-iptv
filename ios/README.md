# SANY IPTV iOS

This folder contains the first native iPhone app target for SANY IPTV.

## Current baseline
- Native UIKit application (not a web wrapper).
- iPhone target only: `TARGETED_DEVICE_FAMILY = 1`.
- Minimum deployment target: iOS 12.0, chosen to include supported iPhone 6 devices running iOS 12.5.8.
- Initial app shell only; IPTV features are not implemented in this bootstrap commit yet.

## Build
The GitHub Actions workflow at `.github/workflows/ios.yml` builds the app for the iOS Simulator on a GitHub-hosted macOS runner and uploads the resulting `.app` as an artifact.

This simulator artifact is for CI validation; it is not an installable iPhone IPA. A device IPA requires an iOS device build plus Apple code signing and provisioning. No signing credentials are stored in this repository.

## Next implementation stages
1. Recreate the Android app's navigation and visual design in UIKit.
2. Add M3U/M3U8 import, channel categories, search, favorites, and checker.
3. Add HLS playback using AVPlayer and validate supported codecs/streams.
4. Add EPG and playback preferences.
5. Configure protected Apple signing secrets only when device distribution is requested.


The unsigned device IPA packaging workflow is available as `.github/workflows/ios-unsigned-ipa.yml` and can be started from the Actions tab. The output is unsigned and cannot be installed on a physical iPhone until it is signed and provisioned.
