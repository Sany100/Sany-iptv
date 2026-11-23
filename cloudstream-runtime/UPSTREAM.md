# CloudStream Runtime Port

## Pinned upstream

- Project: https://github.com/recloudstream/cloudstream
- License: GPL-3.0
- Upstream commit: a28e6468f54cfe420feebd205d3d21d08ed0cde8
- Upstream snapshot date: 2026-11-22

The upstream revision is pinned to avoid silently following incompatible API changes.

## Integration scope

SANY keeps its existing Android UI, native Live TV implementation and Media3 player. The port targets CloudStream's plugin ABI and runtime services so compatible `.cs3` packages can be loaded and their providers/extractors can be called by SANY.

Required pieces include, at minimum:

1. Matching `BasePlugin`, plugin manifest and plugin API ABI.
2. Matching `MainAPI`, response models, `APIHolder` and provider registration.
3. Matching `ExtractorApi` registry and extractor invocation services.
4. Android DEX class loading, plugin lifecycle, unload and failure isolation.
5. Host HTTP, serialization, coroutine and logging services required by providers.
6. A SANY adapter from CloudStream provider responses to the existing Anime UI and Media3 player.
7. Tests using the AniLight Anime-only provider: load, register, search, details, episodes, source extraction and unload.

## Compatibility and safety notes

- The current SANY `SanyExtensionRepositories` code downloads and validates `.cs3` files; it does not execute them.
- Do not enable DEX execution until the matching ABI and host services are present.
- CloudStream plugins are executable third-party code. Keep loading restricted to explicitly installed/trusted repositories and retain package hash verification.
- Preserve SANY's current player/UI integration; do not import CloudStream's app UI or replace SANY's Media3 player as part of this port.
- Do not claim full CloudStream compatibility until the AniLight end-to-end tests pass on Android.

## GPL-3.0 compliance

This port is based on GPL-3.0-licensed CloudStream code. When distributing a combined/modified application, retain the upstream copyright and license notices, include the complete GPL-3.0 license text, identify modifications and provide the corresponding source code under GPL-3.0. Track and retain notices for all separately licensed dependencies. This file is provenance documentation, not a substitute for the license text or corresponding-source obligations.
