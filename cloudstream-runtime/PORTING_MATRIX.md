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

The SANY AniLight provider on this branch is restricted to `TvType.Anime` and `TvType.OVA`. Movie homepage rows are removed, `MOVIE` search results are filtered, and `load()` rejects movie details. This is a provider-content restriction; it does not imply that the CloudStream host ABI has been integrated.

## Runtime safety gate

SANY must continue to treat downloaded `.cs3` files as verified but non-executable packages until every required ABI and host service above is implemented and tested. Keep HTTPS-only repository/download handling, size limits, SHA-256 checks, archive validation, and trusted-repository warnings.

Do not enable `PathClassLoader` merely because a DEX file exists. A plugin may execute arbitrary code with the app's permissions.

## Acceptance tests before enabling plugin execution

- Load the pinned AniLight `.cs3` package on-device without linkage/class verification errors.
- Instantiate the manifest-declared plugin class using the matching classloader.
- Run plugin load lifecycle and register at least one `MainAPI` provider and extractor.
- Search, load details, enumerate episodes, and resolve at least one playable source.
- Pass source URL, headers, subtitles, and quality into SANY's existing Media3 playback path.
- Unload the plugin and prove provider/extractor registrations and classloader references are removed.
- Repeat on minSdk 23 and a current Android version.
- Confirm Live TV, existing Movies/Anime screens, and the SANY player remain unaffected.

## Current implementation boundary

The current `SanyExtensionRuntime.kt` remains a repository/package downloader and validator. It does not execute plugin DEX. The audit above is not a claim of completed CloudStream runtime support.
