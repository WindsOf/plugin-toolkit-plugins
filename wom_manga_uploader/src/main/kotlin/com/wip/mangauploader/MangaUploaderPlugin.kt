package com.wip.mangauploader

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import org.wip.plugintoolkit.api.ExecutionResult
import org.wip.plugintoolkit.api.OS
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.annotations.Capability
import org.wip.plugintoolkit.api.annotations.CapabilityParam
import org.wip.plugintoolkit.api.annotations.InteractiveTool
import org.wip.plugintoolkit.api.annotations.PluginAction
import org.wip.plugintoolkit.api.annotations.PluginInfo
import org.wip.plugintoolkit.api.annotations.PluginLoad
import org.wip.plugintoolkit.api.annotations.PluginLocks
import org.wip.plugintoolkit.api.annotations.PluginSetting
import org.wip.plugintoolkit.api.annotations.PluginSetup
import org.wip.plugintoolkit.api.annotations.PluginValidate
import org.wip.plugintoolkit.api.annotations.RequiresLock
import org.wip.plugintoolkit.api.annotations.ResumeState
import org.wip.plugintoolkit.api.sdui.InteractiveUiProcessor
import org.wip.plugintoolkit.api.sdui.UiEvent
import org.wip.plugintoolkit.api.sdui.UiNodeTree
import org.wip.plugintoolkit.api.sdui.UiSessionContext

/**
 * Host-managed plugin settings (rendered by the host settings page AND by the in-plugin
 * `Settings & Locks` view; both write through the same settings pipeline).
 * Kept in the same source file as [MangaUploaderPlugin] so incremental KSP runs always
 * discover every `@PluginSetting` property alongside `@PluginInfo`.
 */
data class MangaUploaderSettings(
    // ── Cubari / GitHub ─────────────────────────────────────────────
    @PluginSetting(description = "GitHub username that owns the Cubari repository")
    val cubariUsername: String? = "",

    @PluginSetting(description = "GitHub personal access token (repo scope)", secret = true)
    val cubariGithubToken: String? = "",

    @PluginSetting(description = "Remote Git repository URL holding the Cubari JSON files (https://github.com/<user>/<repo>.git)")
    val cubariRemoteRepoUrl: String? = "",

    @PluginSetting(description = "Optional absolute path of an existing local clone. Empty = managed clone inside the plugin data folder")
    val cubariLocalRepoPath: String? = "",

    @PluginSetting(description = "Git branch to pull from and push to (empty = remote default branch)", defaultValue = "main")
    val cubariBranch: String? = "main",

    @PluginSetting(description = "Optional subfolder inside the repository that holds the series JSON files (empty = repository root)", defaultValue = "")
    val cubariSeriesDir: String? = "",

    @PluginSetting(description = "Group name written into Cubari chapter entries", defaultValue = "Uploader")
    val cubariGroupName: String? = "Uploader",

    // ── Aruba FTP / SFTP ───────────────────────────────────────────
    @PluginSetting(description = "Storage protocol: SFTP, FTP or FTPS", defaultValue = "SFTP")
    val arubaProtocol: String? = "SFTP",

    @PluginSetting(description = "Aruba storage host name")
    val arubaHost: String? = "",

    @PluginSetting(description = "Aruba storage port (22 for SFTP, 21 for FTP)", defaultValue = "22", minValue = 1.0, maxValue = 65535.0)
    val arubaPort: Int? = 22,

    @PluginSetting(description = "Aruba storage user name")
    val arubaUsername: String? = "",

    @PluginSetting(description = "Aruba storage password", secret = true)
    val arubaPassword: String? = "",

    @PluginSetting(description = "Remote directory that contains the chapter folders", defaultValue = "/chapters")
    val arubaChaptersPath: String? = "/chapters",

    @PluginSetting(description = "Public HTTP(S) base URL that maps to the chapters directory (used to build image links)")
    val arubaPublicBaseUrl: String? = "",

    @PluginSetting(description = "Page numbering mode: 'auto' (detect or unpadded 1.png), '0' (unpadded), '2' (01.png), '3' (001.png), '4' (0001.png)", defaultValue = "auto")
    val arubaPagePadding: String? = "auto",

    @PluginSetting(description = "Parallel page uploads", defaultValue = "2", minValue = 1.0, maxValue = 16.0)
    val concurrentUploads: Int? = 2,

    @PluginSetting(description = "Parallel page downloads (MangaDex -> Cubari backfill)", defaultValue = "2", minValue = 1.0, maxValue = 16.0)
    val concurrentDownloads: Int? = 2,

    @PluginSetting(description = "Maximum simultaneous FTP/SFTP connections", defaultValue = "2", minValue = 1.0, maxValue = 16.0)
    val concurrentConnections: Int? = 2,

    // ── MangaDex ───────────────────────────────────────────────────
    @PluginSetting(description = "MangaDex OAuth / personal API client id")
    val mangadexClientId: String? = "",

    @PluginSetting(description = "MangaDex personal API client secret (leave blank for public OAuth PKCE clients)", secret = true)
    val mangadexClientSecret: String? = "",

    @PluginSetting(description = "MangaDex account user name")
    val mangadexUsername: String? = "",

    @PluginSetting(description = "MangaDex account password (only needed to log in)", secret = true)
    val mangadexPassword: String? = "",

    @PluginSetting(description = "MangaDex OAuth access token (managed by Log In)", secret = true)
    val mangadexAccessToken: String? = "",

    @PluginSetting(description = "MangaDex OAuth refresh token (managed by Log In)", secret = true)
    val mangadexRefreshToken: String? = "",

    @PluginSetting(description = "Logged-in MangaDex profile name (read-only, managed by Log In)")
    val mangadexProfileName: String? = "",

    @PluginSetting(description = "Logged-in MangaDex profile URL (read-only, managed by Log In)")
    val mangadexProfileUrl: String? = "",

    @PluginSetting(description = "Scanlation / fansub group UUID or MangaDex group URL")
    val mangadexGroupId: String? = "",

    @PluginSetting(description = "Language code for uploaded chapters", defaultValue = "en")
    val mangadexLanguage: String? = "en",

    @PluginSetting(description = "Minimum delay between MangaDex upload requests in milliseconds", defaultValue = "1500", minValue = 0.0, maxValue = 60000.0)
    val mangadexUploadIntervalMs: Int? = 1500,

    @PluginSetting(description = "MangaDex API base URL (override only if using a mirror / reverse proxy)", defaultValue = DEFAULT_MD_API_URL)
    val mangadexApiBaseUrl: String? = DEFAULT_MD_API_URL,

    @PluginSetting(description = "MangaDex OAuth auth base URL (override only if using a mirror / reverse proxy)", defaultValue = DEFAULT_MD_AUTH_URL)
    val mangadexAuthBaseUrl: String? = DEFAULT_MD_AUTH_URL,

    @PluginSetting(description = "Optional fixed loopback OAuth redirect URI for Browser OAuth (empty = dynamic http://127.0.0.1:<port>/callback)")
    val mangadexOAuthRedirectUri: String? = "",

    @PluginSetting(description = "Optional HTTP(S) proxy URL for MangaDex requests (e.g. http://127.0.0.1:7890)")
    val mangadexProxyUrl: String? = "",

    @PluginSetting(description = "Automatically resolve MangaDex hosts via DNS-over-HTTPS (1.1.1.1 / 8.8.8.8) when OS DNS returns 127.0.0.1 / 0.0.0.0", defaultValue = "true")
    val mangadexAutoDoh: Boolean? = true,

    // ── Transmission limits ────────────────────────────────────────
    @PluginSetting(description = "Maximum upload speed in KB/s (0 = unlimited)", defaultValue = "0", minValue = 0.0)
    val maxUploadSpeedKbps: Int? = 0,

    @PluginSetting(description = "Maximum download speed in KB/s (0 = unlimited)", defaultValue = "0", minValue = 0.0)
    val maxDownloadSpeedKbps: Int? = 0,

    @PluginSetting(description = "Maximum local image cache size in MB for chapter pages (0 = unlimited). Evicts oldest published chapters first; unpublished drafts are never deleted.", defaultValue = "$DEFAULT_MAX_CACHE_MB", minValue = 0.0, maxValue = 100000.0)
    val maxCacheSizeMb: Int? = DEFAULT_MAX_CACHE_MB,

    // ── Preview hold ───────────────────────────────────────────────
    @PluginSetting(description = "Default preview hold window in hours between Cubari and MangaDex", defaultValue = "24", minValue = 0.0, maxValue = 720.0)
    val defaultPreviewHours: Int? = 24,

    @PluginSetting(description = "true: anchor the hold on the latest MangaDex chapter, false: on the Cubari publish timestamp", defaultValue = "true")
    val previewAnchorLatestMangaDex: Boolean? = true
)

@PluginInfo(
    id = "com.wip.mangauploader",
    name = "WOM Manga Uploader",
    version = "1.0.8",
    description = "2-Stage Manga Uploader for Stage 1 (Aruba FTP/SFTP + GitHub Cubari JSON + Cubari.moe) and Stage 2 (MangaDex) with missing-chapter backfill.",
    supportedOs = [OS.WINDOWS, OS.LINUX, OS.MACOS]
)
class MangaUploaderPlugin(val settings: MangaUploaderSettings) : InteractiveUiProcessor {

    @PluginLoad
    fun onLoad(logger: PluginLogger): Result<Unit> {
        logger.info("[MangaUploader] Loaded WOM Manga Uploader")
        return Result.success(Unit)
    }

    @PluginSetup
    fun onSetup(logger: PluginLogger): Result<Unit> {
        logger.info("[MangaUploader] Setup complete")
        return Result.success(Unit)
    }

    @PluginValidate
    fun onValidate(context: PluginContext): Result<Unit> {
        val cfg = MangaUploaderConfig.from(context.settings)
        context.logger.info("[MangaUploader] Validated config (branch=${cfg.cubariBranch}, protocol=${cfg.arubaProtocol}, previewHours=${cfg.defaultPreviewHours})")
        return Result.success(Unit)
    }

    @PluginLocks
    suspend fun checkLocks(context: PluginContext): Map<String, Boolean> {
        val cfg = MangaUploaderConfig.from(context.settings)
        return LockEvaluator.evaluate(cfg).toLockMap()
    }

    // ───────────────────────── Actions ─────────────────────────

    @PluginAction(
        name = "Test Cubari Git Repo",
        description = "Verifies remote GitHub repository connectivity and credentials.",
        showToast = false
    )
    suspend fun testCubariRepo(context: PluginContext) {
        UploaderActions(context).testCubariRepo()
    }

    @PluginAction(
        name = "Restore Cubari Git Repo",
        description = "Clears stale locks/uncommitted state and hard-resets the local Cubari clone to origin.",
        showToast = false
    )
    suspend fun restoreCubariRepo(context: PluginContext) {
        UploaderActions(context).restoreCubariRepo()
    }

    @PluginAction(
        name = "Test Aruba Storage",
        description = "Connects to Aruba FTP/SFTP and verifies the chapters directory.",
        showToast = false
    )
    suspend fun testArubaStorage(context: PluginContext) {
        UploaderActions(context).testArubaStorage()
    }

    @PluginAction(
        name = "Log In to MangaDex",
        description = "Authenticates with MangaDex OAuth using your Personal Client credentials.",
        showToast = false
    )
    suspend fun loginMangaDex(context: PluginContext) {
        UploaderActions(context).loginMangaDex()
    }

    @PluginAction(
        name = "Log In via Browser (OAuth)",
        description = "Opens MangaDex OAuth authorization in your browser (PKCE) and saves session tokens.",
        showToast = false
    )
    suspend fun loginMangaDexBrowser(context: PluginContext) {
        UploaderActions(context).loginMangaDexBrowser()
    }

    @PluginAction(
        name = "Verify MangaDex Token",
        description = "Verifies or refreshes the saved MangaDex OAuth token and updates your profile info.",
        showToast = false
    )
    suspend fun verifyMangaDexSession(context: PluginContext) {
        UploaderActions(context).verifyMangaDexSession()
    }

    @PluginAction(
        name = "Log Out of MangaDex",
        description = "Clears saved MangaDex OAuth tokens and locks Stage 2 publishing.",
        showToast = false
    )
    suspend fun logoutMangaDex(context: PluginContext) {
        UploaderActions(context).logoutMangaDex()
    }

    @PluginAction(
        name = "Check MangaDex Group",
        description = "Verifies the configured scanlation/fansub group UUID on MangaDex.",
        showToast = false
    )
    suspend fun checkMangaDexGroup(context: PluginContext) {
        UploaderActions(context).checkMangaDexGroup()
    }

    @PluginAction(
        name = "Check MangaDex Upload Session",
        description = "Checks whether the authenticated MangaDex account currently has an open upload session.",
        showToast = false
    )
    suspend fun checkMangaDexUploadSession(context: PluginContext) {
        UploaderActions(context).checkMangaDexUploadSession()
    }

    @PluginAction(
        name = "Force Cancel MangaDex Upload Session",
        description = "Closes any active or stuck MangaDex upload session on the authenticated account.",
        showToast = false
    )
    suspend fun forceCancelMangaDexUploadSession(context: PluginContext) {
        UploaderActions(context).forceCancelMangaDexUploadSession()
    }

    @PluginAction(
        name = "Prune Local Image Cache",
        description = "Deletes orphaned cached page files and evicts oldest published chapters if above Max Cache MB.",
        showToast = false
    )
    suspend fun pruneLocalCache(context: PluginContext) {
        UploaderActions(context).pruneLocalCache()
    }

    // ───────────────────────── Capabilities ─────────────────────────

    @Capability(
        name = "syncCubariGitRepo",
        description = "Clones or pulls the Cubari Git repository, parses series JSON files into the local library index, and optionally pushes local commits.",
        supportsPause = true
    )
    @RequiresLock([LockKeys.CUBARI])
    suspend fun syncCubariGitRepo(
        @CapabilityParam(description = "Whether to commit and push local changes after syncing", defaultValue = "false")
        push: Boolean = false,
        @ResumeState resumeState: JsonElement? = null,
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).syncCubariGitRepo(push = push, resume = resumeState)

    @Capability(
        name = "publishToArubaAndCubari",
        description = "Uploads chapter pages to Aruba FTP/SFTP, updates the Cubari series JSON (including series last_updated), and pushes to GitHub.",
        supportsPause = true
    )
    @RequiresLock([LockKeys.STAGE1])
    suspend fun publishToArubaAndCubari(
        @CapabilityParam(description = "Local series ID")
        seriesId: String,
        @CapabilityParam(description = "Local chapter ID (optional for series_meta)", defaultValue = "")
        chapterId: String = "",
        @CapabilityParam(description = "Operation: 'publish', 'remove', or 'series_meta'", defaultValue = "publish")
        operation: String = UploaderWorkers.OP_PUBLISH,
        @ResumeState resumeState: JsonElement? = null,
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).publishToArubaAndCubari(
        seriesId = seriesId,
        chapterId = chapterId,
        operation = operation,
        resume = resumeState
    )

    @Capability(
        name = "publishToMangaDex",
        description = "Uploads or updates a chapter on MangaDex with scanlation group association and upload rate limiting.",
        supportsPause = true
    )
    @RequiresLock([LockKeys.STAGE2])
    suspend fun publishToMangaDex(
        @CapabilityParam(description = "Local series ID")
        seriesId: String,
        @CapabilityParam(description = "Local chapter ID")
        chapterId: String,
        @ResumeState resumeState: JsonElement? = null,
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).publishToMangaDex(
        seriesId = seriesId,
        chapterId = chapterId,
        resume = resumeState
    )

    @Capability(
        name = "syncMangaDexToCubari",
        description = "Syncs selected chapters (or all missing when backfillAll=true) from MangaDex to Aruba and Cubari JSON.",
        supportsPause = true
    )
    @RequiresLock([LockKeys.SYNC])
    suspend fun syncMangaDexToCubari(
        @CapabilityParam(description = "Local series ID")
        seriesId: String,
        @CapabilityParam(description = "Comma-separated chapter numbers to sync from MangaDex", defaultValue = "")
        chapterNumbers: String = "",
        @CapabilityParam(description = "Only scan and update missing status without downloading/uploading pages", defaultValue = "false")
        scanOnly: Boolean = false,
        @CapabilityParam(description = "Pull all missing chapters from MangaDex to Cubari when chapterNumbers is empty", defaultValue = "false")
        backfillAll: Boolean = false,
        @ResumeState resumeState: JsonElement? = null,
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).syncMangaDexToCubari(
        seriesId = seriesId,
        chapterNumbers = chapterNumbers,
        scanOnly = scanOnly,
        backfillAll = backfillAll,
        resume = resumeState
    )

    @Capability(
        name = "uploadOrRemoveCubariCover",
        description = "Uploads a series cover image to Aruba and updates or removes the Cubari JSON cover field."
    )
    @RequiresLock([LockKeys.STAGE1])
    suspend fun uploadOrRemoveCubariCover(
        @CapabilityParam(description = "Local series ID")
        seriesId: String,
        @CapabilityParam(description = "Action: 'publish' or 'remove'", defaultValue = "publish")
        action: String = UploaderWorkers.OP_PUBLISH,
        @CapabilityParam(description = "Relative path of the cover image inside plugin storage", defaultValue = "")
        coverRelativePath: String = "",
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).uploadOrRemoveCubariCover(
        seriesId = seriesId,
        action = action,
        coverRelativePath = coverRelativePath
    )

    @Capability(
        name = "fetchChapterPages",
        description = "Inspects image URLs/availability on Cubari/Aruba or MangaDex and optionally downloads them into the editable chapter draft.",
        supportsPause = true
    )
    suspend fun fetchChapterPages(
        @CapabilityParam(description = "Local series ID")
        seriesId: String,
        @CapabilityParam(description = "Local chapter ID")
        chapterId: String,
        @CapabilityParam(description = "Source: 'cubari' or 'mangadex'", defaultValue = "cubari")
        source: String = "cubari",
        @CapabilityParam(description = "Mode: 'check' or 'load'", defaultValue = "check")
        mode: String = UploaderWorkers.MODE_CHECK,
        @CapabilityParam(description = "When loading, replace existing local draft pages instead of appending", defaultValue = "true")
        replace: Boolean = true,
        @CapabilityParam(description = "Optional 0-based single page index to download (-1 = all pages)", defaultValue = "-1")
        pageIndex: Int = -1,
        @ResumeState resumeState: JsonElement? = null,
        context: PluginContext
    ): ExecutionResult = UploaderWorkers(context).fetchChapterPages(
        seriesId = seriesId,
        chapterId = chapterId,
        source = source,
        mode = mode,
        replace = replace,
        pageIndex = pageIndex,
        resume = resumeState
    )

    // ───────────────────────── Interactive UI ─────────────────────────

    @InteractiveTool(
        name = "mangaUploaderWorkspace",
        description = "Opens the 2-Stage Manga Uploader workspace for Cubari/Aruba and MangaDex publishing, settings/locks, and queue monitoring."
    )
    fun mangaUploaderWorkspace(): String = "WOM Manga Uploader workspace ready"

    override fun openUiSession(context: UiSessionContext, events: Flow<UiEvent>): Flow<UiNodeTree> =
        runMangaUploaderSession(context, events)
}
