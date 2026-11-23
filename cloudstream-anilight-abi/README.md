# AniLight CloudStream ABI compile gate

This isolated Android library compiles the unmodified AniLight provider, API,
models, domain helper, and SANY-neutral bridge against the pinned upstream
CloudStream `:library` project.

It is intentionally **not** an app dependency and is not packaged into SANY.

Excluded for this gate:
- `AniLightPlugin.kt`: its `Plugin` superclass belongs to the upstream
  CloudStream Android app module, not the shared library.
- DEX loading, PluginManager lifecycle, registration, and Media3 playback.

A successful `:cloudstream-anilight-abi:assembleDebug` proves only that the
provider source compiles against the pinned CloudStream library API. It does
not prove the Android Plugin ABI or executable DEX runtime.
