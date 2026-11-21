# Development feature integration validation

This branch is based on the current `main` commit for isolated Android build and release-signing validation.

The audit found that the required development Android features are already present in `main` from the earlier integration. The following `main`-specific safeguards are intentionally retained and must not be overwritten by `development`:

- Mandatory update remains disabled when the remote update configuration has `enabled: false`.
- Production release workflow remains manual-only on `main`.
- Production signing and release version configuration remain from `main`.
- The fuller `main` playlist is retained; the older/smaller development playlist is not copied over it.
- `FtpDataSource.kt` is identical between `main` and `development`.

No production release is published by this validation branch.
