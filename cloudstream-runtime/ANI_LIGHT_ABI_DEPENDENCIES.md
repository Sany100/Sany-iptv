# AniLight anime-only runtime ABI dependency map

Pinned upstream: https://github.com/recloudstream/cloudstream/tree/a28e6468f54cfe420feebd205d3d21d08ed0cde8

This document narrows the first CloudStream-compatible runtime target to the
existing AniLight episodic-anime provider. It is an integration plan, not a
claim that the runtime is already executable.

## Provider entry points actually used

`AniLightPlugin` extends Android `Plugin` and calls
`registerMainAPI(AniLightProvider())`.

`AniLightProvider` extends `MainAPI` and overrides:
- `getMainPage(page, request)`
- `search(query)`
- `load(url)`
- `loadLinks(data, isCasting, subtitleCallback, callback)`

The provider also uses the following CloudStream API symbols:
- Models: `DubStatus`, `Episode`, `HomePageResponse`, `LoadResponse`,
  `MainPageRequest`, `Score`, `SearchResponse`, `ShowStatus`,
  `SubtitleFile`, `TvType`.
- Builders/extensions: `addDate`, `addEpisodes`, `addTrailer`,
  `mainPageOf`, `newAnimeLoadResponse`, `newAnimeSearchResponse`,
  `newEpisode`, `newHomePageResponse`, `newSubtitleFile`.
- Link API: `ExtractorLink`, `ExtractorLinkType`, `newExtractorLink`.
- Utilities: `AppUtils.parseJson`, `AppUtils.toJson`, `Log`.
- Coroutine API: `Dispatchers`, `async`, `coroutineScope`.

`AniLightApi` additionally expects the CloudStream global `app` HTTP client
and `AppUtils.parseJson`. `FirebaseDomainHelper` uses the same HTTP and JSON
services. The upstream AniLight provider previously invoked `DonationManager` from its
homepage path. That call and import have now been removed from this port: the
provider has no donation-dialog or `CommonActivity` dependency. This is an
intentional host decoupling; donation UI is not part of the anime provider ABI.

## Important extractor finding

The AniLight provider resolves its own source tokens and directly emits
`ExtractorLink` and `SubtitleFile` callbacks. It does not need CloudStream's
global built-in extractor registry for its own source URLs. The
`ExtractorApi` class is still part of the upstream `BasePlugin` public ABI
because `registerExtractorAPI(ExtractorApi)` is exposed, but the full
CloudStream extractor catalogue is not required for the first AniLight
end-to-end milestone.

## Build compatibility

At the pinned upstream revision, the CloudStream `library` module is Kotlin
Multiplatform with an Android multiplatform library plugin, compileSdk 37,
Kotlin 2.4.0 and AGP 9.1.1. Its common source dependencies include Jackson
Kotlin, kotlinx atomicfu/coroutines/datetime/serialization, Ktor HTTP,
NiceHttp, KSoup and cryptography. The Android app additionally uses the
CloudStream app host, shared module, Compose, Media3 1.9.3, and other services.

SANY currently uses AGP 8.7.3, Kotlin Android 2.4.0, compileSdk 35, Java 17,
and Media3 1.5.1. Importing the upstream KMP library directly is therefore
not a safe one-line Gradle dependency change. Do not add guessed Maven/JitPack
coordinates or silently upgrade SANY's build toolchain.

## First compile boundary

The next implementation should be a separate Android library module with a
strictly defined ABI slice and tests. It must provide real upstream-compatible
class descriptors and signatures for the AniLight provider calls, plus the
matching HTTP, JSON, coroutine and model implementations. Only after this
module compiles against the unmodified AniLight provider should the app add
DEX loading and a Media3 source adapter.

## Acceptance criteria

1. Compile the unmodified AniLight provider against the compatibility module.
2. Load the built `.cs3` package's actual DEX in an isolated class loader.
3. Instantiate the manifest's plugin class and invoke Android `load(Context)`.
4. Verify the provider is registered exactly once and can be unregistered.
5. Exercise search, homepage, detail, sub/dub episode listing and loadLinks.
6. Verify returned stream URLs, headers, quality, and subtitle tracks are mapped
   into SANY's existing Media3 playback path.
7. Verify plugin unload clears provider registrations and class-loader references.
8. Run on minSdk 23 and current Android before enabling the feature by default.

## Current status

- Upstream source snapshots and GPL-3.0 license: present under
  `cloudstream-runtime/`.
- AniLight provider plugin build: verified successful in GitHub Actions for
  the anime-only catalog metadata update.
- SANY runtime validation build: previous commits passed; latest run must be
  checked independently.
- Compiled CloudStream ABI module, actual DEX execution, and end-to-end playback:
  not yet implemented or verified.
