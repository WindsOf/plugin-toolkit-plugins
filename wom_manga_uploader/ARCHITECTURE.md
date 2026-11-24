# WOM Manga Uploader — Architecture & Developer Guide

**Module:** `:wom_manga_uploader` (`com.wip.mangauploader`)  
**Entrypoint:** [MangaUploaderPlugin.kt](src/main/kotlin/com/wip/mangauploader/MangaUploaderPlugin.kt)

## 1. High-Level Architecture

`wom_manga_uploader` implements a 2-stage publishing pipeline with local draft management, incremental page diffing, and bidirectional remote sync:

- **Stage 1 (Aruba FTP/SFTP + GitHub Cubari JSON):** Uploads chapter pages to `<chaptersPath>/<SeriesFolder>/ch<key>/<page>`, updates `<slug>.json` in the Cubari Git repository, commits, and pushes.
- **Stage 2 (MangaDex):** Enforces an optional preview hold window (global or per-series override, anchored on either the latest MangaDex chapter or the Cubari publish timestamp), then creates/edits a MangaDex upload session (`api.mangadex.org`) and commits the chapter.
- **Backfill (`syncMangaDexToCubari`):** Scans MangaDex title feeds and downloads missing chapters from MangaDex@Home to Aruba + Cubari JSON.

### Source File Map
| File | Responsibility |
| :--- | :--- |
| [MangaUploaderPlugin.kt](src/main/kotlin/com/wip/mangauploader/MangaUploaderPlugin.kt) | `@PluginSetting` schema, `@PluginInfo`, `@PluginAction`s, `@Capability` declarations, `@InteractiveTool` entry |
| [Models.kt](src/main/kotlin/com/wip/mangauploader/Models.kt) | Serializable domain models (`Series`, `Chapter`, `PageRef`, `Library`), `ChangeDetector`, `IncrementalPagePlanner` |
| [LibraryStore.kt](src/main/kotlin/com/wip/mangauploader/LibraryStore.kt) | `library.json` persistence, `LibraryOps`, `PageImporter`, `ImageCacheManager`, `PersistedJobStore`, `UiSessionStateStore` |
| [Workers.kt](src/main/kotlin/com/wip/mangauploader/Workers.kt) | `@Capability` implementations, concurrency mutexes, progress/ETA reporting, job checkpoint persistence |
| [Session.kt](src/main/kotlin/com/wip/mangauploader/Session.kt) | Reactive SDUI `channelFlow` event loop, draft auto-save, session state restore, job reattachment |
| [UiState.kt](src/main/kotlin/com/wip/mangauploader/UiState.kt) | Immutable `UiState`, `WorkspaceLayoutMode`, `ConfirmKind`, `UiSelectors`, `UploaderLinks` |
| [UiRender.kt](src/main/kotlin/com/wip/mangauploader/UiRender.kt) | Pure `RenderModel -> UiNodeTree` SDUI renderer (3-Pane & 2-Pane Workspace, Settings & Locks, Queue) |
| [Actions.kt](src/main/kotlin/com/wip/mangauploader/Actions.kt) | `@PluginAction` implementations (Git/Aruba checks, MangaDex OAuth, cache pruning) |
| [Settings.kt](src/main/kotlin/com/wip/mangauploader/Settings.kt) | Typed `MangaUploaderConfig` parsed from `Map<String, JsonElement>` |
| [Locks.kt](src/main/kotlin/com/wip/mangauploader/Locks.kt) | `LockEvaluator` computing `cubari_ready`, `aruba_ready`, `mangadex_ready`, `stage1_ready`, `stage2_ready`, `sync_ready` |
| [CubariJson.kt](src/main/kotlin/com/wip/mangauploader/CubariJson.kt) | Non-destructive JSON editor for Cubari series manifests & Base64 reader URL builder |
| [Gateways.kt](src/main/kotlin/com/wip/mangauploader/Gateways.kt) | Transport interfaces (`GitClient`, `ArubaStorage`), `ArubaPaths`, `BandwidthThrottle` |
| [ArubaStorages.kt](src/main/kotlin/com/wip/mangauploader/ArubaStorages.kt) | Pooled `SftpStorage` (JSch) & `FtpStorage`/`FTPS` (Apache Commons Net) with shared-hosting webroot resolution |
| [CliGitClient.kt](src/main/kotlin/com/wip/mangauploader/CliGitClient.kt) | `git` CLI wrapper using one-shot `http.extraHeader` auth and token masking |
| [MangaDexApi.kt](src/main/kotlin/com/wip/mangauploader/MangaDexApi.kt) | MangaDex REST & OAuth client with 429 retry/backoff and edit-session prefilled file matching |
| [NetworkHelpers.kt](src/main/kotlin/com/wip/mangauploader/NetworkHelpers.kt) | `SmartMangaDexNetworkClient` (DoH + `LoopbackConnectRelay`), `ProxyNetworkClient`, `OAuthLoopbackReceiver` |
| [PreviewTiming.kt](src/main/kotlin/com/wip/mangauploader/PreviewTiming.kt) | Pure preview hold window evaluation |

## 2. Critical Concurrency & Persistence Invariants

1. **MangaDex Upload Serialization:**
   - `@Capability(name = "publishToMangaDex", maxConcurrentExecutions = 1)` ensures the host queues sequential chapter uploads rather than running them simultaneously.
   - `UploaderWorkers.mdUploadMutex` further serializes all `publishToMangaDex` executions inside the JVM. Library loading, OAuth token refresh (`ensureValidMangaDexToken`), `ChangeDetector.planMangaDexPush`, and `updateChapterMetadata` **must all occur inside `mdUploadMutex.withLock`**.
   - The UI allows queueing different chapters to MangaDex while another chapter is running/queued, disabling only duplicate pushes for the *same* chapter already queued/running.
2. **Cubari Git Repo Serialization:**
   - `UploaderWorkers.cubariGitMutex` serializes all `CubariRepo` Git operations (`prepare`, `writeSeries`, `commitAndPush`, `syncCubariGitRepo`, cover updates) so concurrent Stage 1 jobs never collide on `.git/index.lock`.
3. **Cross-Session Job & UI State:**
   - `UploaderWorkers` records job start, progress, pause, completion, and failure directly into `PersistedJobStore` so jobs that finish while the GUI is closed retain their terminal state and human-readable `displayTitle` (`targetSeriesId`, `targetChapterId`).
   - `Session.kt` restores `UiSessionStateStore` (search queries, filters, sort order, active series/chapter, layout mode, grid expansion) on reopen and auto-saves dirty chapter drafts before switching chapters/series.
