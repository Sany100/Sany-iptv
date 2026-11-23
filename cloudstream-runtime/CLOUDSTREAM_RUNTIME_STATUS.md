# SANY CloudStream Runtime — implementation status

Pinned CloudStream source: `recloudstream/cloudstream@a28e6468f54cfe420feebd205d3d21d08ed0cde8`

Active branch: `migration/cloudstream-runtime-toolchain`

This file is the current implementation checkpoint. Older design notes in this directory are historical and may describe earlier states before the runtime integration was added.

## Implemented in the active branch

- The Android app includes the pinned `:cloudstream-library` module and depends on it directly. This supplies the real CloudStream `MainAPI`, response models, `BasePlugin`, `ExtractorApi`, extractor registry and provider API rather than app-local lookalike classes.
- SANY's Android `Plugin` host class provides the `load(Context)`, resources and settings members needed by the current provider scope. It is intentionally not the complete CloudStream app plugin host: app-only `VideoClickAction` integrations are not implemented.
- `SanyCloudStreamPluginRuntime` validates the installed private `.cs3` path, extracts `classes.dex` to app code-cache, makes the DEX read-only, loads it with `DexClassLoader`, invokes the manifest plugin class, runs the plugin lifecycle, discovers registered `MainAPI` providers and unregisters provider/extractor mappings on rollback/unload.
- The runtime exposes provider `search()`, `load()` and `loadLinks()`. Calling the real CloudStream `loadLinks()` method allows providers to use the pinned CloudStream extractor registry and plugin-registered extractors.
- The GitHub Anime index sync validates trusted SANY package URLs, SHA-256 metadata, package size, manifest and DEX presence before installation. Provider DEX execution is a separate step.
- The CloudStream playback adapter maps extractor URL, HLS/DASH/direct type, quality, provider headers and subtitle callback results to SANY's player-neutral request.
- The Media3 path accepts request headers, MIME hints and multiple subtitle configurations. Provider audio-language and quality hints are now forwarded into the Anime track selector instead of being discarded.

## Known limitations — not yet complete

- The SANY Android `Plugin` host is a scoped compatibility host, not the full CloudStream Android application plugin system. Plugins that depend on `VideoClickAction`, CloudStream UI/application services or other app-only APIs are not supported by this host.
- CloudStream `ExtractorLink.audioTracks` external audio-file entries are not yet adapted to Media3. Embedded audio tracks in the selected stream remain selectable through Media3.
- Subtitle-specific HTTP headers are not independently applied per subtitle track. The current Media3 data-source path uses the provider's shared request headers.
- ASS/SSA subtitle decoding is not verified. Mapping an ASS/SSA MIME label does not mean Media3 has a compatible decoder installed.
- Torrent/magnet links are not playable through the current HTTP(S)-only SANY playback entry point.
- No real-device test has yet proved that each of AniLight, Anichi, YugenAnime and WcofunProvider completes search → details → episodes → extractor resolution → Media3 playback. A successful compile is not that proof.

## Verification gates

| Gate | Required evidence | Current status |
|---|---|---|
| Pinned CloudStream library compiles | CI builds the exact pinned library | Build workflow checks this |
| Provider ABI compiles | Provider source compiles against pinned ABI | AniLight compile gate exists |
| SANY APK compiles | CI assembles Debug APK | Build workflow checks this |
| DEX load/register/unload | Android instrumentation/device test using a real `.cs3` | Open |
| Provider search/details/episodes | Per-provider integration test with real or deterministic fixtures | Open |
| Extractor resolution | Each provider's real host/extractor returns a supported stream | Open |
| Media3 playback | Real stream decodes on Android device | Open |
| Subtitle/audio behavior | Language, headers and track selection tested on device | Open |

Do not label a provider fully working until its provider-specific runtime and playback gates pass.
