package com.wip.mangauploader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.wip.plugintoolkit.api.ExecutionResult
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginNotificationType
import org.wip.plugintoolkit.api.PluginResponse
import org.wip.plugintoolkit.api.ProgressData
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class PublishResumeState(val nextPage: Int = 0, val uploadedUrls: List<String> = emptyList())

@Serializable
data class MangaDexResumeState(
    val sessionId: String? = null,
    val nextPage: Int = 0,
    val uploadedIds: List<String> = emptyList(),
    val slotIds: List<String?> = emptyList()
)

@Serializable
data class BackfillResumeState(val doneChapterKeys: List<String> = emptyList())

@Serializable
data class RepoSyncResumeState(val nextIndex: Int = 0, val skipped: List<String> = emptyList())

@Serializable
data class LoadPagesResumeState(val nextIndex: Int = 0, val pages: List<PageRef> = emptyList())

@Serializable
data class CubariIndexEntry(val slug: String, val title: String, val chapterKeys: List<String>, val lastUpdated: Long?)

/**
 * Implementation of every long-running `@Capability`. One instance is created per execution so the
 * [PauseGate] signal handler is registered exactly once per job.
 */
class UploaderWorkers(
    private val context: PluginContext,
    private val services: ServiceFactory = MangaUploaderServices.factory
) {
    private val store = LibraryStore(context.fileSystem, context.logger)
    private val gate = PauseGate(context)
    private val config: MangaUploaderConfig get() = MangaUploaderConfig.from(context.settings)
    private val clock get() = services.clock

    // ───────────────────────── syncCubariGitRepo ─────────────────────────

    suspend fun syncCubariGitRepo(push: Boolean, resume: JsonElement?): ExecutionResult = guarded("syncCubariGitRepo") {
        val cfg = config
        val git = services.git(context, cfg)
        val repo = CubariRepo.create(context, cfg, git)
        context.progress.report(ProgressData.indeterminate("Cloning / pulling Cubari repository"))
        repo.prepare()
        git.resolveBranch(repo.dir)?.takeIf { it.isNotBlank() && it != cfg.cubariBranch.trim() }?.let { branch ->
            context.logger.info("Resolved Cubari repo branch '$branch' (updating setting from '${cfg.cubariBranch}')")
            context.updateSetting(SettingKeys.CUBARI_BRANCH, branch)
        }

        val slugs = repo.listSlugs()
        val foldersStore = ArubaFoldersStore(context.storage)
        val cachedFolders = foldersStore.load()
        val liveFolders = if (cfg.arubaHost.isNotBlank() && cfg.arubaUsername.isNotBlank()) {
            context.progress.report(ProgressData.indeterminate("Scanning Aruba series folders..."))
            runCatching {
                services.aruba(context, cfg).use { it.listDirectoryNames(cfg.arubaChaptersPath.trim().ifEmpty { "/" }) }
            }.getOrNull()?.also { foldersStore.save(it) }
        } else null
        val availableFolders = liveFolders ?: cachedFolders

        val prevResume = decode(resume, RepoSyncResumeState.serializer())
        val start = prevResume?.nextIndex ?: 0
        val skipped = (prevResume?.skipped ?: emptyList()).toMutableList()
        val index = loadIndex().filterKeys { it in slugs }.toMutableMap()
        context.logger.info("Cubari repo has ${slugs.size} series file(s); resuming at $start")

        for (i in start until slugs.size) {
            if (gate.pauseCheckpoint()) {
                saveIndex(index)
                return@guarded ExecutionResult.Paused(encode(RepoSyncResumeState(i, skipped), RepoSyncResumeState.serializer()))
            }
            val slug = slugs[i]
            val json = try {
                repo.readSeries(slug)
            } catch (e: SecurityException) {
                // Known host RelativePath.TRAVERSAL_REGEX false positive for filenames containing '...' / '....'.
                context.logger.error("Skipped '$slug.json': ${e.message}", e)
                skipped += "$slug.json"
                context.progress.report(ProgressData.ratio((i + 1).toDouble(), slugs.size.toDouble(), "series", "Skipped $slug"))
                continue
            }
            if (json != null && CubariJson.looksLikeCubariSeries(json)) {
                store.mutate {
                    LibraryOps.mergeCubariSeries(
                        library = it,
                        slug = slug,
                        json = json,
                        group = cfg.cubariGroupName,
                        nowEpochSec = clock.nowEpochSec(),
                        arubaPublicBaseUrl = cfg.arubaPublicBaseUrl,
                        availableArubaFolders = availableFolders
                    )
                }
                index[slug] = CubariIndexEntry(
                    slug, CubariJson.string(json, "title") ?: slug,
                    CubariJson.chapterKeys(json).toList(), CubariJson.lastUpdated(json)
                )
            }
            context.progress.report(ProgressData.ratio((i + 1).toDouble(), slugs.size.toDouble(), "series", "Read $slug"))
        }
        if (availableFolders.isNotEmpty()) {
            store.mutate { LibraryOps.applyArubaFolders(it, cfg.arubaPublicBaseUrl, availableFolders) }
        }
        saveIndex(index)

        val pushed = if (push) {
            context.progress.report(ProgressData.indeterminate("Committing & pushing Cubari changes..."))
            repo.commitAndPush("Sync Cubari repository")
        } else false
        context.progress.report(ProgressData.ratio(slugs.size.toDouble(), slugs.size.coerceAtLeast(1).toDouble(), "series", "Synced ${index.size} series"))
        if (skipped.isNotEmpty()) {
            context.showNotification(
                "Cubari sync skipped ${skipped.size} file(s)",
                "Skipped ${skipped.joinToString()}: host RelativePath rejected '...' in filename.",
                PluginNotificationType.WARNING
            )
        }
        val skippedNote = if (skipped.isEmpty()) "" else " (skipped ${skipped.size}: ${skipped.joinToString()})"
        success("Synced ${index.size} series" + (if (pushed) " and pushed changes" else "") + skippedNote) {
            put("series", index.size); put("pushed", pushed); put("skipped", skipped.size)
        }
    }

    // ───────────────────────── publishToArubaAndCubari ─────────────────────────

    suspend fun publishToArubaAndCubari(
        seriesId: String,
        chapterId: String,
        operation: String,
        resume: JsonElement?
    ): ExecutionResult = guarded("publishToArubaAndCubari") {
        val cfg = config
        val library = store.load()
        val series = library.seriesById(seriesId) ?: error("Series $seriesId not found")
        val repo = CubariRepo.create(context, cfg, services.git(context, cfg))
        context.progress.report(ProgressData.indeterminate("Preparing Cubari repository"))
        repo.prepare()

        if (operation == OP_SERIES_META) {
            val now = clock.nowEpochSec()
            context.progress.report(ProgressData.indeterminate("Updating ${series.title} metadata on Cubari..."))
            val updatedJson = CubariJson.applySeriesMeta(repo.readSeries(series.cubariSlug), series, now)
            repo.writeSeries(series.cubariSlug, updatedJson)
            repo.commitAndPush("Update ${series.title} series metadata")
            store.mutate { lib ->
                val latest = lib.seriesById(seriesId) ?: series
                LibraryOps.upsertSeries(
                    lib,
                    latest.copy(
                        lastUpdatedEpochSec = now,
                        cubariSnapshot = ChangeDetector.cubariSeriesSnapshot(latest)
                    )
                )
            }
            context.progress.report(ProgressData.ratio(1.0, 1.0, "series", "Updated metadata for ${series.title}"))
            context.showNotification("Updated Cubari series", series.title, PluginNotificationType.SUCCESS)
            return@guarded success("Updated metadata for ${series.title} on Cubari") {
                put("metadataOnly", true)
                put("lastUpdated", now)
            }
        }

        val chapter = library.chapterById(chapterId) ?: error("Chapter $chapterId not found")
        val arubaCfg = ArubaConfig.from(cfg)
        val plan = ChangeDetector.planCubariPush(series, chapter, includeSeriesMeta = true)

        if (operation == OP_REMOVE) {
            val dirKey = plan.previousRemoteKey ?: chapter.cubariKey
            val remoteDir = ArubaPaths.chapterDir(arubaCfg, series.effectiveArubaFolder, dirKey)
            context.progress.report(ProgressData.indeterminate("Removing ch. ${chapter.number} from Aruba & Cubari..."))
            return@guarded services.aruba(context, cfg).use { storage ->
                removeFromCubari(cfg, repo, storage, series, chapter, remoteDir, dirKey)
            }
        }

        require(chapter.number.isNotBlank()) { "Chapter number is required" }

        // Metadata-only path: update Cubari JSON (including key renames) without touching Aruba storage.
        val existingJson = repo.readSeries(series.cubariSlug)
        val hostedUrls = if (plan.kind == TargetPushKind.METADATA_ONLY) {
            (plan.previousRemoteKey?.let { CubariJson.pageUrls(existingJson, it, cfg.cubariGroupName) }?.ifEmpty { null }
                ?: CubariJson.pageUrls(existingJson, chapter.cubariKey, cfg.cubariGroupName).ifEmpty { null }
                ?: chapter.cubariPageUrls)
        } else emptyList()

        if (plan.kind == TargetPushKind.METADATA_ONLY && hostedUrls.isNotEmpty()) {
            val now = clock.nowEpochSec()
            context.progress.report(ProgressData.indeterminate("Updating Cubari metadata for ch. ${chapter.number}..."))
            val updatedJson = writeCubariChapterJson(
                existing = existingJson,
                series = series,
                chapter = chapter,
                group = cfg.cubariGroupName,
                urls = hostedUrls,
                previousRemoteKey = plan.previousRemoteKey,
                now = now
            )
            repo.writeSeries(series.cubariSlug, updatedJson)
            repo.commitAndPush("Update ${series.title} ch. ${chapter.number} metadata")
            recordCubariPublish(series, chapter, hostedUrls, now)
            context.progress.report(ProgressData.ratio(1.0, 1.0, "chapter", "Updated ch. ${chapter.number} metadata"))
            context.showNotification(
                "Updated Cubari metadata",
                "${series.title} ch. ${chapter.number} (${plan.changedFields.joinToString()})",
                PluginNotificationType.SUCCESS
            )
            return@guarded success("Updated metadata for ch. ${chapter.number} on Cubari") {
                put("metadataOnly", true)
                put("pages", hostedUrls.size)
                put("lastUpdated", now)
            }
        }

        val remoteDir = ArubaPaths.chapterDir(arubaCfg, series.effectiveArubaFolder, chapter.cubariKey)
        val oldRemoteDir = plan.previousRemoteKey
            ?.takeIf { it != chapter.cubariKey }
            ?.let { ArubaPaths.chapterDir(arubaCfg, series.effectiveArubaFolder, it) }
        val padDigits = ArubaPaths.resolvePadDigits(arubaCfg, series, library.chaptersOf(series.id))
        val effectivePages = chapter.pages.ifEmpty { PageImporter.remotePageRefs(chapter.cubariPageUrls) }
        require(effectivePages.isNotEmpty()) { "Chapter has no pages" }

        val targetPaths = effectivePages.indices.map { idx ->
            val ext = PageImporter.extension(effectivePages[idx].originalName)
            "$remoteDir/${ArubaPaths.pageName(idx, ext, padDigits)}"
        }
        val targetUrls = targetPaths.map { ArubaPaths.publicUrl(arubaCfg, it) }
        val currentHashes = resolvePageHashes(effectivePages)

        val downloadThrottle = BandwidthThrottle({ config.maxDownloadKbps }, clock)

        services.aruba(context, cfg).use { storage ->
            val state = decode(resume, PublishResumeState.serializer()) ?: PublishResumeState()
            val prevSnap = chapter.cubariSnapshot
            val prevUrls = prevSnap?.pageUrls?.takeIf { it.isNotEmpty() }
                ?: CubariJson.pageUrls(existingJson, plan.previousRemoteKey ?: chapter.cubariKey, cfg.cubariGroupName).takeIf { it.isNotEmpty() }
                ?: chapter.cubariPageUrls
            val prevPaths = prevUrls.map { ArubaPaths.remotePathFromUrl(arubaCfg, it) }
            val dirsToInspect = buildSet {
                add(remoteDir)
                oldRemoteDir?.let { add(it) }
                prevPaths.filterNotNull().forEach { p ->
                    p.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            val remoteFilesByDir = dirsToInspect.associateWith { dir ->
                runCatching { storage.listNames(dir).toSet() }.getOrDefault(emptySet())
            }
            val matchedPrev = IncrementalPagePlanner.matchExistingPages(
                currentPages = effectivePages,
                currentHashes = currentHashes,
                previousSignatures = prevSnap?.pageSignature.orEmpty(),
                previousHashes = prevSnap?.pageHashes.orEmpty(),
                previousUrls = prevUrls,
                isPreviousEligible = { j ->
                    val path = prevPaths.getOrNull(j)
                    if (path != null) {
                        val dir = path.substringBeforeLast('/', "")
                        val name = path.substringAfterLast('/')
                        remoteFilesByDir[dir]?.contains(name) == true
                    } else false
                },
                requireSameExtension = true
            )
            val reusedSources = effectivePages.indices.map { i ->
                val prevPath = matchedPrev[i]?.let { prevPaths.getOrNull(it) }
                when {
                    prevPath != null -> prevPath
                    !effectivePages[i].isLocal && effectivePages[i].remoteUrl == targetUrls[i] -> targetPaths[i]
                    else -> null
                }
            }
            val slotNeedsUpload = BooleanArray(effectivePages.size) { i -> reusedSources[i] == null }

            if (state.nextPage == 0) {
                val moves = effectivePages.indices.mapNotNull { i ->
                    val src = reusedSources[i]
                    if (src != null && src != targetPaths[i]) src to targetPaths[i] else null
                }
                val keptCount = effectivePages.indices.count { reusedSources[it] == targetPaths[it] }
                val dirtyCount = slotNeedsUpload.count { it }
                context.logger.info(
                    "Aruba upload plan for Ch. ${chapter.number}: total=${effectivePages.size}, " +
                        "kept=$keptCount, rename=${moves.size}, upload=$dirtyCount"
                )
                if (moves.isNotEmpty()) {
                    context.progress.report(ProgressData.indeterminate("Renaming ${moves.size} shifted page(s) on Aruba..."))
                    val tmpToken = clock.nowEpochSec()
                    val renameOps = IncrementalPagePlanner.planCycleSafeRenames(moves) { src, seq ->
                        val dir = src.substringBeforeLast('/', "")
                        val ext = src.substringAfterLast('.', "tmp")
                        val file = ".wom_tmp_${tmpToken}_$seq.$ext"
                        if (dir.isBlank()) file else "$dir/$file"
                    }
                    for ((src, dst) in renameOps) {
                        storage.rename(src, dst)
                    }
                }
            }

            val remainingDirtyIndices = effectivePages.indices.filter { it >= state.nextPage && slotNeedsUpload[it] }
            val knownDirtyBytes = measureKnownLocalBytes(effectivePages, remainingDirtyIndices)
            val tracker = TransferProgressTracker(
                context,
                clock,
                remainingDirtyIndices.size,
                "Aruba Ch. ${chapter.number}:",
                knownDirtyBytes,
                limitKbpsProvider = { config.maxUploadKbps }
            )
            if (remainingDirtyIndices.isNotEmpty()) {
                tracker.reportInitial()
                context.logger.info(
                    "Starting Aruba incremental upload for Ch. ${chapter.number} (${remainingDirtyIndices.size}/${effectivePages.size} page(s) to upload, " +
                        "limits: upload=${limitLabel(cfg.maxUploadKbps)}, download=${limitLabel(cfg.maxDownloadKbps)})"
                )
            } else {
                context.progress.report(ProgressData.indeterminate("No page uploads needed; finalizing Ch. ${chapter.number} on Aruba & Cubari..."))
            }

            val uploadedUrls = state.uploadedUrls.toMutableList()
            var next = state.nextPage
            val total = effectivePages.size
            val batchSize = cfg.concurrentUploads.coerceAtLeast(1)
            val remoteApi by lazy { services.mangadex(context, cfg) }

            while (next < total) {
                if (!slotNeedsUpload[next]) {
                    uploadedUrls += targetUrls[next]
                    next++
                    continue
                }
                if (gate.pauseCheckpoint()) {
                    context.logger.info("Publish paused at page $next/$total")
                    return@guarded ExecutionResult.Paused(encode(PublishResumeState(next, uploadedUrls), PublishResumeState.serializer()))
                }
                val batchIndices = mutableListOf<Int>()
                var cursor = next
                while (cursor < total && batchIndices.size < batchSize && slotNeedsUpload[cursor]) {
                    batchIndices += cursor
                    cursor++
                }
                val uploadedBytes = coroutineScope {
                    batchIndices.map { idx ->
                        async {
                            val page = effectivePages[idx]
                            val targetPath = targetPaths[idx]
                            val bytes = if (page.isLocal) {
                                context.fileSystem.readFile(relPath(page.storedPath))
                                    ?: error("Page file missing: ${page.originalName}")
                            } else {
                                remoteApi.download(page.remoteUrl ?: error("Remote page URL missing for ${page.originalName}"))
                                    .also { downloadThrottle.acquire(it.size) }
                            }
                            storage.upload(targetPath, bytes)
                            bytes.size.toLong()
                        }
                    }.awaitAll()
                }
                for (i in next until cursor) {
                    uploadedUrls += targetUrls[i]
                }
                val batchBytes = uploadedBytes.sum()
                next = cursor
                tracker.advance(batchIndices.size, batchBytes)
            }
            (storage as? ThrottledStorage)?.stats()?.let { st ->
                if (st.bytes > 0L) {
                    context.logger.info(
                        "Aruba upload transfer summary: ${formatBytes(st.bytes)} in ${st.elapsedMs}ms " +
                            "(~${st.effectiveKbps.toInt()} KB/s, limit=${limitLabel(st.limitKbps)}, throttleWait=${st.waitedMs}ms)"
                    )
                }
            }

            context.progress.clearSecondary()
            context.progress.report(ProgressData.indeterminate("Finalizing Cubari JSON & Git push for ch. ${chapter.number}..."))
            removeStalePages(storage, remoteDir, uploadedUrls)
            if (oldRemoteDir != null && oldRemoteDir != remoteDir) {
                removeStalePages(storage, oldRemoteDir, emptyList())
            }
            val urls: List<String> = uploadedUrls

            val now = clock.nowEpochSec()
            val wasPublished = chapter.isOnCubari
            val json = writeCubariChapterJson(
                existing = repo.readSeries(series.cubariSlug),
                series = series,
                chapter = chapter,
                group = cfg.cubariGroupName,
                urls = urls,
                previousRemoteKey = plan.previousRemoteKey,
                now = now
            )
            repo.writeSeries(series.cubariSlug, json)
            repo.commitAndPush("${if (wasPublished) "Update" else "Add"} ${series.title} ch. ${chapter.number}")

            val resolvedHashes = currentHashes.filterNotNull().takeIf { it.size == effectivePages.size }
            recordCubariPublish(series, chapter, urls, now, resolvedHashes)
            context.showNotification(
                "Published to Cubari",
                "${series.title} ch. ${chapter.number} (${urls.size} pages)",
                PluginNotificationType.SUCCESS
            )
            success("${if (wasPublished) "Updated" else "Published"} ch. ${chapter.number} on Cubari") {
                put("pages", urls.size); put("lastUpdated", now)
            }
        }
    }

    private fun writeCubariChapterJson(
        existing: kotlinx.serialization.json.JsonObject?,
        series: Series,
        chapter: Chapter,
        group: String,
        urls: List<String>,
        previousRemoteKey: String?,
        now: Long
    ): kotlinx.serialization.json.JsonObject {
        val withMeta = CubariJson.applySeriesMeta(existing, series, now)
        val cleaned = if (previousRemoteKey != null && previousRemoteKey != chapter.cubariKey) {
            CubariJson.removeChapter(withMeta, previousRemoteKey, group, now)
        } else withMeta
        return CubariJson.upsertChapter(cleaned, chapter.cubariKey, chapter.title, chapter.volume, group, urls, now)
    }

    private suspend fun recordCubariPublish(
        series: Series,
        chapter: Chapter,
        urls: List<String>,
        now: Long,
        pageHashes: List<String>? = chapter.cubariSnapshot?.pageHashes
    ) {
        var urlsChanged = false
        store.mutate { lib ->
            val latestSeries = lib.seriesById(series.id) ?: series
            val latest = lib.chapterById(chapter.id) ?: chapter
            urlsChanged = latest.cubariPageUrls != urls
            val hadRemotePages = latest.pages.any { it.isRemoteOnly }
            // Mixed remote+local drafts were edits atop a remote chapter: once published, clear the
            // transient draft so the UI displays the canonical published Aruba/Cubari URLs without stale
            // local items or shifted pre-publish remote URLs.
            val nextPages = when {
                latest.pages.isEmpty() -> emptyList()
                hadRemotePages -> emptyList()
                else -> latest.pages.mapIndexed { idx, ref ->
                    ref.copy(remoteUrl = urls.getOrNull(idx) ?: ref.remoteUrl)
                }
            }
            val sigPages = if (hadRemotePages && urls.isNotEmpty()) PageImporter.remotePageRefs(urls) else nextPages
            val snap = CubariChapterSnapshot(
                key = latest.cubariKey,
                volume = latest.volume.trim(),
                title = latest.title.trim(),
                pageUrls = urls,
                pageSignature = ChangeDetector.pageListSignature(sigPages),
                pageHashes = pageHashes
            )
            var updated = LibraryOps.upsertChapter(
                lib,
                latest.copy(
                    pages = nextPages,
                    cubariPublishedAtEpochSec = latest.cubariPublishedAtEpochSec ?: now,
                    cubariUpdatedAtEpochSec = now,
                    cubariPageUrls = urls,
                    cubariSnapshot = snap
                )
            )
            updated = LibraryOps.upsertSeries(
                updated,
                latestSeries.copy(
                    lastUpdatedEpochSec = now,
                    cubariSnapshot = ChangeDetector.cubariSeriesSnapshot(latestSeries)
                )
            )
            updated
        }
        if (urlsChanged) {
            RemotePagesStore(context.storage).remove(chapter.id, SOURCE_CUBARI)
        }
        ImageCacheManager.prune(
            fs = context.fileSystem,
            store = store,
            maxCacheMb = config.maxCacheMb,
            logger = context.logger,
            protectedChapterIds = setOf(chapter.id)
        )
    }

    private suspend fun removeFromCubari(
        cfg: MangaUploaderConfig,
        repo: CubariRepo,
        storage: ArubaStorage,
        series: Series,
        chapter: Chapter,
        remoteDir: String,
        remoteKey: String = chapter.cubariKey
    ): ExecutionResult {
        val now = clock.nowEpochSec()
        val existing = repo.readSeries(series.cubariSlug)
        if (existing != null) {
            repo.writeSeries(series.cubariSlug, CubariJson.removeChapter(existing, remoteKey, cfg.cubariGroupName, now))
        }
        runCatching { storage.listNames(remoteDir).forEach { storage.delete("$remoteDir/$it") } }
            .onFailure { context.logger.warn("Could not delete remote pages in $remoteDir: ${it.message}") }
        repo.commitAndPush("Remove ${series.title} ch. ${chapter.number}")
        store.mutate { lib ->
            val latest = lib.chapterById(chapter.id) ?: chapter
            LibraryOps.touchSeries(
                LibraryOps.upsertChapter(
                    lib,
                    latest.copy(
                        cubariPublishedAtEpochSec = null,
                        cubariUpdatedAtEpochSec = null,
                        cubariPageUrls = emptyList(),
                        lastSyncedCubariTs = null,
                        lastSyncedMdTs = null,
                        cubariSnapshot = null
                    )
                ),
                series.id, now
            )
        }
        context.showNotification("Removed from Cubari", "${series.title} ch. ${chapter.number}", PluginNotificationType.INFO)
        return success("Removed ch. ${chapter.number} from Cubari") { put("removed", true) }
    }

    private suspend fun removeStalePages(storage: ArubaStorage, remoteDir: String, urls: List<String>) {
        val keep = urls.map { ArubaPaths.decodeUrlSegment(it.substringAfterLast('/')) }.toSet()
        runCatching {
            storage.listNames(remoteDir).filter { STALE_PAGE.matches(it) && it !in keep }.forEach {
                context.logger.info("Deleting stale remote page $it")
                storage.delete("$remoteDir/$it")
            }
        }.onFailure { context.logger.warn("Stale page cleanup skipped: ${it.message}") }
    }

    // ───────────────────────── publishToMangaDex ─────────────────────────

    suspend fun publishToMangaDex(seriesId: String, chapterId: String, resume: JsonElement?): ExecutionResult =
        guarded("publishToMangaDex") {
            val cfg = config
            val library = store.load()
            val series = library.seriesById(seriesId) ?: error("Series $seriesId not found")
            val chapter = library.chapterById(chapterId) ?: error("Chapter $chapterId not found")
            val mangaId = series.mangadexId?.let { MangaUploaderConfig.extractUuid(it) }
                ?: error("Series '${series.title}' has no MangaDex manga id (set it in the series editor)")
            val groupId = cfg.mdGroupId ?: error("No MangaDex scanlation group configured")

            val api = services.mangadex(context, cfg)
            val token = ensureMangaDexToken(api, cfg)
            val plan = ChangeDetector.planMangaDexPush(chapter, cfg.mdLanguage)
            val draft = MdChapterDraft(chapter.volume, chapter.number, chapter.title, cfg.mdLanguage)

            if (plan.kind == TargetPushKind.METADATA_ONLY && !chapter.mangadexChapterId.isNullOrBlank()) {
                context.progress.report(ProgressData.indeterminate("Updating MangaDex metadata for ch. ${chapter.number}..."))
                val updated = api.updateChapterMetadata(token, chapter.mangadexChapterId, chapter.mangadexVersion ?: 1, draft)
                val now = clock.nowEpochSec()
                val sig = chapter.mdSnapshot?.pageSignature ?: ChangeDetector.pageListSignature(chapter.pages)
                recordMangaDexPublish(chapterId, chapter, updated, cfg.mdLanguage, now, sig)
                context.progress.report(ProgressData.ratio(1.0, 1.0, "chapter", "Updated MangaDex metadata for ch. ${chapter.number}"))
                context.showNotification(
                    "Updated MangaDex metadata",
                    "${series.title} ch. ${chapter.number} (${plan.changedFields.joinToString()})",
                    PluginNotificationType.SUCCESS
                )
                return@guarded success("Updated metadata for ch. ${chapter.number} on MangaDex") {
                    put("mangadexChapterId", updated.id)
                    put("metadataOnly", true)
                }
            }

            val effectivePages = chapter.pages.ifEmpty { PageImporter.remotePageRefs(chapter.cubariPageUrls) }
            require(effectivePages.isNotEmpty()) { "Chapter has no pages" }
            val currentHashes = resolvePageHashes(effectivePages)

            if (mdUploadMutex.isLocked) {
                context.progress.report(ProgressData.indeterminate("Waiting for active MangaDex upload session to finish..."))
            }
            mdUploadMutex.withLock {
                val state = decode(resume, MangaDexResumeState.serializer()) ?: MangaDexResumeState()
                if (state.sessionId == null) {
                    context.progress.report(ProgressData.indeterminate("Checking active MangaDex upload sessions..."))
                    val existing = api.activeUploadSession(token)
                    if (existing != null && !existing.isCommitted && !existing.isDeleted) {
                        val ageSec = existing.ageSeconds(clock.nowEpochSec())
                        if (ageSec != null && ageSec >= STALE_UPLOAD_SESSION_SEC) {
                            context.logger.info(
                                "Closing stale MangaDex upload session ${existing.id} (age ${ageSec / 60}m >= ${STALE_UPLOAD_SESSION_SEC / 60}m)"
                            )
                            context.progress.report(
                                ProgressData.indeterminate("Closing stale upload session (${ageSec / 60}m old)...")
                            )
                            api.closeUploadSession(token, existing.id)
                        } else {
                            val ageHint = ageSec?.let { "${it / 60}m old" } ?: "age unknown"
                            throw MangaDexException(
                                409,
                                "Active MangaDex upload session ${existing.id} ($ageHint) is already open; wait for it to finish or use 'Force Cancel Upload Session' in Settings & Locks."
                            )
                        }
                    }
                }

                val editChapterId = chapter.mangadexChapterId?.takeIf { it.isNotBlank() }
                val sessionId: String
                val initialSlotIds: List<String?>
                if (state.sessionId != null) {
                    sessionId = state.sessionId
                    initialSlotIds = if (state.slotIds.size == effectivePages.size) {
                        state.slotIds
                    } else {
                        List(effectivePages.size) { idx -> state.uploadedIds.getOrNull(idx) }
                    }
                } else if (editChapterId != null) {
                    val editSession = api.beginEditSession(token, editChapterId, chapter.mangadexVersion ?: 1)
                    sessionId = editSession.sessionId
                    initialSlotIds = matchMangaDexEditSlots(
                        api = api,
                        chapter = chapter,
                        editChapterId = editChapterId,
                        effectivePages = effectivePages,
                        currentHashes = currentHashes,
                        prefilled = editSession.prefilledFiles
                    )
                } else {
                    sessionId = api.beginUpload(token, mangaId, groupId)
                    initialSlotIds = List(effectivePages.size) { null }
                }

                val slotIds = initialSlotIds.toMutableList()
                val dirtyIndices = effectivePages.indices.filter { slotIds[it] == null }
                val reusedCount = effectivePages.size - dirtyIndices.size
                val total = effectivePages.size
                val dirtyTotal = dirtyIndices.size
                val knownDirtyBytes = measureKnownLocalBytes(effectivePages, dirtyIndices)
                val tracker = TransferProgressTracker(
                    context,
                    clock,
                    dirtyTotal,
                    "MangaDex Ch. ${chapter.number}:",
                    knownDirtyBytes,
                    limitKbpsProvider = { config.maxUploadKbps }
                )
                if (dirtyIndices.isNotEmpty()) {
                    tracker.reportInitial()
                    context.logger.info(
                        "Starting MangaDex upload session $sessionId for Ch. ${chapter.number} " +
                            "($dirtyTotal/$total page(s) to upload, reused=$reusedCount, " +
                            "limits: upload=${limitLabel(cfg.maxUploadKbps)}, download=${limitLabel(cfg.maxDownloadKbps)}, " +
                            "interval=${cfg.mdUploadIntervalMs}ms, concurrency=${cfg.concurrentDownloads})"
                    )
                } else {
                    context.logger.info(
                        "MangaDex edit session $sessionId for Ch. ${chapter.number}: reusing all $reusedCount page(s), 0 uploads needed"
                    )
                    context.progress.report(
                        ProgressData.indeterminate("Reusing all $reusedCount page(s) for MangaDex ch. ${chapter.number}...")
                    )
                }

                val downloadThrottle = BandwidthThrottle({ config.maxDownloadKbps }, clock)
                val uploadThrottle = BandwidthThrottle({ config.maxUploadKbps }, clock)
                val dlSem = Semaphore(cfg.concurrentDownloads.coerceAtLeast(1))
                val completedDl = AtomicInteger(0)
                var dirtyCursor = 0
                var lastRequestNanos = 0L
                try {
                    while (dirtyCursor < dirtyIndices.size) {
                        if (gate.pauseCheckpoint()) {
                            context.logger.info("MangaDex upload paused at dirty page $dirtyCursor/${dirtyIndices.size}")
                            return@guarded ExecutionResult.Paused(
                                encode(
                                    MangaDexResumeState(sessionId, dirtyCursor, slotIds.filterNotNull(), slotIds),
                                    MangaDexResumeState.serializer()
                                )
                            )
                        }
                        val firstDirtyPage = effectivePages[dirtyIndices[dirtyCursor]]
                        val preparedSlots = mutableListOf<Pair<Int, MdUploadFile>>()
                        var batchBytes = 0L

                        if (firstDirtyPage.isLocal) {
                            while (dirtyCursor + preparedSlots.size < dirtyIndices.size && preparedSlots.size < MD_MAX_FILES_PER_REQUEST) {
                                val slotIdx = dirtyIndices[dirtyCursor + preparedSlots.size]
                                val page = effectivePages[slotIdx]
                                if (!page.isLocal) break
                                val bytes = context.fileSystem.readFile(relPath(page.storedPath))
                                    ?: error("Page file missing: ${page.originalName}")
                                if (preparedSlots.isNotEmpty() && batchBytes + bytes.size > MD_MAX_BATCH_BYTES) break
                                batchBytes += bytes.size.toLong()
                                val ext = PageImporter.extension(page.originalName)
                                preparedSlots += slotIdx to MdUploadFile(ArubaPaths.pageName(slotIdx, ext), bytes, contentType(ext))
                            }
                        } else {
                            val remoteEnd = minOf(dirtyIndices.size, dirtyCursor + MD_REMOTE_BATCH_SIZE)
                            val remoteSlice = dirtyIndices.subList(dirtyCursor, remoteEnd)
                                .takeWhile { !effectivePages[it].isLocal }
                            context.progress.reportSecondary(
                                ProgressData.ratio(
                                    dirtyCursor.toDouble(),
                                    dirtyTotal.toDouble().coerceAtLeast(1.0),
                                    "pages",
                                    "Downloading pages ${dirtyCursor + 1}..${dirtyCursor + remoteSlice.size}/$dirtyTotal"
                                )
                            )
                            val downloaded = coroutineScope {
                                remoteSlice.map { slotIdx ->
                                    val page = effectivePages[slotIdx]
                                    async {
                                        dlSem.withPermit {
                                            val dlStart = clock.nowNanos()
                                            val bytes = api.download(page.remoteUrl ?: error("Remote page URL missing for ${page.originalName}"))
                                                .also { downloadThrottle.acquire(it.size) }
                                            val dlMs = ((clock.nowNanos() - dlStart) / 1_000_000L).coerceAtLeast(1L)
                                            val doneSoFar = completedDl.incrementAndGet()
                                            context.logger.info(
                                                "Fetched page ${slotIdx + 1}/$total (${page.originalName}, ${formatBytes(bytes.size.toLong())} in ${dlMs}ms)"
                                            )
                                            context.progress.reportSecondary(
                                                ProgressData.ratio(
                                                    doneSoFar.toDouble().coerceAtMost(dirtyTotal.toDouble()),
                                                    dirtyTotal.toDouble().coerceAtLeast(1.0),
                                                    "pages",
                                                    "Fetched $doneSoFar/$dirtyTotal (${formatBytes(bytes.size.toLong())})"
                                                )
                                            )
                                            val ext = PageImporter.extension(page.originalName)
                                            slotIdx to MdUploadFile(ArubaPaths.pageName(slotIdx, ext), bytes, contentType(ext))
                                        }
                                    }
                                }.awaitAll()
                            }
                            preparedSlots += downloaded
                            batchBytes = downloaded.sumOf { it.second.bytes.size.toLong() }
                        }

                        var subStart = 0
                        while (subStart < preparedSlots.size) {
                            val chunk = mutableListOf<Pair<Int, MdUploadFile>>()
                            var chunkBytes = 0L
                            while (subStart < preparedSlots.size && chunk.size < MD_MAX_FILES_PER_REQUEST) {
                                val entry = preparedSlots[subStart]
                                if (chunk.isNotEmpty() && chunkBytes + entry.second.bytes.size > MD_MAX_BATCH_BYTES) break
                                chunk += entry
                                chunkBytes += entry.second.bytes.size.toLong()
                                subStart++
                            }
                            val firstNum = dirtyCursor + 1
                            val lastNum = dirtyCursor + chunk.size
                            context.progress.reportSecondary(
                                ProgressData.ratio(
                                    lastNum.toDouble().coerceAtMost(dirtyTotal.toDouble()),
                                    dirtyTotal.toDouble().coerceAtLeast(1.0),
                                    "pages",
                                    "Uploading pages $firstNum..$lastNum/$dirtyTotal (${formatBytes(chunkBytes)})"
                                )
                            )
                            val upStartNanos = clock.nowNanos()
                            uploadThrottle.acquire(chunkBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                            lastRequestNanos = waitForUploadSlot(lastRequestNanos, cfg.mdUploadIntervalMs)
                            val acked = api.uploadPages(token, sessionId, chunk.map { it.second })
                            val upMs = ((clock.nowNanos() - upStartNanos) / 1_000_000L).coerceAtLeast(1L)
                            val chunkKbps = (chunkBytes / 1024.0) / (upMs / 1000.0)
                            context.logger.info(
                                "Uploaded MangaDex pages $firstNum..$lastNum/$dirtyTotal (${formatBytes(chunkBytes)} in ${upMs}ms, ~${chunkKbps.toInt()} KB/s)"
                            )
                            chunk.forEachIndexed { k, (slotIdx, _) ->
                                slotIds[slotIdx] = acked[k]
                            }
                            dirtyCursor += chunk.size
                            tracker.advance(chunk.size, chunkBytes)
                        }
                    }
                    val upStats = uploadThrottle.stats()
                    val dlStats = downloadThrottle.stats()
                    context.logger.info(
                        "MangaDex transfer summary: upload=${formatBytes(upStats.bytes)} (~${upStats.effectiveKbps.toInt()} KB/s, limit=${limitLabel(upStats.limitKbps)}, wait=${upStats.waitedMs}ms)" +
                            if (dlStats.bytes > 0L) ", download=${formatBytes(dlStats.bytes)} (~${dlStats.effectiveKbps.toInt()} KB/s, limit=${limitLabel(dlStats.limitKbps)}, wait=${dlStats.waitedMs}ms)" else ""
                    )
                    context.progress.clearSecondary()
                    context.progress.report(ProgressData.indeterminate("Committing MangaDex chapter ${chapter.number}..."))
                    lastRequestNanos = waitForUploadSlot(lastRequestNanos, cfg.mdUploadIntervalMs)
                    val finalPageOrder = slotIds.mapIndexed { idx, id ->
                        id ?: error("Missing MangaDex file id for page ${idx + 1}")
                    }
                    val committed = api.commit(token, sessionId, draft, finalPageOrder)
                    val now = clock.nowEpochSec()
                    val resolvedHashes = currentHashes.filterNotNull().takeIf { it.size == effectivePages.size }
                    recordMangaDexPublish(chapterId, chapter, committed, cfg.mdLanguage, now, ChangeDetector.pageListSignature(chapter.pages), resolvedHashes)
                    context.showNotification("Published to MangaDex", "${series.title} ch. ${chapter.number}", PluginNotificationType.SUCCESS)
                    success("Published ch. ${chapter.number} to MangaDex") { put("mangadexChapterId", committed.id) }
                } catch (e: Exception) {
                    if (e !is CancellationException) api.abort(token, sessionId)
                    throw e
                }
            }
        }

    private suspend fun matchMangaDexEditSlots(
        api: MangaDexApi,
        chapter: Chapter,
        editChapterId: String,
        effectivePages: List<PageRef>,
        currentHashes: List<String?>,
        prefilled: List<MdSessionFileInfo>
    ): List<String?> {
        if (prefilled.isEmpty()) return List(effectivePages.size) { null }
        val prevSnap = chapter.mdSnapshot
        val prevSigs = prevSnap?.pageSignature.orEmpty()
        val prevHashes = prevSnap?.pageHashes.orEmpty()
        val prevMdUrls = if (prevSigs.isEmpty() && effectivePages.any { !it.remoteUrl.isNullOrBlank() }) {
            runCatching { api.pageUrls(editChapterId) }.getOrDefault(emptyList())
        } else emptyList()
        val prevCount = maxOf(prevSigs.size, prevHashes.size, prevMdUrls.size)
        if (prevCount > 0 && prefilled.size == prevCount) {
            val matchedPrev = IncrementalPagePlanner.matchExistingPages(
                currentPages = effectivePages,
                currentHashes = currentHashes,
                previousSignatures = prevSigs,
                previousHashes = prevHashes,
                previousUrls = prevMdUrls,
                isPreviousEligible = { it in prefilled.indices },
                requireSameExtension = false
            )
            return matchedPrev.map { prevIdx -> prevIdx?.let { prefilled[it].id } }
        }
        if (prefilled.any { !it.fileHash.isNullOrBlank() }) {
            val used = BooleanArray(prefilled.size)
            return currentHashes.map { hash ->
                if (hash.isNullOrBlank()) null
                else {
                    val idx = prefilled.indices.firstOrNull { !used[it] && prefilled[it].fileHash == hash }
                    if (idx != null) {
                        used[idx] = true
                        prefilled[idx].id
                    } else null
                }
            }
        }
        return List(effectivePages.size) { null }
    }

    private suspend fun recordMangaDexPublish(
        chapterId: String,
        fallback: Chapter,
        committed: MdChapter,
        language: String,
        now: Long,
        pageSignature: List<String>?,
        pageHashes: List<String>? = fallback.mdSnapshot?.pageHashes
    ) {
        val committedTs = committed.updatedAtEpochSec ?: committed.publishAtEpochSec ?: now
        store.mutate { lib ->
            val latest = lib.chapterById(chapterId) ?: fallback
            val cubariSnapshot = latest.effectiveCubariTimestampSec
            val mdSnap = MangaDexChapterSnapshot(
                chapterId = committed.id,
                version = committed.version,
                number = (committed.chapter ?: latest.number).trim(),
                volume = (committed.volume ?: latest.volume).trim(),
                title = (committed.title ?: latest.title).trim(),
                language = language.trim(),
                pageSignature = pageSignature,
                pageCount = committed.pageCount.takeIf { it > 0 } ?: latest.mdSnapshot?.pageCount,
                pageHashes = pageHashes
            )
            LibraryOps.upsertChapter(
                lib,
                latest.copy(
                    mangadexChapterId = committed.id,
                    mangadexVersion = committed.version,
                    mangadexPublishedAtEpochSec = latest.mangadexPublishedAtEpochSec ?: committed.publishAtEpochSec ?: now,
                    mangadexUpdatedAtEpochSec = committedTs,
                    lastSyncedCubariTs = cubariSnapshot ?: latest.lastSyncedCubariTs,
                    lastSyncedMdTs = if (cubariSnapshot != null) committedTs else latest.lastSyncedMdTs,
                    mdSnapshot = mdSnap
                )
            )
        }
        ImageCacheManager.prune(
            fs = context.fileSystem,
            store = store,
            maxCacheMb = config.maxCacheMb,
            logger = context.logger,
            protectedChapterIds = setOf(chapterId)
        )
    }

    private suspend fun waitForUploadSlot(lastNanos: Long, intervalMs: Int): Long {
        if (lastNanos != 0L && intervalMs > 0) {
            val elapsedMs = (clock.nowNanos() - lastNanos) / 1_000_000L
            val waitMs = intervalMs - elapsedMs
            if (waitMs > 0) {
                context.progress.reportSecondary(ProgressData.indeterminate("Upload rate limit: waiting ${waitMs}ms"))
                delay(waitMs)
                context.progress.clearSecondary()
            }
        }
        return clock.nowNanos()
    }

    // ───────────────────────── syncMangaDexToCubari ─────────────────────────

    suspend fun syncMangaDexToCubari(
        seriesId: String,
        chapterNumbers: String,
        scanOnly: Boolean,
        backfillAll: Boolean = false,
        resume: JsonElement? = null
    ): ExecutionResult = guarded("syncMangaDexToCubari") {
        val cfg = config
        val requested = chapterNumbers.split(',').map { normalizeChapterKey(it) }.filter { it.isNotEmpty() }.toSet()
        if (!scanOnly && !backfillAll && requested.isEmpty()) {
            error("No target chapter(s) specified for MangaDex -> Cubari sync")
        }
        val library = store.load()
        val series = library.seriesById(seriesId) ?: error("Series $seriesId not found")
        val mangaId = series.mangadexId?.let { MangaUploaderConfig.extractUuid(it) }
            ?: error("Series '${series.title}' has no MangaDex manga id")
        val api = services.mangadex(context, cfg)
        val repo = CubariRepo.create(context, cfg, services.git(context, cfg))
        context.progress.report(ProgressData.indeterminate("Reading MangaDex feed and Cubari repository"))
        repo.prepare()

        val mdChapters = fetchFeedChapters(api, mangaId, cfg, allowGroupFallback = false).filter { it.key.isNotBlank() }
        var json = repo.readSeries(series.cubariSlug)
        val existingCubariKeys = CubariJson.chapterKeys(json)
        val missingCount = mdChapters.count { it.key !in existingCubariKeys }
        val targets = when {
            scanOnly -> emptyList()
            requested.isNotEmpty() -> mdChapters.filter { it.key in requested }
            else -> mdChapters.filter { it.key !in existingCubariKeys }
        }
        val syncNow = clock.nowEpochSec()
        val mergedLib = store.mutate { lib ->
            val withCubari = json?.let {
                LibraryOps.mergeCubariSeries(lib, series.cubariSlug, it, cfg.cubariGroupName, syncNow, cfg.arubaPublicBaseUrl)
            } ?: lib
            LibraryOps.mergeMangaDexChapters(withCubari, seriesId, mdChapters, syncNow, cfg.mdLanguage)
        }
        val effectiveSeries = mergedLib.seriesById(seriesId) ?: series
        context.logger.info("MangaDex has ${mdChapters.size} chapter(s); $missingCount missing on Cubari; targeting ${targets.size}")

        if (scanOnly) {
            val summary = "Found ${mdChapters.size} MangaDex chapter(s) · $missingCount missing on Cubari"
            context.showToast(summary)
            return@guarded success(summary) {
                put("found", mdChapters.size)
                put("missing", missingCount)
            }
        }
        if (requested.isNotEmpty() && targets.isEmpty()) {
            error("Requested chapter(s) ${requested.joinToString()} not found on MangaDex for '${series.title}'")
        }
        if (targets.isEmpty()) {
            val summary = "Found ${mdChapters.size} MangaDex chapter(s) · 0 missing on Cubari"
            return@guarded success(summary) {
                put("found", mdChapters.size)
                put("missing", 0)
                put("chapters", 0)
            }
        }

        val arubaCfg = ArubaConfig.from(cfg)
        val padDigits = ArubaPaths.resolvePadDigits(arubaCfg, effectiveSeries, mergedLib.chaptersOf(seriesId))
        val done = (decode(resume, BackfillResumeState.serializer())?.doneChapterKeys ?: emptyList()).toMutableList()
        val downloadThrottle = BandwidthThrottle({ cfg.maxDownloadKbps }, clock)
        services.aruba(context, cfg).use { storage ->
            for ((index, md) in targets.withIndex()) {
                if (md.key in done) continue
                if (gate.pauseCheckpoint()) {
                    context.logger.info("Backfill paused after ${done.size}/${targets.size} chapters")
                    repo.commitAndPush("Backfill ${effectiveSeries.title} from MangaDex (partial)")
                    return@guarded ExecutionResult.Paused(encode(BackfillResumeState(done), BackfillResumeState.serializer()))
                }
                val sources = api.pageUrls(md.id)
                val dir = ArubaPaths.chapterDir(arubaCfg, effectiveSeries.effectiveArubaFolder, md.key)
                val urls = mutableListOf<String>()
                var chapterBytes = 0L
                val chStartNanos = clock.nowNanos()
                for (chunk in sources.withIndex().chunked(cfg.concurrentDownloads)) {
                    val bytesList = coroutineScope {
                        chunk.map { (_, url) ->
                            async { api.download(url).also { downloadThrottle.acquire(it.size) } }
                        }.awaitAll()
                    }
                    coroutineScope {
                        chunk.mapIndexed { i, (pageIndex, url) ->
                            async {
                                val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
                                val name = ArubaPaths.pageName(pageIndex, ext, padDigits)
                                storage.upload("$dir/$name", bytesList[i])
                            }
                        }.awaitAll()
                    }
                    chapterBytes += bytesList.sumOf { it.size.toLong() }
                    chunk.forEach { (pageIndex, url) ->
                        val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
                        urls += ArubaPaths.publicUrl(arubaCfg, "$dir/${ArubaPaths.pageName(pageIndex, ext, padDigits)}")
                    }
                    val totalPages = sources.size.coerceAtLeast(1)
                    val speedSuffix = transferStatsSuffix(urls.size, totalPages, chapterBytes, clock.nowNanos() - chStartNanos)
                    context.progress.report(
                        ProgressData.ratio(
                            index + urls.size.toDouble() / totalPages, targets.size.toDouble(),
                            "chapters", "Ch. ${md.key}: ${urls.size}/${sources.size} pages ($speedSuffix)"
                        )
                    )
                    context.progress.reportSecondary(
                        ProgressData.ratio(urls.size.toDouble(), totalPages.toDouble(), "pages", "Ch. ${md.key}: $speedSuffix")
                    )
                }
                removeStalePages(storage, dir, urls)
                val now = clock.nowEpochSec()
                val mdTs = md.updatedAtEpochSec ?: md.publishAtEpochSec ?: now
                val mdNumber = md.chapter.orEmpty().trim().ifBlank { md.key }
                val mdVolume = md.volume.orEmpty().trim()
                val mdTitle = md.title.orEmpty().trim()
                val baseJson = CubariJson.applySeriesMeta(json, effectiveSeries, now)
                val existingLocal = store.load().chapters.firstOrNull {
                    it.seriesId == seriesId && (it.mangadexChapterId == md.id || it.cubariKey == md.key)
                }
                val renamedJson = if (existingLocal != null && existingLocal.cubariKey != md.key && existingLocal.isOnCubari) {
                    CubariJson.removeChapter(baseJson, existingLocal.cubariKey, cfg.cubariGroupName, now)
                } else baseJson
                val nextJson = CubariJson.upsertChapter(
                    renamedJson, md.key, mdTitle, mdVolume, cfg.cubariGroupName, urls, mdTs
                )
                json = nextJson
                repo.writeSeries(series.cubariSlug, nextJson)
                store.mutate { lib ->
                    val local = lib.chapters.firstOrNull { it.seriesId == seriesId && (it.mangadexChapterId == md.id || it.cubariKey == md.key) }
                    val syncedPages = emptyList<PageRef>()
                    val pageSig = ChangeDetector.pageListSignature(syncedPages)
                    val cubariSnap = CubariChapterSnapshot(
                        key = md.key,
                        volume = mdVolume,
                        title = mdTitle,
                        pageUrls = urls,
                        pageSignature = pageSig
                    )
                    val mdSnap = MangaDexChapterSnapshot(
                        chapterId = md.id,
                        version = md.version,
                        number = mdNumber,
                        volume = mdVolume,
                        title = mdTitle,
                        language = cfg.mdLanguage.trim().ifEmpty { local?.mdSnapshot?.language ?: "en" },
                        pageSignature = pageSig,
                        pageCount = sources.size.takeIf { it > 0 } ?: md.pageCount
                    )
                    val updatedChapter = (local ?: Chapter(id = newId("ch"), seriesId = seriesId, createdAtEpochSec = now)).copy(
                        number = mdNumber,
                        volume = mdVolume,
                        title = mdTitle,
                        pages = syncedPages,
                        mangadexChapterId = md.id,
                        mangadexVersion = md.version,
                        cubariPublishedAtEpochSec = local?.cubariPublishedAtEpochSec ?: mdTs,
                        cubariUpdatedAtEpochSec = mdTs,
                        mangadexPublishedAtEpochSec = local?.mangadexPublishedAtEpochSec ?: md.publishAtEpochSec ?: mdTs,
                        mangadexUpdatedAtEpochSec = md.updatedAtEpochSec ?: md.publishAtEpochSec ?: mdTs,
                        lastSyncedCubariTs = mdTs,
                        lastSyncedMdTs = md.updatedAtEpochSec ?: md.publishAtEpochSec ?: local?.effectiveMangaDexTimestampSec ?: mdTs,
                        cubariPageUrls = urls,
                        cubariSnapshot = cubariSnap,
                        mdSnapshot = mdSnap
                    )
                    LibraryOps.touchSeries(LibraryOps.upsertChapter(lib, updatedChapter), seriesId, now)
                }
                done += md.key
            }
            context.progress.clearSecondary()
            context.progress.report(ProgressData.indeterminate("Pushing synced Cubari chapters to Git..."))
            repo.commitAndPush("Sync ${series.title} from MangaDex (${done.size} chapter(s))")
        }
        context.showNotification("MangaDex -> Cubari complete", "${series.title}: ${done.size} chapter(s) synced", PluginNotificationType.SUCCESS)
        success("Synced ${done.size} chapter(s) from MangaDex") { put("chapters", done.size) }
    }

    // ───────────────────────── uploadOrRemoveCubariCover ─────────────────────────

    suspend fun uploadOrRemoveCubariCover(seriesId: String, action: String, coverRelativePath: String): ExecutionResult =
        guarded("uploadOrRemoveCubariCover") {
            val cfg = config
            val series = store.load().seriesById(seriesId) ?: error("Series $seriesId not found")
            val repo = CubariRepo.create(context, cfg, services.git(context, cfg))
            repo.prepare()
            val arubaCfg = ArubaConfig.from(cfg)
            val now = clock.nowEpochSec()

            services.aruba(context, cfg).use { storage ->
                if (action == OP_REMOVE) {
                    series.coverUrl?.let { url ->
                        val remote = ArubaPaths.remotePathFromUrl(arubaCfg, url)
                            ?: ArubaPaths.coverPath(arubaCfg, series.effectiveArubaFolder, PageImporter.extension(url))
                        runCatching { storage.delete(remote) }.onFailure { context.logger.warn("Could not delete remote cover: ${it.message}") }
                    }
                    val json = repo.readSeries(series.cubariSlug)
                    if (json != null) repo.writeSeries(series.cubariSlug, CubariJson.setCover(json, null, now))
                    repo.commitAndPush("Remove cover of ${series.title}")
                    store.mutate { lib ->
                        val nextSeries = (lib.seriesById(seriesId) ?: series).copy(coverUrl = null, coverPath = null, lastUpdatedEpochSec = now)
                        LibraryOps.upsertSeries(lib, nextSeries.copy(cubariSnapshot = ChangeDetector.cubariSeriesSnapshot(nextSeries)))
                    }
                    return@guarded success("Cover removed") { put("cover", false) }
                }
                val bytes = context.fileSystem.readFile(relPath(coverRelativePath)) ?: error("Cover file missing: $coverRelativePath")
                val coverKb = (bytes.size / 1024.0).coerceAtLeast(1.0)
                context.progress.report(ProgressData.ratio(0.0, coverKb, "KB", "Uploading cover (${formatBytes(bytes.size.toLong())}) to Aruba..."))
                val remote = ArubaPaths.coverPath(arubaCfg, series.effectiveArubaFolder, PageImporter.extension(coverRelativePath))
                storage.upload(remote, bytes)
                context.progress.report(ProgressData.ratio(coverKb, coverKb, "KB", "Updating Cubari JSON cover & Git..."))
                val url = ArubaPaths.publicUrl(arubaCfg, remote)
                val baseSeries = (store.load().seriesById(seriesId) ?: series).copy(coverUrl = url, coverPath = coverRelativePath, lastUpdatedEpochSec = now)
                val updatedSeries = baseSeries.copy(cubariSnapshot = ChangeDetector.cubariSeriesSnapshot(baseSeries))
                val base = repo.readSeries(series.cubariSlug) ?: CubariJson.applySeriesMeta(null, updatedSeries, now)
                repo.writeSeries(series.cubariSlug, CubariJson.setCover(base, url, now))
                repo.commitAndPush("Update cover of ${series.title}")
                store.mutate { LibraryOps.upsertSeries(it, updatedSeries) }
                success("Cover uploaded") { put("cover", url) }
            }
        }

    // ───────────────────────── fetchChapterPages ─────────────────────────

    suspend fun fetchChapterPages(
        seriesId: String,
        chapterId: String,
        source: String,
        mode: String,
        replace: Boolean,
        pageIndex: Int = -1,
        resume: JsonElement?
    ): ExecutionResult = guarded("fetchChapterPages") {
        val cfg = config
        val library = store.load()
        val series = library.seriesById(seriesId) ?: error("Series $seriesId not found")
        var chapter = library.chapterById(chapterId) ?: error("Chapter $chapterId not found")
        val src = if (source.equals(SOURCE_MANGADEX, ignoreCase = true)) SOURCE_MANGADEX else SOURCE_CUBARI
        val isLoad = mode.equals(MODE_LOAD, ignoreCase = true)
        val label = if (src == SOURCE_MANGADEX) "MangaDex" else "Cubari / Aruba"
        val api = services.mangadex(context, cfg)
        val remoteStore = RemotePagesStore(context.storage)

        context.progress.report(ProgressData.indeterminate("Resolving $label images for Ch. ${chapter.number}"))
        val urls = resolveRemotePageUrls(cfg, series, chapter, src, api)
        chapter = store.load().chapterById(chapterId) ?: chapter

        if (urls.isEmpty()) {
            val msg = "No uploaded images found on $label for Ch. ${chapter.number}"
            if (isLoad) error(msg)
            remoteStore.save(RemoteChapterPages(chapter.id, src, clock.nowEpochSec(), emptyList(), errorMessage = msg))
            context.showToast(msg)
            return@guarded success(msg) { put("total", 0); put("reachable", 0) }
        }

        if (!isLoad) {
            val checked = mutableListOf<RemotePageStatus>()
            for (chunk in urls.withIndex().chunked(cfg.concurrentDownloads)) {
                if (gate.pauseCheckpoint()) {
                    return@guarded ExecutionResult.Paused(buildJsonObject {})
                }
                val batchStatuses = coroutineScope {
                    chunk.map { (idx, url) ->
                        async {
                            val code = api.checkUrl(url)
                            RemotePageStatus(idx, url, remoteFileName(idx, url), code)
                        }
                    }.awaitAll()
                }
                checked += batchStatuses
                context.progress.report(
                    ProgressData.ratio(checked.size.toDouble(), urls.size.toDouble(), "images", "Checked ${checked.size}/${urls.size} on $label")
                )
            }
            val report = RemoteChapterPages(chapter.id, src, clock.nowEpochSec(), checked)
            remoteStore.save(report)
            val summary = "$label: ${checked.size} image(s) · ${report.reachableCount}/${checked.size} reachable"
            context.showToast(summary)
            return@guarded success(summary) {
                put("total", checked.size)
                put("reachable", report.reachableCount)
            }
        }

        if (isLoad && pageIndex >= 0) {
            require(pageIndex in urls.indices) { "Page index #${pageIndex + 1} out of range (1..${urls.size})" }
            val url = urls[pageIndex]
            context.progress.report(ProgressData.indeterminate("Downloading page #${pageIndex + 1} from $label..."))
            val throttle = BandwidthThrottle({ cfg.maxDownloadKbps }, clock)
            val bytes = api.download(url).also { throttle.acquire(it.size) }
            val downloadedPage = PageImporter.storeChapterPage(
                context.fileSystem,
                chapter.id,
                remoteFileName(pageIndex, url),
                bytes
            )
            store.mutate { lib ->
                val latest = lib.chapterById(chapterId) ?: chapter
                val base = latest.pages.ifEmpty { PageImporter.remotePageRefs(urls) }.toMutableList()
                if (pageIndex < base.size) base[pageIndex] = downloadedPage else base += downloadedPage
                LibraryOps.upsertChapter(
                    lib,
                    latest.copy(
                        pages = base,
                        cubariPageUrls = if (src == SOURCE_CUBARI) urls else latest.cubariPageUrls
                    )
                )
            }
            ImageCacheManager.prune(
                fs = context.fileSystem,
                store = store,
                maxCacheMb = cfg.maxCacheMb,
                logger = context.logger,
                protectedChapterIds = setOf(chapterId)
            )
            context.progress.report(ProgressData.ratio(1.0, 1.0, "pages", "Downloaded page #${pageIndex + 1} (${formatBytes(bytes.size.toLong())})"))
            context.showToast("Downloaded page #${pageIndex + 1} to local draft")
            return@guarded success("Downloaded page #${pageIndex + 1} from $label") { put("pages", 1) }
        }

        val state = decode(resume, LoadPagesResumeState.serializer()) ?: LoadPagesResumeState()
        val downloaded = state.pages.toMutableList()
        var next = state.nextIndex
        val throttle = BandwidthThrottle({ config.maxDownloadKbps }, clock)
        val dlTracker = TransferProgressTracker(
            context,
            clock,
            urls.size,
            "Downloading $label:",
            limitKbpsProvider = { config.maxDownloadKbps }
        )
        dlTracker.reportInitial()

        while (next < urls.size) {
            if (gate.pauseCheckpoint()) {
                return@guarded ExecutionResult.Paused(encode(LoadPagesResumeState(next, downloaded), LoadPagesResumeState.serializer()))
            }
            val chunk = (next until minOf(urls.size, next + cfg.concurrentDownloads)).toList()
            val bytesList = coroutineScope {
                chunk.map { idx ->
                    async { api.download(urls[idx]).also { throttle.acquire(it.size) } }
                }.awaitAll()
            }
            chunk.forEachIndexed { offset, idx ->
                downloaded += PageImporter.storeChapterPage(
                    context.fileSystem,
                    chapter.id,
                    remoteFileName(idx, urls[idx]),
                    bytesList[offset],
                    remoteUrl = urls[idx]
                )
            }
            next += chunk.size
            dlTracker.advance(chunk.size, bytesList.sumOf { it.size.toLong() })
        }
        context.progress.clearSecondary()

        val okReport = RemoteChapterPages(
            chapter.id,
            src,
            clock.nowEpochSec(),
            urls.mapIndexed { idx, u -> RemotePageStatus(idx, u, remoteFileName(idx, u), 200) }
        )
        remoteStore.save(okReport)

        store.mutate { lib ->
            val latest = lib.chapterById(chapterId) ?: chapter
            LibraryOps.upsertChapter(
                lib,
                latest.copy(
                    pages = if (replace) downloaded else latest.pages + downloaded,
                    cubariPageUrls = if (src == SOURCE_CUBARI) urls else latest.cubariPageUrls
                )
            )
        }
        ImageCacheManager.prune(
            fs = context.fileSystem,
            store = store,
            maxCacheMb = cfg.maxCacheMb,
            logger = context.logger,
            protectedChapterIds = setOf(chapterId)
        )
        context.showNotification("Loaded images into draft", "${series.title} ch. ${chapter.number}: ${downloaded.size} pages ($label)", PluginNotificationType.SUCCESS)
        success("Loaded ${downloaded.size} page(s) from $label") { put("pages", downloaded.size) }
    }

    private suspend fun resolveRemotePageUrls(
        cfg: MangaUploaderConfig,
        series: Series,
        chapter: Chapter,
        src: String,
        api: MangaDexApi
    ): List<String> {
        if (src == SOURCE_MANGADEX) {
            var mdId = chapter.mangadexChapterId?.takeIf { it.isNotBlank() }
            if (mdId == null) {
                val mangaUuid = series.mangadexId?.let { MangaUploaderConfig.extractUuid(it) }
                if (mangaUuid != null) {
                    val found = fetchFeedChapters(api, mangaUuid, cfg, allowGroupFallback = true)
                        .firstOrNull { it.key == chapter.cubariKey }
                    if (found != null) {
                        mdId = found.id
                        store.mutate { lib ->
                            val latest = lib.chapterById(chapter.id) ?: return@mutate lib
                            LibraryOps.upsertChapter(
                                lib,
                                latest.copy(
                                    mangadexChapterId = found.id,
                                    mangadexVersion = found.version,
                                    mangadexPublishedAtEpochSec = found.publishAtEpochSec ?: latest.mangadexPublishedAtEpochSec,
                                    mangadexUpdatedAtEpochSec = found.updatedAtEpochSec ?: found.publishAtEpochSec ?: latest.mangadexUpdatedAtEpochSec
                                )
                            )
                        }
                    }
                }
            }
            return mdId?.let { api.pageUrls(it) } ?: emptyList()
        }

        if (LockEvaluator.evaluate(cfg).cubari.readiness == Readiness.READY) {
            val repo = CubariRepo.create(context, cfg, services.git(context, cfg))
            val json = runCatching {
                repo.prepare()
                repo.readSeries(series.cubariSlug)
            }.getOrNull()
            val repoUrls = CubariJson.pageUrls(json, chapter.cubariKey, cfg.cubariGroupName)
            if (repoUrls.isNotEmpty()) {
                val now = clock.nowEpochSec()
                store.mutate { lib ->
                    val latest = lib.chapterById(chapter.id) ?: return@mutate lib
                    LibraryOps.upsertChapter(
                        lib,
                        latest.copy(
                            cubariPageUrls = repoUrls,
                            cubariPublishedAtEpochSec = latest.cubariPublishedAtEpochSec ?: now
                        )
                    )
                }
                return repoUrls
            }
        }
        return chapter.cubariPageUrls
    }

    private fun remoteFileName(index: Int, url: String): String {
        val raw = ArubaPaths.decodeUrlSegment(url.substringBefore('?').substringAfterLast('/'))
        val ext = PageImporter.extension(raw).takeIf { it in PageImporter.IMAGE_EXTENSIONS } ?: "png"
        return if (raw.isNotBlank() && raw.length <= 36 && '.' in raw) raw
        else "${(index + 1).toString().padStart(2, '0')}.$ext"
    }

    // ───────────────────────── helpers ─────────────────────────

    /**
     * Queries a MangaDex title feed using the configured group/language and falls back to all languages
     * (and optionally all groups for single-chapter remote inspection) when zero chapters match.
     */
    private suspend fun fetchFeedChapters(
        api: MangaDexApi,
        mangaId: String,
        cfg: MangaUploaderConfig,
        allowGroupFallback: Boolean
    ): List<MdChapter> {
        val preferredLang = cfg.mdLanguage.trim()
        val initial = api.listChapters(mangaId, cfg.mdGroupId, preferredLang)
        context.logger.info("MangaDex feed ($mangaId, group=${cfg.mdGroupId ?: "any"}, lang=${preferredLang.ifEmpty { "all" }}): ${initial.size} chapter(s)")
        if (initial.isNotEmpty()) return initial
        if (preferredLang.isNotEmpty()) {
            val allLangs = api.listChapters(mangaId, cfg.mdGroupId, "")
            context.logger.info("MangaDex feed language fallback ($mangaId, group=${cfg.mdGroupId ?: "any"}, lang=all): ${allLangs.size} chapter(s)")
            if (allLangs.isNotEmpty()) return allLangs
        }
        if (allowGroupFallback && cfg.mdGroupId != null) {
            val anyGroup = api.listChapters(mangaId, null, "")
            context.logger.info("MangaDex feed group fallback ($mangaId, group=any, lang=all): ${anyGroup.size} chapter(s)")
            return anyGroup
        }
        return emptyList()
    }

    private suspend fun ensureMangaDexToken(api: MangaDexApi, cfg: MangaUploaderConfig): String =
        ensureValidMangaDexToken(context, api, cfg)

    private suspend fun guarded(name: String, block: suspend () -> ExecutionResult): ExecutionResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        context.logger.error("$name failed: ${e.message}", e)
        context.showNotification("$name failed", e.message ?: e::class.simpleName.orEmpty(), PluginNotificationType.ERROR)
        ExecutionResult.Error(e)
    }

    private fun success(message: String, extra: JsonObjectBuilder.() -> Unit = {}): ExecutionResult {
        context.logger.info(message)
        val result = buildJsonObject { put("message", message); extra() }
        return ExecutionResult.Success(PluginResponse(result = result))
    }

    private fun <T> decode(element: JsonElement?, serializer: KSerializer<T>): T? =
        element?.let { runCatching { Json.decodeFromJsonElement(serializer, it) }.getOrNull() }

    private fun <T> encode(value: T, serializer: KSerializer<T>): JsonElement =
        Json.encodeToJsonElement(serializer, value)

    private suspend fun loadIndex(): Map<String, CubariIndexEntry> {
        val stored = context.storage.get(INDEX_KEY) ?: return emptyMap()
        return runCatching {
            Json.decodeFromJsonElement(ListSerializer(CubariIndexEntry.serializer()), stored).associateBy { it.slug }
        }.getOrDefault(emptyMap())
    }

    private suspend fun saveIndex(index: Map<String, CubariIndexEntry>) {
        context.storage.put(
            INDEX_KEY,
            Json.encodeToJsonElement(ListSerializer(CubariIndexEntry.serializer()), index.values.toList())
        )
    }

    private suspend fun resolvePageHashes(pages: List<PageRef>): List<String?> =
        pages.map { page ->
            when {
                !page.contentHash.isNullOrBlank() -> page.contentHash
                page.isLocal -> context.fileSystem.readFile(relPath(page.storedPath))?.let(::sha256Hex)
                else -> null
            }
        }

    private suspend fun measureKnownLocalBytes(pages: List<PageRef>, indices: Iterable<Int> = pages.indices): Long? {
        if (!indices.iterator().hasNext()) return null
        if (indices.any { !pages[it].isLocal }) return null
        var total = 0L
        for (idx in indices) {
            val bytes = context.fileSystem.readFile(relPath(pages[idx].storedPath)) ?: return null
            total += bytes.size.toLong()
        }
        return total
    }

    private fun contentType(ext: String) = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    companion object {
        const val OP_PUBLISH = "publish"
        const val OP_REMOVE = "remove"
        const val OP_SERIES_META = "series_meta"
        const val SOURCE_CUBARI = "cubari"
        const val SOURCE_MANGADEX = "mangadex"
        const val MODE_CHECK = "check"
        const val MODE_LOAD = "load"
        const val INDEX_KEY = "cubari_index"
        const val STALE_UPLOAD_SESSION_SEC = 2L * 3600L
        private const val MD_MAX_FILES_PER_REQUEST = 10
        private const val MD_REMOTE_BATCH_SIZE = 3
        private const val MD_MAX_BATCH_BYTES = 3L * 1024L * 1024L
        private val STALE_PAGE = Regex("^\\d+\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)
        private val mdUploadMutex = Mutex()
    }
}

private class TransferProgressTracker(
    private val context: PluginContext,
    private val clock: Clock,
    private val totalItems: Int,
    private val actionLabel: String,
    private val knownTotalBytes: Long? = null,
    private val limitKbpsProvider: () -> Int = { 0 }
) {
    private val startNanos: Long = clock.nowNanos()
    private var bytesDone: Long = 0L
    private var itemsDone: Int = 0

    fun reportInitial() {
        val total = totalItems.coerceAtLeast(1)
        val limit = limitKbpsProvider()
        val limitNote = if (limit > 0) " · cap $limit KB/s" else ""
        val msg = if (knownTotalBytes != null && knownTotalBytes > 0L) {
            "$actionLabel 0/$totalItems (${formatBytes(knownTotalBytes)} total$limitNote)"
        } else {
            "$actionLabel 0/$totalItems$limitNote"
        }
        if (knownTotalBytes != null && knownTotalBytes > 0L) {
            context.progress.report(ProgressData.ratio(0.0, (knownTotalBytes / 1024.0).coerceAtLeast(1.0), "KB", msg))
        } else {
            context.progress.report(ProgressData.ratio(0.0, total.toDouble(), "pages", msg))
        }
        context.progress.reportSecondary(ProgressData.ratio(0.0, total.toDouble(), "pages", "0/$totalItems pages"))
    }

    fun advance(itemsDelta: Int, bytesDelta: Long) {
        val total = totalItems.coerceAtLeast(1)
        itemsDone = (itemsDone + itemsDelta).coerceAtMost(total)
        bytesDone += bytesDelta.coerceAtLeast(0L)
        val suffix = transferStatsSuffix(
            itemsDone = itemsDone,
            totalItems = total,
            bytesDone = bytesDone,
            elapsedNanos = clock.nowNanos() - startNanos,
            knownTotalBytes = knownTotalBytes,
            limitKbps = limitKbpsProvider()
        )
        val summary = if (suffix.isNotEmpty()) "$actionLabel $itemsDone/$total ($suffix)" else "$actionLabel $itemsDone/$total"

        if (knownTotalBytes != null && knownTotalBytes > 0L) {
            val totalKb = (knownTotalBytes / 1024.0).coerceAtLeast(1.0)
            val doneKb = (bytesDone / 1024.0).coerceIn(0.0, totalKb)
            context.progress.report(ProgressData.ratio(doneKb, totalKb, "KB", summary))
            context.progress.reportSecondary(ProgressData.ratio(itemsDone.toDouble(), total.toDouble(), "pages", "Pages $itemsDone/$total"))
        } else {
            context.progress.report(ProgressData.ratio(itemsDone.toDouble(), total.toDouble(), "pages", summary))
            if (bytesDone > 0L) {
                val estTotalBytes = ((bytesDone.toDouble() / itemsDone.coerceAtLeast(1)) * total).toLong()
                val estTotalKb = (estTotalBytes / 1024.0).coerceAtLeast(1.0)
                val doneKb = (bytesDone / 1024.0).coerceIn(0.0, estTotalKb)
                context.progress.reportSecondary(ProgressData.ratio(doneKb, estTotalKb, "KB", "~${formatBytes(estTotalBytes)} est."))
            }
        }
    }
}

private fun transferStatsSuffix(
    itemsDone: Int,
    totalItems: Int,
    bytesDone: Long,
    elapsedNanos: Long,
    knownTotalBytes: Long? = null,
    limitKbps: Int = 0
): String {
    val elapsedSec = elapsedNanos.coerceAtLeast(0L) / 1_000_000_000.0
    val speedBps = if (elapsedSec >= 0.05 && bytesDone > 0L) bytesDone / elapsedSec else null
    val remBytes = when {
        knownTotalBytes != null && knownTotalBytes >= bytesDone -> knownTotalBytes - bytesDone
        itemsDone > 0 && itemsDone < totalItems && bytesDone > 0L ->
            ((bytesDone.toDouble() / itemsDone) * (totalItems - itemsDone)).toLong()
        else -> null
    }
    val etaSec = when {
        itemsDone >= totalItems -> 0L
        speedBps != null && speedBps > 0.0 && remBytes != null -> (remBytes / speedBps).toLong().coerceAtLeast(0L)
        elapsedSec >= 0.05 && itemsDone > 0 -> ((elapsedSec / itemsDone) * (totalItems - itemsDone)).toLong().coerceAtLeast(0L)
        else -> null
    }
    return buildList {
        if (knownTotalBytes != null && knownTotalBytes > 0L) {
            add("${formatBytes(bytesDone)} / ${formatBytes(knownTotalBytes)}")
        } else if (bytesDone > 0L) {
            add(formatBytes(bytesDone))
        }
        if (speedBps != null && speedBps >= 1.0) {
            val speedText = "${formatBytes(speedBps.toLong())}/s"
            add(if (limitKbps > 0) "$speedText (cap $limitKbps KB/s)" else speedText)
        } else if (limitKbps > 0) {
            add("cap $limitKbps KB/s")
        }
        if (etaSec != null && itemsDone < totalItems) add("~${formatEta(etaSec)} left")
    }.joinToString(" · ")
}

private fun limitLabel(kbps: Int): String = if (kbps > 0) "$kbps KB/s" else "unlimited"

private fun formatBytes(bytes: Long): String = formatByteCount(bytes)

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600L -> "${seconds / 3600L}h ${(seconds % 3600L) / 60L}m"
    seconds >= 60L -> "${seconds / 60L}m ${seconds % 60L}s"
    else -> "${seconds}s"
}
