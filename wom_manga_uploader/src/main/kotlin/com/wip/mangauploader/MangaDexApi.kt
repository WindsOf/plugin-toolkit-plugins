package com.wip.mangauploader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.wip.plugintoolkit.api.HttpMethod
import org.wip.plugintoolkit.api.HttpRequest
import org.wip.plugintoolkit.api.HttpResponse
import org.wip.plugintoolkit.api.MultipartPart
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.PluginNetworkClient
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.time.OffsetDateTime

data class MdTokens(val accessToken: String, val refreshToken: String, val expiresInSec: Long)
data class MdProfile(val id: String, val username: String) {
    val url: String get() = "https://mangadex.org/user/$id"
}

data class MdGroup(val id: String, val name: String)

data class MdChapter(
    val id: String,
    val chapter: String?,
    val volume: String?,
    val title: String?,
    val publishAtEpochSec: Long?,
    val version: Int,
    val pageCount: Int,
    val groupIds: Set<String> = emptySet(),
    val hasGroupRelationships: Boolean = false,
    val updatedAtEpochSec: Long? = null
) {
    val key: String get() = normalizeChapterKey(chapter.orEmpty())
}

data class MdChapterDraft(val volume: String, val chapter: String, val title: String, val language: String)
data class MdUploadFile(val fileName: String, val bytes: ByteArray, val contentType: String)
data class MdUploadSession(
    val id: String,
    val isCommitted: Boolean = false,
    val isProcessed: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    /** Most recent session activity timestamp (prefers [updatedAt], falls back to [createdAt]). */
    val lastActivityEpochSec: Long?
        get() = listOfNotNull(updatedAt, createdAt)
            .mapNotNull { raw -> runCatching { OffsetDateTime.parse(raw.trim()).toEpochSecond() }.getOrNull() }
            .maxOrNull()

    /** Age in seconds relative to [nowEpochSec], or `null` when timestamps are unavailable/unparseable. */
    fun ageSeconds(nowEpochSec: Long): Long? =
        lastActivityEpochSec?.let { (nowEpochSec - it).coerceAtLeast(0L) }
}

/** One file entry pre-populated inside a MangaDex edit upload session. */
data class MdSessionFileInfo(
    val id: String,
    val originalFileName: String? = null,
    val fileHash: String? = null
)

/** Active MangaDex chapter-edit session together with its pre-existing session file entries. */
data class MdEditSession(
    val sessionId: String,
    val prefilledFiles: List<MdSessionFileInfo> = emptyList()
)

class MangaDexException(val statusCode: Int, message: String) : RuntimeException(message)

/** Subset of the MangaDex REST API required by the uploader. */
interface MangaDexApi {
    fun authorizeUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        "$DEFAULT_MD_AUTH_URL/realms/mangadex/protocol/openid-connect/auth"

    suspend fun login(clientId: String, clientSecret: String, username: String, password: String): MdTokens
    suspend fun exchangeCode(clientId: String, clientSecret: String, code: String, redirectUri: String, codeVerifier: String): MdTokens =
        throw UnsupportedOperationException("Authorization code exchange not implemented")

    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): MdTokens
    suspend fun profile(accessToken: String): MdProfile
    suspend fun group(groupId: String): MdGroup
    suspend fun listChapters(mangaId: String, groupId: String?, language: String): List<MdChapter>

    /** Full-size page image URLs of a published chapter (MangaDex@Home). */
    suspend fun pageUrls(chapterId: String): List<String>
    suspend fun checkUrl(url: String): Int = 200
    suspend fun download(url: String): ByteArray

    suspend fun activeUploadSession(accessToken: String): MdUploadSession? = null
    suspend fun closeUploadSession(accessToken: String, sessionId: String) {
        abort(accessToken, sessionId)
    }

    suspend fun beginUpload(accessToken: String, mangaId: String, groupId: String): String
    suspend fun beginEdit(accessToken: String, chapterId: String, version: Int): String
    suspend fun beginEditSession(accessToken: String, chapterId: String, version: Int): MdEditSession =
        MdEditSession(beginEdit(accessToken, chapterId, version))
    suspend fun updateChapterMetadata(accessToken: String, chapterId: String, version: Int, draft: MdChapterDraft): MdChapter =
        throw UnsupportedOperationException("Metadata-only chapter update not implemented")
    suspend fun uploadPages(accessToken: String, sessionId: String, files: List<MdUploadFile>): List<String>
    suspend fun commit(accessToken: String, sessionId: String, draft: MdChapterDraft, pageOrder: List<String>): MdChapter
    suspend fun abort(accessToken: String, sessionId: String)
}

/**
 * [MangaDexApi] on top of the host's metered [PluginNetworkClient].
 * Honors `429 Too Many Requests` (`Retry-After`) with bounded retries.
 */
class HttpMangaDexApi(
    private val network: PluginNetworkClient,
    private val logger: PluginLogger? = null,
    private val apiBaseUrl: String = DEFAULT_MD_API_URL,
    private val authBaseUrl: String = DEFAULT_MD_AUTH_URL,
    private val usingProxy: Boolean = false,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 5
) : MangaDexApi {

    private val api get() = apiBaseUrl.trimEnd('/')
    private val authBase get() = authBaseUrl.trimEnd('/')
    private val tokenUrl get() = "$authBase/realms/mangadex/protocol/openid-connect/token"

    override fun authorizeUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        "$authBase/realms/mangadex/protocol/openid-connect/auth" +
            "?response_type=code" +
            "&client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(redirectUri)}" +
            "&state=${enc(state)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&scope=${enc("openid email profile groups offline_access")}"

    override suspend fun login(clientId: String, clientSecret: String, username: String, password: String): MdTokens =
        token(
            listOfNotNull(
                "grant_type" to "password",
                "username" to username,
                "password" to password,
                "client_id" to clientId,
                clientSecret.takeIf { it.isNotBlank() }?.let { "client_secret" to it }
            )
        )

    override suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        codeVerifier: String
    ): MdTokens = token(
        listOfNotNull(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to codeVerifier,
            clientSecret.takeIf { it.isNotBlank() }?.let { "client_secret" to it }
        )
    )

    override suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): MdTokens =
        token(
            listOfNotNull(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
                clientSecret.takeIf { it.isNotBlank() }?.let { "client_secret" to it }
            )
        )

    private suspend fun token(form: List<Pair<String, String>>): MdTokens {
        val body = form.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.encodeToByteArray()
        val response = send(
            HttpRequest(
                url = tokenUrl, method = HttpMethod.POST, body = body,
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")
            )
        )
        val json = parse(response)
        return MdTokens(
            accessToken = json.str("access_token") ?: throw MangaDexException(response.statusCode, "No access_token in response"),
            refreshToken = json.str("refresh_token").orEmpty(),
            expiresInSec = json["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 900L
        )
    }

    override suspend fun profile(accessToken: String): MdProfile {
        val data = parse(send(get("$api/user/me", accessToken)))["data"]?.jsonObject
            ?: throw MangaDexException(0, "Malformed /user/me response")
        return MdProfile(
            id = data.str("id").orEmpty(),
            username = data["attributes"]?.jsonObject?.str("username").orEmpty()
        )
    }

    override suspend fun group(groupId: String): MdGroup {
        val data = parse(send(get("$api/group/$groupId", null)))["data"]?.jsonObject
            ?: throw MangaDexException(0, "Malformed /group response")
        return MdGroup(groupId, data["attributes"]?.jsonObject?.str("name").orEmpty())
    }

    override suspend fun listChapters(mangaId: String, groupId: String?, language: String): List<MdChapter> {
        val result = mutableListOf<MdChapter>()
        var offset = 0
        val cleanLang = language.trim()
        val langQuery = if (cleanLang.isNotBlank()) "&translatedLanguage[]=${enc(cleanLang)}" else ""
        val ratingQuery = "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica&contentRating[]=pornographic"
        while (true) {
            // /manga/{id}/feed does not accept groups[] query param; fetch with scanlation_group relationship and filter locally.
            // Note: passing includeExternalUrl=0 / includeFuturePublishAt=1 on /feed returns empty collections on live MangaDex for many titles.
            val url = "$api/manga/$mangaId/feed?limit=$PAGE_LIMIT&offset=$offset$langQuery$ratingQuery" +
                "&includes[]=scanlation_group&order[chapter]=asc"
            val json = parse(send(get(url, null)))
            val data = json["data"]?.jsonArray ?: JsonArray(emptyList())
            data.forEach { item ->
                val obj = item.jsonObject
                if (obj["attributes"]?.jsonObject?.str("externalUrl") != null) return@forEach
                val parsed = parseChapter(obj)
                if (groupId == null || !parsed.hasGroupRelationships || groupId in parsed.groupIds) {
                    result += parsed
                }
            }
            val total = json["total"]?.jsonPrimitive?.intOrNull ?: 0
            offset += data.size
            if (data.isEmpty() || offset >= total) break
        }
        return result
    }

    override suspend fun pageUrls(chapterId: String): List<String> {
        val json = parse(send(get("$api/at-home/server/$chapterId", null)))
        val base = json.str("baseUrl") ?: throw MangaDexException(0, "No baseUrl in at-home response")
        val chapter = json["chapter"]?.jsonObject ?: throw MangaDexException(0, "No chapter in at-home response")
        val hash = chapter.str("hash").orEmpty()
        return chapter["data"]?.jsonArray?.map { "$base/data/$hash/${it.jsonPrimitive.content}" } ?: emptyList()
    }

    override suspend fun checkUrl(url: String): Int {
        if (url.isBlank()) return -1
        val imageHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "image/*,*/*;q=0.8")
        val head = runCatching {
            network.request(HttpRequest(url = url, method = HttpMethod.HEAD, headers = imageHeaders, timeoutMs = 10_000L))
        }.getOrNull()
        val code = head?.statusCode ?: -1
        if (code in 200..399) return code
        // CDNs / Apache / WAF / transport quirks may reject or fail HEAD; verify via a 1-byte ranged GET.
        val range = runCatching {
            network.request(
                HttpRequest(
                    url = url,
                    method = HttpMethod.GET,
                    headers = imageHeaders + ("Range" to "bytes=0-0"),
                    timeoutMs = 15_000L
                )
            )
        }.getOrNull()
        return range?.statusCode?.takeIf { it > 0 } ?: if (code > 0) code else -1
    }

    override suspend fun download(url: String): ByteArray {
        require(url.isNotBlank()) { "Cannot download image: empty URL" }
        val imageHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "image/*,*/*;q=0.8")
        val response = send(HttpRequest(url = url, method = HttpMethod.GET, headers = imageHeaders, timeoutMs = 60_000L))
        return response.body
    }

    override suspend fun activeUploadSession(accessToken: String): MdUploadSession? {
        val response = try {
            send(get("$api/upload", accessToken))
        } catch (e: MangaDexException) {
            if (e.statusCode == 404) return null
            throw e
        }
        val data = parse(response)["data"]?.jsonObject ?: return null
        val id = data.str("id") ?: return null
        val attrs = data["attributes"]?.jsonObject ?: JsonObject(emptyMap())
        return MdUploadSession(
            id = id,
            isCommitted = attrs.bool("isCommitted"),
            isProcessed = attrs.bool("isProcessed"),
            isDeleted = attrs.bool("isDeleted"),
            createdAt = attrs.str("createdAt"),
            updatedAt = attrs.str("updatedAt")
        )
    }

    override suspend fun closeUploadSession(accessToken: String, sessionId: String) {
        send(HttpRequest(url = "$api/upload/$sessionId", method = HttpMethod.DELETE, headers = auth(accessToken)))
    }

    override suspend fun beginUpload(accessToken: String, mangaId: String, groupId: String): String {
        val body = buildJsonObject {
            put("manga", mangaId)
            putJsonArray("groups") { add(JsonPrimitive(groupId)) }
        }
        return beginSession("$api/upload/begin", accessToken, body)
    }

    override suspend fun beginEdit(accessToken: String, chapterId: String, version: Int): String =
        beginEditSession(accessToken, chapterId, version).sessionId

    override suspend fun beginEditSession(accessToken: String, chapterId: String, version: Int): MdEditSession {
        suspend fun openEdit(ver: Int): MdEditSession {
            val body = buildJsonObject { put("version", ver) }
            return parseEditSession(send(post("$api/upload/begin/$chapterId", accessToken, body)))
        }
        return try {
            openEdit(version)
        } catch (e: MangaDexException) {
            if (e.statusCode != 409) throw e
            val latest = parse(send(get("$api/chapter/$chapterId", accessToken)))["data"]?.jsonObject ?: throw e
            val latestVer = latest["attributes"]?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull ?: throw e
            openEdit(latestVer)
        }
    }

    override suspend fun updateChapterMetadata(
        accessToken: String,
        chapterId: String,
        version: Int,
        draft: MdChapterDraft
    ): MdChapter {
        suspend fun sendUpdate(ver: Int): MdChapter {
            val body = buildJsonObject {
                put("volume", draft.volume.trim().ifBlank { null })
                put("chapter", draft.chapter.trim().ifBlank { null })
                put("title", draft.title.trim().ifBlank { null })
                put("translatedLanguage", draft.language.trim())
                put("version", ver)
            }
            val json = parse(send(jsonRequest(HttpMethod.PUT, "$api/chapter/$chapterId", accessToken, body)))
            return parseChapter(json["data"]?.jsonObject ?: throw MangaDexException(0, "Malformed /chapter update response"))
        }
        return try {
            sendUpdate(version)
        } catch (e: MangaDexException) {
            if (e.statusCode != 409) throw e
            val latest = parse(send(get("$api/chapter/$chapterId", accessToken)))["data"]?.jsonObject
                ?: throw e
            val latestVer = latest["attributes"]?.jsonObject?.get("version")?.jsonPrimitive?.intOrNull ?: throw e
            sendUpdate(latestVer)
        }
    }

    private suspend fun beginSession(url: String, accessToken: String, body: JsonElement): String = try {
        sessionId(send(post(url, accessToken, body)))
    } catch (e: MangaDexException) {
        val msg = e.message.orEmpty()
        if (msg.contains("Upload session already exists", ignoreCase = true)) {
            throw MangaDexException(
                e.statusCode,
                "$msg — use 'Force Cancel Upload Session' in Settings & Locks (MangaDex) to close it."
            )
        }
        throw e
    }

    override suspend fun uploadPages(accessToken: String, sessionId: String, files: List<MdUploadFile>): List<String> {
        val response = send(
            HttpRequest(
                url = "$api/upload/$sessionId", method = HttpMethod.POST,
                headers = auth(accessToken),
                multipartParts = files.map { MultipartPart("file", it.bytes, it.fileName, it.contentType) },
                timeoutMs = 120_000L
            )
        )
        val json = parse(response)
        val serverErrors = json["errors"]?.jsonArray.orEmpty().mapNotNull { errEl ->
            val errObj = errEl as? JsonObject ?: return@mapNotNull null
            val detail = errObj.str("detail")?.trim().orEmpty()
            val title = errObj.str("title")?.trim().orEmpty()
            val status = errObj["status"]?.jsonPrimitive?.intOrNull
            listOfNotNull(
                status?.let { "HTTP $it" },
                title.ifBlank { null },
                detail.ifBlank { null }
            ).joinToString(": ").ifBlank { null }
        }
        val data = json["data"]?.jsonArray
        if (data == null || (data.isEmpty() && serverErrors.isNotEmpty())) {
            val detail = if (serverErrors.isNotEmpty()) serverErrors.joinToString("; ") else "No upload data"
            throw MangaDexException(response.statusCode, "MangaDex upload batch rejected: $detail")
        }
        val acks = data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val origName = o["attributes"]?.jsonObject?.str("originalFileName").orEmpty()
            id to origName
        }
        if (serverErrors.isNotEmpty() && acks.size < files.size) {
            throw MangaDexException(
                response.statusCode,
                "MangaDex upload errors: ${serverErrors.joinToString("; ")}"
            )
        }
        val remaining = acks.toMutableList()
        fun consumeMatch(predicate: (String) -> Boolean): String? {
            val idx = remaining.indexOfFirst { predicate(it.second) }
            return if (idx >= 0) remaining.removeAt(idx).first else null
        }
        val resultIds = arrayOfNulls<String>(files.size)
        // Pass 1: exact originalFileName match.
        files.forEachIndexed { idx, file ->
            resultIds[idx] = consumeMatch { it == file.fileName }
        }
        // Pass 2: case-insensitive / stripped-path match.
        fun normName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').trim().lowercase()
        files.forEachIndexed { idx, file ->
            if (resultIds[idx] == null) {
                val target = normName(file.fileName)
                resultIds[idx] = consumeMatch { normName(it) == target }
            }
        }
        // Pass 3: positional fallback when server reported no errors and every uploaded file has an ID.
        val missingIndices = files.indices.filter { resultIds[it] == null }
        if (missingIndices.isNotEmpty() && serverErrors.isEmpty() && remaining.size == missingIndices.size) {
            logger?.warn(
                "MangaDex uploadPages returned ${remaining.size} filename(s) differing from input " +
                    "(${remaining.joinToString { it.second }}); matching positionally"
            )
            missingIndices.forEach { idx ->
                resultIds[idx] = remaining.removeAt(0).first
            }
        }
        return files.mapIndexed { idx, file ->
            resultIds[idx] ?: run {
                val errHint = if (serverErrors.isNotEmpty()) " (errors: ${serverErrors.joinToString("; ")})" else ""
                val ackNames = acks.mapNotNull { it.second.ifBlank { null } }
                val ackHint = if (ackNames.isNotEmpty()) " [acknowledged: ${ackNames.joinToString()}]" else ""
                throw MangaDexException(0, "Upload of ${file.fileName} was not acknowledged$errHint$ackHint")
            }
        }
    }

    override suspend fun commit(accessToken: String, sessionId: String, draft: MdChapterDraft, pageOrder: List<String>): MdChapter {
        val body = buildJsonObject {
            putJsonObject("chapterDraft") {
                put("volume", draft.volume.ifBlank { null })
                put("chapter", draft.chapter)
                put("title", draft.title.ifBlank { null })
                put("translatedLanguage", draft.language)
            }
            put("pageOrder", buildJsonArray { pageOrder.forEach { add(JsonPrimitive(it)) } })
        }
        val json = parse(send(post("$api/upload/$sessionId/commit", accessToken, body)))
        return parseChapter(json["data"]?.jsonObject ?: throw MangaDexException(0, "Malformed commit response"))
    }

    override suspend fun abort(accessToken: String, sessionId: String) {
        runCatching {
            send(HttpRequest(url = "$api/upload/$sessionId", method = HttpMethod.DELETE, headers = auth(accessToken)))
        }.onFailure { logger?.warn("Could not abort MangaDex upload session $sessionId: ${it.message}") }
    }

    // ── helpers ───────────────────────────────────────────────────

    private fun sessionId(response: HttpResponse): String =
        parse(response)["data"]?.jsonObject?.str("id") ?: throw MangaDexException(response.statusCode, "No upload session id")

    private fun parseEditSession(response: HttpResponse): MdEditSession {
        val root = parse(response)
        val data = root["data"]?.jsonObject ?: throw MangaDexException(response.statusCode, "No upload session id")
        val id = data.str("id") ?: throw MangaDexException(response.statusCode, "No upload session id")
        val includedAttrs = (root["included"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.str("type") == "upload_session_file" }
            ?.mapNotNull { item ->
                val fileId = item.str("id") ?: return@mapNotNull null
                val attrs = item["attributes"]?.jsonObject
                fileId to (attrs?.str("originalFileName") to (attrs?.str("fileHash") ?: attrs?.str("hash")))
            }
            ?.toMap()
            .orEmpty()
        val prefilled = (data["relationships"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.str("type") == "upload_session_file" }
            ?.mapNotNull { rel ->
                val fileId = rel.str("id") ?: return@mapNotNull null
                val attrs = rel["attributes"]?.jsonObject
                val inc = includedAttrs[fileId]
                MdSessionFileInfo(
                    id = fileId,
                    originalFileName = attrs?.str("originalFileName") ?: inc?.first,
                    fileHash = attrs?.str("fileHash") ?: attrs?.str("hash") ?: inc?.second
                )
            }
            .orEmpty()
        return MdEditSession(sessionId = id, prefilledFiles = prefilled)
    }

    private fun parseChapter(obj: JsonObject): MdChapter {
        val attrs = obj["attributes"]?.jsonObject ?: JsonObject(emptyMap())
        val relationships = obj["relationships"] as? JsonArray
        val groupIds = relationships
            ?.mapNotNull { it as? JsonObject }
            ?.filter { it.str("type") == "scanlation_group" }
            ?.mapNotNull { it.str("id") }
            ?.toSet()
            .orEmpty()
        return MdChapter(
            id = obj.str("id").orEmpty(),
            chapter = attrs.str("chapter"),
            volume = attrs.str("volume"),
            title = attrs.str("title"),
            publishAtEpochSec = parseIsoEpochSec(attrs.str("publishAt")),
            version = attrs["version"]?.jsonPrimitive?.intOrNull ?: 1,
            pageCount = attrs["pages"]?.jsonPrimitive?.intOrNull ?: 0,
            groupIds = groupIds,
            hasGroupRelationships = relationships != null,
            updatedAtEpochSec = parseIsoEpochSec(attrs.str("updatedAt"))
        )
    }

    private fun parseIsoEpochSec(raw: String?): Long? =
        raw?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

    private fun get(url: String, token: String?) = HttpRequest(url = url, method = HttpMethod.GET, headers = auth(token))

    private fun post(url: String, token: String, body: JsonElement) =
        jsonRequest(HttpMethod.POST, url, token, body)

    private fun jsonRequest(method: HttpMethod, url: String, token: String, body: JsonElement) = HttpRequest(
        url = url, method = method, body = body.toString().encodeToByteArray(),
        headers = auth(token) + ("Content-Type" to "application/json")
    )

    private fun auth(token: String?): Map<String, String> =
        buildMap {
            put("User-Agent", USER_AGENT)
            if (!token.isNullOrBlank()) put("Authorization", "Bearer $token")
        }

    private fun parse(response: HttpResponse): JsonObject =
        runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw MangaDexException(response.statusCode, "Invalid JSON response (HTTP ${response.statusCode})") }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** Sends [request], retrying on 429 / transient 5xx. Throws [MangaDexException] on final failure. */
    private suspend fun send(request: HttpRequest): HttpResponse {
        var attempt = 0
        while (true) {
            attempt++
            val response = network.request(request)
            if (response.isSuccess) return response
            val retryable = response.statusCode == 429 || response.statusCode in 500..599
            if (!retryable || attempt >= maxAttempts) {
                val detail = response.errorMessage ?: response.bodyAsText().take(300)
                val dnsHint = if (response.statusCode == 0 && !usingProxy) loopbackDnsHint(request.url) else ""
                throw MangaDexException(
                    response.statusCode,
                    "HTTP ${response.statusCode} ${request.method} ${request.url.substringBefore('?')}: $detail$dnsHint"
                )
            }
            val retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
            val waitMs = (retryAfterSec?.times(1000L)) ?: (BACKOFF_BASE_MS * attempt)
            logger?.warn("MangaDex returned HTTP ${response.statusCode}, retrying in ${waitMs}ms (attempt $attempt/$maxAttempts)")
            sleeper(waitMs)
        }
    }

    private suspend fun loopbackDnsHint(url: String): String = withContext(Dispatchers.IO) {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        if (host.isBlank() || host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return@withContext ""
        val addrs = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        val loopback = addrs.filter { it.isLoopbackAddress }
        if (loopback.isEmpty()) ""
        else " (OS DNS resolved '$host' to loopback ${loopback.joinToString { it.hostAddress }}; configure an HTTP Proxy URL or mirror base URL in Settings & Locks)"
    }

    companion object {
        const val API = "https://api.mangadex.org"
        const val AUTH_URL = "https://auth.mangadex.org/realms/mangadex/protocol/openid-connect/token"
        private const val USER_AGENT = "WOM-MangaUploader/1.0"
        private const val PAGE_LIMIT = 100
        private const val BACKOFF_BASE_MS = 2_000L
    }
}
