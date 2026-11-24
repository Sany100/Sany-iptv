# Third-party CloudStream Anime plugins

SANY IPTV bundles the following unmodified CloudStream `.cs3` plugin packages in its built-in Anime provider set:

- **Anichi** — source: https://github.com/hexated/cloudstream-extensions-hexated/tree/6da85d924f69ffb6beaa27f56ddf167e47016c1f/Anichi
- **YugenAnime** — source: https://github.com/hexated/cloudstream-extensions-hexated/tree/6da85d924f69ffb6beaa27f56ddf167e47016c1f/YugenAnime
- **WcofunProvider** — source: https://github.com/hexated/cloudstream-extensions-hexated/tree/6da85d924f69ffb6beaa27f56ddf167e47016c1f/WcofunProvider

Upstream repository: https://github.com/hexated/cloudstream-extensions-hexated
Pinned upstream build commit: `6da85d924f69ffb6beaa27f56ddf167e47016c1f`
License: GNU General Public License v3.0 (GPL-3.0), as declared by the upstream repository's `LICENSE.md`.

These plugin packages are third-party works, are not authored by SANY IPTV, and are distributed without warranty. Their corresponding source code and license are available from the pinned upstream commit above. SANY's generated `builds/plugins.json` records each bundled package's version, size, source metadata, and SHA-256 digest.


## Indflix — MultiMovies

- Provider: **MultiMovies v51**
- Upstream source: https://github.com/dipender98/Indflix/tree/main/Multimovies
- Binary package: https://raw.githubusercontent.com/dipender98/Indflix/builds/Multimovies.cs3
- Pinned SHA-256: `8838683cd8b26612d57c23af5036789f57a4baf125d61eb926f29933ff7a514c`
- License: GPL-3.0; upstream `LICENSE` and `NOTICE` are included with the imported source under `cloudstream-original/Indflix-Multimovies/`.
- The full Kotlin source and upstream unit tests are preserved there. SANY distributes the original plugin package without modifying its contents; the app-side Movie screen only controls installation, manual selection, display, and playback handoff.
