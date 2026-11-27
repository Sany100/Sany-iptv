# CloudStream GPL Runtime Porting Matrix

Pinned upstream: `recloudstream/cloudstream@a28e6468f54cfe420feebd205d3d21d08ed0cde8`

This document records the source audit for the SANY-hosted runtime port. It is deliberately explicit about what is and is not executable.

## Confirmed upstream layout

- Plugin ABI: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/plugins/BasePlugin.kt`
- Provider API and registry: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainAPI.kt`
- Extractor API and built-in extractor registry: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/utils/ExtractorApi.kt`
- Default request client: `library/src/commonMain/kotlin/com/lagradost/cloudstream3/MainActivity.kt`
- Android plugin subclass: `app/src/main/java/com/lagradost/cloudstream3/plugins/Plugin.kt`
- Android DEX loading and lifecycle: `app/src/main/java/com/lagradost/cloudstream3/plugins/PluginManager.kt`

## Host integration requirements

| Area | Upstream contract | SANY status |
|---|---|---|
| Plugin base ABI | `BasePlugin`, manifest model, registerMainAPI/registerExtractorAPI, beforeUnload/load | Not integrated |
| Provider API | `MainAPI`, response models, TvType, APIHolder, provider mapping | Not integrated |
| Extractors | `ExtractorApi`, extractorApis registry, helper models | Not integrated |
| Android plugin class | `Plugin extends BasePlugin`, Context lifecycle, resources/settings hooks | Not integrated |
| DEX loader | PathClassLoader, plugin class instantiation, classloader tracking | Not integrated |
| Host services | HTTP client, JSON serialization, coroutine dispatchers, logging, app context | Not integrated |
| Lifecycle | load, register, unload, unregister, error isolation | Not integrated |
| SANY adapter | map CloudStream search/details/episodes/links into existing SANY screens and Media3 player | Not integrated |

## Compatibility findings

1. The upstream `library` is Kotlin Multiplatform, not a small standalone Android JAR.
2. Its build uses a newer Android Gradle Plugin line and newer Android/Media3 dependencies than the current SANY app. Importing the upstream Gradle module unchanged would force a broad build-tool/dependency upgrade.
3. `BasePlugin` exposes the binary-facing `registerMainAPI(MainAPI)`, `registerExtractorAPI(ExtractorApi)`, `beforeUnload()`, `load()`, and `filename` members. Registration mutates `APIHolder.allProviders`, calls `APIHolder.addPluginMapping`, writes `sourcePlugin`, and appends to the global `extractorApis` collection. These registry contracts and their collection element types must match compiled plugin bytecode.
4. The Android `Plugin` subclass adds `load(Context)`, `registerVideoClickAction`, `Resources?`, and an `openSettings` callback. Even plugins that do not use settings still link against the `Plugin` superclass and its ABI.
5. `ExtractorApi.kt` imports a large built-in extractor catalogue plus `Ksoup`, `AudioFile`, `IDownloadableMinimum`, `SubtitleFile`, `USER_AGENT`, and the global `app` HTTP service. It is not a single independent interface that can safely be copied alone.
6. The pinned `library` build uses Kotlin Multiplatform Android/JVM source sets and dependencies including Jackson Kotlin, kotlinx serialization/coroutines, NiceHttp, Ktor HTTP, Ksoup/Jsoup, Rhino, cryptography, and NewPipeExtractor. The SANY app currently uses AGP 8.7.3, compileSdk 35, Kotlin Android 2.4.0, and Media3 1.5.1; upstream uses AGP 9.1.1, compileSdk 37, and Media3 1.9.3. A direct module import is not a safe incremental change.
7. The upstream Android `PluginManager` depends on CloudStream application state, repository storage, activity callbacks, AndroidX Fragment APIs, notifications, and UI utilities. It cannot be copied into SANY unchanged.
8. `MainAPI` and `ExtractorApi` depend on a broad model/helper/network surface. A minimal class with the same name would not be binary compatible with compiled `.cs3` providers.

## Anime-only provider scope

The AniLight provider now declares `TvType.Anime`, `TvType.OVA`, and `TvType.AnimeMovie`. It adds an `Anime Movies` homepage row backed by `popularMovies`, retains `MOVIE` search results, and builds a single `Movie` episode for movie details. Movie link extraction uses the same AniLight sources/session/token resolver as episodic content, requesting episode number `1` with `sub` or `dub` language. When movie watch rows omit both audio flags, the provider currently falls back to one SUB movie row. This fallback is a compatibility assumption, not yet confirmed against a live movie API response. The `.cs3` plugin artifact built successfully at workflow run `45092214003`; its archive contains `manifest.json` and `classes.dex`, and DEX inspection confirms AniLight provider/API classes and movie/source-resolution code are packaged. This does not mean SANY's APK can execute the DEX: the CloudStream host ABI and DEX loader remain unintegrated.

## Runtime safety gate

SANY must continue to treat downloaded `.cs3` files as verified but non-executable packages until every required ABI and host service above is implemented and tested. Keep HTTPS-only repository/download handling, size limits, SHA-256 checks, archive validation, and trusted-repository warnings.

Do not enable `PathClassLoader` merely because a DEX file exists. A plugin may execute arbitrary code with the app's permissions.

## Acceptance tests before enabling plugin execution

- Load the pinned AniLight `.cs3` package on-device without linkage/class verification errors.
- Instantiate the manifest-declared plugin class using the matching classloader.
- Run plugin load lifecycle and register at least one `MainAPI` provider and extractor.
- Search, load details, enumerate episodes (including a real AnimeMovie), and resolve at least one real movie stream URL from the live AniLight API.
- Confirm a real movie stream starts in SANY's Media3 player on-device, including redirect/headers and audio/subtitle behavior.
- Pass source URL, headers, subtitles, and quality into SANY's existing Media3 playback path.
- Unload the plugin and prove provider/extractor registrations and classloader references are removed.
- Repeat on minSdk 23 and a current Android version.
- Confirm Live TV, existing Movies/Anime screens, and the SANY player remain unaffected.

## Current implementation boundary

The current `SanyExtensionRuntime.kt` remains a repository/package downloader and validator. It does not execute plugin DEX. The audit above is not a claim of completed CloudStream runtime support.


## 2026-11-23 source-port checkpoint

The exact pinned upstream source for the following GPL-3.0 host API classes has
now been vendored as a reference snapshot under `cloudstream-runtime/upstream-source/`:

- `library/.../plugins/BasePlugin.kt` — commit-pinned upstream implementation.
- `app/.../plugins/Plugin.kt` — Android plugin lifecycle base class.

These files are intentionally outside the Android app source set. They are **not
compiled into SANY yet** and do not make .cs3 execution available. This avoids
introducing unresolved upstream dependencies or accidentally shipping a fake ABI.

The next source-port dependency set must include the real `MainAPI`, `APIHolder`,
`ExtractorApi`, extractor registry, host HTTP client, serialization/model types,
logging and coroutine utilities. `BasePlugin` itself directly references
`APIHolder`, `MainAPI`, `ExtractorApi`, `extractorApis`, and the upstream
logging API, so compiling only these two vendored classes is insufficient.

Validation gate remains unchanged: compile, install, load the actual AniLight
.cs3 package, register its provider, search, load details/episodes, extract a
playable source, play through SANY Media3, and unload without stale registrations.


## 2026-11-23 provider/extractor API source snapshot

Added exact source snapshots from pinned upstream commit
`a28e6468f54cfe420feebd205d3d21d08ed0cde8`:

- `upstream-source/library/.../MainAPI.kt` (95,768 characters)
- `upstream-source/library/.../utils/ExtractorApi.kt` (48,994 characters)

These are reference/vendor snapshots, not yet part of SANY's compiled source set.
They expose why a safe runtime port is larger than adding two Kotlin files:

- `MainAPI.kt` references shared response/model types, tracker and sync models,
  host HTTP/network helpers, serialization (Jackson + kotlinx), Ktor URL types,
  OkHttp, coroutine utilities, date/time APIs and subtitle helpers.
- `ExtractorApi.kt` references a broad set of concrete extractor classes,
  common models, networking and coroutine helpers. It is not a standalone
  interface file that can be compiled in isolation.

SANY's current app module uses AGP 8.7.3, Kotlin Android plugin 2.4.0,
compileSdk 35 and Java 17. No upstream dependency coordinates have been assumed.
The next gate is a reproducible dependency map and a small isolated compile
experiment; do not add the upstream source tree to the app source set until
its multiplatform dependencies and host APIs are resolved.


## Anime-only AniLight scope refinement

Inspection of the actual `AniLightProvider.kt` shows that it implements its
own `loadLinks()` path: it resolves AniLight's episode tokens and emits
`ExtractorLink` objects directly. It does not call the global CloudStream
extractor registry to resolve its own episode URLs. Therefore, for the first
working Anime-only milestone, a full port of every CloudStream built-in
extractor implementation is not a prerequisite. The ABI still needs the
`ExtractorApi` type because upstream `BasePlugin` exposes
`registerExtractorAPI(ExtractorApi)`, but we should avoid porting the entire
extractor catalog until a provider actually needs it.

Also corrected AniLight's plugin catalog metadata in
`cloudstream-original/AniLight/build.gradle.kts`: `tvTypes` now lists only
`Anime` and `OVA`, matching the provider's runtime filtering and ensuring
AnimeMovie is not advertised by the repository metadata.

The focused first runtime milestone is now:
1. CloudStream plugin manifest and Android `Plugin` lifecycle.
2. `BasePlugin` registration ABI.
3. The exact provider-facing subset of `MainAPI`, response models and helper
   methods used by AniLight.
4. The provider's HTTP/serialization/coroutine dependencies.
5. `ExtractorLink`/subtitle callback ABI and SANY Media3 adapter.
6. Actual AniLight .cs3 DEX load, provider registration, episode extraction,
   playback and unload tests.

This is a scope reduction for the anime-only first milestone, not a claim that
the full CloudStream runtime or all extractors are already supported.

See [ANI_LIGHT_ABI_DEPENDENCIES.md](ANI_LIGHT_ABI_DEPENDENCIES.md) for the concrete AniLight provider symbol/dependency map and the first compile boundary.


## AniLight host-UI decoupling checkpoint (2026-11-23)

Removed the provider's `DonationManager` import and homepage invocation. The
AniLight provider no longer calls into CloudStream's donation dialog or
`CommonActivity` lifecycle. This reduces one host coupling only; it does not
replace the upstream plugin ABI, HTTP/JSON services, provider registry, DEX
loader, lifecycle manager, or SANY Media3 adapter. The provider still cannot
be loaded from `.cs3` by SANY at this checkpoint.


## 2026-11-23 Anime Movie provider enablement

The AniLight provider was updated to include `AnimeMovie` alongside series,
OVA, and specials. `MOVIE` search results are no longer filtered; movie
metadata maps to CloudStream's exact `TvType.AnimeMovie` enum. A dedicated
`home:movies` row consumes AniLight's `popularMovies` section. Movie detail
pages expose a single Movie item when the watch endpoint returns no episode
rows, and that item uses the same source-token resolution path as series
episodes (episode number 1, SUB fallback).

The provider catalog metadata now advertises `AnimeMovie`. The stream route
is implemented against the API contract visible in the provider source, but
the actual movie API response shape and playable link have not yet been
verified live. The CloudStream runtime/Dex loading status is unchanged.


## 2026-11-23 AnimeMovie artifact checkpoint

- CloudStream plugin workflow run: https://github.com/Sany100/Sany-iptv/actions/runs/45092214003 (completed successfully).
- Artifact: `anilight-cloudstream-plugin`; archive contains `AniLight.cs3` and `plugins.json`.
- Published repository index on the `builds` branch declares `Anime`, `AnimeMovie`, and `OVA`.
- The packaged DEX contains AniLight provider/API classes and compiled movie/source-resolver code. Static package inspection is not a live API test or playback test.
- Live AniLight movie API/source resolution and SANY device playback remain unverified. The current SANY host still does not execute CloudStream DEX plugins.


## SANY host adapter architecture checkpoint (2026-11-23)

Added [SANY_HOST_ADAPTER_DESIGN.md](SANY_HOST_ADAPTER_DESIGN.md) after inspecting
the current `MainActivity.kt`, `SanyExtensionRuntime.kt`, and app Gradle
configuration. The audit confirms the built-in AniLight native screen already
passes a URL and provider headers into SANY's Media3 playback method; the
downloaded CloudStream `.cs3` package path remains verification-only.

The design separates the work into an exact upstream ABI Android library,
plugin-scoped DEX loader/lifecycle, and a CloudStream-to-SANY Media3 DTO
adapter. It also records a concrete playback gap: the current SANY player
accepts provider headers but only one optional subtitle URL (assumed WebVTT).
Multiple subtitle tracks with their actual MIME types/languages must be added
before the CloudStream player adapter is considered complete.

Next coding gate is an isolated compile experiment for the exact pinned
upstream ABI/dependency slice against the unmodified AniLight provider. No
plugin DEX execution or player changes should be enabled before that gate
passes. This is an architecture checkpoint, not runtime implementation.


## Pinned upstream build gate added (2026-11-23)

Added `.github/workflows/cloudstream-upstream-abi-validation.yml`.
It checks out the exact pinned upstream commit
`a28e6468f54cfe420feebd205d3d21d08ed0cde8`, asserts that the real
`MainAPI`, `BasePlugin`, `ExtractorApi`, Android `Plugin`, and
`PluginManager` source files are present, then asks the upstream Gradle
wrapper to compile `:library:assemble` and
`:app:compileDebugKotlin`.

This is a reproducible upstream baseline build gate, not yet the full Gate 1
AniLight-against-local-source ABI probe. The Android `Plugin` implementation
lives in the upstream app module and depends on app-side action APIs, while
`MainAPI`/ `BasePlugin`/ `ExtractorApi` live in the KMP library. A small
standalone module cannot safely reproduce that boundary with fake classes.
The next step is to extract or package the exact host ABI classes from this
pinned build and compile the unchanged AniLight provider against them.

No SANY app dependency, DEX execution, or playback behavior is changed by this
validation workflow. Its result must be checked in GitHub Actions before
calling the upstream baseline build successful.


## AniLight against pinned upstream ABI compile probe (2026-11-23)

Workflow commit: `418cc315896c1ada8a129a8e9dce6c87da72387b`.

The upstream validation workflow now has a second compile gate. After compiling
the pinned CloudStream `:library` and stable Android app variant, it copies
the unchanged AniLight Kotlin source tree into the checked-out upstream
`:app` source tree and compiles that app variant again. This puts the real
upstream library and Android host classes on the Kotlin compiler classpath,
including the Android `Plugin` superclass, rather than relying on local
placeholder ABI declarations.

The workflow is triggered by changes to either `cloudstream-runtime/**` or
`cloudstream-original/AniLight/**`. Its result must be checked in Actions
before claiming AniLight source/API compatibility. Even a successful compile
does not prove that the generated `.cs3` loads in SANY, that AniLight's live
movie API returns a playable URL, or that SANY Media3 playback succeeds.


## 2026-11-23 exact AniLight ABI compile result

The pinned upstream ABI validation workflow completed successfully:

- Workflow run: https://github.com/Sany100/Sany-iptv/actions/runs/45094997414
- Run ID: `45094997414`
- Commit tested: `b7d6cb8652e0172fc9ca7dfe76b3b61ae8fe9321`
- Upstream baseline build: passed.
- Unchanged AniLight provider compile against the exact upstream Android app/library classpath: passed.

This closes the source/API compile probe only. The validation copied the provider
source into the pinned upstream app module and compiled it there; it did not
create an embeddable SANY Android library, execute the packaged `.cs3` DEX,
exercise live AniLight API calls, or play a stream through SANY Media3.

Next gate is a separately testable host-service bridge for AniLight's provider
calls (HTTP client, JSON utilities, coroutine/runtime services and provider
registration). DEX loading must remain disabled until those services and the
matching upstream ABI are present in SANY and covered by tests.
