Version: 1.0.2
Date: 2026-11-15
Fixed:
  - Fixed MangaDex chapter feed queries sending unsupported `groups[]` parameter (HTTP 400); now requests `includes[]=scanlation_group` and filters chapters by their scanlation group relationships locally.
  - Fixed FTP uploads failing with `553 Can't open that file: No such file or directory` by navigating/creating each directory segment step-by-step and storing files inside the resolved remote working directory, throwing explicit errors if directory creation fails.
  - Fixed image reachability checks (`checkUrl`) reporting `0/N reachable` on CDNs/hosts where `HEAD` fails or returns HTTP `400/403/405/501`: now falls back cleanly to a 1-byte ranged `GET`, uses image `Accept` headers, and falls back to direct JVM `HttpURLConnection` when Ktor CIO fails transport.
  - Scoped MangaDex HTTP/SOCKS proxy settings cleanly to MangaDex hosts so Cubari/Aruba CDN image downloads do not fail against a stopped local MangaDex proxy.
  - Fixed background job UI refresh so `Session` reloads synced series, Aruba folder lists, and drafts on any terminal job state transition (not only `COMPLETED`).
Added:
  - Unified Pages view: merged the separate Uploaded Images preview grid and local Draft grid into one interactive, expandable/collapsible `Pages` grid showing both remote (`AssetReference.Uri`) and local (`AssetReference.Relative`) images side-by-side.
  - Selective per-page editing without downloading unchanged pages: click any page to **View Full**, **Download Local** (single page), **Replace Page...** (single slot), **Delete**, or drag-reorder; publishing to Aruba/Cubari reuses unchanged remote URLs and uploads only added/modified/shifted pages while Stage 2 fetches untouched remote pages on demand.
  - Live Aruba folder discovery (`scanArubaFolders` in Aruba settings, Series Editor, and best-effort during Cubari sync) with automatic case-insensitive/URL matching (`Solo Leveling Ragnarok` <-> `Solo leveling ragnarok`) and a **Detected Aruba folder** dropdown in the Series Editor.
  - **Open on Cubari** / **Open on MangaDex** (Series Editor) and **Open Cubari** / **Open MangaDex** (Chapter Inspector) browser launch buttons.
-------------------------------------------------------------------------------------------------
Version: 1.0.1
Date: 2026-11-15
Added:
  - Support for empty/root `cubariSeriesDir` (`""`, `"/"`, `"."`, `"./"`) and preserving readable filenames (spaces, apostrophes, casing) for Cubari JSON files at repository root.
  - Blank `cubariBranch` option to pull/push using the remote repository's default branch.
  - MangaDex Browser OAuth 2.0 Authorization Code + PKCE loopback flow (`loginMangaDexBrowser`) supporting public OAuth clients without a `client_secret`, optional fixed loopback `mangadexOAuthRedirectUri`, clear guard when a Personal Client (`personal-client-...`) is used with Browser OAuth, plus `verifyMangaDexSession` token validation/refresh action.
  - Built-in automatic DNS-over-HTTPS (DoH via `1.1.1.1` / `8.8.8.8`, controlled by `mangadexAutoDoh`) + loopback `CONNECT` relay to bypass sinkholed OS DNS (`127.0.0.1` / `0.0.0.0`), alongside optional `mangadexApiBaseUrl`, `mangadexAuthBaseUrl`, and `mangadexProxyUrl` settings and actionable dead-proxy diagnostics.
  - Check & load uploaded chapter images (`fetchChapterPages` capability + Chapter Inspector **Uploaded Images (Cubari / MangaDex)** panel): dropdown to select `Cubari / Aruba` or `MangaDex`, **Check Images** to verify live image URLs/HTTP status & preview thumbnails, and **Load into Draft** to download remote pages into editable draft storage.
  - Per-file `SecurityException` isolation in `syncCubariGitRepo` so host `RelativePath` rejections on ellipsis filenames (`...` / `....`) log a warning and skip only the affected file while syncing the rest of the repository.
  - Aruba storage layout `<chaptersPath>/<SeriesFolder>/ch<number>[.<decimal>]/<page>` with configurable and auto-detected page numbering (`auto`, unpadded `1.png`, or left-padded `01.png`/`001.png`/`0001.png`), per-series `Aruba folder name` and `Cubari JSON filename` overrides, and RFC-3986 path-segment percent encoding for URLs containing spaces or apostrophes.
Fixed:
  - Fixed `col_chapters` middle pane rendering a brighter background (`SURFACE_CONTAINER`) than `col_series`, the right inspector, and the host `SplitPaneContainer` frame (`SURFACE_CONTAINER_LOW`).
  - Fixed Settings & Locks actions (`Test Repo`, `Test Storage`, `Log In`, `Log Out`, `Check Group`) not immediately refreshing diagnostic banners when invoked from the interactive workspace.
  - Fixed `CubariJson.readerUrl` in Series Editor using `cubariUsername` instead of the GitHub repository owner parsed from `cubariRemoteRepoUrl`.
  - Fixed stale remote page cleanup on Aruba so unpadded (`1.png`..`99.png`) and 2-digit padded (`01.png`) stale images are removed when replacing pages.
Migration:
  - `cubariSeriesDir` default changed from `"series"` to `""` (repository root). If your Cubari JSON files live inside a subfolder such as `series/`, verify **Settings & Locks -> Cubari & GitHub Repo -> Series directory** is explicitly set to `series`.
  - Aruba uploads now write to `<chaptersPath>/<SeriesFolder>/ch<number>/<page>` instead of `<chaptersPath>/<slug>/<number>/%03d.<ext>`. Existing synced series automatically infer `arubaFolder` and zero-padding width from their existing Cubari chapter URLs; override per series in the Series Editor if needed.
-------------------------------------------------------------------------------------------------
Version: 1.0.0
Date: 2026-11-14
Added:
  - Initial release of WOM Manga Uploader with 2-Stage publishing (Stage 1: Aruba FTP/SFTP + GitHub Cubari JSON + Cubari.moe, Stage 2: MangaDex), MangaDex -> Cubari backfill, bidirectional Settings & Locks sync, 24h global and per-series preview hold timing, and pausable @Capability queue workers.
-------------------------------------------------------------------------------------------------
