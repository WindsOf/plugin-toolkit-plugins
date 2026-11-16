package com.wip.mangauploader

import kotlinx.serialization.json.JsonObject
import org.wip.plugintoolkit.api.HttpMethod
import org.wip.plugintoolkit.api.HttpRequest
import org.wip.plugintoolkit.api.HttpResponse
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginNetworkClient
import org.wip.plugintoolkit.api.PluginSignal
import org.wip.plugintoolkit.api.ScopedFileSystem
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/** Factory for every external dependency, replaceable in tests via [MangaUploaderServices.factory]. */
interface ServiceFactory {
    val clock: Clock
    fun git(context: PluginContext, config: MangaUploaderConfig): GitClient
    fun aruba(context: PluginContext, config: MangaUploaderConfig): ArubaStorage
    fun mangadex(context: PluginContext, config: MangaUploaderConfig = MangaUploaderConfig.from(context.settings)): MangaDexApi
    fun copyToClipboard(text: String) {
        require(text.isNotBlank()) { "Nothing to copy" }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

object DefaultServiceFactory : ServiceFactory {
    override val clock: Clock = SystemClock

    override fun git(context: PluginContext, config: MangaUploaderConfig): GitClient =
        CliGitClient(config.cubariUsername, config.cubariToken, context.logger)

    override fun aruba(context: PluginContext, config: MangaUploaderConfig): ArubaStorage {
        val aruba = ArubaConfig.from(config)
        val raw = if (aruba.protocol == "SFTP") SftpStorage(aruba, context.logger) else FtpStorage(aruba, context.logger)
        return ThrottledStorage(raw, BandwidthThrottle({ MangaUploaderConfig.from(context.settings).maxUploadKbps }, clock))
    }

    override fun mangadex(context: PluginContext, config: MangaUploaderConfig): MangaDexApi {
        val directClient = SmartMangaDexNetworkClient(
            delegate = context.networkClient,
            autoDoh = config.mdAutoDoh,
            logger = context.logger
        )
        val useProxy = config.mdProxyUrl.isNotBlank()
        val client = if (useProxy) {
            HostScopedProxyClient(
                proxyClient = ProxyNetworkClient(config.mdProxyUrl),
                directClient = directClient,
                proxiedHosts = setOfNotNull(
                    runCatching { URI(config.mdApiBaseUrl).host?.lowercase() }.getOrNull(),
                    runCatching { URI(config.mdAuthBaseUrl).host?.lowercase() }.getOrNull()
                )
            )
        } else {
            directClient
        }
        return HttpMangaDexApi(
            network = client,
            logger = context.logger,
            apiBaseUrl = config.mdApiBaseUrl,
            authBaseUrl = config.mdAuthBaseUrl,
            usingProxy = useProxy
        )
    }

    /** Routes MangaDex API/auth/at-home traffic through the configured proxy while keeping external image CDNs direct. */
    private class HostScopedProxyClient(
        private val proxyClient: PluginNetworkClient,
        private val directClient: PluginNetworkClient,
        private val proxiedHosts: Set<String>
    ) : PluginNetworkClient {
        private fun shouldProxy(url: String): Boolean {
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
            return host == "mangadex.org" || host.endsWith(".mangadex.org") || host in proxiedHosts
        }

        override suspend fun get(url: String, headers: Map<String, String>) =
            (if (shouldProxy(url)) proxyClient else directClient).get(url, headers)

        override suspend fun getText(url: String, headers: Map<String, String>) =
            (if (shouldProxy(url)) proxyClient else directClient).getText(url, headers)

        override suspend fun post(url: String, body: ByteArray, headers: Map<String, String>) =
            (if (shouldProxy(url)) proxyClient else directClient).post(url, body, headers)

        override suspend fun postText(url: String, body: String, headers: Map<String, String>) =
            (if (shouldProxy(url)) proxyClient else directClient).postText(url, body, headers)

        override suspend fun request(request: HttpRequest): HttpResponse =
            (if (shouldProxy(request.url)) proxyClient else directClient).request(request)
    }
}

object MangaUploaderServices {
    @Volatile
    var factory: ServiceFactory = DefaultServiceFactory
}

/**
 * Cooperative pause support for long capability loops.
 * Call [pauseCheckpoint] between work units; when it returns `true` the capability must persist
 * its progress and return [org.wip.plugintoolkit.api.ExecutionResult.Paused].
 */
class PauseGate(context: PluginContext) {
    @Volatile
    private var pauseRequested = false

    @Volatile
    var cancelRequested = false
        private set

    init {
        context.onSignal { signal ->
            when (signal) {
                PluginSignal.PAUSE -> pauseRequested = true
                PluginSignal.CANCEL -> cancelRequested = true
            }
        }
    }

    fun pauseCheckpoint(): Boolean = pauseRequested || cancelRequested
}

/** [RepoFiles] over the plugin's scoped data directory (`prefix` = repo folder name). */
class ScopedRepoFiles(private val fs: ScopedFileSystem, private val prefix: String) : RepoFiles {
    private fun p(path: String) = relPath("$prefix/${path.trim('/')}".trimEnd('/'))
    override suspend fun read(relativePath: String): String? = fs.readTextFile(p(relativePath))
    override suspend fun write(relativePath: String, text: String) {
        fs.writeTextFile(p(relativePath), text).getOrThrow()
    }

    override suspend fun exists(relativePath: String): Boolean = fs.exists(p(relativePath))
    override suspend fun listNames(relativeDir: String): List<String> =
        fs.listFiles(p(relativeDir)).map { it.trimEnd('/').substringAfterLast('/') }
}

/** [RepoFiles] over a user-chosen absolute repository folder (needs a granted `pickDirectory`). */
class HostRepoFiles(private val host: HostFileSystem, private val root: String) : RepoFiles {
    private fun p(path: String): String {
        val cleanRoot = root.trimEnd('/', '\\')
        val cleanPath = path.trim('/')
        return if (cleanPath.isEmpty()) cleanRoot else "$cleanRoot/$cleanPath"
    }

    override suspend fun read(relativePath: String): String? = host.readTextFile(p(relativePath))
    override suspend fun write(relativePath: String, text: String) {
        host.writeTextFile(p(relativePath), text).getOrThrow()
    }

    override suspend fun exists(relativePath: String): Boolean = host.exists(p(relativePath))
    override suspend fun listNames(relativeDir: String): List<String> =
        host.listFiles(p(relativeDir)).map { it.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\') }
}

/**
 * High level access to the local Cubari Git repository: JSON documents per series + git sync.
 */
class CubariRepo(
    val dir: String,
    private val files: RepoFiles,
    private val git: GitClient,
    private val config: MangaUploaderConfig
) {
    /** Relative path inside the Git repo for [slug]'s Cubari JSON (handles empty root `cubariSeriesDir`). */
    fun seriesRelativePath(slug: String): String =
        listOf(config.cubariSeriesDir.trim('/'), "$slug.json").filter { it.isNotEmpty() }.joinToString("/")

    /** Clone (if needed) and fast-forward the local clone. */
    suspend fun prepare() {
        git.ensureRepo(dir, config.cubariRemoteUrl, config.cubariBranch)
        git.pull(dir, config.cubariBranch)
    }

    suspend fun readSeries(slug: String): JsonObject? =
        files.read(seriesRelativePath(slug))?.let { CubariJson.parse(it) }

    suspend fun writeSeries(slug: String, json: JsonObject) =
        files.write(seriesRelativePath(slug), CubariJson.render(json))

    suspend fun listSlugs(): List<String> =
        files.listNames(config.cubariSeriesDir)
            .filter { !it.startsWith(".") && it.endsWith(".json", ignoreCase = true) }
            .map { it.dropLast(5) }
            .sorted()

    /** @return true when a commit was pushed. */
    suspend fun commitAndPush(message: String): Boolean {
        val committed = git.commitAll(dir, message)
        if (committed) git.push(dir, config.cubariBranch)
        return committed
    }

    companion object {
        const val MANAGED_DIR = "cubari-repo"

        /** Builds the repo accessor, using the managed clone inside the plugin dir unless a local path is configured. */
        fun create(context: PluginContext, config: MangaUploaderConfig, git: GitClient): CubariRepo =
            if (config.cubariLocalPath.isNotBlank()) {
                CubariRepo(config.cubariLocalPath, HostRepoFiles(context.hostFileSystem, config.cubariLocalPath), git, config)
            } else {
                val dir = context.fileSystem.getBasePath().trimEnd('/', '\\') + "/" + MANAGED_DIR
                CubariRepo(dir, ScopedRepoFiles(context.fileSystem, MANAGED_DIR), git, config)
            }
    }
}
