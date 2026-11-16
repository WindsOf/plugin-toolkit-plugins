package com.wip.mangauploader

import kotlinx.coroutines.delay
import java.net.URI

/** Time source (injectable so hold timing and `last_updated` writes are deterministic in tests). */
interface Clock {
    fun nowEpochSec(): Long
    fun nowNanos(): Long
}

object SystemClock : Clock {
    override fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
    override fun nowNanos(): Long = System.nanoTime()
}

/** Snapshot of bytes/timing observed by a [BandwidthThrottle]. */
data class ThrottleStats(
    val bytes: Long,
    val limitKbps: Int,
    val waitedMs: Long,
    val elapsedMs: Long,
    val effectiveKbps: Double
)

/**
 * Thread-safe leaky-bucket rate limiter targeting `kbps` KB/s (`0` = unlimited).
 * [limitProvider] is evaluated on every call so setting changes apply to running jobs immediately.
 */
class BandwidthThrottle(
    private val limitProvider: () -> Int,
    private val clock: Clock = SystemClock,
    private val sleeper: suspend (Long) -> Unit = { delay(it) }
) {
    private val lock = Any()
    private var nextFreeNanos: Long = 0L
    private var firstAcquireNanos: Long? = null
    private var totalBytes: Long = 0L
    private var totalWaitedMs: Long = 0L

    suspend fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val kbps = limitProvider()
        val waitMs = synchronized(lock) {
            val now = clock.nowNanos()
            if (firstAcquireNanos == null) firstAcquireNanos = now
            totalBytes += bytes.toLong()
            if (kbps <= 0) {
                0L
            } else {
                val start = maxOf(now, nextFreeNanos)
                val cost = bytes.toLong() * 1_000_000_000L / (kbps.toLong() * 1024L)
                nextFreeNanos = start + cost
                val delayMs = (start - now) / 1_000_000L
                if (delayMs > 0L) totalWaitedMs += delayMs
                delayMs
            }
        }
        if (waitMs > 0L) sleeper(waitMs)
    }

    fun stats(): ThrottleStats = synchronized(lock) {
        val now = clock.nowNanos()
        val elapsedMs = firstAcquireNanos?.let { ((now - it) / 1_000_000L).coerceAtLeast(0L) } ?: 0L
        val effectiveKbps = if (elapsedMs > 0L && totalBytes > 0L) {
            (totalBytes / 1024.0) / (elapsedMs / 1000.0)
        } else 0.0
        ThrottleStats(
            bytes = totalBytes,
            limitKbps = limitProvider().coerceAtLeast(0),
            waitedMs = totalWaitedMs,
            elapsedMs = elapsedMs,
            effectiveKbps = effectiveKbps
        )
    }
}

/** Git operations on the local Cubari repository clone. */
interface GitClient {
    /** Clones [remoteUrl] into [dir] when it is not a repository yet; otherwise just verifies it. */
    suspend fun ensureRepo(dir: String, remoteUrl: String, branch: String)
    suspend fun pull(dir: String, branch: String)
    suspend fun hasChanges(dir: String): Boolean

    /** @return true if a commit was created. */
    suspend fun commitAll(dir: String, message: String): Boolean
    suspend fun push(dir: String, branch: String)

    /** Verifies remote reachability + credentials; returns a human readable summary. */
    suspend fun testRemote(remoteUrl: String): String

    /** Returns the resolved default or checked-out branch name of [dir], if detectable. */
    suspend fun resolveBranch(dir: String): String? = null

    /** Aborts stuck rebase/merge/lock state and resets the local clone to match `origin/<branch>`. */
    suspend fun resetHardFromRemote(dir: String, remoteUrl: String, branch: String): String = "Reset skipped"
}

/** File access relative to the local Cubari repository root. */
interface RepoFiles {
    suspend fun read(relativePath: String): String?
    suspend fun write(relativePath: String, text: String)
    suspend fun exists(relativePath: String): Boolean
    suspend fun listNames(relativeDir: String): List<String>
}

/** Remote Aruba storage (SFTP/FTP). Paths are absolute remote paths. */
interface ArubaStorage : AutoCloseable {
    suspend fun upload(remotePath: String, bytes: ByteArray)
    suspend fun rename(fromPath: String, toPath: String)
    suspend fun delete(remotePath: String)
    suspend fun listNames(remoteDir: String): List<String>

    /** Lists subdirectory names inside [remoteDir]; falls back to [listNames] for minimal implementations. */
    suspend fun listDirectoryNames(remoteDir: String): List<String> = listNames(remoteDir)

    /** Connects, makes sure the chapters directory exists and returns a summary. */
    suspend fun testConnection(): String
    override fun close() {}
}

data class ArubaConfig(
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val chaptersPath: String,
    val publicBaseUrl: String,
    val maxConnections: Int,
    val pagePadding: String = "auto"
) {
    companion object {
        fun from(config: MangaUploaderConfig) = ArubaConfig(
            protocol = config.arubaProtocol,
            host = config.arubaHost,
            port = config.arubaPort,
            username = config.arubaUsername,
            password = config.arubaPassword,
            chaptersPath = config.arubaChaptersPath,
            publicBaseUrl = config.arubaPublicBaseUrl,
            maxConnections = config.concurrentConnections,
            pagePadding = config.arubaPagePadding
        )
    }
}

/** Remote path / public URL layout of uploaded assets (`<chaptersPath>/<Series>/ch<number>/<page>`). */
object ArubaPaths {
    private val CHAPTER_DIR_REGEX = Regex("^(?i)ch(.+)$")
    private val LEADING_ZERO_DIGITS = Regex("^0+\\d+$")
    private val PLAIN_DIGITS = Regex("^\\d+$")

    fun chapterFolderName(chapterKey: String): String {
        val clean = normalizeChapterKey(chapterKey).replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "ch$clean"
    }

    fun chapterDir(config: ArubaConfig, seriesFolder: String, chapterKey: String): String =
        joinPath(config.chaptersPath, sanitizeFolderName(seriesFolder), chapterFolderName(chapterKey))

    fun pageName(index: Int, extension: String, padDigits: Int = 0): String {
        val num = (index + 1).toString()
        val stem = if (padDigits <= 1) num else num.padStart(padDigits, '0')
        return "$stem.${extension.lowercase()}"
    }

    private val WEBROOT_SEGMENTS = setOf("public_html", "htdocs", "httpdocs", "www")
    private val RESERVED_ROOT_FOLDERS = setOf("covers")

    fun coverPath(config: ArubaConfig, seriesFolder: String, extension: String): String {
        val stem = sanitizeFolderName(seriesFolder).ifBlank { "series" }
        return joinPath(config.chaptersPath, "covers", "$stem.${extension.lowercase()}")
    }

    /**
     * Public URL for a remote path under Aruba storage (URL-encoding each path segment).
     * Strips leading webroot directories (e.g. `public_html`, `www.domain.tld`) and any
     * trailing path segments already present in [ArubaConfig.publicBaseUrl], while preserving
     * non-redundant folders such as `/MangaKoFi`.
     */
    fun publicUrl(config: ArubaConfig, remotePath: String): String {
        val baseUrl = config.publicBaseUrl.trim().trimEnd('/')
        val baseUri = runCatching { URI(baseUrl) }.getOrNull()
        val baseHost = baseUri?.host?.trim()?.lowercase().orEmpty()
        val bareHost = baseHost.removePrefix("www.")
        val basePathSegs = baseUri?.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }

        val rootSegs = config.chaptersPath.trim('/').split('/').filter { it.isNotEmpty() }
        val remoteSegs = remotePath.trim('/').split('/').filter { it.isNotEmpty() }

        val hasRootPrefix = rootSegs.isNotEmpty() &&
            remoteSegs.size >= rootSegs.size &&
            remoteSegs.subList(0, rootSegs.size).zip(rootSegs).all { (a, b) -> a.equals(b, ignoreCase = true) }

        val stripCount = if (hasRootPrefix) {
            val afterWebrootStart = if (isWebrootSegment(rootSegs[0], baseHost, bareHost)) 1 else 0
            val remainingRoot = rootSegs.subList(afterWebrootStart, rootSegs.size)
            afterWebrootStart + overlapSuffixPrefix(basePathSegs, remainingRoot)
        } else {
            0
        }

        val urlRelSegs = remoteSegs.drop(stripCount)
        val encoded = urlRelSegs.joinToString("/", transform = ::encodeUrlSegment)
        return if (encoded.isEmpty()) baseUrl else "$baseUrl/$encoded"
    }

    /** Reconstructs the Aruba remote path corresponding to a published [url] when possible. */
    fun remotePathFromUrl(config: ArubaConfig, url: String): String? {
        val clean = url.substringBefore('?').substringBefore('#').trim()
        val urlSegs = runCatching { URI(clean).path }.getOrNull()
            ?.trim('/')
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?.map(::decodeUrlSegment)
            ?: return null
        if (urlSegs.isEmpty()) return null
        val rootSegs = config.chaptersPath.trim('/').split('/').filter { it.isNotEmpty() }
        val overlap = overlapSuffixPrefix(rootSegs, urlSegs)
        val combined = rootSegs + urlSegs.drop(overlap)
        return if (config.chaptersPath.trim().startsWith("/")) "/" + combined.joinToString("/")
        else combined.joinToString("/")
    }

    private fun isWebrootSegment(segment: String, baseHost: String, bareHost: String): Boolean {
        val lower = segment.lowercase()
        return lower in WEBROOT_SEGMENTS ||
            (baseHost.isNotEmpty() && (lower == baseHost || lower == bareHost))
    }

    private fun overlapSuffixPrefix(left: List<String>, right: List<String>): Int {
        val maxK = minOf(left.size, right.size)
        for (k in maxK downTo 1) {
            val leftTail = left.subList(left.size - k, left.size)
            val rightHead = right.subList(0, k)
            if (leftTail.zip(rightHead).all { (a, b) -> a.equals(b, ignoreCase = true) }) return k
        }
        return 0
    }

    fun resolvePadDigits(config: ArubaConfig, series: Series, chapters: List<Chapter>): Int {
        series.arubaZeroPadDigits?.let { return it.coerceIn(0, 6) }
        return when (config.pagePadding.lowercase()) {
            "0" -> 0
            "2" -> 2
            "3" -> 3
            "4" -> 4
            else -> inferPadDigitsFromUrls(chapters.flatMap { it.cubariPageUrls }) ?: 0
        }
    }

    /** Infers zero-padding width from existing chapter page URLs (`01.png` -> 2, `001.png` -> 3, `1.png` -> 0). */
    fun inferPadDigitsFromUrls(urls: List<String>): Int? {
        var foundUnpadded = false
        for (raw in urls) {
            val file = decodeUrlSegment(raw.substringBefore('?').substringBefore('#').substringAfterLast('/'))
            val stem = file.substringBeforeLast('.', "")
            if (stem.isEmpty()) continue
            if (LEADING_ZERO_DIGITS.matches(stem) && stem.length >= 2) return stem.length
            if (PLAIN_DIGITS.matches(stem) && (stem.length == 1 || !stem.startsWith("0"))) foundUnpadded = true
        }
        return if (foundUnpadded) 0 else null
    }

    /** Extracts the Aruba series folder name from existing Cubari chapter page URLs, if recognizable. */
    fun inferSeriesFolderFromUrls(publicBaseUrl: String, urls: List<String>): String? {
        val base = publicBaseUrl.trimEnd('/')
        for (raw in urls) {
            val clean = raw.substringBefore('?').substringBefore('#')
            val segments = clean.substringAfter("://", clean).substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            if (segments.size >= 3 && CHAPTER_DIR_REGEX.matches(segments[segments.size - 2])) {
                return decodeUrlSegment(segments[segments.size - 3])
            }
            if (base.isNotEmpty() && clean.startsWith("$base/", ignoreCase = true)) {
                val rel = clean.substring(base.length + 1).split('/').filter { it.isNotEmpty() }
                if (rel.size >= 3) return decodeUrlSegment(rel[0])
            }
        }
        return null
    }

    /** True if [folderName] is a reserved non-series directory (such as `covers`). */
    fun isReservedSeriesFolder(folderName: String): Boolean = folderName.trim().lowercase() in RESERVED_ROOT_FOLDERS

    fun encodeUrlSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val isUnreserved = (b in 'A'.code..'Z'.code) ||
                (b in 'a'.code..'z'.code) ||
                (b in '0'.code..'9'.code) ||
                b == '-'.code || b == '.'.code || b == '_'.code || b == '~'.code
            if (isUnreserved) out.append(b.toChar())
            else out.append('%').append("0123456789ABCDEF"[b ushr 4]).append("0123456789ABCDEF"[b and 0x0F])
        }
        return out.toString()
    }

    fun decodeUrlSegment(segment: String): String {
        val bytes = ArrayList<Byte>(segment.length)
        var i = 0
        while (i < segment.length) {
            if (segment[i] == '%' && i + 2 < segment.length) {
                val hi = segment[i + 1].digitToIntOrNull(16)
                val lo = segment[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                    continue
                }
            }
            for (b in segment[i].toString().encodeToByteArray()) bytes.add(b)
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun normalizeFolderKey(text: String): String =
        text.trim().lowercase().replace(Regex("[^a-z0-9]+"), "")

    /**
     * Matches an existing folder on Aruba against URL-inferred folder names, series title, or Cubari JSON slug.
     */
    fun matchSeriesFolder(
        seriesTitle: String,
        cubariSlug: String,
        inferredFromUrls: String?,
        availableFolders: List<String>
    ): String? {
        if (availableFolders.isEmpty()) return inferredFromUrls?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(
            inferredFromUrls?.trim()?.takeIf { it.isNotEmpty() },
            seriesTitle.trim().takeIf { it.isNotEmpty() },
            cubariSlug.trim().takeIf { it.isNotEmpty() }
        )
        for (c in candidates) {
            availableFolders.firstOrNull { it == c }?.let { return it }
            availableFolders.firstOrNull { it.equals(c, ignoreCase = true) }?.let { return it }
            val norm = normalizeFolderKey(c)
            if (norm.isNotEmpty()) {
                availableFolders.firstOrNull { normalizeFolderKey(it) == norm }?.let { return it }
            }
        }
        return inferredFromUrls?.takeIf { it.isNotBlank() }
    }

    private fun joinPath(root: String, vararg subParts: String): String {
        val cleanSubs = subParts.map { it.trim('/') }.filter { it.isNotEmpty() }
        val trimmedRoot = root.trim()
        return if (trimmedRoot.startsWith("/")) {
            val cleanRoot = trimmedRoot.trim('/')
            val all = if (cleanRoot.isEmpty()) cleanSubs else listOf(cleanRoot) + cleanSubs
            "/" + all.joinToString("/")
        } else {
            val cleanRoot = trimmedRoot.trim('/')
            val all = if (cleanRoot.isEmpty()) cleanSubs else listOf(cleanRoot) + cleanSubs
            all.joinToString("/")
        }
    }
}

/** Decorator applying the upload speed limit setting. */
class ThrottledStorage(private val delegate: ArubaStorage, private val throttle: BandwidthThrottle) : ArubaStorage {
    override suspend fun upload(remotePath: String, bytes: ByteArray) {
        throttle.acquire(bytes.size)
        delegate.upload(remotePath, bytes)
    }

    fun stats(): ThrottleStats = throttle.stats()

    override suspend fun rename(fromPath: String, toPath: String) = delegate.rename(fromPath, toPath)
    override suspend fun delete(remotePath: String) = delegate.delete(remotePath)
    override suspend fun listNames(remoteDir: String) = delegate.listNames(remoteDir)
    override suspend fun listDirectoryNames(remoteDir: String) = delegate.listDirectoryNames(remoteDir)
    override suspend fun testConnection() = delegate.testConnection()
    override fun close() = delegate.close()
}
