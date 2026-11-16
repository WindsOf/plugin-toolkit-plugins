package com.wip.mangauploader

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.wip.plugintoolkit.api.HostFileSystem
import org.wip.plugintoolkit.api.PluginFileSystem
import org.wip.plugintoolkit.api.PluginLogger
import org.wip.plugintoolkit.api.PluginStorage
import org.wip.plugintoolkit.api.ProgressData
import org.wip.plugintoolkit.api.RelativePath
import org.wip.plugintoolkit.api.ScopedFileSystem
import org.wip.plugintoolkit.api.sdui.CapabilityJobSnapshot
import org.wip.plugintoolkit.api.sdui.CapabilityJobStatus
import java.io.File
import java.util.UUID

/** Builds a validated [RelativePath] (throws on traversal attempts). */
fun relPath(path: String): RelativePath = RelativePath.from(path).getOrThrow()

fun newId(prefix: String): String = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

/**
 * Persists the local [Library] as `library.json` inside the plugin data directory.
 * All writes go through [mutate], which serialises read-modify-write cycles so the UI session and
 * background capabilities never overwrite each other's results.
 */
class LibraryStore(private val fs: ScopedFileSystem, private val logger: PluginLogger? = null) {

    suspend fun load(): Library {
        val text = fs.readTextFile(relPath(LIBRARY_FILE)) ?: return Library()
        return try {
            json.decodeFromString(Library.serializer(), text)
        } catch (e: Exception) {
            logger?.error("Failed to parse $LIBRARY_FILE, starting with an empty library: ${e.message}", e)
            Library()
        }
    }

    suspend fun mutate(block: (Library) -> Library): Library = MUTEX.withLock {
        val updated = block(load())
        fs.writeTextFile(relPath(LIBRARY_FILE), json.encodeToString(Library.serializer(), updated)).getOrThrow()
        updated
    }

    companion object {
        const val LIBRARY_FILE = "library.json"
        private val MUTEX = Mutex()
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Library mutations as pure functions (used by UI and workers, covered by unit tests). */
object LibraryOps {
    fun upsertSeries(library: Library, series: Series): Library {
        val exists = library.series.any { it.id == series.id }
        val list = if (exists) library.series.map { if (it.id == series.id) series else it }
        else library.series + series.copy(order = (library.series.maxOfOrNull { it.order } ?: -1) + 1)
        return library.copy(series = list)
    }

    fun upsertChapter(library: Library, chapter: Chapter): Library {
        val exists = library.chapters.any { it.id == chapter.id }
        val list = if (exists) library.chapters.map { if (it.id == chapter.id) chapter else it }
        else library.chapters + chapter
        return library.copy(chapters = list)
    }

    fun deleteSeries(library: Library, seriesId: String): Library = library.copy(
        series = library.series.filterNot { it.id == seriesId },
        chapters = library.chapters.filterNot { it.seriesId == seriesId }
    )

    fun deleteChapters(library: Library, ids: Set<String>): Library =
        library.copy(chapters = library.chapters.filterNot { it.id in ids })

    /** Applies a new "Custom Order" given the (possibly partial) visible id order. */
    fun reorderSeries(library: Library, orderedIds: List<String>): Library {
        val byId = library.series.associateBy { it.id }
        val head = orderedIds.mapNotNull { byId[it] }
        val tail = library.series.filter { it.id !in orderedIds.toSet() }.sortedBy { it.order }
        val reordered = (head + tail).mapIndexed { index, s -> s.copy(order = index) }
        return library.copy(series = reordered)
    }

    /** Next free chapter number (max numeric + 1, "1" for an empty series). */
    fun nextChapterNumber(library: Library, seriesId: String): String {
        val max = library.chapters.filter { it.seriesId == seriesId }
            .mapNotNull { it.number.trim().toDoubleOrNull() }.maxOrNull() ?: return "1"
        return (max.toLong() + 1).toString()
    }

    /** Bumps the series `lastUpdatedEpochSec` (mirrors the Cubari JSON `last_updated`). */
    fun touchSeries(library: Library, seriesId: String, nowEpochSec: Long): Library = library.copy(
        series = library.series.map { if (it.id == seriesId) it.copy(lastUpdatedEpochSec = nowEpochSec) else it }
    )

    /**
     * Imports a Cubari JSON file into the library: creates the series when unknown, infers Aruba folder /
     * padding conventions from existing URLs, and records every published chapter.
     */
    fun mergeCubariSeries(
        library: Library,
        slug: String,
        json: JsonObject,
        group: String,
        nowEpochSec: Long,
        arubaPublicBaseUrl: String = "",
        availableArubaFolders: List<String> = emptyList()
    ): Library {
        val existing = library.series.firstOrNull { it.cubariSlug == slug }
        val lastUpdated = CubariJson.lastUpdated(json)
        val keys = CubariJson.chapterKeys(json)
        val urlsByKey = keys.associateWith { CubariJson.pageUrls(json, it, group) }
        val allUrls = urlsByKey.values.flatten()
        val rawTitle = CubariJson.string(json, "title") ?: slug
        val remoteDesc = CubariJson.string(json, "description").orEmpty().trim()
        val remoteAuthor = CubariJson.string(json, "author").orEmpty().trim()
        val remoteArtist = CubariJson.string(json, "artist").orEmpty().trim()
        val remoteCover = CubariJson.string(json, "cover")
        val remoteSeriesSnap = CubariSeriesSnapshot(
            title = rawTitle.trim(),
            description = remoteDesc,
            author = remoteAuthor,
            artist = remoteArtist,
            coverUrl = remoteCover
        )
        val inferredFromUrls = ArubaPaths.inferSeriesFolderFromUrls(arubaPublicBaseUrl, allUrls).orEmpty()
        val matchedFolder = ArubaPaths.matchSeriesFolder(rawTitle, slug, inferredFromUrls, availableArubaFolders)
            ?: inferredFromUrls
        val hadLocalSeriesEdits = existing?.cubariSnapshot?.let { prev ->
            existing.title.trim() != prev.title ||
                existing.description.trim() != prev.description ||
                existing.author.trim() != prev.author ||
                existing.artist.trim() != prev.artist
        } ?: false
        val series = if (existing == null) {
            Series(
                id = newId("sr"),
                title = rawTitle,
                description = remoteDesc,
                author = remoteAuthor,
                artist = remoteArtist,
                coverUrl = remoteCover,
                lastUpdatedEpochSec = lastUpdated,
                cubariSlug = slug,
                arubaFolder = matchedFolder,
                cubariSnapshot = remoteSeriesSnap
            )
        } else {
            val adoptRemote = existing.cubariSnapshot != null && !hadLocalSeriesEdits
            existing.copy(
                title = if (adoptRemote && rawTitle.isNotBlank()) rawTitle else existing.title.ifBlank { rawTitle },
                description = if (adoptRemote) remoteDesc else existing.description.ifBlank { remoteDesc },
                author = if (adoptRemote) remoteAuthor else existing.author.ifBlank { remoteAuthor },
                artist = if (adoptRemote) remoteArtist else existing.artist.ifBlank { remoteArtist },
                coverUrl = remoteCover ?: existing.coverUrl,
                lastUpdatedEpochSec = lastUpdated ?: existing.lastUpdatedEpochSec,
                arubaFolder = existing.arubaFolder.ifBlank { matchedFolder },
                cubariSnapshot = remoteSeriesSnap
            )
        }
        var result = upsertSeries(library, series)
        val saved = result.series.first { it.cubariSlug == slug }
        val matchedChapterIds = mutableSetOf<String>()
        for (key in keys) {
            val chapterTs = CubariJson.chapterLastUpdated(json, key) ?: lastUpdated ?: nowEpochSec
            val urls = urlsByKey[key].orEmpty()
            val local = result.chapters.firstOrNull {
                it.seriesId == saved.id && (it.cubariKey == key || it.cubariSnapshot?.key == key)
            }
            val entry = (json["chapters"] as? JsonObject)?.get(key) as? JsonObject
            val remoteVol = CubariJson.string(entry, "volume").orEmpty().trim()
            val remoteTitle = CubariJson.string(entry, "title").orEmpty().trim()
            val preservedSig = when {
                local == null || local.pages.isEmpty() -> emptyList()
                local.cubariSnapshot != null && local.cubariSnapshot.pageUrls == urls -> local.cubariSnapshot.pageSignature
                else -> emptyList()
            }
            val remoteSnap = CubariChapterSnapshot(
                key = key,
                volume = remoteVol,
                title = remoteTitle,
                pageUrls = urls,
                pageSignature = preservedSig
            )
            val updatedChapter = if (local == null) {
                Chapter(
                    id = newId("ch"), seriesId = saved.id, number = key,
                    volume = remoteVol,
                    title = remoteTitle,
                    createdAtEpochSec = nowEpochSec,
                    cubariPublishedAtEpochSec = chapterTs,
                    cubariPageUrls = urls,
                    cubariSnapshot = remoteSnap
                )
            } else {
                val hadLocalMetaEdits = local.cubariSnapshot?.let { prev ->
                    local.cubariKey != prev.key ||
                        local.volume.trim() != prev.volume ||
                        local.title.trim() != prev.title
                } ?: false
                val adoptRemote = local.cubariSnapshot != null && !hadLocalMetaEdits
                local.copy(
                    number = if (adoptRemote) key else local.number,
                    volume = if (adoptRemote) remoteVol else local.volume.ifBlank { remoteVol },
                    title = if (adoptRemote) remoteTitle else local.title.ifBlank { remoteTitle },
                    cubariPublishedAtEpochSec = local.cubariPublishedAtEpochSec ?: chapterTs,
                    cubariUpdatedAtEpochSec = chapterTs.takeIf { it > (local.cubariUpdatedAtEpochSec ?: local.cubariPublishedAtEpochSec ?: 0L) }
                        ?: local.cubariUpdatedAtEpochSec,
                    cubariPageUrls = urls.ifEmpty { local.cubariPageUrls },
                    cubariSnapshot = remoteSnap
                )
            }
            matchedChapterIds += updatedChapter.id
            result = upsertChapter(result, updatedChapter)
        }
        // Clear Cubari published state if a chapter was removed from the remote JSON since last sync.
        for (orphaned in result.chapters.filter { it.seriesId == saved.id && it.id !in matchedChapterIds && (it.isOnCubari || it.cubariSnapshot != null) }) {
            result = upsertChapter(
                result,
                orphaned.copy(
                    cubariPublishedAtEpochSec = null,
                    cubariUpdatedAtEpochSec = null,
                    cubariPageUrls = emptyList(),
                    cubariSnapshot = null,
                    lastSyncedCubariTs = null,
                    lastSyncedMdTs = null
                )
            )
        }
        return result
    }

    /** Records MangaDex chapter ids/timestamps; chapters unknown locally become page-less remote entries. */
    fun mergeMangaDexChapters(
        library: Library,
        seriesId: String,
        md: List<MdChapter>,
        nowEpochSec: Long,
        language: String = "en"
    ): Library {
        var result = library
        for (remote in md) {
            val key = remote.key
            if (key.isEmpty()) continue
            val local = result.chapters.firstOrNull {
                it.seriesId == seriesId && (it.mangadexChapterId == remote.id || it.cubariKey == key)
            }
            val publishedAt = remote.publishAtEpochSec ?: nowEpochSec
            val updatedAt = remote.updatedAtEpochSec ?: remote.publishAtEpochSec ?: publishedAt
            val preservedSig = when {
                local == null || local.pages.isEmpty() -> emptyList()
                local.mdSnapshot != null && local.mdSnapshot.chapterId == remote.id && local.mdSnapshot.version == remote.version ->
                    local.mdSnapshot.pageSignature
                else -> emptyList()
            }
            val remoteSnap = MangaDexChapterSnapshot(
                chapterId = remote.id,
                version = remote.version,
                number = remote.chapter.orEmpty().trim(),
                volume = remote.volume.orEmpty().trim(),
                title = remote.title.orEmpty().trim(),
                language = language.trim().ifEmpty { local?.mdSnapshot?.language ?: "en" },
                pageSignature = preservedSig,
                pageCount = remote.pageCount
            )
            result = upsertChapter(
                result,
                local?.copy(
                    mangadexChapterId = remote.id,
                    mangadexVersion = remote.version,
                    mangadexPublishedAtEpochSec = publishedAt,
                    mangadexUpdatedAtEpochSec = updatedAt,
                    mdSnapshot = remoteSnap
                )
                    ?: Chapter(
                        id = newId("ch"), seriesId = seriesId, number = remote.chapter.orEmpty().trim(),
                        volume = remote.volume.orEmpty(), title = remote.title.orEmpty(),
                        createdAtEpochSec = nowEpochSec,
                        mangadexChapterId = remote.id, mangadexVersion = remote.version,
                        mangadexPublishedAtEpochSec = publishedAt,
                        mangadexUpdatedAtEpochSec = updatedAt,
                        mdSnapshot = remoteSnap
                    )
            )
        }
        return result
    }

    /** Matches series lacking an explicit [Series.arubaFolder] against live Aruba folders or cached Cubari URLs. */
    fun applyArubaFolders(library: Library, arubaPublicBaseUrl: String, availableFolders: List<String>): Library {
        val selectable = availableFolders.filterNot { ArubaPaths.isReservedSeriesFolder(it) }
        val updatedSeries = library.series.map { series ->
            val current = series.arubaFolder.trim()
            if (current.isNotEmpty() && (selectable.isEmpty() || current in selectable)) return@map series
            val urls = library.chaptersOf(series.id).flatMap { it.cubariPageUrls }
            val inferred = current.ifBlank { ArubaPaths.inferSeriesFolderFromUrls(arubaPublicBaseUrl, urls) }
            val matched = ArubaPaths.matchSeriesFolder(series.title, series.cubariSlug, inferred, selectable)
            if (!matched.isNullOrBlank() && matched != series.arubaFolder) series.copy(arubaFolder = matched) else series
        }
        return if (updatedSeries == library.series) library else library.copy(series = updatedSeries)
    }
}

/** Copies dropped / picked images into the plugin data dir and returns page references. */
class PageImporter(
    private val pluginFs: PluginFileSystem,
    private val hostFs: HostFileSystem,
    private val logger: PluginLogger? = null
) {
    /** Expands directories (one level) and filters to supported image extensions sorted naturally. */
    suspend fun expand(paths: List<String>): List<String> {
        val files = mutableListOf<String>()
        for (path in paths) {
            val isImage = isImage(path)
            if (isImage) {
                files += path
            } else {
                val children = runCatching { hostFs.listFiles(path) }.getOrDefault(emptyList())
                files += children.filter { isImage(it) }
            }
        }
        return files.distinct().sortedWith(FILE_NAME_NATURAL)
    }

    suspend fun import(chapterId: String, paths: List<String>): List<PageRef> {
        val pages = mutableListOf<PageRef>()
        for (source in expand(paths)) {
            val bytes = hostFs.readFile(source)
            if (bytes == null) {
                logger?.warn("Skipping unreadable page file: $source")
                continue
            }
            val name = source.substringAfterLast('/').substringAfterLast('\\')
            pages += storeChapterPage(pluginFs, chapterId, name, bytes)
        }
        logger?.info("Imported ${pages.size} page(s) into chapter $chapterId")
        return pages
    }

    companion object {
        val IMAGE_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")
        val NATURAL: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }
        val FILE_NAME_NATURAL: Comparator<String> = Comparator { a, b ->
            val nameA = a.substringAfterLast('/').substringAfterLast('\\')
            val nameB = b.substringAfterLast('/').substringAfterLast('\\')
            naturalCompare(nameA, nameB).takeIf { it != 0 } ?: naturalCompare(a, b)
        }

        fun extension(name: String): String = name.substringAfterLast('.', "png").lowercase()
        fun isImage(path: String): Boolean = extension(path) in IMAGE_EXTENSIONS && path.contains('.')

        /** Saves raw image [bytes] inside the plugin data directory (`chapters/<chapterId>/...`). */
        suspend fun storeChapterPage(
            pluginFs: PluginFileSystem,
            chapterId: String,
            originalName: String,
            bytes: ByteArray,
            remoteUrl: String? = null
        ): PageRef {
            val cleanExt = extension(originalName).takeIf { it in IMAGE_EXTENSIONS } ?: "png"
            val id = newId("pg")
            val stored = "chapters/$chapterId/$id.$cleanExt"
            pluginFs.writeFile(relPath(stored), bytes).getOrThrow()
            return PageRef(
                id = id,
                storedPath = stored,
                originalName = originalName,
                remoteUrl = remoteUrl,
                contentHash = sha256Hex(bytes)
            )
        }

        /** Wraps remote image URLs as remote-backed [PageRef] entries without downloading bytes eagerly. */
        fun remotePageRefs(urls: List<String>): List<PageRef> = urls.mapIndexed { index, url ->
            val raw = ArubaPaths.decodeUrlSegment(url.substringBefore('?').substringAfterLast('/'))
            val ext = extension(raw).takeIf { it in IMAGE_EXTENSIONS } ?: "png"
            val name = if (raw.isNotBlank() && raw.length <= 36 && '.' in raw) raw else "${(index + 1).toString().padStart(2, '0')}.$ext"
            PageRef(id = newId("pg"), storedPath = "", originalName = name, remoteUrl = url)
        }

        fun naturalCompare(a: String, b: String): Int {
            val ta = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val tb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ta.size, tb.size)) {
                val x = ta[i]
                val y = tb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.toBigInteger().compareTo(y.toBigInteger()).takeIf { it != 0 } ?: x.length.compareTo(y.length)
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return ta.size.compareTo(tb.size)
        }
    }
}

/** Formats a byte count cleanly for UI status messages, logs, and transfer summaries. */
fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

/** Summary of a local image cache inspection / prune pass under `chapters/`. */
data class CachePruneResult(
    val remainingBytes: Long,
    val orphanFilesRemoved: Int = 0,
    val evictedChapters: Int = 0,
    val freedBytes: Long = 0L
)

/**
 * Manages cached chapter page image files stored under `chapters/` in plugin data storage.
 * - Cleans orphaned files not referenced by any chapter page in `library.json` (and heals stale
 *   post-publish mixed `pages` lists).
 * - When the total size exceeds [maxCacheMb] (> 0), evicts oldest already-published chapters that
 *   have confirmed remote URLs and no unpushed local edits, converting them back to remote-only.
 * - Unpublished drafts and protected chapters are never deleted.
 */
object ImageCacheManager {
    suspend fun measureBytes(fs: ScopedFileSystem, library: Library): Long =
        discoverCachedFiles(fs, library).values.sum()

    suspend fun prune(
        fs: ScopedFileSystem,
        store: LibraryStore,
        maxCacheMb: Int,
        logger: PluginLogger? = null,
        protectedChapterIds: Set<String> = emptySet(),
        maxBytesOverride: Long? = null
    ): CachePruneResult {
        var library = store.load()

        // Heal any pre-v1.0.8 chapter that still holds a mixed remote+local draft matching its
        // already-published Cubari snapshot so its temporary local files can be reclaimed as orphans.
        val healIds = library.chapters.filter(::isPublishedStaleMixedChapter).mapTo(mutableSetOf()) { it.id }
        if (healIds.isNotEmpty()) {
            library = store.mutate { current ->
                var next = current
                for (ch in current.chapters) {
                    if (ch.id in healIds && isPublishedStaleMixedChapter(ch)) {
                        next = LibraryOps.upsertChapter(next, ch.copy(pages = emptyList()))
                    }
                }
                next
            }
        }

        val discovered = discoverCachedFiles(fs, library)
        val remaining = discovered.toMutableMap()
        val referenced = referencedPaths(library)

        var orphanCount = 0
        var freedBytes = 0L
        for ((path, size) in discovered) {
            if (path !in referenced) {
                if (runCatching { fs.deleteFile(relPath(path)).isSuccess }.getOrDefault(false)) {
                    remaining.remove(path)
                    orphanCount++
                    freedBytes += size
                }
            }
        }

        val limitBytes = maxBytesOverride ?: if (maxCacheMb > 0) maxCacheMb.toLong() * 1024L * 1024L else 0L
        var totalBytes = remaining.values.sum()
        val evictedModes = linkedMapOf<String, Boolean>() // chapterId -> clearPagesList

        if (limitBytes > 0L && totalBytes > limitBytes) {
            val candidates = library.chapters
                .filter { ch -> ch.id !in protectedChapterIds && ch.pages.any { it.isLocal } && isSafeToEvict(ch) }
                .sortedBy { it.cubariUpdatedAtEpochSec ?: it.cubariPublishedAtEpochSec ?: it.mangadexUpdatedAtEpochSec ?: it.createdAtEpochSec }

            for (ch in candidates) {
                if (totalBytes <= limitBytes) break
                val chPaths = ch.pages.mapNotNull { it.storedPath.takeIf(String::isNotBlank) }.toSet()
                val otherReferenced = library.chapters
                    .filter { it.id != ch.id && it.id !in evictedModes }
                    .flatMap { it.pages }
                    .mapNotNull { it.storedPath.takeIf(String::isNotBlank) }
                    .toSet()

                var chFreed = 0L
                for (path in chPaths) {
                    if (path !in otherReferenced) {
                        val size = remaining[path] ?: 0L
                        if (runCatching { fs.deleteFile(relPath(path)).isSuccess }.getOrDefault(false)) {
                            remaining.remove(path)
                            chFreed += size
                        }
                    }
                }
                if (chFreed > 0L || chPaths.isNotEmpty()) {
                    totalBytes = (totalBytes - chFreed).coerceAtLeast(0L)
                    freedBytes += chFreed
                    evictedModes[ch.id] = ch.isOnCubari && ch.cubariPageUrls.size == ch.pages.size
                }
            }

            if (evictedModes.isNotEmpty()) {
                store.mutate { current ->
                    var next = current
                    for ((chId, clearList) in evictedModes) {
                        val ch = next.chapterById(chId) ?: continue
                        if (!isSafeToEvict(ch)) continue
                        val updatedPages = if (clearList) emptyList() else ch.pages.map { it.copy(storedPath = "") }
                        next = LibraryOps.upsertChapter(next, ch.copy(pages = updatedPages))
                    }
                    next
                }
            }
        }

        pruneEmptyDiskDirs(fs)
        if (orphanCount > 0 || evictedModes.isNotEmpty()) {
            logger?.info(
                "Cache prune complete: removed $orphanCount orphan(s), evicted ${evictedModes.size} chapter(s), " +
                    "freed $freedBytes byte(s), remaining $totalBytes byte(s)"
            )
        }
        return CachePruneResult(
            remainingBytes = totalBytes,
            orphanFilesRemoved = orphanCount,
            evictedChapters = evictedModes.size,
            freedBytes = freedBytes
        )
    }

    private fun isPublishedStaleMixedChapter(ch: Chapter): Boolean =
        ch.isOnCubari &&
            ch.pages.isNotEmpty() &&
            ch.pages.any { it.isRemoteOnly } &&
            !ChangeDetector.hasPageChanges(ch.pages, ch.cubariSnapshot?.pageSignature, true, ch.cubariSnapshot?.pageHashes)

    private fun isSafeToEvict(ch: Chapter): Boolean {
        val noUnpushedCubariEdits = !ch.isOnCubari ||
            !ChangeDetector.hasPageChanges(ch.pages, ch.cubariSnapshot?.pageSignature, true, ch.cubariSnapshot?.pageHashes)
        val cubariBacked = ch.isOnCubari && ch.cubariPageUrls.size == ch.pages.size && noUnpushedCubariEdits
        val allRemoteUrlBacked = ch.pages.isNotEmpty() && ch.pages.all { !it.remoteUrl.isNullOrBlank() } && noUnpushedCubariEdits
        return cubariBacked || allRemoteUrlBacked
    }

    private fun referencedPaths(library: Library): Set<String> =
        library.chapters.flatMap { it.pages }
            .mapNotNull { it.storedPath.takeIf(String::isNotBlank) }
            .toSet()

    private suspend fun discoverCachedFiles(fs: ScopedFileSystem, library: Library): Map<String, Long> {
        val sizes = linkedMapOf<String, Long>()
        val basePath = fs.getBasePath()
        val chaptersDir = File(basePath, "chapters")
        if (chaptersDir.isDirectory) {
            val baseFile = File(basePath)
            chaptersDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = baseFile.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                sizes[rel] = file.length()
            }
            return sizes
        }
        val dirs = buildSet {
            library.chapters.forEach { add("chapters/${it.id}") }
            library.chapters.flatMap { it.pages }.forEach { p ->
                val sp = p.storedPath
                if (sp.startsWith("chapters/")) {
                    sp.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
        }
        for (dir in dirs) {
            val entries = runCatching { fs.listFiles(relPath(dir)) }.getOrDefault(emptyList())
            for (entry in entries) {
                val rel = if (entry.contains('/')) entry else "$dir/$entry"
                if (rel.startsWith("chapters/") && rel !in sizes) {
                    val size = runCatching { fs.readFile(relPath(rel))?.size?.toLong() }.getOrNull() ?: 0L
                    sizes[rel] = size
                }
            }
        }
        for (ch in library.chapters) {
            for (p in ch.pages) {
                val sp = p.storedPath
                if (sp.startsWith("chapters/") && sp !in sizes) {
                    val data = runCatching { fs.readFile(relPath(sp)) }.getOrNull()
                    if (data != null) sizes[sp] = data.size.toLong()
                }
            }
        }
        return sizes
    }

    private fun pruneEmptyDiskDirs(fs: ScopedFileSystem) {
        val chaptersDir = File(fs.getBasePath(), "chapters")
        if (!chaptersDir.isDirectory) return
        chaptersDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.listFiles()?.isEmpty() == true) {
                runCatching { child.delete() }
            }
        }
    }
}

/** Persists per-(chapter, source) remote image inspection reports inside plugin storage. */
class RemotePagesStore(private val storage: PluginStorage) {
    suspend fun save(report: RemoteChapterPages) {
        storage.put(key(report.chapterId, report.source), json.encodeToJsonElement(RemoteChapterPages.serializer(), report))
    }

    suspend fun remove(chapterId: String, source: String) {
        storage.remove(key(chapterId, source))
    }

    suspend fun loadAll(): Map<String, RemoteChapterPages> =
        storage.getAll().entries
            .filter { it.key.startsWith(PREFIX) }
            .mapNotNull { (_, value) ->
                runCatching { json.decodeFromJsonElement(RemoteChapterPages.serializer(), value) }.getOrNull()
                    ?.let { "${it.chapterId}:${it.source}" to it }
            }
            .toMap()

    companion object {
        private const val PREFIX = "remote_pages_"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun key(chapterId: String, source: String): String = "$PREFIX${chapterId}_$source"
    }
}

/** Persists the last scanned list of Aruba series folders in plugin storage. */
class ArubaFoldersStore(private val storage: PluginStorage) {
    suspend fun save(folders: List<String>) {
        val clean = folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        storage.put(KEY, json.encodeToJsonElement(ListSerializer(String.serializer()), clean))
    }

    suspend fun load(): List<String> {
        val elem = storage.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(ListSerializer(String.serializer()), elem) }
            .getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "aruba_scanned_folders"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
data class PersistedProgress(
    val fraction: Float? = null,
    val current: Double? = null,
    val total: Double? = null,
    val unit: String? = null,
    val message: String? = null
) {
    fun toProgressData(): ProgressData =
        if (current != null && total != null && total > 0.0) ProgressData.ratio(current, total, unit, message)
        else ProgressData.indeterminate(message)

    companion object {
        fun from(p: ProgressData): PersistedProgress = PersistedProgress(
            fraction = p.fraction,
            current = p.current,
            total = p.total,
            unit = p.unit,
            message = p.message
        )
    }
}

@Serializable
data class PersistedJobRecord(
    val jobId: String,
    val capabilityName: String,
    val status: CapabilityJobStatus = CapabilityJobStatus.QUEUED,
    val primaryProgress: PersistedProgress? = null,
    val secondaryProgress: PersistedProgress? = null,
    val subProgress: Map<String, PersistedProgress> = emptyMap(),
    val recentLogs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null
) {
    val isTerminal: Boolean
        get() = status == CapabilityJobStatus.COMPLETED ||
            status == CapabilityJobStatus.FAILED ||
            status == CapabilityJobStatus.CANCELLED

    fun toSnapshot(): CapabilityJobSnapshot = CapabilityJobSnapshot(
        jobId = jobId,
        capabilityName = capabilityName,
        status = status,
        primaryProgress = primaryProgress?.toProgressData(),
        secondaryProgress = secondaryProgress?.toProgressData(),
        subProgress = subProgress.mapValues { it.value.toProgressData() },
        recentLogs = recentLogs,
        errorMessage = errorMessage,
        startedAtEpochMs = startedAtEpochMs,
        completedAtEpochMs = completedAtEpochMs
    )

    companion object {
        fun from(snap: CapabilityJobSnapshot, fallbackStartedMs: Long? = null): PersistedJobRecord = PersistedJobRecord(
            jobId = snap.jobId,
            capabilityName = snap.capabilityName,
            status = snap.status,
            primaryProgress = snap.primaryProgress?.let(PersistedProgress::from),
            secondaryProgress = snap.secondaryProgress?.let(PersistedProgress::from),
            subProgress = snap.subProgress.mapValues { PersistedProgress.from(it.value) },
            recentLogs = snap.recentLogs.takeLast(10),
            errorMessage = snap.errorMessage,
            startedAtEpochMs = snap.startedAtEpochMs ?: fallbackStartedMs,
            completedAtEpochMs = snap.completedAtEpochMs
        )
    }
}

/** Persists launched capability job snapshots across UI session close/reopen cycles. */
class PersistedJobStore(private val storage: PluginStorage) {
    suspend fun loadAll(): List<PersistedJobRecord> = MUTEX.withLock {
        loadUnlocked()
    }

    suspend fun upsert(snapshot: CapabilityJobSnapshot, fallbackStartedMs: Long? = null) = MUTEX.withLock {
        val current = loadUnlocked().associateBy { it.jobId }.toMutableMap()
        val existingStart = current[snapshot.jobId]?.startedAtEpochMs
        current[snapshot.jobId] = PersistedJobRecord.from(snapshot, existingStart ?: fallbackStartedMs)
        saveUnlocked(current.values.toList())
    }

    suspend fun syncAll(snapshots: List<CapabilityJobSnapshot>, fallbackStartedMs: Long? = null) = MUTEX.withLock {
        if (snapshots.isEmpty()) return@withLock
        val current = loadUnlocked().associateBy { it.jobId }.toMutableMap()
        for (snap in snapshots) {
            val existingStart = current[snap.jobId]?.startedAtEpochMs
            current[snap.jobId] = PersistedJobRecord.from(snap, existingStart ?: fallbackStartedMs)
        }
        saveUnlocked(current.values.toList())
    }

    suspend fun removeByIds(jobIds: Set<String>) = MUTEX.withLock {
        if (jobIds.isEmpty()) return@withLock
        val next = loadUnlocked().filterNot { it.jobId in jobIds }
        saveUnlocked(next)
    }

    suspend fun clearTerminal() = MUTEX.withLock {
        val activeOnly = loadUnlocked().filterNot { it.isTerminal }
        saveUnlocked(activeOnly)
    }

    private suspend fun loadUnlocked(): List<PersistedJobRecord> {
        val elem = storage.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(ListSerializer(PersistedJobRecord.serializer()), elem) }
            .getOrDefault(emptyList())
    }

    private suspend fun saveUnlocked(records: List<PersistedJobRecord>) {
        val trimmed = records
            .sortedBy { it.startedAtEpochMs ?: Long.MAX_VALUE }
            .takeLast(MAX_RECORDS)
        storage.put(KEY, json.encodeToJsonElement(ListSerializer(PersistedJobRecord.serializer()), trimmed))
    }

    companion object {
        private const val KEY = "persisted_jobs"
        private const val MAX_RECORDS = 25
        private val MUTEX = Mutex()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
