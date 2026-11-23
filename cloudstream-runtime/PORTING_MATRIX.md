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
3. `BasePlugin` directly calls `APIHolder`, `MainAPI`, `ExtractorApi`, `extractorApis`, and the upstream logging service. Copying only the base class cannot provide a working ABI.
4. The upstream Android `Plugin` adds Context/resource/settings hooks. The upstream `PluginManager` also depends on CloudStream application state, repository storage, activity callbacks, AndroidX Fragment APIs, notifications, and UI utilities. It cannot be copied into SANY unchanged.
5. `MainAPI` and `ExtractorApi` depend on a broad model/helper/network surface. A minimal class with the same name would not be binary compatible with compiled `.cs3` providers.

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
