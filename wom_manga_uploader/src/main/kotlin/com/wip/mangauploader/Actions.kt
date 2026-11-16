package com.wip.mangauploader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginNotificationType
import org.wip.plugintoolkit.api.PluginStorage
import java.awt.Desktop
import java.net.URI

/** Outcome of a connectivity / auth check, shown as diagnostics in `Settings & Locks`. */
@Serializable
data class Diagnostic(val ok: Boolean, val message: String, val atEpochSec: Long)

/** Persists the last diagnostics per target (`cubari`, `aruba`, `mangadex`, `group`) in plugin storage. */
class DiagnosticsStore(private val storage: PluginStorage) {
    suspend fun put(target: String, diagnostic: Diagnostic) {
        storage.put(KEY_PREFIX + target, Json.encodeToJsonElement(Diagnostic.serializer(), diagnostic))
    }

    suspend fun loadAll(): Map<String, Diagnostic> = TARGETS.mapNotNull { target ->
        storage.get(KEY_PREFIX + target)
            ?.let { runCatching { Json.decodeFromJsonElement(Diagnostic.serializer(), it) }.getOrNull() }
            ?.let { target to it }
    }.toMap()

    companion object {
        private const val KEY_PREFIX = "diag_"
        val TARGETS = listOf("cubari", "aruba", "mangadex", "md_session", "group")
    }
}

/** Opens [url] in the OS default browser on [Dispatchers.IO]. */
internal suspend fun openExternalUrl(url: String) = withContext(Dispatchers.IO) {
    require(url.isNotBlank()) { "URL is empty" }
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    } else {
        throw IllegalStateException("System browser cannot be opened automatically; open manually: $url")
    }
}

/**
 * Obtains a valid MangaDex Bearer access token, refreshing via `refresh_token` when configured
 * and persisting rotated tokens into plugin settings.
 */
internal suspend fun ensureValidMangaDexToken(
    context: PluginContext,
    api: MangaDexApi,
    cfg: MangaUploaderConfig
): String {
    if (cfg.mdRefreshToken.isNotBlank() && cfg.mdClientId.isNotBlank()) {
        val tokens = try {
            api.refresh(cfg.mdClientId, cfg.mdClientSecret, cfg.mdRefreshToken)
        } catch (e: MangaDexException) {
            throw IllegalStateException("MangaDex session expired - log in again in Settings & Locks (${e.message})", e)
        }
        context.updateSetting(SettingKeys.MD_ACCESS_TOKEN, tokens.accessToken)
        if (tokens.refreshToken.isNotBlank()) context.updateSetting(SettingKeys.MD_REFRESH_TOKEN, tokens.refreshToken)
        return tokens.accessToken
    }
    return cfg.mdAccessToken.takeIf { it.isNotBlank() } ?: error("Not authenticated with MangaDex")
}

/**
 * Implementation of the `@PluginAction`s triggered by the Settings & Locks buttons.
 * Every action records a [Diagnostic], toasts the outcome and escalates real failures as notifications.
 */
class UploaderActions(
    private val context: PluginContext,
    private val services: ServiceFactory = MangaUploaderServices.factory
) {
    private val diagnostics = DiagnosticsStore(context.storage)
    private val foldersStore = ArubaFoldersStore(context.storage)
    private val libraryStore = LibraryStore(context.fileSystem, context.logger)
    private val config get() = MangaUploaderConfig.from(context.settings)

    suspend fun testCubariRepo() = check("cubari", "Test Repo", critical = false) {
        val cfg = config
        require(cfg.cubariRemoteUrl.isNotBlank()) { "Remote repository URL is not set" }
        services.git(context, cfg).testRemote(cfg.cubariRemoteUrl)
    }

    suspend fun restoreCubariRepo(): Boolean {
        var succeeded = false
        check("cubari", "Restore from Git", critical = true) {
            val cfg = config
            require(cfg.cubariRemoteUrl.isNotBlank()) { "Remote repository URL is not set" }
            val git = services.git(context, cfg)
            val repo = CubariRepo.create(context, cfg, git)
            git.resetHardFromRemote(repo.dir, cfg.cubariRemoteUrl, cfg.cubariBranch).also {
                succeeded = true
            }
        }
        return succeeded
    }

    suspend fun testArubaStorage() = check("aruba", "Test Storage", critical = false) {
        val cfg = config
        require(cfg.arubaHost.isNotBlank()) { "Aruba host is not set" }
        services.aruba(context, cfg).use { it.testConnection() }
    }

    /** Connects to Aruba, caches detected series folders, and auto-matches matching library series. */
    suspend fun scanArubaFolders(): List<String> {
        var scanned = emptyList<String>()
        check("aruba", "Scan Aruba Folders", critical = false) {
            val cfg = config
            require(cfg.arubaHost.isNotBlank()) { "Aruba host is not set" }
            val root = cfg.arubaChaptersPath.trim().ifEmpty { "/" }
            val dirs = services.aruba(context, cfg).use { it.listDirectoryNames(root) }
                .filterNot { ArubaPaths.isReservedSeriesFolder(it) }
            foldersStore.save(dirs)
            libraryStore.mutate { LibraryOps.applyArubaFolders(it, cfg.arubaPublicBaseUrl, dirs) }
            scanned = dirs
            "Found ${dirs.size} Aruba folder(s) under $root"
        }
        return scanned
    }

    suspend fun loginMangaDex() = check("mangadex", "MangaDex login", critical = true) {
        val cfg = config
        require(cfg.mdClientId.isNotBlank() && cfg.mdClientSecret.isNotBlank()) { "API client id and client secret are required for password login (use Browser OAuth for public PKCE)" }
        require(cfg.mdUsername.isNotBlank() && cfg.mdPassword.isNotBlank()) { "Username and password are required" }
        val api = services.mangadex(context, cfg)
        val tokens = api.login(cfg.mdClientId, cfg.mdClientSecret, cfg.mdUsername, cfg.mdPassword)
        val profile = api.profile(tokens.accessToken)
        saveSession(tokens, profile)
        "Logged in as ${profile.username}"
    }

    suspend fun loginMangaDexBrowser(
        browseUrl: suspend (String) -> Unit = { openExternalUrl(it) }
    ) = check("mangadex", "MangaDex OAuth", critical = true) {
        val cfg = config
        require(cfg.mdClientId.isNotBlank()) { "API client id is required for MangaDex OAuth" }
        require(!(cfg.mdClientId.startsWith("personal-client-", ignoreCase = true) && cfg.mdOAuthRedirectUri.isBlank())) {
            "MangaDex Personal Clients ('personal-client-...') do not register a redirect_uri for Browser OAuth. " +
                "Use 'Password Login' with your client secret, or configure a Third-Party OAuth client & OAuth redirect URI."
        }
        val api = services.mangadex(context, cfg)
        val pkce = OAuthPkce.generate()
        val state = OAuthPkce.randomState()
        OAuthLoopbackReceiver(state, cfg.mdOAuthRedirectUri).use { receiver ->
            val authUrl = api.authorizeUrl(cfg.mdClientId, receiver.redirectUri, state, pkce.challenge)
            context.logger.info("Opening MangaDex OAuth URL (redirect=${receiver.redirectUri}): $authUrl")
            context.showToast("Continue MangaDex OAuth sign-in in your browser...")
            browseUrl(authUrl)
            val code = receiver.awaitCode()
            val tokens = api.exchangeCode(cfg.mdClientId, cfg.mdClientSecret, code, receiver.redirectUri, pkce.verifier)
            val profile = api.profile(tokens.accessToken)
            saveSession(tokens, profile)
            "OAuth logged in as ${profile.username}"
        }
    }

    suspend fun verifyMangaDexSession() = check("mangadex", "Verify MangaDex Token", critical = false) {
        val cfg = config
        val api = services.mangadex(context, cfg)
        var access = cfg.mdAccessToken
        var refresh = cfg.mdRefreshToken
        var profile = if (access.isNotBlank()) {
            try {
                api.profile(access)
            } catch (e: MangaDexException) {
                if ((e.statusCode == 401 || e.statusCode == 403) && refresh.isNotBlank() && cfg.mdClientId.isNotBlank()) null
                else throw e
            }
        } else null
        if (profile == null) {
            if (refresh.isNotBlank() && cfg.mdClientId.isNotBlank()) {
                val tokens = api.refresh(cfg.mdClientId, cfg.mdClientSecret, refresh)
                access = tokens.accessToken
                if (tokens.refreshToken.isNotBlank()) refresh = tokens.refreshToken
                profile = api.profile(access)
            } else {
                error("No saved MangaDex access or refresh token to verify")
            }
        }
        saveSession(MdTokens(access, refresh, 0L), profile)
        "Verified session as ${profile.username}"
    }

    suspend fun logoutMangaDex() {
        SettingKeys.MD_SESSION_KEYS.forEach { context.clearSetting(it) }
        diagnostics.put("mangadex", Diagnostic(true, "Logged out", services.clock.nowEpochSec()))
        diagnostics.put("md_session", Diagnostic(true, "Logged out", services.clock.nowEpochSec()))
        context.logger.info("MangaDex session cleared")
        context.showToast("Logged out of MangaDex")
    }

    suspend fun checkMangaDexUploadSession() = check("md_session", "Check Upload Session", critical = false) {
        val cfg = config
        val api = services.mangadex(context, cfg)
        val token = ensureValidMangaDexToken(context, api, cfg)
        val active = api.activeUploadSession(token)
        if (active == null) {
            "No active upload session on MangaDex"
        } else {
            val started = active.createdAt?.let { " · started $it" }.orEmpty()
            "Active upload session: ${active.id}$started"
        }
    }

    suspend fun forceCancelMangaDexUploadSession() = check("md_session", "Force Cancel Upload Session", critical = true) {
        val cfg = config
        val api = services.mangadex(context, cfg)
        val token = ensureValidMangaDexToken(context, api, cfg)
        val active = api.activeUploadSession(token)
            ?: return@check "No active upload session to cancel"
        api.closeUploadSession(token, active.id)
        "Force-cancelled MangaDex upload session ${active.id}"
    }

    suspend fun checkMangaDexGroup() = check("group", "Check Group", critical = false) {
        val cfg = config
        val groupId = cfg.mdGroupId ?: error("No valid group UUID or URL configured")
        val group = services.mangadex(context, cfg).group(groupId)
        "Group: ${group.name} ($groupId)"
    }

    suspend fun pruneLocalCache(protectedChapterIds: Set<String> = emptySet()): CachePruneResult {
        val res = ImageCacheManager.prune(
            fs = context.fileSystem,
            store = libraryStore,
            maxCacheMb = config.maxCacheMb,
            logger = context.logger,
            protectedChapterIds = protectedChapterIds
        )
        val summary = buildString {
            append("Cache: ${formatByteCount(res.remainingBytes)} used")
            if (res.orphanFilesRemoved > 0 || res.evictedChapters > 0) {
                append(
                    " (removed ${res.orphanFilesRemoved} orphan(s), " +
                        "evicted ${res.evictedChapters} chapter(s), freed ${formatByteCount(res.freedBytes)})"
                )
            }
        }
        diagnostics.put("cache", Diagnostic(true, summary, services.clock.nowEpochSec()))
        context.showToast(summary)
        return res
    }

    private suspend fun saveSession(tokens: MdTokens, profile: MdProfile) {
        context.updateSetting(SettingKeys.MD_ACCESS_TOKEN, tokens.accessToken)
        if (tokens.refreshToken.isNotBlank()) context.updateSetting(SettingKeys.MD_REFRESH_TOKEN, tokens.refreshToken)
        context.updateSetting(SettingKeys.MD_PROFILE_NAME, profile.username)
        context.updateSetting(SettingKeys.MD_PROFILE_URL, profile.url)
        context.clearSetting(SettingKeys.MD_PASSWORD)
    }

    private suspend fun check(target: String, title: String, critical: Boolean, block: suspend () -> String) {
        val now = services.clock.nowEpochSec()
        try {
            val message = block()
            diagnostics.put(target, Diagnostic(true, message, now))
            context.logger.info("$title: $message")
            context.showToast(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            diagnostics.put(target, Diagnostic(false, message, now))
            context.logger.error("$title failed: $message", e)
            if (critical) context.showNotification("$title failed", message, PluginNotificationType.ERROR)
            else context.showToast("$title failed: $message")
        }
    }
}
