package com.wip.mangauploader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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

@Serializable
data class PublishResumeState(val nextPage: Int = 0, val uploadedUrls: List<String> = emptyList())

@Serializable
data class MangaDexResumeState(val sessionId: String? = null, val nextPage: Int = 0, val uploadedIds: List<String> = emptyList())

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

        val pushed = if (push) repo.commitAndPush("Sync Cubari repository") else false
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
        val padDigits = ArubaPaths.resolvePadDigits(arubaCfg, series, library.chaptersOf(series.id))
        val effectivePages = chapter.pages.ifEmpty { PageImporter.remotePageRefs(chapter.cubariPageUrls) }
        require(effectivePages.isNotEmpty()) { "Chapter has no pages" }

        services.aruba(context, cfg).use { storage ->
            val state = decode(resume, PublishResumeState.serializer()) ?: PublishResumeState()
            val uploadedUrls = state.uploadedUrls.toMutableList()
            var next = state.nextPage
            val total = effectivePages.size
            val batchSize = cfg.concurrentUploads
            val remoteApi by lazy { services.mangadex(context, cfg) }

            while (next < total) {
                if (gate.pauseCheckpoint()) {
                    context.logger.info("Publish paused at page $next/$total")
                    return@guarded ExecutionResult.Paused(encode(PublishResumeState(next, uploadedUrls), PublishResumeState.serializer()))
                }
                val batch = effectivePages.subList(next, minOf(total, next + batchSize))
                val base = next
                val uploaded = coroutineScope {
                    batch.mapIndexed { offset, page ->
                        async {
                            val ext = PageImporter.extension(page.originalName)
                            val name = ArubaPaths.pageName(base + offset, ext, padDigits)
                            val targetPath = "$remoteDir/$name"
                            val targetUrl = ArubaPaths.publicUrl(arubaCfg, targetPath)
                            if (!page.isLocal && page.remoteUrl == targetUrl) {
                                targetUrl
                            } else {
                                val bytes = if (page.isLocal) {
                                    context.fileSystem.readFile(relPath(page.storedPath))
                                        ?: error("Page file missing: ${page.originalName}")
                                } else {
                                    remoteApi.download(page.remoteUrl ?: error("Remote page URL missing for ${page.originalName}"))
                                }
                                storage.upload(targetPath, bytes)
                                targetUrl
                            }
                        }
                    }.awaitAll()
                }
                uploadedUrls += uploaded
                next += batch.size
                context.progress.report(ProgressData.ratio(next.toDouble(), total.toDouble(), "pages", "Uploaded $next/$total pages"))
            }

            removeStalePages(storage, remoteDir, uploadedUrls)
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

            recordCubariPublish(series, chapter, urls, now)
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

    private suspend fun recordCubariPublish(series: Series, chapter: Chapter, urls: List<String>, now: Long) {
        store.mutate { lib ->
            val latestSeries = lib.seriesById(series.id) ?: series
            val latest = lib.chapterById(chapter.id) ?: chapter
            val snap = CubariChapterSnapshot(
                key = latest.cubariKey,
                volume = latest.volume.trim(),
                title = latest.title.trim(),
                pageUrls = urls,
                pageSignature = ChangeDetector.pageListSignature(latest.pages)
            )
            var updated = LibraryOps.upsertChapter(
                lib,
                latest.copy(
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
                val updated = api.updateChapterMetadata(token, chapter.mangadexChapterId, chapter.mangadexVersion ?: 1, draft)
                val now = clock.nowEpochSec()
                val sig = chapter.mdSnapshot?.pageSignature ?: ChangeDetector.pageListSignature(chapter.pages)
                recordMangaDexPublish(chapterId, chapter, updated, cfg.mdLanguage, now, sig)
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

            val state = decode(resume, MangaDexResumeState.serializer()) ?: MangaDexResumeState()
            val sessionId = state.sessionId ?: chapter.mangadexChapterId?.let { api.beginEdit(token, it, chapter.mangadexVersion ?: 1) }
                ?: api.beginUpload(token, mangaId, groupId)

            val ids = state.uploadedIds.toMutableList()
            var next = state.nextPage
            val total = effectivePages.size
            val throttle = BandwidthThrottle({ cfg.maxUploadKbps }, clock)
            var lastRequestNanos = 0L
            try {
                while (next < total) {
                    if (gate.pauseCheckpoint()) {
                        context.logger.info("MangaDex upload paused at page $next/$total")
                        return@guarded ExecutionResult.Paused(
                            encode(MangaDexResumeState(sessionId, next, ids), MangaDexResumeState.serializer())
                        )
                    }
                    val batch = effectivePages.subList(next, minOf(total, next + MD_MAX_FILES_PER_REQUEST))
                    val files = batch.mapIndexed { offset, page ->
                        val bytes = if (page.isLocal) {
                            context.fileSystem.readFile(relPath(page.storedPath))
                                ?: error("Page file missing: ${page.originalName}")
                        } else {
                            api.download(page.remoteUrl ?: error("Remote page URL missing for ${page.originalName}"))
                        }
                        throttle.acquire(bytes.size)
                        val ext = PageImporter.extension(page.originalName)
                        MdUploadFile(ArubaPaths.pageName(next + offset, ext), bytes, contentType(ext))
                    }
                    lastRequestNanos = waitForUploadSlot(lastRequestNanos, cfg.mdUploadIntervalMs)
                    ids += api.uploadPages(token, sessionId, files)
                    next += batch.size
                    context.progress.report(ProgressData.ratio(next.toDouble(), total.toDouble(), "pages", "Uploaded $next/$total pages to MangaDex"))
                }
                lastRequestNanos = waitForUploadSlot(lastRequestNanos, cfg.mdUploadIntervalMs)
                val committed = api.commit(token, sessionId, draft, ids)
                val now = clock.nowEpochSec()
                recordMangaDexPublish(chapterId, chapter, committed, cfg.mdLanguage, now, ChangeDetector.pageListSignature(chapter.pages))
                context.showNotification("Published to MangaDex", "${series.title} ch. ${chapter.number}", PluginNotificationType.SUCCESS)
                success("Published ch. ${chapter.number} to MangaDex") { put("mangadexChapterId", committed.id) }
            } catch (e: Exception) {
                if (e !is CancellationException) api.abort(token, sessionId)
                throw e
            }
        }

    private suspend fun recordMangaDexPublish(
        chapterId: String,
        fallback: Chapter,
        committed: MdChapter,
        language: String,
        now: Long,
        pageSignature: List<String>?
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
                pageSignature = pageSignature
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
                    chunk.forEach { (pageIndex, url) ->
                        val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
                        urls += ArubaPaths.publicUrl(arubaCfg, "$dir/${ArubaPaths.pageName(pageIndex, ext, padDigits)}")
                    }
                    context.progress.report(
                        ProgressData.ratio(
                            index + urls.size.toDouble() / sources.size.coerceAtLeast(1), targets.size.toDouble(),
                            "chapters", "Chapter ${md.key}: ${urls.size}/${sources.size} pages"
                        )
                    )
                }
                removeStalePages(storage, dir, urls)
                val now = clock.nowEpochSec()
                val mdTs = md.updatedAtEpochSec ?: md.publishAtEpochSec ?: now
                val nextJson = CubariJson.upsertChapter(
                    CubariJson.applySeriesMeta(json, series, now),
                    md.key, md.title.orEmpty(), md.volume.orEmpty(), cfg.cubariGroupName, urls, mdTs
                )
                json = nextJson
                repo.writeSeries(series.cubariSlug, nextJson)
                store.mutate { lib ->
                    val local = lib.chapters.firstOrNull { it.seriesId == seriesId && it.cubariKey == md.key }
                    if (local == null) lib else LibraryOps.touchSeries(
                        LibraryOps.upsertChapter(
                            lib,
                            local.copy(
                                cubariPublishedAtEpochSec = local.cubariPublishedAtEpochSec ?: mdTs,
                                cubariUpdatedAtEpochSec = mdTs,
                                mangadexUpdatedAtEpochSec = local.mangadexUpdatedAtEpochSec ?: mdTs,
                                lastSyncedCubariTs = mdTs,
                                lastSyncedMdTs = local.effectiveMangaDexTimestampSec ?: mdTs,
                                cubariPageUrls = urls,
                                cubariSnapshot = CubariChapterSnapshot(
                                    key = md.key,
                                    volume = md.volume.orEmpty().trim(),
                                    title = md.title.orEmpty().trim(),
                                    pageUrls = urls,
                                    pageSignature = ChangeDetector.pageListSignature(local.pages)
                                )
                            )
                        ),
                        seriesId, now
                    )
                }
                done += md.key
            }
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
                val remote = ArubaPaths.coverPath(arubaCfg, series.effectiveArubaFolder, PageImporter.extension(coverRelativePath))
                storage.upload(remote, bytes)
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
            context.showToast("Downloaded page #${pageIndex + 1} to local draft")
            return@guarded success("Downloaded page #${pageIndex + 1} from $label") { put("pages", 1) }
        }

        val state = decode(resume, LoadPagesResumeState.serializer()) ?: LoadPagesResumeState()
        val downloaded = state.pages.toMutableList()
        var next = state.nextIndex
        val throttle = BandwidthThrottle({ cfg.maxDownloadKbps }, clock)

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
                    bytesList[offset]
                )
            }
            next += chunk.size
            context.progress.report(
                ProgressData.ratio(next.toDouble(), urls.size.toDouble(), "pages", "Downloaded $next/${urls.size} from $label")
            )
        }

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
        private const val MD_MAX_FILES_PER_REQUEST = 10
        private val STALE_PAGE = Regex("^\\d+\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)
    }
}
