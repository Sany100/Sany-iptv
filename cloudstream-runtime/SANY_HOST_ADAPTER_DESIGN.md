# SANY CloudStream Host Adapter Design

Pinned upstream: `recloudstream/cloudstream@a28e6468f54cfe420feebd205d3d21d08ed0cde8`

Status: architecture/design checkpoint only. This document does not claim that
CloudStream plugin DEX is executable in SANY.

## 1. What the current app actually contains

The app source audit confirms three separate layers:

1. `SanyExtensionRuntime.kt` defines SANY-native DTOs
   (`SanyMediaItem`, `SanyVideoSource`), a `SanyExtensionProvider`
   interface/registry, and a CloudStream repository/package downloader.
   Its `.cs3` path verifies HTTPS downloads, size, SHA-256, manifest and DEX
   presence. It explicitly does not load or execute DEX.
2. `MainActivity.renderAniLightNative()` opens the built-in
   `AniLightNativeScreen`. Its callback passes the resolved URL and request
   headers into the existing `play(Channel(...), ...)` path.
3. `MainActivity.play()` already creates Media3's
   `DefaultHttpDataSource.Factory`, applies provider request headers,
   follows cross-protocol redirects, detects HLS/DASH MIME types, and starts
   the existing ExoPlayer. This is a useful playback endpoint, but it is not
   a CloudStream ABI or plugin loader.

The native AniLight screen and the downloadable CloudStream `.cs3` plugin
must not be conflated: the former can call SANY directly; the latter cannot
currently be loaded.

## 2. Required architecture

Keep the runtime behind a separate module boundary. Do not place partial
CloudStream classes in the SANY app package and do not make the current
`SanyExtensionProvider` pretend to be `MainAPI`.

### A. Exact CloudStream ABI module

Create an Android library module (proposed name: `:cloudstream-host`) pinned
to the same upstream revision as the plugin build. It must compile the actual
upstream ABI and matching dependency versions, not hand-written classes with
matching names.

First target is the AniLight provider only:
- exact plugin manifest model and Android `Plugin` base class;
- exact `BasePlugin` registration/lifecycle contract;
- provider-facing `MainAPI` descriptors and the response/model/builder types
  AniLight actually links against;
- the upstream `ExtractorApi` type required by the public
  `BasePlugin.registerExtractorAPI` descriptor, even if AniLight registers no
  built-in extractor;
- compatible HTTP client, JSON parsing, coroutine dispatchers and logging
  services used by the provider.

Before changing AGP/Kotlin/compileSdk for the whole app, run this module as an
isolated compile experiment. If exact upstream dependencies cannot be built
with the current toolchain, record the required toolchain migration and test it
on a separate branch. Do not silently downgrade/replace ABI dependencies.

### B. Plugin loader and lifecycle

Only after the ABI module compiles:
1. Verify repository trust, HTTPS origin, declared size, SHA-256, API version,
   manifest and DEX entry.
2. Load the plugin with a dedicated `PathClassLoader` whose parent is the
   host ABI classloader. Do not use the SANY app classloader as an accidental
   substitute.
3. Instantiate only the manifest-declared class after checking it is a
   subclass of the exact host `Plugin` class.
4. Invoke the upstream load lifecycle and collect provider/extractor
   registrations in a plugin-scoped registry.
5. Isolate provider exceptions; reject duplicate IDs and unsupported API
   versions.
6. On unload, invoke `beforeUnload`, unregister every provider/extractor,
   close owned resources where supported, and drop all references to the
   plugin instance and classloader.

A `.cs3` file is executable code with the app's permissions. The feature must
remain disabled for untrusted repositories and must not auto-execute a package
just because it was downloaded.

### C. SANY provider-to-player adapter

Do not let CloudStream model classes leak into `MainActivity`. Add a narrow
host DTO at the module boundary with:
- display title and resolved stream URI;
- stream type/container or MIME hint;
- request headers (including provider-specific User-Agent/Referer/Cookie);
- quality and audio-language metadata;
- zero or more subtitle entries, each with URI, MIME type and language.

The adapter maps a CloudStream `ExtractorLink` callback plus subtitle
callbacks into this DTO. The UI then passes the DTO to a single SANY playback
entry point, which delegates to the existing Media3 player.

Current playback code already accepts a header map and handles HLS/DASH.
However, its `play()` signature currently accepts only one optional subtitle
URL, and that path assumes WebVTT. Before CloudStream playback is enabled,
extend the player boundary to support multiple subtitle configurations with
their actual MIME types/languages, while preserving existing Anime subtitle
defaults and all existing Live TV/Movies playback behavior.

### D. AnimeMovie mapping

For AniLight:
- search and homepage results map to SANY anime/movie cards;
- series details map to ordered episodes;
- `AnimeMovie` details map to one movie item, not a synthetic multi-episode
  season;
- `loadLinks` must return a real playable URI and headers before the player
  adapter is called.

The provider's current movie episode-number/language fallback is still an
assumption until verified against a live movie API response. Do not mark this
step complete based only on a successful `.cs3` compile or DEX inspection.

## 3. Incremental implementation gates

| Gate | Deliverable | Pass condition |
|---|---|---|
| 0 | Host inventory (this audit) | Existing SANY loader/player boundaries documented |
| 1 | Isolated exact-ABI compile module | Unmodified AniLight provider compiles against pinned upstream ABI |
| 2 | Host service bridge | Provider homepage/search/details/episodes work in instrumented tests |
| 3 | Plugin DEX loader | Actual AniLight `.cs3` loads and unloads without linkage errors |
| 4 | Player DTO/adapter | Headers, HLS/DASH, subtitles and quality map correctly |
| 5 | Live Movie test | Real AniLight AnimeMovie stream resolves and plays on device |
| 6 | Regression | Live TV, SANY Movies, Anime series and Android minSdk 23 remain stable |

Do not skip gates or describe a future gate as implemented.

## 4. Immediate next coding task

Start Gate 1 only: create the isolated Android library module and prove it can
compile the exact upstream ABI/dependency slice against the unmodified AniLight
plugin. Keep it out of the app runtime until the compile and dependency tests
pass. No DEX loading, automatic plugin execution, or player UI change belongs
in Gate 1.

## 5. Explicit current limitations

- `SanyExtensionProvider` is a SANY-native contract, not CloudStream binary ABI.
- Current `.cs3` handling is download/verification only.
- No CloudStream plugin DEX loader or lifecycle host is integrated.
- No CloudStream `ExtractorLink` to Media3 adapter is integrated.
- Live AniLight movie source resolution and on-device playback are not verified.
