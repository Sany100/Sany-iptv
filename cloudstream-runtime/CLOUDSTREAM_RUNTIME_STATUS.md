# SANY CloudStream Runtime — implementation status

Pinned CloudStream source: `recloudstream/cloudstream@a28e6468f54cfe420feebd205d3d21d08ed0cde8`

Active branch: `migration/cloudstream-runtime-toolchain`

Latest verified build: workflow run `45130226716` on commit `3190a1b1719d03ced254fd9373a5cd2157cb8423` completed successfully (16 successful steps, no failed steps). Artifact: `sany-cloudstream-library-debug`. This is compile/package evidence, not device playback evidence.

This file is the current implementation checkpoint. Older design notes in this directory are historical and may describe earlier states before the runtime integration was added.

## Implemented in the active branch

- The Android app includes the pinned `:cloudstream-library` module and depends on it directly. This supplies the real CloudStream `MainAPI`, response models, `BasePlugin`, `ExtractorApi`, extractor registry and provider API rather than app-local lookalike classes.
- SANY's Android `Plugin` host class provides the `load(Context)`, resources and settings members needed by the current provider scope. It is intentionally not the complete CloudStream app plugin host: app-only `VideoClickAction` integrations are not implemented.
- `SanyCloudStreamPluginRuntime` validates the installed private `.cs3` path, extracts `classes.dex` to app code-cache, makes the DEX read-only, loads it with `DexClassLoader`, invokes the manifest plugin class, runs the plugin lifecycle, discovers registered `MainAPI` providers and unregisters provider/extractor mappings on rollback/unload.
- The runtime exposes provider `search()`, `load()` and `loadLinks()`. Calling the real CloudStream `loadLinks()` method allows providers to use the pinned CloudStream extractor registry and plugin-registered extractors.
- The GitHub Anime index sync validates trusted SANY package URLs, SHA-256 metadata, package size, manifest and DEX presence before installation. Provider DEX execution is a separate step.
- The CloudStream playback adapter maps extractor URL, HLS/DASH/direct type, quality, provider headers and subtitle callback results to SANY's player-neutral request.
- The Media3 path accepts request headers, MIME hints and multiple subtitle configurations. Provider audio-language and quality hints are forwarded into the Anime track selector.
- CloudStream `ExtractorLink.audioTracks` HTTP(S) entries are adapted to SANY external-audio track metadata and merged with the video source through Media3 `MergingMediaSource`. Per-track headers and user-agent metadata are retained for those audio sources.
- Subtitle-specific HTTP headers are copied from CloudStream subtitle entries and applied to the matching subtitle URI through Media3's resolving data source, rather than relying only on shared provider headers.

## Known limitations — not yet complete

- The SANY Android `Plugin` host is a scoped compatibility host, not the full CloudStream Android application plugin system. Plugins that depend on `VideoClickAction`, CloudStream UI/application services or other app-only APIs are not supported by this host.
- External audio track mapping and source merging compile in the latest CI run, but actual external audio selection/playback and language labeling still require real-device verification. Some providers may not supply useful audio language metadata.
- Subtitle-specific headers are implemented and compile in CI, but authenticated subtitle retrieval and track rendering still require device/provider testing.
- ASS/SSA subtitle decoding is not verified. Mapping an ASS/SSA MIME label does not mean Media3 has a compatible decoder installed.
- Torrent/magnet links are not playable through the current HTTP(S)-oriented SANY playback path.
- No real-device test has yet proved that each of AniLight, Anichi, YugenAnime and WcofunProvider completes search → details → episodes → extractor resolution → Media3 playback. A successful compile is not that proof.
- The current provider ABI compile gate validates AniLight; it does not establish that every listed provider is ABI-compatible.

## Verification gates

| Gate | Required evidence | Current status |
|---|---|---|
| Pinned CloudStream library compiles | CI builds the exact pinned library | Passed in workflow run 45130226716 |
| Provider ABI compiles | Provider source compiles against pinned ABI | AniLight gate passed in workflow run 45130226716 |
| SANY APK compiles | CI assembles Debug APK | Passed in workflow run 45130226716 |
| DEX load/register/unload | Android instrumentation/device test using a real `.cs3` | Open |
| Provider search/details/episodes | Per-provider integration test with real or deterministic fixtures | Open |
| Extractor resolution | Each provider's real host/extractor returns a supported stream | Open |
| Media3 playback | Real stream decodes on Android device | Open |
| Subtitle/audio behavior | Language, headers and track selection tested on device | Open |

Do not label a provider fully working until its provider-specific runtime and playback gates pass.
