package com.wip.mangauploader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.wip.plugintoolkit.api.ExecutionFileSystem
import org.wip.plugintoolkit.api.ExecutionResult
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.HttpRequest
import org.wip.plugintoolkit.api.HttpResponse
import org.wip.plugintoolkit.api.PluginAction
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginEntry
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.PluginNetworkClient
import org.wip.plugintoolkit.api.PluginNotificationType
import org.wip.plugintoolkit.api.PluginRequest
import org.wip.plugintoolkit.api.PluginResponse
import org.wip.plugintoolkit.api.PluginSecretStorage
import org.wip.plugintoolkit.api.PluginSignal
import org.wip.plugintoolkit.api.PluginSignalManager
import org.wip.plugintoolkit.api.PluginStorage
import org.wip.plugintoolkit.api.ProgressData
import org.wip.plugintoolkit.api.ProgressReporter
import org.wip.plugintoolkit.api.RelativePath
import org.wip.plugintoolkit.api.sdui.CapabilityJobSnapshot
import org.wip.plugintoolkit.api.sdui.CapabilityJobStatus
import org.wip.plugintoolkit.api.sdui.UiNode
import org.wip.plugintoolkit.api.sdui.UiNodeTree
import org.wip.plugintoolkit.api.sdui.UiSessionContext

class FakeClock(var epochSec: Long = 1_700_000_000L, var nanos: Long = 1_000_000_000L) : Clock {
    override fun nowEpochSec(): Long = epochSec
    override fun nowNanos(): Long = nanos
}

class FakeLogger : PluginLogger {
    val entries = mutableListOf<String>()
    override fun verbose(message: String) { entries += "VERBOSE: $message" }
    override fun debug(message: String) { entries += "DEBUG: $message" }
    override fun info(message: String) { entries += "INFO: $message" }
    override fun warn(message: String) { entries += "WARN: $message" }
    override fun error(message: String, throwable: Throwable?) { entries += "ERROR: $message" }
}

class FakeProgressReporter(
    private val onPrimary: (ProgressData) -> Unit = {},
    private val onSecondary: (ProgressData?) -> Unit = {}
) : ProgressReporter {
    val reports = mutableListOf<ProgressData>()
    override fun report(progress: Float) = report(ProgressData.percentage(progress))
    override fun report(data: ProgressData) {
        reports += data
        onPrimary(data)
    }
    override fun reportSecondary(progress: Float) = onSecondary(ProgressData.percentage(progress))
    override fun reportSecondary(data: ProgressData) = onSecondary(data)
    override fun clearSecondary() = onSecondary(null)
}

class FakeSignals : PluginSignalManager {
    private val handlers = mutableListOf<suspend (PluginSignal) -> Unit>()
    override fun onSignal(handler: suspend (PluginSignal) -> Unit) { handlers += handler }
    override suspend fun sendSignal(signal: PluginSignal) { handlers.forEach { it(signal) } }
}

class FakeStorage : PluginStorage {
    val map = mutableMapOf<String, JsonElement>()
    override suspend fun get(key: String): JsonElement? = map[key]
    override suspend fun put(key: String, value: JsonElement) { map[key] = value }
    override suspend fun getAll(): Map<String, JsonElement> = map.toMap()
    override suspend fun remove(key: String) { map.remove(key) }
}

class FakePluginFs(private val basePath: String = "/plugin-data") : PluginFileSystem {
    val files = mutableMapOf<String, ByteArray>()
    override suspend fun readFile(relativePath: RelativePath): ByteArray? = files[relativePath.value]
    override suspend fun readTextFile(relativePath: RelativePath): String? = files[relativePath.value]?.decodeToString()
    override suspend fun writeFile(relativePath: RelativePath, data: ByteArray): Result<Unit> {
        files[relativePath.value] = data
        return Result.success(Unit)
    }
    override suspend fun writeTextFile(relativePath: RelativePath, text: String): Result<Unit> =
        writeFile(relativePath, text.encodeToByteArray())
    override suspend fun exists(relativePath: RelativePath): Boolean = relativePath.value in files
    override suspend fun listFiles(relativePath: RelativePath): List<String> {
        val prefix = relativePath.value.trimEnd('/')
        return files.keys.filter { key ->
            if (prefix.isEmpty()) !key.contains('/')
            else key.startsWith("$prefix/") && !key.removePrefix("$prefix/").contains('/')
        }.sorted()
    }
    override suspend fun deleteFile(relativePath: RelativePath): Result<Unit> {
        files.remove(relativePath.value)
        return Result.success(Unit)
    }
    override fun getBasePath(): String = basePath
    override suspend fun extractResource(resourcePath: String, targetRelativePath: RelativePath): Result<Unit> = Result.success(Unit)
}

class FakeHostFs : HostFileSystem {
    val files = mutableMapOf<String, ByteArray>()
    val dirs = mutableMapOf<String, List<String>>()
    override suspend fun readFile(absolutePath: String): ByteArray? = files[absolutePath]
    override suspend fun readTextFile(absolutePath: String): String? = files[absolutePath]?.decodeToString()
    override suspend fun writeFile(absolutePath: String, data: ByteArray): Result<Unit> {
        files[absolutePath] = data
        return Result.success(Unit)
    }
    override suspend fun writeTextFile(absolutePath: String, text: String): Result<Unit> =
        writeFile(absolutePath, text.encodeToByteArray())
    override suspend fun exists(absolutePath: String): Boolean = absolutePath in files || absolutePath in dirs
    override suspend fun listFiles(absolutePath: String): List<String> = dirs[absolutePath] ?: emptyList()
    override suspend fun deleteFile(absolutePath: String): Result<Unit> {
        files.remove(absolutePath)
        return Result.success(Unit)
    }
    override suspend fun createDirectory(absolutePath: String): Result<Unit> {
        dirs.putIfAbsent(absolutePath, emptyList())
        return Result.success(Unit)
    }
}

class FakeSecrets : PluginSecretStorage {
    val map = mutableMapOf<String, String>()
    override suspend fun getSecret(key: String): String? = map[key]
    override suspend fun putSecret(key: String, value: String) { map[key] = value }
    override suspend fun removeSecret(key: String) { map.remove(key) }
    override suspend fun listSecretKeys(): List<String> = map.keys.toList()
}

class FakeNetwork : PluginNetworkClient {
    val requests = mutableListOf<HttpRequest>()
    var responder: (HttpRequest) -> HttpResponse = { HttpResponse(200, body = "{}".encodeToByteArray()) }
    override suspend fun get(url: String, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, headers = headers)).body
    override suspend fun getText(url: String, headers: Map<String, String>): String =
        get(url, headers).decodeToString()
    override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>): ByteArray =
        request(HttpRequest(url = url, method = org.wip.plugintoolkit.api.HttpMethod.POST, body = body, headers = headers)).body
    override suspend fun postText(url: String, body: String, headers: Map<String, String>): String =
        post(url, body.encodeToByteArray(), headers).decodeToString()
    override suspend fun request(request: HttpRequest): HttpResponse {
        requests += request
        return responder(request)
    }
}

class FakeGitClient(
    private val pluginFs: FakePluginFs,
    private val repoPrefix: String = CubariRepo.MANAGED_DIR,
    var seriesSubdir: String = "series"
) : GitClient {
    val commits = mutableListOf<String>()
    var pushedCount = 0
    var pulledCount = 0
    var resetCount = 0
    var failNextPush: String? = null
    var overwriteOnPull = false
    val remoteSeries = mutableMapOf<String, JsonObject>()

    private fun repoRelPath(slug: String): RelativePath {
        val sub = seriesSubdir.trim('/')
        val full = if (sub.isEmpty()) "$repoPrefix/$slug.json" else "$repoPrefix/$sub/$slug.json"
        return relPath(full)
    }

    override suspend fun ensureRepo(dir: String, remoteUrl: String, branch: String) {
        remoteSeries.forEach { (slug, json) ->
            val path = repoRelPath(slug)
            if (!pluginFs.exists(path)) {
                pluginFs.writeTextFile(path, CubariJson.render(json))
            }
        }
    }

    override suspend fun pull(dir: String, branch: String) {
        pulledCount++
        remoteSeries.forEach { (slug, json) ->
            val path = repoRelPath(slug)
            if (overwriteOnPull || !pluginFs.exists(path)) {
                pluginFs.writeTextFile(path, CubariJson.render(json))
            }
        }
    }

    override suspend fun hasChanges(dir: String): Boolean = true

    override suspend fun commitAll(dir: String, message: String): Boolean {
        commits += message
        return true
    }

    override suspend fun push(dir: String, branch: String) {
        failNextPush?.let { msg ->
            failNextPush = null
            throw IllegalStateException(msg)
        }
        pushedCount++
    }

    override suspend fun testRemote(remoteUrl: String): String = "Repository reachable (1 branch(es))"

    override suspend fun resetHardFromRemote(dir: String, remoteUrl: String, branch: String): String {
        resetCount++
        remoteSeries.forEach { (slug, json) ->
            pluginFs.writeTextFile(repoRelPath(slug), CubariJson.render(json))
        }
        return "Restored from origin/${branch.ifBlank { "main" }}"
    }
}

class FakeArubaStorage : ArubaStorage {
    val files = linkedMapOf<String, ByteArray>()
    val directories = linkedSetOf<String>()
    val deleted = mutableListOf<String>()
    var onUpload: suspend (String) -> Unit = {}

    override suspend fun upload(remotePath: String, bytes: ByteArray) {
        onUpload(remotePath)
        files[remotePath] = bytes
    }

    override suspend fun delete(remotePath: String) {
        deleted += remotePath
        files.remove(remotePath)
    }

    override suspend fun listNames(remoteDir: String): List<String> {
        val prefix = remoteDir.trimEnd('/') + "/"
        val fromFiles = files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
        val fromDirs = directories.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
        return (fromFiles + fromDirs).filter { it.isNotBlank() }.distinct().sorted()
    }

    override suspend fun listDirectoryNames(remoteDir: String): List<String> {
        val prefix = remoteDir.trimEnd('/') + "/"
        val explicit = directories.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }
        val fromSubFiles = files.keys.filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val rel = key.removePrefix(prefix)
                if ('/' in rel) rel.substringBefore('/') else null
            }
        return (explicit + fromSubFiles).filter { it.isNotBlank() }.distinct().sorted()
    }

    override suspend fun testConnection(): String = "SFTP connected (${files.size} entries)"
}

class FakeMangaDexApi : MangaDexApi {
    var tokens = MdTokens("access-1", "refresh-1", 900L)
    var profile = MdProfile("user-1", "ScanLead")
    var group = MdGroup("11111111-1111-1111-1111-111111111111", "ScanGroup")
    var feed = listOf<MdChapter>()
    val pageUrlsByChapter = mutableMapOf<String, List<String>>()
    val urlStatuses = mutableMapOf<String, Int>()
    val downloadedBytes = mutableMapOf<String, ByteArray>()
    val uploadedFiles = mutableListOf<MdUploadFile>()
    val committedDrafts = mutableListOf<MdChapterDraft>()
    val updatedMetadataCalls = mutableListOf<Triple<String, Int, MdChapterDraft>>()
    var onUpdateChapterMetadata: (suspend (String, Int, MdChapterDraft) -> MdChapter?)? = null
    var abortedSessions = mutableListOf<String>()
    var activeSession: MdUploadSession? = null
    val closedSessionIds = mutableListOf<String>()
    var onUploadBatch: suspend (List<MdUploadFile>) -> Unit = {}
    var sessionCounter = 0

    override suspend fun activeUploadSession(accessToken: String): MdUploadSession? = activeSession

    override suspend fun closeUploadSession(accessToken: String, sessionId: String) {
        closedSessionIds += sessionId
        if (activeSession?.id == sessionId) activeSession = null
    }

    override suspend fun login(clientId: String, clientSecret: String, username: String, password: String): MdTokens = tokens
    override suspend fun exchangeCode(clientId: String, clientSecret: String, code: String, redirectUri: String, codeVerifier: String): MdTokens = tokens
    override suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): MdTokens = tokens
    override suspend fun profile(accessToken: String): MdProfile = profile
    override suspend fun group(groupId: String): MdGroup = group
    override suspend fun listChapters(mangaId: String, groupId: String?, language: String): List<MdChapter> = feed
    override suspend fun pageUrls(chapterId: String): List<String> = pageUrlsByChapter[chapterId] ?: emptyList()
    override suspend fun checkUrl(url: String): Int = urlStatuses[url] ?: 200
    override suspend fun download(url: String): ByteArray = downloadedBytes[url] ?: "img".encodeToByteArray()

    override suspend fun beginUpload(accessToken: String, mangaId: String, groupId: String): String =
        "sess_${++sessionCounter}"

    override suspend fun beginEdit(accessToken: String, chapterId: String, version: Int): String =
        "edit_sess_${++sessionCounter}"

    override suspend fun uploadPages(accessToken: String, sessionId: String, files: List<MdUploadFile>): List<String> {
        onUploadBatch(files)
        uploadedFiles += files
        return files.mapIndexed { i, f -> "${sessionId}_file_${uploadedFiles.size - files.size + i}_${f.fileName}" }
    }

    override suspend fun commit(accessToken: String, sessionId: String, draft: MdChapterDraft, pageOrder: List<String>): MdChapter {
        committedDrafts += draft
        return MdChapter(
            id = "md_ch_${committedDrafts.size}",
            chapter = draft.chapter,
            volume = draft.volume,
            title = draft.title,
            publishAtEpochSec = 1_700_000_500L,
            version = 1,
            pageCount = pageOrder.size
        )
    }

    override suspend fun updateChapterMetadata(accessToken: String, chapterId: String, version: Int, draft: MdChapterDraft): MdChapter {
        updatedMetadataCalls += Triple(chapterId, version, draft)
        onUpdateChapterMetadata?.invoke(chapterId, version, draft)?.let { return it }
        val existing = feed.firstOrNull { it.id == chapterId }
        return MdChapter(
            id = chapterId,
            chapter = draft.chapter,
            volume = draft.volume,
            title = draft.title,
            publishAtEpochSec = existing?.publishAtEpochSec ?: 1_700_000_500L,
            version = version + 1,
            pageCount = existing?.pageCount ?: 0
        )
    }

    override suspend fun abort(accessToken: String, sessionId: String) {
        abortedSessions += sessionId
    }
}

class FakeServiceFactory(
    override val clock: FakeClock = FakeClock(),
    val pluginFs: FakePluginFs = FakePluginFs()
) : ServiceFactory {
    val git = FakeGitClient(pluginFs)
    val aruba = FakeArubaStorage()
    var arubaCalls = 0
    val mangadex = FakeMangaDexApi()
    val copiedTexts = mutableListOf<String>()
    override fun git(context: PluginContext, config: MangaUploaderConfig): GitClient =
        git.apply { seriesSubdir = config.cubariSeriesDir }
    override fun aruba(context: PluginContext, config: MangaUploaderConfig): ArubaStorage {
        arubaCalls++
        return aruba
    }
    override fun mangadex(context: PluginContext, config: MangaUploaderConfig): MangaDexApi = mangadex
    override fun copyToClipboard(text: String) {
        copiedTexts += text
    }
}

class FakePluginContext(
    override val fileSystem: FakePluginFs = FakePluginFs(),
    override val hostFileSystem: FakeHostFs = FakeHostFs(),
    override val storage: FakeStorage = FakeStorage(),
    override val signals: FakeSignals = FakeSignals(),
    override val progress: FakeProgressReporter = FakeProgressReporter(),
    override val logger: FakeLogger = FakeLogger(),
    override val secrets: FakeSecrets = FakeSecrets(),
    override val networkClient: FakeNetwork = FakeNetwork(),
    initialSettings: Map<String, JsonElement> = emptyMap(),
    private val onSettingsChanged: (Map<String, JsonElement>) -> Unit = {}
) : PluginContext {
    val mutableSettings = initialSettings.toMutableMap()
    val toasts = mutableListOf<String>()
    val notifications = mutableListOf<Triple<String, String, PluginNotificationType>>()

    override val settings: Map<String, JsonElement> get() = mutableSettings
    override val cacheFileSystem: PluginFileSystem get() = fileSystem
    override val executionFileSystem: ExecutionFileSystem get() = throw UnsupportedOperationException()

    override fun setRequiredAction(actionName: String?) {}
    override fun showToast(message: String) { toasts += message }
    override fun showNotification(title: String, message: String, type: PluginNotificationType) {
        notifications += Triple(title, message, type)
    }

    override suspend fun updateSetting(key: String, value: JsonElement) {
        mutableSettings[key] = value
        onSettingsChanged(mutableSettings.toMap())
    }

    override suspend fun clearSetting(key: String) {
        mutableSettings.remove(key)
        onSettingsChanged(mutableSettings.toMap())
    }
}

class FakeSessionContext(
    override val capabilityName: String = "mangaUploaderWorkspace",
    private val entry: PluginEntry,
    private val scope: CoroutineScope,
    val base: FakePluginContext,
    val settingsState: MutableStateFlow<Map<String, JsonElement>> = MutableStateFlow(base.mutableSettings.toMap()),
    val locksState: MutableStateFlow<Map<String, Boolean>> = MutableStateFlow(emptyMap()),
    override val initialParameters: Map<String, JsonElement> = emptyMap(),
    var pickedFile: String? = null,
    var pickedFiles: List<String> = emptyList(),
    var pickedDirectory: String? = null
) : UiSessionContext, PluginContext by base {
    val jobsState = MutableStateFlow<List<CapabilityJobSnapshot>>(emptyList())
    val launchedCapabilities = mutableListOf<Triple<String, String, Map<String, JsonElement>>>()
    val pausedJobIds = mutableListOf<String>()
    val resumedJobIds = mutableListOf<String>()
    val cancelledJobIds = mutableListOf<String>()
    private var jobCounter = 0

    override val sessionId: String = "test_session"
    override val settingsFlow: StateFlow<Map<String, JsonElement>> = settingsState.asStateFlow()
    override val locksFlow: StateFlow<Map<String, Boolean>> = locksState.asStateFlow()
    override val capabilityJobs: StateFlow<List<CapabilityJobSnapshot>> = jobsState.asStateFlow()

    override fun completeSession(response: PluginResponse) {}
    override fun cancelSession(reason: String) {}
    override suspend fun pickFile(title: String?, allowedExtensions: List<String>): String? = pickedFile
    override suspend fun pickFiles(title: String?, allowedExtensions: List<String>): List<String> = pickedFiles
    override suspend fun pickDirectory(title: String?): String? = pickedDirectory

    override suspend fun updateSetting(key: String, value: JsonElement) {
        base.updateSetting(key, value)
        settingsState.value = base.mutableSettings.toMap()
        locksState.value = entry.getProcessor().getOrThrow().refreshLocks(this)
    }

    override suspend fun clearSetting(key: String) {
        base.clearSetting(key)
        settingsState.value = base.mutableSettings.toMap()
        locksState.value = entry.getProcessor().getOrThrow().refreshLocks(this)
    }

    override suspend fun launchAction(actionName: String, parameters: Map<String, JsonElement>): Result<Unit> {
        val proc = entry.getProcessor().getOrThrow()
        val action = entry.getManifest().getOrThrow().actions.find {
            it.functionName.equals(actionName, ignoreCase = true)
        } ?: PluginAction(actionName, actionName, actionName)
        val res = proc.runAction(action, parameters, this)
        settingsState.value = base.mutableSettings.toMap()
        locksState.value = proc.refreshLocks(this)
        return res
    }

    override suspend fun launchCapability(
        capabilityName: String,
        parameters: Map<String, JsonElement>,
        runInBackgroundQueue: Boolean
    ): String {
        val id = "job_${++jobCounter}"
        launchedCapabilities += Triple(id, capabilityName, parameters)
        jobsState.update { it + CapabilityJobSnapshot(id, capabilityName, CapabilityJobStatus.RUNNING) }
        scope.launch {
            val proc = entry.getProcessor().getOrThrow()
            val result = proc.process(PluginRequest(capabilityName, parameters), this@FakeSessionContext)
            val status = when (result) {
                is ExecutionResult.Success -> CapabilityJobStatus.COMPLETED
                is ExecutionResult.Paused -> CapabilityJobStatus.PAUSED
                is ExecutionResult.Error -> CapabilityJobStatus.FAILED
            }
            jobsState.update { list ->
                list.map { if (it.jobId == id) it.copy(status = status, result = result, errorMessage = (result as? ExecutionResult.Error)?.message) else it }
            }
        }
        return id
    }

    override suspend fun pauseCapabilityJob(jobId: String) {
        pausedJobIds += jobId
        jobsState.update { list -> list.map { if (it.jobId == jobId) it.copy(status = CapabilityJobStatus.PAUSED) else it } }
    }

    override suspend fun resumeCapabilityJob(jobId: String) {
        resumedJobIds += jobId
        jobsState.update { list -> list.map { if (it.jobId == jobId) it.copy(status = CapabilityJobStatus.RUNNING) else it } }
    }

    override suspend fun cancelCapabilityJob(jobId: String) {
        cancelledJobIds += jobId
        jobsState.update { list -> list.map { if (it.jobId == jobId) it.copy(status = CapabilityJobStatus.CANCELLED) else it } }
    }
}

fun UiNodeTree.findById(id: String): UiNode? = root.findNodeById(id)

fun UiNode.findNodeById(targetId: String): UiNode? {
    if (id == targetId) return this
    return when (this) {
        is org.wip.plugintoolkit.api.sdui.ColumnNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.RowNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.BoxNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.CardNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.ScrollableColumnNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.ScrollableRowNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.GridNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.ListItemNode -> trailingContent.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.DialogNode -> children.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.SplitPaneNode -> first.findNodeById(targetId) ?: second.findNodeById(targetId)
        is org.wip.plugintoolkit.api.sdui.ScaffoldNode -> listOfNotNull(topBar, leftSidebar, content, rightSidebar, bottomBar).firstNotNullOfOrNull { it.findNodeById(targetId) }
            ?: floatingPanels.firstNotNullOfOrNull { it.findNodeById(targetId) }
        is org.wip.plugintoolkit.api.sdui.TabsNode -> content.findNodeById(targetId)
        else -> null
    }
}
