package com.wip.mangauploader

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Setting keys shared by the settings class, UI, locks and workers. */
object SettingKeys {
    const val CUBARI_USERNAME = "cubariUsername"
    const val CUBARI_TOKEN = "cubariGithubToken"
    const val CUBARI_REMOTE_URL = "cubariRemoteRepoUrl"
    const val CUBARI_LOCAL_PATH = "cubariLocalRepoPath"
    const val CUBARI_BRANCH = "cubariBranch"
    const val CUBARI_SERIES_DIR = "cubariSeriesDir"
    const val CUBARI_GROUP = "cubariGroupName"

    const val ARUBA_PROTOCOL = "arubaProtocol"
    const val ARUBA_HOST = "arubaHost"
    const val ARUBA_PORT = "arubaPort"
    const val ARUBA_USERNAME = "arubaUsername"
    const val ARUBA_PASSWORD = "arubaPassword"
    const val ARUBA_CHAPTERS_PATH = "arubaChaptersPath"
    const val ARUBA_PUBLIC_URL = "arubaPublicBaseUrl"
    const val ARUBA_PAGE_PADDING = "arubaPagePadding"
    const val CONCURRENT_UPLOADS = "concurrentUploads"
    const val CONCURRENT_DOWNLOADS = "concurrentDownloads"
    const val CONCURRENT_CONNECTIONS = "concurrentConnections"

    const val MD_CLIENT_ID = "mangadexClientId"
    const val MD_CLIENT_SECRET = "mangadexClientSecret"
    const val MD_USERNAME = "mangadexUsername"
    const val MD_PASSWORD = "mangadexPassword"
    const val MD_ACCESS_TOKEN = "mangadexAccessToken"
    const val MD_REFRESH_TOKEN = "mangadexRefreshToken"
    const val MD_PROFILE_NAME = "mangadexProfileName"
    const val MD_PROFILE_URL = "mangadexProfileUrl"
    const val MD_GROUP = "mangadexGroupId"
    const val MD_LANGUAGE = "mangadexLanguage"
    const val MD_UPLOAD_INTERVAL = "mangadexUploadIntervalMs"
    const val MD_API_BASE_URL = "mangadexApiBaseUrl"
    const val MD_AUTH_BASE_URL = "mangadexAuthBaseUrl"
    const val MD_OAUTH_REDIRECT_URI = "mangadexOAuthRedirectUri"
    const val MD_PROXY_URL = "mangadexProxyUrl"
    const val MD_AUTO_DOH = "mangadexAutoDoh"

    const val MAX_UPLOAD_KBPS = "maxUploadSpeedKbps"
    const val MAX_DOWNLOAD_KBPS = "maxDownloadSpeedKbps"

    const val PREVIEW_HOURS = "defaultPreviewHours"
    const val PREVIEW_ANCHOR_MD = "previewAnchorLatestMangaDex"

    /** Settings restored by "Reset Defaults" in the Preview Hold card. */
    val PREVIEW_KEYS = listOf(PREVIEW_HOURS, PREVIEW_ANCHOR_MD)

    /** Settings that hold MangaDex session state (cleared by Log Out). */
    val MD_SESSION_KEYS = listOf(MD_ACCESS_TOKEN, MD_REFRESH_TOKEN, MD_PROFILE_NAME, MD_PROFILE_URL, MD_PASSWORD)
}

const val DEFAULT_PREVIEW_HOURS = 24
const val DEFAULT_MD_API_URL = "https://api.mangadex.org"
const val DEFAULT_MD_AUTH_URL = "https://auth.mangadex.org"

/**
 * Immutable typed snapshot of the effective settings (manifest defaults merged with user overrides).
 * Built from `context.settings` / `context.settingsFlow.value`, so it is trivially unit-testable.
 */
data class MangaUploaderConfig(
    val cubariUsername: String = "",
    val cubariToken: String = "",
    val cubariRemoteUrl: String = "",
    val cubariLocalPath: String = "",
    val cubariBranch: String = "main",
    val cubariSeriesDir: String = "",
    val cubariGroupName: String = "Uploader",

    val arubaProtocol: String = "SFTP",
    val arubaHost: String = "",
    val arubaPort: Int = 22,
    val arubaUsername: String = "",
    val arubaPassword: String = "",
    val arubaChaptersPath: String = "/chapters",
    val arubaPublicBaseUrl: String = "",
    val arubaPagePadding: String = "auto",
    val concurrentUploads: Int = 2,
    val concurrentDownloads: Int = 2,
    val concurrentConnections: Int = 2,

    val mdClientId: String = "",
    val mdClientSecret: String = "",
    val mdUsername: String = "",
    val mdPassword: String = "",
    val mdAccessToken: String = "",
    val mdRefreshToken: String = "",
    val mdProfileName: String = "",
    val mdProfileUrl: String = "",
    val mdGroupRaw: String = "",
    val mdLanguage: String = "en",
    val mdUploadIntervalMs: Int = 1500,
    val mdApiBaseUrl: String = DEFAULT_MD_API_URL,
    val mdAuthBaseUrl: String = DEFAULT_MD_AUTH_URL,
    val mdOAuthRedirectUri: String = "",
    val mdProxyUrl: String = "",
    val mdAutoDoh: Boolean = true,

    val maxUploadKbps: Int = 0,
    val maxDownloadKbps: Int = 0,

    val defaultPreviewHours: Int = DEFAULT_PREVIEW_HOURS,
    val previewAnchorLatestMangaDex: Boolean = true
) {
    /** Group UUID extracted from [mdGroupRaw] (plain UUID or `https://mangadex.org/group/<uuid>/...`). */
    val mdGroupId: String? get() = extractUuid(mdGroupRaw)
    val isMangaDexAuthenticated: Boolean get() = mdAccessToken.isNotBlank() || mdRefreshToken.isNotBlank()

    companion object {
        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun extractUuid(text: String): String? = UUID_REGEX.find(text)?.value?.lowercase()

        /** Normalizes repository series subfolder (`""`, `"/"`, `"."`, `"./"` all become `""` = repo root). */
        fun normalizeRepoDir(raw: String): String {
            val cleaned = raw.trim().replace('\\', '/').removePrefix("./").trim('/')
            return if (cleaned.isEmpty() || cleaned == ".") "" else cleaned
        }

        fun normalizePaddingMode(raw: String): String {
            val cleaned = raw.trim().lowercase()
            return if (cleaned in setOf("auto", "0", "2", "3", "4")) cleaned else "auto"
        }

        fun from(settings: Map<String, JsonElement>): MangaUploaderConfig {
            fun str(key: String, default: String = ""): String =
                (settings[key] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" } ?: default

            fun int(key: String, default: Int): Int =
                (settings[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() ?: default

            fun bool(key: String, default: Boolean): Boolean =
                (settings[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toBooleanStrictOrNull() ?: default

            return MangaUploaderConfig(
                cubariUsername = str(SettingKeys.CUBARI_USERNAME).trim(),
                cubariToken = str(SettingKeys.CUBARI_TOKEN).trim(),
                cubariRemoteUrl = str(SettingKeys.CUBARI_REMOTE_URL).trim(),
                cubariLocalPath = str(SettingKeys.CUBARI_LOCAL_PATH).trim(),
                cubariBranch = str(SettingKeys.CUBARI_BRANCH, "main").trim(),
                cubariSeriesDir = normalizeRepoDir(str(SettingKeys.CUBARI_SERIES_DIR, "")),
                cubariGroupName = str(SettingKeys.CUBARI_GROUP, "Uploader").trim().ifEmpty { "Uploader" },
                arubaProtocol = str(SettingKeys.ARUBA_PROTOCOL, "SFTP").trim().uppercase().ifEmpty { "SFTP" },
                arubaHost = str(SettingKeys.ARUBA_HOST).trim(),
                arubaPort = int(SettingKeys.ARUBA_PORT, 22).coerceIn(1, 65535),
                arubaUsername = str(SettingKeys.ARUBA_USERNAME).trim(),
                arubaPassword = str(SettingKeys.ARUBA_PASSWORD),
                arubaChaptersPath = str(SettingKeys.ARUBA_CHAPTERS_PATH, "/chapters").trim().ifEmpty { "/" },
                arubaPublicBaseUrl = str(SettingKeys.ARUBA_PUBLIC_URL).trim(),
                arubaPagePadding = normalizePaddingMode(str(SettingKeys.ARUBA_PAGE_PADDING, "auto")),
                concurrentUploads = int(SettingKeys.CONCURRENT_UPLOADS, 2).coerceIn(1, 16),
                concurrentDownloads = int(SettingKeys.CONCURRENT_DOWNLOADS, 2).coerceIn(1, 16),
                concurrentConnections = int(SettingKeys.CONCURRENT_CONNECTIONS, 2).coerceIn(1, 16),
                mdClientId = str(SettingKeys.MD_CLIENT_ID).trim(),
                mdClientSecret = str(SettingKeys.MD_CLIENT_SECRET).trim(),
                mdUsername = str(SettingKeys.MD_USERNAME).trim(),
                mdPassword = str(SettingKeys.MD_PASSWORD),
                mdAccessToken = str(SettingKeys.MD_ACCESS_TOKEN).trim(),
                mdRefreshToken = str(SettingKeys.MD_REFRESH_TOKEN).trim(),
                mdProfileName = str(SettingKeys.MD_PROFILE_NAME).trim(),
                mdProfileUrl = str(SettingKeys.MD_PROFILE_URL).trim(),
                mdGroupRaw = str(SettingKeys.MD_GROUP).trim(),
                mdLanguage = str(SettingKeys.MD_LANGUAGE, "en").trim().ifEmpty { "en" },
                mdUploadIntervalMs = int(SettingKeys.MD_UPLOAD_INTERVAL, 1500).coerceAtLeast(0),
                mdApiBaseUrl = str(SettingKeys.MD_API_BASE_URL, DEFAULT_MD_API_URL).trim().trimEnd('/').ifEmpty { DEFAULT_MD_API_URL },
                mdAuthBaseUrl = str(SettingKeys.MD_AUTH_BASE_URL, DEFAULT_MD_AUTH_URL).trim().trimEnd('/').ifEmpty { DEFAULT_MD_AUTH_URL },
                mdOAuthRedirectUri = str(SettingKeys.MD_OAUTH_REDIRECT_URI).trim(),
                mdProxyUrl = str(SettingKeys.MD_PROXY_URL).trim(),
                mdAutoDoh = bool(SettingKeys.MD_AUTO_DOH, true),
                maxUploadKbps = int(SettingKeys.MAX_UPLOAD_KBPS, 0).coerceAtLeast(0),
                maxDownloadKbps = int(SettingKeys.MAX_DOWNLOAD_KBPS, 0).coerceAtLeast(0),
                defaultPreviewHours = int(SettingKeys.PREVIEW_HOURS, DEFAULT_PREVIEW_HOURS).coerceAtLeast(0),
                previewAnchorLatestMangaDex = bool(SettingKeys.PREVIEW_ANCHOR_MD, true)
            )
        }
    }
}
