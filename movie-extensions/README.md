# SANY Movie Extensions

This directory contains the Movie-only extension catalogue for SANY IPTV.

Movie extensions are managed independently from Anime extensions. The Android app
uses `SanyMovieExtensionManager` to read this index, verify package SHA-256,
install/update/uninstall CloudStream-compatible `.cs3` packages, and load them
through the existing SANY CloudStream runtime. Movie streams are resolved through
the existing SANY Media3 player.

## Repository index schema

The index is UTF-8 JSON:

```json
{
  "schema": 1,
  "extensions": [
    {
      "id": "sample-movie-provider",
      "name": "Sample Movie Provider",
      "version": "1.0.0",
      "description": "Example Movie catalogue provider",
      "packageUrl": "https://example.com/sample.cs3",
      "sha256": "64-character-lowercase-sha256-hex"
    }
  ]
}
```

- `id` must be a stable lowercase identifier.
- `packageUrl` must use HTTPS and point to a CloudStream-compatible `.cs3` file.
- `sha256` must match the exact package bytes. SANY refuses packages with a
  mismatched or missing checksum.
- Increment `version` when publishing an update.
- The extension package must expose a CloudStream `MainAPI` provider whose
  loaded title responses are `MovieLoadResponse` objects.

Do not put Anime providers in this index. Anime extension repositories and
preferences are intentionally separate.

## Current implementation status

The app-side repository catalogue, checksum-verified installer/updater,
rollback-on-load-failure, installed-extension list, uninstall action, Movie
search entry point, MovieLoadResponse validation, and handoff to SANY's existing
player are implemented. The official catalogue is initially empty until a
Movie provider package is published and added here.
