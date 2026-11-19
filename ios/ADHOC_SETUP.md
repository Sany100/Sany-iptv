# SANY IPTV iOS Ad Hoc signing setup

The workflow `.github/workflows/ios-adhoc.yml` is prepared, but it cannot produce an installable IPA until Apple signing assets exist.

## Required Apple-side setup
1. Enroll in the Apple Developer Program (or use an eligible organization membership).
2. Register each iPhone's UDID in the Apple Developer account.
3. Create an iOS App Development/Ad Hoc distribution signing certificate and an Ad Hoc provisioning profile for bundle ID `com.sany.iptv.ios`, including the registered device(s).
4. Export the certificate and its private key together as a password-protected `.p12` file, and download the `.mobileprovision` profile.

Ad Hoc IPA files only install on devices included in that profile. A new iPhone or changed UDID requires an updated profile and a new IPA.

## GitHub Actions secrets
In the repository, open Settings → Secrets and variables → Actions → New repository secret. Add:

- `IOS_CERTIFICATE_BASE64`: Base64 text of the complete `.p12` file.
- `IOS_CERTIFICATE_PASSWORD`: password used to protect that `.p12`.
- `IOS_PROVISIONING_PROFILE_BASE64`: Base64 text of the complete `.mobileprovision` file.
- `IOS_TEAM_ID`: Apple Developer Team ID (not the Apple ID email).

Never put certificates, passwords, private keys, or provisioning files in source code, issues, or chat messages.

## Build
After all four secrets are saved, open Actions → iOS Ad Hoc IPA → Run workflow. A successful run publishes the `SANY-IPTV-AdHoc-IPA` artifact. Download the IPA on the registered iPhone and install it using a compatible signing/installation method. The current workflow does not bypass Apple's device registration or signing requirements.

The iOS Simulator workflow is separate and does not create an installable iPhone IPA.
