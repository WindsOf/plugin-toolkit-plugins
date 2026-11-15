package com.wip.mangauploader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.wip.plugintoolkit.api.sdui.CapabilityJobStatus
import org.wip.plugintoolkit.api.sdui.UiEvent
import org.wip.plugintoolkit.api.sdui.UiNodeTree
import org.wip.plugintoolkit.api.sdui.UiSessionContext

private val INT_SETTING_KEYS = setOf(
    SettingKeys.ARUBA_PORT,
    SettingKeys.CONCURRENT_UPLOADS,
    SettingKeys.CONCURRENT_DOWNLOADS,
    SettingKeys.CONCURRENT_CONNECTIONS,
    SettingKeys.MD_UPLOAD_INTERVAL,
    SettingKeys.MAX_UPLOAD_KBPS,
    SettingKeys.MAX_DOWNLOAD_KBPS,
    SettingKeys.PREVIEW_HOURS
)

/**
 * Reactive session loop for the `@InteractiveTool` workspace.
 * Combines local UI state with live `capabilityJobs`, `settingsFlow`, and `locksFlow`.
 */
internal fun runMangaUploaderSession(
    context: UiSessionContext,
    events: Flow<UiEvent>,
    services: ServiceFactory = MangaUploaderServices.factory
): Flow<UiNodeTree> = channelFlow {
    val store = LibraryStore(context.fileSystem, context.logger)
    val diagnosticsStore = DiagnosticsStore(context.storage)
    val remotePagesStore = RemotePagesStore(context.storage)
    val arubaFoldersStore = ArubaFoldersStore(context.storage)
    val importer = PageImporter(context.fileSystem, context.hostFileSystem, context.logger)
    val stateFlow = MutableStateFlow(UiState())
    val lastJobStatuses = mutableMapOf<String, CapabilityJobStatus>()
    val pendingEchoes = mutableMapOf<String, JsonElement?>()
    var lastObservedSettings = context.settingsFlow.value

    fun currentConfig(): MangaUploaderConfig = MangaUploaderConfig.from(context.settingsFlow.value)

    fun isUnlocked(lockKey: String): Boolean =
        context.locksFlow.value[lockKey]
            ?: LockEvaluator.evaluate(currentConfig()).toLockMap()[lockKey]
            ?: false

    fun openChapterDraft(chapter: Chapter?, prevRevision: Int): ChapterDraft? =
        chapter?.let {
            ChapterDraft(
                chapterId = it.id,
                volume = it.volume,
                number = it.number,
                title = it.title,
                pages = it.pages,
                dirty = false,
                revision = prevRevision + 1
            )
        }

    suspend fun persistChapterDraft(showToast: Boolean = false): Chapter? {
        val draft = stateFlow.value.chapterDraft ?: return null
        val existing = stateFlow.value.library.chapterById(draft.chapterId) ?: return null
        val saved = existing.copy(
            volume = draft.volume.trim(),
            number = draft.number.trim(),
            title = draft.title.trim(),
            pages = draft.pages
        )
        val updatedLib = store.mutate { LibraryOps.upsertChapter(it, saved) }
        stateFlow.update { st ->
            st.copy(
                library = updatedLib,
                chapterDraft = st.chapterDraft?.takeIf { it.chapterId == saved.id }?.copy(
                    volume = saved.volume,
                    number = saved.number,
                    title = saved.title,
                    pages = saved.pages,
                    dirty = false
                )
            )
        }
        if (showToast) context.showToast("Saved locally")
        return saved
    }

    suspend fun reloadFromDisk(forceDraftRefresh: Boolean = false) {
        val lib = store.load()
        val diags = diagnosticsStore.loadAll()
        val remotes = remotePagesStore.loadAll()
        val folders = arubaFoldersStore.load()
        stateFlow.update { st ->
            val validSeriesIds = lib.series.map { it.id }.toSet()
            val reqSeries = (context.initialParameters["seriesId"] as? JsonPrimitive)?.content?.takeIf { !st.loaded && it in validSeriesIds }
            val reqChapter = (context.initialParameters["chapterId"] as? JsonPrimitive)?.content?.takeIf { !st.loaded && lib.chapterById(it) != null }
            val nextSeriesId = reqSeries
                ?: reqChapter?.let { lib.chapterById(it)?.seriesId }
                ?: st.activeSeriesId?.takeIf { it in validSeriesIds }
                ?: lib.series.sortedBy { it.order }.firstOrNull()?.id
            val seriesChapters = nextSeriesId?.let { lib.chaptersOf(it) }.orEmpty()
            val nextChapterId = reqChapter
                ?: st.activeChapterId?.takeIf { id -> seriesChapters.any { it.id == id } }
                ?: seriesChapters.firstOrNull()?.id
            val nextChapter = lib.chapterById(nextChapterId)
            val pagesChanged = st.library.chapterById(nextChapterId)?.pages != nextChapter?.pages
            val keepDirty = !(forceDraftRefresh && pagesChanged) &&
                st.chapterDraft != null && st.chapterDraft.chapterId == nextChapterId && st.chapterDraft.dirty
            val nextDraft = if (keepDirty) st.chapterDraft else openChapterDraft(nextChapter, st.chapterDraft?.revision ?: 0)
            st.copy(
                loaded = true,
                library = lib,
                arubaFolders = folders,
                activeSeriesId = nextSeriesId,
                activeChapterId = nextChapterId,
                checkedChapterIds = st.checkedChapterIds.filterTo(mutableSetOf()) { lib.chapterById(it) != null },
                chapterDraft = nextDraft,
                diagnostics = diags,
                remotePages = remotes
            )
        }
    }

    fun selectedRemoteSource(chapterId: String): String {
        val st = stateFlow.value
        val ch = st.library.chapterById(chapterId)
        val series = st.library.seriesById(ch?.seriesId)
        return st.remoteSourceByChapter[chapterId] ?: UiSelectors.defaultRemoteSource(ch, series)
    }

    fun materializeUnifiedPages(): List<PageRef> {
        val st = stateFlow.value
        val draft = st.chapterDraft
        val ch = draft?.let { st.library.chapterById(it.chapterId) } ?: st.library.chapterById(st.activeChapterId)
        val src = ch?.let { selectedRemoteSource(it.id) } ?: UploaderWorkers.SOURCE_CUBARI
        val report = ch?.let { st.remotePages["${it.id}:$src"] }
        return UiSelectors.unifiedPages(draft, ch, report, src)
    }

    fun seriesForLinks(): Series? =
        stateFlow.value.seriesDraft?.series ?: stateFlow.value.library.seriesById(stateFlow.value.activeSeriesId)

    fun activeChapterAndSeriesForLinks(): Pair<Series?, Chapter?> {
        val st = stateFlow.value
        val ch = st.library.chapterById(st.activeChapterId)
        val sr = st.library.seriesById(ch?.seriesId)
        return sr to ch
    }

    suspend fun openUrlOrToast(url: String?) {
        if (url.isNullOrBlank()) {
            context.showToast("URL not available yet")
            return
        }
        runCatching { openExternalUrl(url) }
            .onFailure { context.showToast(it.message ?: "Could not open browser") }
    }

    suspend fun copyUrlOrToast(url: String?, label: String) {
        if (url.isNullOrBlank()) {
            context.showToast("URL not available yet")
            return
        }
        runCatching { services.copyToClipboard(url) }
            .onSuccess { context.showToast("Copied $label URL to clipboard") }
            .onFailure { context.showToast(it.message ?: "Could not copy URL to clipboard") }
    }

    suspend fun launchFetchRemotePages(
        chapterId: String,
        source: String,
        mode: String,
        replace: Boolean = true,
        pageIndex: Int = -1
    ) {
        val ch = stateFlow.value.library.chapterById(chapterId) ?: run {
            context.showToast("Open a chapter first")
            return
        }
        context.launchCapability(
            capabilityName = "fetchChapterPages",
            parameters = mapOf(
                "seriesId" to JsonPrimitive(ch.seriesId),
                "chapterId" to JsonPrimitive(ch.id),
                "source" to JsonPrimitive(source),
                "mode" to JsonPrimitive(mode),
                "replace" to JsonPrimitive(replace),
                "pageIndex" to JsonPrimitive(pageIndex)
            ),
            runInBackgroundQueue = true
        )
        val verb = when {
            pageIndex >= 0 -> "Downloading page #${pageIndex + 1} from"
            mode == UploaderWorkers.MODE_LOAD -> "Loading images from"
            else -> "Checking images on"
        }
        context.showToast("$verb ${UiSelectors.remoteSourceLabel(source)}...")
    }

    suspend fun persistSeriesDraft(draft: SeriesDraft, keepEditing: Boolean): Series? {
        val title = draft.series.title.trim()
        if (title.isEmpty()) {
            context.showToast("Series title is required")
            return null
        }
        val hours = draft.hoursText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()?.coerceAtLeast(0)
        val slug = sanitizeFolderName(draft.series.cubariSlug.ifBlank { title })
        val arubaFolder = draft.series.arubaFolder.trim().let { if (it.isEmpty()) "" else sanitizeFolderName(it) }
        val saved = draft.series.copy(
            title = title,
            description = draft.series.description.trim(),
            author = draft.series.author.trim(),
            artist = draft.series.artist.trim(),
            mangadexId = draft.series.mangadexId?.trim()?.ifBlank { null },
            overridePreviewHours = hours,
            cubariSlug = slug,
            arubaFolder = arubaFolder
        )
        val updatedLib = store.mutate { LibraryOps.upsertSeries(it, saved) }
        val chapters = updatedLib.chaptersOf(saved.id)
        val activeCh = stateFlow.value.activeChapterId?.takeIf { id -> chapters.any { it.id == id } } ?: chapters.firstOrNull()?.id
        stateFlow.update {
            if (keepEditing) {
                it.copy(
                    library = updatedLib,
                    activeSeriesId = saved.id,
                    seriesDraft = draft.copy(series = saved, isNew = false, revision = draft.revision + 1)
                )
            } else {
                it.copy(
                    library = updatedLib,
                    activeSeriesId = saved.id,
                    activeChapterId = activeCh,
                    inspector = InspectorMode.CHAPTER,
                    seriesDraft = null,
                    chapterDraft = openChapterDraft(updatedLib.chapterById(activeCh), it.chapterDraft?.revision ?: 0)
                )
            }
        }
        return saved
    }

    suspend fun launchCubariPush(chapterIds: List<String>) {
        if (!isUnlocked(LockKeys.STAGE1)) {
            context.showToast("Stage 1 locked — configure Cubari & Aruba in Settings & Locks")
            return
        }
        val activeId = stateFlow.value.chapterDraft?.chapterId
        if (activeId != null && activeId in chapterIds) persistChapterDraft()
        val lib = stateFlow.value.library
        var queued = 0
        for (id in chapterIds) {
            val ch = lib.chapterById(id) ?: continue
            context.launchCapability(
                capabilityName = "publishToArubaAndCubari",
                parameters = mapOf(
                    "seriesId" to JsonPrimitive(ch.seriesId),
                    "chapterId" to JsonPrimitive(ch.id),
                    "operation" to JsonPrimitive(UploaderWorkers.OP_PUBLISH)
                ),
                runInBackgroundQueue = true
            )
            queued++
        }
        if (queued > 0) context.showToast("Job queued ($queued chapter(s) -> Cubari)")
    }

    suspend fun launchMangaDexPush(chapterIds: List<String>, bypassHold: Boolean) {
        if (!isUnlocked(LockKeys.STAGE2)) {
            context.showToast("Stage 2 locked — log in & set MangaDex group in Settings & Locks")
            return
        }
        val activeId = stateFlow.value.chapterDraft?.chapterId
        if (activeId != null && activeId in chapterIds) persistChapterDraft()
        val lib = stateFlow.value.library
        val cfg = currentConfig()
        val now = services.clock.nowEpochSec()

        if (!bypassHold) {
            val holding = chapterIds.mapNotNull { lib.chapterById(it) }.filter { ch ->
                ChangeDetector.planMangaDexPush(ch, cfg.mdLanguage).kind != TargetPushKind.METADATA_ONLY
            }.mapNotNull { ch ->
                val series = lib.seriesById(ch.seriesId)
                val status = PreviewTiming.evaluate(series, ch, lib.chaptersOf(ch.seriesId), cfg, now)
                if (status.state == HoldState.HOLDING) ch to status else null
            }
            if (holding.isNotEmpty()) {
                val summary = holding.joinToString(", ") { (ch, st) ->
                    "Ch. ${ch.number} (${PreviewTiming.formatRemaining(st.remainingSec)} left)"
                }
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.PUSH_DURING_HOLD,
                            message = "Preview hold is still active for: $summary. Push to MangaDex anyway?",
                            chapterIds = chapterIds
                        )
                    )
                }
                return
            }
        }

        var queued = 0
        for (id in chapterIds) {
            val ch = lib.chapterById(id) ?: continue
            context.launchCapability(
                capabilityName = "publishToMangaDex",
                parameters = mapOf(
                    "seriesId" to JsonPrimitive(ch.seriesId),
                    "chapterId" to JsonPrimitive(ch.id)
                ),
                runInBackgroundQueue = true
            )
            queued++
        }
        if (queued > 0) context.showToast("Job queued ($queued chapter(s) -> MangaDex)")
    }

    fun requireSeriesForMangaDexSync(): Series? {
        val seriesId = stateFlow.value.activeSeriesId ?: run {
            context.showToast("Select a series first")
            return null
        }
        val series = stateFlow.value.library.seriesById(seriesId)
        if (series?.mangadexId.isNullOrBlank()) {
            context.showToast("Set a MangaDex manga ID on the series first")
            return null
        }
        return series
    }

    suspend fun launchMangaDexSync(seriesId: String, chapterNumbers: String, scanOnly: Boolean, backfillAll: Boolean) {
        if (!scanOnly && !isUnlocked(LockKeys.SYNC)) {
            context.showToast("Sync locked — configure Stage 1 & MangaDex login first")
            return
        }
        context.launchCapability(
            capabilityName = "syncMangaDexToCubari",
            parameters = mapOf(
                "seriesId" to JsonPrimitive(seriesId),
                "chapterNumbers" to JsonPrimitive(chapterNumbers),
                "scanOnly" to JsonPrimitive(scanOnly),
                "backfillAll" to JsonPrimitive(backfillAll)
            ),
            runInBackgroundQueue = true
        )
        val toast = when {
            scanOnly -> "Scanning MangaDex..."
            backfillAll -> "Full backfill job queued"
            else -> "Sync job queued (MangaDex -> Cubari)"
        }
        context.showToast(toast)
    }

    suspend fun launchPullChecked() {
        val series = requireSeriesForMangaDexSync() ?: return
        val lib = stateFlow.value.library
        val checkedNumbers = UiSelectors.checkedChapterIds(stateFlow.value)
            .mapNotNull { lib.chapterById(it) }
            .filter { it.seriesId == series.id }
            .map { normalizeChapterKey(it.number) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (checkedNumbers.isEmpty()) {
            context.showToast("Check one or more chapters in the active series first")
            return
        }
        launchMangaDexSync(series.id, checkedNumbers.joinToString(","), scanOnly = false, backfillAll = false)
    }

    suspend fun launchPullSingleChapter() {
        val chId = UiSelectors.activeChapterId(stateFlow.value) ?: run {
            context.showToast("Open a chapter first")
            return
        }
        val lib = stateFlow.value.library
        val ch = lib.chapterById(chId) ?: return
        val series = lib.seriesById(ch.seriesId)
        if (series == null || series.mangadexId.isNullOrBlank()) {
            context.showToast("Set a MangaDex manga ID on the series first")
            return
        }
        val key = normalizeChapterKey(ch.number)
        if (key.isEmpty()) {
            context.showToast("Set a chapter number before pulling from MangaDex")
            return
        }
        launchMangaDexSync(series.id, key, scanOnly = false, backfillAll = false)
    }

    suspend fun replaceOrAppendPages(paths: List<String>, replace: Boolean) {
        val draft = stateFlow.value.chapterDraft ?: run {
            context.showToast("Open a chapter first")
            return
        }
        val imported = importer.import(draft.chapterId, paths)
        if (imported.isEmpty()) {
            context.showToast("No supported images found (PNG, JPG, WEBP, GIF)")
            return
        }
        val base = if (replace) emptyList() else materializeUnifiedPages()
        val nextPages = base + imported
        stateFlow.update { st ->
            st.copy(
                chapterDraft = st.chapterDraft?.copy(pages = nextPages, dirty = true),
                selectedPageId = imported.first().id
            )
        }
        persistChapterDraft()
        context.showToast(if (replace) "Replaced with ${imported.size} page(s)" else "Added ${imported.size} page(s)")
    }

    suspend fun replaceSingleSelectedPage(pickedPath: String) {
        val st = stateFlow.value
        val draft = st.chapterDraft ?: return
        val targetId = st.selectedPageId ?: return
        val base = materializeUnifiedPages()
        val idx = base.indexOfFirst { it.id == targetId }
        if (idx < 0) return
        val imported = importer.import(draft.chapterId, listOf(pickedPath)).firstOrNull() ?: run {
            context.showToast("Could not read selected image")
            return
        }
        val nextPages = base.toMutableList().apply { set(idx, imported) }
        stateFlow.update {
            it.copy(
                chapterDraft = it.chapterDraft?.copy(pages = nextPages, dirty = true),
                selectedPageId = imported.id,
                previewPageId = if (it.previewPageId == targetId) imported.id else it.previewPageId
            )
        }
        persistChapterDraft()
        context.showToast("Replaced page #${idx + 1}")
    }

    suspend fun deleteSelectedPage() {
        val st = stateFlow.value
        val draft = st.chapterDraft ?: return
        val pageId = st.selectedPageId ?: return
        val base = materializeUnifiedPages()
        val idx = base.indexOfFirst { it.id == pageId }
        if (idx < 0) return
        val nextPages = base.filterNot { it.id == pageId }
        val nextSel = nextPages.getOrNull(idx)?.id ?: nextPages.lastOrNull()?.id
        stateFlow.update {
            it.copy(
                chapterDraft = it.chapterDraft?.copy(pages = nextPages, dirty = true),
                selectedPageId = nextSel,
                previewPageId = if (it.previewPageId == pageId) null else it.previewPageId
            )
        }
        persistChapterDraft()
        context.showToast("Removed page from draft")
    }

    suspend fun handleAction(action: String) {
        when {
            action.startsWith(Ids.ACT_VIEW) -> {
                val view = MainView.fromKey(action.removePrefix(Ids.ACT_VIEW)) ?: return
                stateFlow.update { it.copy(view = view, focusedCard = if (view == MainView.SETTINGS) it.focusedCard else null) }
            }
            action.startsWith(Ids.ACT_GOTO_TARGET) -> {
                val target = action.removePrefix(Ids.ACT_GOTO_TARGET)
                stateFlow.update { it.copy(view = MainView.SETTINGS, focusedCard = target) }
                context.showToast("Opened $target settings")
            }
            action.startsWith(Ids.ACT_SELECT_SERIES) -> {
                val seriesId = action.removePrefix(Ids.ACT_SELECT_SERIES)
                val chapters = stateFlow.value.library.chaptersOf(seriesId)
                val firstChapter = chapters.firstOrNull()
                stateFlow.update {
                    it.copy(
                        activeSeriesId = seriesId,
                        activeChapterId = firstChapter?.id,
                        checkedChapterIds = emptySet(),
                        inspector = InspectorMode.CHAPTER,
                        chapterDraft = openChapterDraft(firstChapter, it.chapterDraft?.revision ?: 0),
                        selectedPageId = firstChapter?.pages?.firstOrNull()?.id,
                        previewPageId = null
                    )
                }
            }
            action.startsWith(Ids.ACT_OPEN_CHAPTER) -> {
                val chapterId = action.removePrefix(Ids.ACT_OPEN_CHAPTER)
                val ch = stateFlow.value.library.chapterById(chapterId) ?: return
                stateFlow.update {
                    it.copy(
                        activeChapterId = ch.id,
                        inspector = InspectorMode.CHAPTER,
                        chapterDraft = openChapterDraft(ch, it.chapterDraft?.revision ?: 0),
                        selectedPageId = ch.pages.firstOrNull()?.id,
                        previewPageId = null
                    )
                }
            }
            action.startsWith(Ids.ACT_SELECT_PAGE) -> {
                val pageId = action.removePrefix(Ids.ACT_SELECT_PAGE)
                stateFlow.update { st ->
                    if (st.selectedPageId == pageId) {
                        st.copy(previewPageId = pageId, previewRemoteUrl = null, previewRemoteLabel = null)
                    } else {
                        st.copy(selectedPageId = pageId)
                    }
                }
            }
            action == Ids.ACT_VIEW_FULL_PAGE -> {
                val pageId = stateFlow.value.selectedPageId ?: return
                stateFlow.update { it.copy(previewPageId = pageId, previewRemoteUrl = null, previewRemoteLabel = null) }
            }
            action == Ids.ACT_TOGGLE_EXPAND_GRID -> {
                stateFlow.update { it.copy(gridExpanded = !it.gridExpanded) }
            }
            action == Ids.ACT_SORT_PAGES -> {
                val base = materializeUnifiedPages()
                if (base.size <= 1) return
                val sorted = base.sortedWith { a, b ->
                    PageImporter.naturalCompare(a.originalName, b.originalName)
                }
                if (sorted == base) {
                    context.showToast("Pages are already in natural order")
                    return
                }
                stateFlow.update { it.copy(chapterDraft = it.chapterDraft?.copy(pages = sorted, dirty = true)) }
                persistChapterDraft()
                context.showToast("Sorted ${sorted.size} page(s) naturally")
            }
            action == Ids.ACT_DOWNLOAD_SINGLE_PAGE -> {
                val st = stateFlow.value
                val chId = st.activeChapterId ?: return
                val pages = materializeUnifiedPages()
                val idx = pages.indexOfFirst { it.id == st.selectedPageId }
                if (idx >= 0) {
                    launchFetchRemotePages(chId, selectedRemoteSource(chId), UploaderWorkers.MODE_LOAD, replace = false, pageIndex = idx)
                }
            }
            action == Ids.ACT_REPLACE_SINGLE_PAGE -> {
                if (stateFlow.value.selectedPageId == null) return
                val picked = context.pickFile("Replace selected page image", PageImporter.IMAGE_EXTENSIONS) ?: return
                replaceSingleSelectedPage(picked)
            }
            action.startsWith(Ids.ACT_PREVIEW_PAGE) -> {
                val pageId = action.removePrefix(Ids.ACT_PREVIEW_PAGE)
                stateFlow.update { it.copy(selectedPageId = pageId, previewPageId = pageId, previewRemoteUrl = null, previewRemoteLabel = null) }
            }
            action.startsWith(Ids.ACT_PREVIEW_REMOTE) -> {
                val idx = action.removePrefix(Ids.ACT_PREVIEW_REMOTE).toIntOrNull() ?: return
                val chId = stateFlow.value.activeChapterId ?: return
                val src = selectedRemoteSource(chId)
                val remote = stateFlow.value.remotePages["$chId:$src"]?.pages?.firstOrNull { it.index == idx } ?: return
                stateFlow.update { it.copy(previewPageId = null, previewRemoteUrl = remote.url, previewRemoteLabel = "#${remote.index + 1} ${remote.fileName} (${remote.statusLabel})") }
            }
            action == Ids.ACT_OPEN_CUBARI_SERIES -> launch {
                openUrlOrToast(UploaderLinks.cubariSeriesUrl(currentConfig(), seriesForLinks()))
            }
            action == Ids.ACT_COPY_CUBARI_SERIES -> launch {
                copyUrlOrToast(UploaderLinks.cubariSeriesUrl(currentConfig(), seriesForLinks()), "Cubari")
            }
            action == Ids.ACT_OPEN_MANGADEX_SERIES -> launch {
                openUrlOrToast(UploaderLinks.mangadexSeriesUrl(seriesForLinks()))
            }
            action == Ids.ACT_COPY_MANGADEX_SERIES -> launch {
                copyUrlOrToast(UploaderLinks.mangadexSeriesUrl(seriesForLinks()), "MangaDex")
            }
            action == Ids.ACT_OPEN_CUBARI_CHAPTER -> launch {
                val (sr, ch) = activeChapterAndSeriesForLinks()
                openUrlOrToast(UploaderLinks.cubariChapterUrl(currentConfig(), sr, ch))
            }
            action == Ids.ACT_COPY_CUBARI_CHAPTER -> launch {
                val (sr, ch) = activeChapterAndSeriesForLinks()
                copyUrlOrToast(UploaderLinks.cubariChapterUrl(currentConfig(), sr, ch), "Cubari chapter")
            }
            action == Ids.ACT_OPEN_MANGADEX_CHAPTER -> launch {
                val (_, ch) = activeChapterAndSeriesForLinks()
                openUrlOrToast(UploaderLinks.mangadexChapterUrl(ch))
            }
            action == Ids.ACT_COPY_MANGADEX_CHAPTER -> launch {
                val (_, ch) = activeChapterAndSeriesForLinks()
                copyUrlOrToast(UploaderLinks.mangadexChapterUrl(ch), "MangaDex chapter")
            }
            action.startsWith(Ids.ACT_ANCHOR) -> {
                val isMd = action.removePrefix(Ids.ACT_ANCHOR) == "mangadex"
                pendingEchoes[SettingKeys.PREVIEW_ANCHOR_MD] = JsonPrimitive(isMd)
                context.updateSetting(SettingKeys.PREVIEW_ANCHOR_MD, isMd)
            }
            action.startsWith(Ids.ACT_JOB_PAUSE) -> context.pauseCapabilityJob(action.removePrefix(Ids.ACT_JOB_PAUSE))
            action.startsWith(Ids.ACT_JOB_RESUME) -> context.resumeCapabilityJob(action.removePrefix(Ids.ACT_JOB_RESUME))
            action.startsWith(Ids.ACT_JOB_CANCEL) -> context.cancelCapabilityJob(action.removePrefix(Ids.ACT_JOB_CANCEL))

            action == Ids.ACT_NEW_CHAPTER -> {
                val seriesId = stateFlow.value.activeSeriesId ?: run {
                    context.showToast("Create or select a series first")
                    return
                }
                val ch = Chapter(
                    id = newId("ch"),
                    seriesId = seriesId,
                    number = LibraryOps.nextChapterNumber(stateFlow.value.library, seriesId),
                    createdAtEpochSec = services.clock.nowEpochSec()
                )
                val updated = store.mutate { LibraryOps.upsertChapter(it, ch) }
                stateFlow.update {
                    it.copy(
                        view = MainView.WORKSPACE,
                        library = updated,
                        activeChapterId = ch.id,
                        inspector = InspectorMode.CHAPTER,
                        chapterDraft = openChapterDraft(ch, it.chapterDraft?.revision ?: 0),
                        selectedPageId = null
                    )
                }
                context.showToast("Created Ch. ${ch.number} draft")
            }

            action == Ids.ACT_SAVE_DRAFT -> persistChapterDraft(showToast = true)

            // ── Bulk actions (strictly checkedChapterIds) ──
            action == Ids.ACT_PUSH_SELECTED -> {
                val targets = UiSelectors.checkedChapterIds(stateFlow.value)
                if (targets.isEmpty()) {
                    context.showToast("Check one or more chapters for bulk push")
                    return
                }
                val snap = stateFlow.value
                val lib = snap.library
                val cfg = currentConfig()
                val stage1Ids = targets.filter { id ->
                    val ch = lib.chapterById(id) ?: return@filter false
                    val eff = UiSelectors.effectiveChapter(ch, snap.chapterDraft)
                    ChangeDetector.planCubariPush(lib.seriesById(ch.seriesId), ch, eff).kind != TargetPushKind.UP_TO_DATE
                }
                val stage2Ids = targets.filter { id -> id !in stage1Ids }
                if (stage1Ids.isNotEmpty()) launchCubariPush(stage1Ids)
                if (stage2Ids.isNotEmpty()) {
                    if (isUnlocked(LockKeys.STAGE2)) {
                        val mdTargets = stage2Ids.filter { id ->
                            val ch = lib.chapterById(id) ?: return@filter false
                            val eff = UiSelectors.effectiveChapter(ch, snap.chapterDraft)
                            ChangeDetector.planMangaDexPush(ch, cfg.mdLanguage, eff).kind != TargetPushKind.UP_TO_DATE
                        }
                        if (mdTargets.isNotEmpty()) launchMangaDexPush(mdTargets, bypassHold = false)
                        else if (stage1Ids.isEmpty()) context.showToast("Checked chapter(s) are already in sync on Cubari and MangaDex")
                    } else {
                        launchCubariPush(stage2Ids)
                    }
                }
            }

            action == Ids.ACT_PUSH_CUBARI -> {
                val targets = UiSelectors.checkedChapterIds(stateFlow.value)
                if (targets.isEmpty()) context.showToast("Check one or more chapters to bulk push to Cubari") else launchCubariPush(targets)
            }

            action == Ids.ACT_PUSH_MANGADEX -> {
                val targets = UiSelectors.checkedChapterIds(stateFlow.value)
                if (targets.isEmpty()) context.showToast("Check one or more chapters to bulk push to MangaDex") else launchMangaDexPush(targets, bypassHold = false)
            }

            action == Ids.ACT_PULL_MISSING -> launchPullChecked()
            action == Ids.ACT_SCAN_MANGADEX -> {
                val series = requireSeriesForMangaDexSync() ?: return
                launchMangaDexSync(series.id, "", scanOnly = true, backfillAll = false)
            }

            action == Ids.ACT_BACKFILL_ALL -> {
                val series = requireSeriesForMangaDexSync() ?: return
                if (!isUnlocked(LockKeys.SYNC)) {
                    context.showToast("Sync locked — configure Stage 1 & MangaDex login first")
                    return
                }
                val missingCount = stateFlow.value.library.missingCubariCount(series.id)
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.BACKFILL_ALL_MISSING,
                            seriesId = series.id,
                            message = "Fetch MangaDex feed for '${series.title}' and backfill $missingCount missing chapter(s) (or all undetected missing chapters) to Aruba and Cubari?"
                        )
                    )
                }
            }

            action == Ids.ACT_REMOVE_CUBARI -> {
                if (!isUnlocked(LockKeys.STAGE1)) {
                    context.showToast("Stage 1 locked — configure Cubari & Aruba first")
                    return
                }
                val targets = UiSelectors.checkedChapterIds(stateFlow.value)
                if (targets.isEmpty()) {
                    context.showToast("Check one or more chapters to remove from Cubari")
                    return
                }
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.REMOVE_CUBARI,
                            message = "Remove ${targets.size} checked chapter(s) from Aruba storage and Cubari JSON?",
                            chapterIds = targets
                        )
                    )
                }
            }

            // ── Single-chapter inspector actions (strictly activeChapterId) ──
            action == Ids.ACT_CH_PUSH_CUBARI -> {
                val chId = UiSelectors.activeChapterId(stateFlow.value) ?: run {
                    context.showToast("Open a chapter first")
                    return
                }
                launchCubariPush(listOf(chId))
            }

            action == Ids.ACT_CH_PUSH_MANGADEX -> {
                val chId = UiSelectors.activeChapterId(stateFlow.value) ?: run {
                    context.showToast("Open a chapter first")
                    return
                }
                launchMangaDexPush(listOf(chId), bypassHold = false)
            }

            action == Ids.ACT_CH_PULL_MD -> launchPullSingleChapter()

            action == Ids.ACT_CH_REMOVE_CUBARI -> {
                if (!isUnlocked(LockKeys.STAGE1)) {
                    context.showToast("Stage 1 locked — configure Cubari & Aruba first")
                    return
                }
                val chId = UiSelectors.activeChapterId(stateFlow.value) ?: run {
                    context.showToast("Open a chapter first")
                    return
                }
                val ch = stateFlow.value.library.chapterById(chId) ?: return
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.REMOVE_CUBARI,
                            message = "Remove single chapter Ch. ${ch.number} from Aruba storage and Cubari JSON?",
                            chapterIds = listOf(chId)
                        )
                    )
                }
            }

            action == Ids.ACT_RESTORE_GIT -> {
                if (!isUnlocked(LockKeys.CUBARI)) {
                    context.showToast("Cubari locked — configure GitHub settings first")
                    return
                }
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.RESTORE_GIT,
                            message = "Clear Git lock files, abort any interrupted rebase/merge, and hard-reset the local Cubari repository to origin? Uncommitted or unpushed local Git changes will be discarded."
                        )
                    )
                }
            }

            action == Ids.ACT_SYNC_REPO -> {
                if (!isUnlocked(LockKeys.CUBARI)) {
                    context.showToast("Cubari locked — configure GitHub settings first")
                    return
                }
                context.launchCapability(
                    capabilityName = "syncCubariGitRepo",
                    parameters = mapOf("push" to JsonPrimitive(false)),
                    runInBackgroundQueue = true
                )
                context.showToast("Repo sync job queued")
            }

            action == Ids.ACT_SERIES_NEW -> {
                val draft = SeriesDraft(
                    isNew = true,
                    series = Series(id = newId("sr"), cubariSlug = ""),
                    revision = (stateFlow.value.seriesDraft?.revision ?: 0) + 1
                )
                stateFlow.update { it.copy(inspector = InspectorMode.SERIES_EDIT, seriesDraft = draft) }
            }

            action == Ids.ACT_SERIES_EDIT -> {
                val series = stateFlow.value.library.seriesById(stateFlow.value.activeSeriesId) ?: return
                val draft = SeriesDraft(
                    isNew = false,
                    series = series,
                    hoursText = series.overridePreviewHours?.toString().orEmpty(),
                    revision = (stateFlow.value.seriesDraft?.revision ?: 0) + 1
                )
                stateFlow.update { it.copy(inspector = InspectorMode.SERIES_EDIT, seriesDraft = draft) }
            }

            action == Ids.ACT_SERIES_DELETE -> {
                val series = stateFlow.value.library.seriesById(stateFlow.value.activeSeriesId) ?: return
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.DELETE_SERIES,
                            message = "Delete '${series.title.ifBlank { series.id }}' and its local chapter drafts?",
                            seriesId = series.id
                        )
                    )
                }
            }

            action == Ids.ACT_SERIES_SAVE -> {
                val draft = stateFlow.value.seriesDraft ?: return
                val saved = persistSeriesDraft(draft, keepEditing = false) ?: return
                context.showToast("Series saved locally")
                stateFlow.update { st -> st.copy(activeSeriesId = saved.id) }
            }

            action == Ids.ACT_PUSH_SERIES_META -> {
                if (!isUnlocked(LockKeys.CUBARI)) {
                    context.showToast("Cubari repo settings required to push series metadata")
                    return
                }
                val draft = stateFlow.value.seriesDraft
                val seriesId = if (draft != null) {
                    (persistSeriesDraft(draft, keepEditing = true) ?: return).id
                } else {
                    stateFlow.value.activeSeriesId ?: return
                }
                context.launchCapability(
                    capabilityName = "publishToArubaAndCubari",
                    parameters = mapOf(
                        "seriesId" to JsonPrimitive(seriesId),
                        "chapterId" to JsonPrimitive(""),
                        "operation" to JsonPrimitive(UploaderWorkers.OP_SERIES_META)
                    ),
                    runInBackgroundQueue = true
                )
                context.showToast("Queued series metadata push to Cubari")
            }

            action == Ids.ACT_REVERT_CHAPTER_CUBARI -> {
                val ch = stateFlow.value.library.chapterById(stateFlow.value.activeChapterId) ?: return
                val snap = ch.cubariSnapshot ?: run {
                    context.showToast("No remote Cubari snapshot recorded yet")
                    return
                }
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.REVERT_LOCAL_CHAPTER,
                            message = "Discard local edits on Ch. ${ch.number} and revert to remote Cubari state (Ch. ${snap.key}, Vol. ${snap.volume.ifBlank { "-" }}, ${snap.pageUrls.size} pages)?",
                            chapterIds = listOf(ch.id)
                        )
                    )
                }
            }

            action == Ids.ACT_REVERT_SERIES_CUBARI -> {
                val series = stateFlow.value.seriesDraft?.series
                    ?: stateFlow.value.library.seriesById(stateFlow.value.activeSeriesId)
                    ?: return
                val snap = series.cubariSnapshot ?: run {
                    context.showToast("No remote Cubari snapshot recorded yet")
                    return
                }
                stateFlow.update {
                    it.copy(
                        confirm = Confirm(
                            kind = ConfirmKind.REVERT_LOCAL_SERIES,
                            message = "Discard local metadata edits and revert '${series.title}' to remote Cubari metadata ('${snap.title}')?",
                            seriesId = series.id
                        )
                    )
                }
            }

            action == Ids.ACT_SERIES_CANCEL -> stateFlow.update { it.copy(inspector = InspectorMode.CHAPTER, seriesDraft = null) }

            action == Ids.ACT_COVER_UPLOAD -> {
                val draft = stateFlow.value.seriesDraft ?: return
                if (draft.isNew) return
                val picked = context.pickFile("Select cover image", PageImporter.IMAGE_EXTENSIONS) ?: return
                val bytes = context.hostFileSystem.readFile(picked) ?: run {
                    context.showToast("Could not read cover file")
                    return
                }
                val ext = PageImporter.extension(picked)
                val stored = "covers/${draft.series.id}.$ext"
                context.fileSystem.writeFile(relPath(stored), bytes).getOrThrow()
                val updatedSeries = draft.series.copy(coverPath = stored)
                val updatedLib = store.mutate { LibraryOps.upsertSeries(it, updatedSeries) }
                stateFlow.update {
                    it.copy(
                        library = updatedLib,
                        seriesDraft = it.seriesDraft?.copy(series = updatedSeries)
                    )
                }
                context.launchCapability(
                    capabilityName = "uploadOrRemoveCubariCover",
                    parameters = mapOf(
                        "seriesId" to JsonPrimitive(updatedSeries.id),
                        "action" to JsonPrimitive(UploaderWorkers.OP_PUBLISH),
                        "coverRelativePath" to JsonPrimitive(stored)
                    ),
                    runInBackgroundQueue = true
                )
                context.showToast("Cover upload queued")
            }

            action == Ids.ACT_COVER_REMOVE -> {
                val draft = stateFlow.value.seriesDraft ?: return
                if (draft.isNew) return
                context.launchCapability(
                    capabilityName = "uploadOrRemoveCubariCover",
                    parameters = mapOf(
                        "seriesId" to JsonPrimitive(draft.series.id),
                        "action" to JsonPrimitive(UploaderWorkers.OP_REMOVE),
                        "coverRelativePath" to JsonPrimitive("")
                    ),
                    runInBackgroundQueue = true
                )
                context.showToast("Cover removal queued")
            }

            action == Ids.ACT_APPEND_PAGES -> {
                val picked = context.pickFiles("Add chapter pages", PageImporter.IMAGE_EXTENSIONS)
                if (picked.isNotEmpty()) replaceOrAppendPages(picked, replace = false)
            }

            action == Ids.ACT_REPLACE_PICK -> {
                val picked = context.pickFiles("Replace all chapter pages", PageImporter.IMAGE_EXTENSIONS)
                if (picked.isNotEmpty()) replaceOrAppendPages(picked, replace = true)
            }

            action == Ids.ACT_DELETE_PAGE -> deleteSelectedPage()

            action == Ids.ACT_CHECK_REMOTE_PAGES -> {
                val chId = stateFlow.value.activeChapterId ?: run {
                    context.showToast("Open a chapter first")
                    return
                }
                launchFetchRemotePages(chId, selectedRemoteSource(chId), UploaderWorkers.MODE_CHECK)
            }

            action == Ids.ACT_LOAD_REMOTE_PAGES -> {
                val st = stateFlow.value
                val chId = st.activeChapterId ?: run {
                    context.showToast("Open a chapter first")
                    return
                }
                val src = selectedRemoteSource(chId)
                val localCount = (st.chapterDraft?.takeIf { it.chapterId == chId }?.pages
                    ?: st.library.chapterById(chId)?.pages.orEmpty()).count { it.isLocal }
                if (localCount > 0) {
                    stateFlow.update {
                        it.copy(
                            confirm = Confirm(
                                kind = ConfirmKind.LOAD_REMOTE_PAGES,
                                message = "Replace $localCount local page file(s) with downloaded images from ${UiSelectors.remoteSourceLabel(src)}?",
                                chapterIds = listOf(chId),
                                source = src
                            )
                        )
                    }
                } else {
                    launchFetchRemotePages(chId, src, UploaderWorkers.MODE_LOAD, replace = true)
                }
            }

            action == Ids.ACT_SELECT_ALL -> {
                val ids = UiSelectors.visibleChapters(stateFlow.value).map { it.id }.toSet()
                stateFlow.update { it.copy(checkedChapterIds = ids) }
            }

            action == Ids.ACT_CLEAR_SELECTION -> stateFlow.update { it.copy(checkedChapterIds = emptySet()) }

            action == Ids.ACT_PICK_REPO -> {
                val dir = context.pickDirectory("Select local Cubari Git repository") ?: return
                pendingEchoes[SettingKeys.CUBARI_LOCAL_PATH] = JsonPrimitive(dir)
                context.updateSetting(SettingKeys.CUBARI_LOCAL_PATH, dir)
                stateFlow.update { it.copy(settingsRevision = it.settingsRevision + 1) }
                context.showToast("Repository folder linked")
            }

            action == Ids.ACT_MD_FORCE_CANCEL_SESSION -> stateFlow.update {
                it.copy(
                    confirm = Confirm(
                        kind = ConfirmKind.FORCE_CANCEL_MD_SESSION,
                        message = "Force cancel your active MangaDex upload session? Any uncommitted temporary upload will be discarded so a new publish job can start."
                    )
                )
            }

            action in setOf(
                Ids.ACT_TEST_REPO, Ids.ACT_MD_LOGIN, Ids.ACT_MD_LOGIN_BROWSER,
                Ids.ACT_MD_VERIFY, Ids.ACT_MD_LOGOUT, Ids.ACT_CHECK_GROUP,
                Ids.ACT_MD_CHECK_SESSION,
                Ids.ACT_TEST_STORAGE, Ids.ACT_SCAN_ARUBA_FOLDERS
            ) -> launch {
                val actions = UploaderActions(context, services)
                when (action) {
                    Ids.ACT_TEST_REPO -> actions.testCubariRepo()
                    Ids.ACT_MD_LOGIN -> actions.loginMangaDex()
                    Ids.ACT_MD_LOGIN_BROWSER -> actions.loginMangaDexBrowser()
                    Ids.ACT_MD_VERIFY -> actions.verifyMangaDexSession()
                    Ids.ACT_MD_LOGOUT -> actions.logoutMangaDex()
                    Ids.ACT_CHECK_GROUP -> actions.checkMangaDexGroup()
                    Ids.ACT_MD_CHECK_SESSION -> actions.checkMangaDexUploadSession()
                    Ids.ACT_TEST_STORAGE -> actions.testArubaStorage()
                    Ids.ACT_SCAN_ARUBA_FOLDERS -> actions.scanArubaFolders()
                }
                reloadFromDisk()
                stateFlow.update { st ->
                    val refreshedDraft = st.seriesDraft?.let { sd ->
                        val updated = st.library.seriesById(sd.series.id)
                        if (updated != null && sd.series.arubaFolder.isBlank() && updated.arubaFolder.isNotBlank()) {
                            sd.copy(series = sd.series.copy(arubaFolder = updated.arubaFolder), revision = sd.revision + 1)
                        } else sd
                    }
                    st.copy(
                        seriesDraft = refreshedDraft,
                        diagnostics = diagnosticsStore.loadAll(),
                        settingsRevision = st.settingsRevision + 1
                    )
                }
            }

            action == Ids.ACT_RESET_PREVIEW -> {
                SettingKeys.PREVIEW_KEYS.forEach { context.clearSetting(it) }
                stateFlow.update { it.copy(settingsRevision = it.settingsRevision + 1) }
                context.showToast("Preview settings reset to defaults")
            }
        }
    }

    launch { reloadFromDisk() }

    // Synchronise library when background capabilities finish/fail and bump settingsRevision on external setting edits.
    launch {
        context.capabilityJobs.collect { jobs ->
            val transitioned = jobs.filter { job ->
                val prev = lastJobStatuses[job.jobId]
                lastJobStatuses[job.jobId] = job.status
                job.isTerminal && prev != job.status
            }
            if (transitioned.isNotEmpty()) {
                val refreshDraft = transitioned.any {
                    it.capabilityName == "fetchChapterPages" ||
                        it.capabilityName == "syncCubariGitRepo" ||
                        it.capabilityName == "syncMangaDexToCubari"
                }
                reloadFromDisk(forceDraftRefresh = refreshDraft)
            }
        }
    }

    launch {
        context.settingsFlow.collect { latest ->
            var externalChange = false
            for (key in (latest.keys + lastObservedSettings.keys)) {
                val oldVal = lastObservedSettings[key]
                val newVal = latest[key]
                if (oldVal != newVal) {
                    val echo = pendingEchoes.remove(key)
                    if (echo == null || echo != newVal) externalChange = true
                }
            }
            lastObservedSettings = latest
            if (externalChange) stateFlow.update { it.copy(settingsRevision = it.settingsRevision + 1) }
        }
    }

    launch {
        events.collect { event ->
            when (event) {
                is UiEvent.Click -> handleAction(event.action ?: event.nodeId)

                is UiEvent.TextChanged -> when {
                    event.nodeId == Ids.SERIES_SEARCH -> stateFlow.update { it.copy(seriesSearch = event.text) }
                    event.nodeId == Ids.CHAPTER_SEARCH -> stateFlow.update { it.copy(chapterSearch = event.text) }
                    event.nodeId == Ids.CH_VOLUME -> stateFlow.update { it.copy(chapterDraft = it.chapterDraft?.copy(volume = event.text, dirty = true)) }
                    event.nodeId == Ids.CH_NUMBER -> stateFlow.update { it.copy(chapterDraft = it.chapterDraft?.copy(number = event.text, dirty = true)) }
                    event.nodeId == Ids.CH_TITLE -> stateFlow.update { it.copy(chapterDraft = it.chapterDraft?.copy(title = event.text, dirty = true)) }
                    event.nodeId == Ids.SD_TITLE -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(title = event.text)) }) }
                    event.nodeId == Ids.SD_DESCRIPTION -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(description = event.text)) }) }
                    event.nodeId == Ids.SD_AUTHOR -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(author = event.text)) }) }
                    event.nodeId == Ids.SD_ARTIST -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(artist = event.text)) }) }
                    event.nodeId == Ids.SD_MANGADEX -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(mangadexId = event.text)) }) }
                    event.nodeId == Ids.SD_ARUBA_FOLDER -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(arubaFolder = event.text)) }) }
                    event.nodeId == Ids.SD_CUBARI_SLUG -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(cubariSlug = event.text)) }) }
                    event.nodeId == Ids.SD_HOURS -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.copy(hoursText = event.text)) }
                    event.nodeId.startsWith(Ids.SETTING_PREFIX) -> {
                        val key = event.nodeId.removePrefix(Ids.SETTING_PREFIX)
                        if (key in INT_SETTING_KEYS) {
                            val trimmed = event.text.trim()
                            if (trimmed.isEmpty()) {
                                pendingEchoes[key] = null
                                context.clearSetting(key)
                            } else {
                                trimmed.toIntOrNull()?.let { n ->
                                    pendingEchoes[key] = JsonPrimitive(n)
                                    context.updateSetting(key, n)
                                }
                            }
                        } else {
                            pendingEchoes[key] = JsonPrimitive(event.text)
                            context.updateSetting(key, event.text)
                        }
                    }
                }

                is UiEvent.OptionSelected -> when (event.nodeId) {
                    Ids.SERIES_SORT -> stateFlow.update { it.copy(seriesSort = SeriesSort.fromKey(event.selectedKey)) }
                    Ids.SERIES_FILTER -> stateFlow.update { it.copy(seriesFilter = SeriesFilter.fromKey(event.selectedKey)) }
                    Ids.CHAPTER_FILTER -> stateFlow.update { it.copy(chapterFilter = ChapterFilter.fromKey(event.selectedKey)) }
                    Ids.SD_STATUS -> stateFlow.update { st -> st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(status = event.selectedKey)) }) }
                    Ids.SD_ANCHOR -> stateFlow.update { st ->
                        val anchor = runCatching { PreviewAnchorOverride.valueOf(event.selectedKey) }.getOrDefault(PreviewAnchorOverride.INHERIT)
                        st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(overridePreviewAnchor = anchor)) })
                    }
                    Ids.SD_ARUBA_FOLDER_SELECT -> stateFlow.update { st ->
                        if (event.selectedKey == "__custom__") return@update st
                        st.copy(
                            seriesDraft = st.seriesDraft?.let {
                                it.copy(
                                    series = it.series.copy(arubaFolder = event.selectedKey),
                                    revision = it.revision + 1
                                )
                            }
                        )
                    }
                    Ids.SD_PAGE_PADDING -> stateFlow.update { st ->
                        val pad = event.selectedKey.toIntOrNull()?.coerceIn(0, 6)
                        st.copy(seriesDraft = st.seriesDraft?.let { it.copy(series = it.series.copy(arubaZeroPadDigits = pad)) })
                    }
                    Ids.SETTING_PREFIX + SettingKeys.ARUBA_PROTOCOL -> {
                        pendingEchoes[SettingKeys.ARUBA_PROTOCOL] = JsonPrimitive(event.selectedKey)
                        context.updateSetting(SettingKeys.ARUBA_PROTOCOL, event.selectedKey)
                    }
                    Ids.SETTING_PREFIX + SettingKeys.ARUBA_PAGE_PADDING -> {
                        pendingEchoes[SettingKeys.ARUBA_PAGE_PADDING] = JsonPrimitive(event.selectedKey)
                        context.updateSetting(SettingKeys.ARUBA_PAGE_PADDING, event.selectedKey)
                    }
                    Ids.REMOTE_SOURCE -> {
                        val chId = stateFlow.value.activeChapterId ?: return@collect
                        stateFlow.update { st -> st.copy(remoteSourceByChapter = st.remoteSourceByChapter + (chId to event.selectedKey)) }
                    }
                }

                is UiEvent.ToggleChanged -> when {
                    event.nodeId.startsWith(Ids.CHAPTER_CHECK_PREFIX) -> {
                        val chapterId = event.nodeId.removePrefix(Ids.CHAPTER_CHECK_PREFIX)
                        stateFlow.update {
                            val next = if (event.checked) it.checkedChapterIds + chapterId else it.checkedChapterIds - chapterId
                            it.copy(checkedChapterIds = next)
                        }
                    }
                    event.nodeId == Ids.SETTING_PREFIX + SettingKeys.MD_AUTO_DOH -> {
                        pendingEchoes[SettingKeys.MD_AUTO_DOH] = JsonPrimitive(event.checked)
                        context.updateSetting(SettingKeys.MD_AUTO_DOH, event.checked)
                    }
                }

                is UiEvent.ItemsReordered -> when (event.nodeId) {
                    Ids.PAGE_GRID -> {
                        if (stateFlow.value.chapterDraft == null) return@collect
                        val base = materializeUnifiedPages()
                        val byId = base.associateBy { it.id }
                        val reordered = event.itemIds.mapNotNull { byId[it] }
                        if (reordered.size == base.size) {
                            stateFlow.update { it.copy(chapterDraft = it.chapterDraft?.copy(pages = reordered, dirty = true)) }
                            persistChapterDraft()
                        }
                    }
                    Ids.SERIES_LIST -> {
                        val ids = event.itemIds.map { it.removePrefix(Ids.SERIES_PREFIX) }
                        val updatedLib = store.mutate { LibraryOps.reorderSeries(it, ids) }
                        stateFlow.update { it.copy(library = updatedLib) }
                    }
                }

                is UiEvent.FilesDropped -> when (event.action) {
                    Ids.ACT_DROP_PAGES -> replaceOrAppendPages(event.paths, replace = false)
                    Ids.ACT_REPLACE_PAGES -> replaceOrAppendPages(event.paths, replace = true)
                    Ids.ACT_DROP_REPO -> {
                        val dir = event.paths.firstOrNull() ?: return@collect
                        pendingEchoes[SettingKeys.CUBARI_LOCAL_PATH] = JsonPrimitive(dir)
                        context.updateSetting(SettingKeys.CUBARI_LOCAL_PATH, dir)
                        stateFlow.update { it.copy(settingsRevision = it.settingsRevision + 1) }
                        context.showToast("Repository folder linked")
                    }
                }

                is UiEvent.ShortcutTriggered -> when (event.action) {
                    Ids.ACT_SAVE_DRAFT -> persistChapterDraft(showToast = true)
                    Ids.ACT_DELETE_PAGE -> deleteSelectedPage()
                    Ids.ACT_DISMISS -> stateFlow.update { st ->
                        when {
                            st.previewPageId != null || st.previewRemoteUrl != null -> st.copy(previewPageId = null, previewRemoteUrl = null, previewRemoteLabel = null)
                            st.confirm != null -> st.copy(confirm = null)
                            st.selectedPageId != null -> st.copy(selectedPageId = null)
                            st.checkedChapterIds.isNotEmpty() -> st.copy(checkedChapterIds = emptySet())
                            else -> st
                        }
                    }
                }

                is UiEvent.DialogAction -> when (event.nodeId) {
                    Ids.DLG_PREVIEW -> stateFlow.update { it.copy(previewPageId = null, previewRemoteUrl = null, previewRemoteLabel = null) }
                    Ids.DLG_CONFIRM -> {
                        val confirm = stateFlow.value.confirm
                        stateFlow.update { it.copy(confirm = null) }
                        if (event.isConfirmed && confirm != null) {
                            when (confirm.kind) {
                                ConfirmKind.REMOVE_CUBARI -> {
                                    val lib = stateFlow.value.library
                                    for (id in confirm.chapterIds) {
                                        val ch = lib.chapterById(id) ?: continue
                                        context.launchCapability(
                                            capabilityName = "publishToArubaAndCubari",
                                            parameters = mapOf(
                                                "seriesId" to JsonPrimitive(ch.seriesId),
                                                "chapterId" to JsonPrimitive(ch.id),
                                                "operation" to JsonPrimitive(UploaderWorkers.OP_REMOVE)
                                            ),
                                            runInBackgroundQueue = true
                                        )
                                    }
                                    context.showToast("Removal job queued")
                                }
                                ConfirmKind.DELETE_SERIES -> {
                                    val id = confirm.seriesId ?: return@collect
                                    val updatedLib = store.mutate { LibraryOps.deleteSeries(it, id) }
                                    val nextSeries = updatedLib.series.sortedBy { it.order }.firstOrNull()
                                    val nextChapter = nextSeries?.let { updatedLib.chaptersOf(it.id).firstOrNull() }
                                    stateFlow.update {
                                        it.copy(
                                            library = updatedLib,
                                            activeSeriesId = nextSeries?.id,
                                            activeChapterId = nextChapter?.id,
                                            checkedChapterIds = emptySet(),
                                            inspector = InspectorMode.CHAPTER,
                                            seriesDraft = null,
                                            chapterDraft = openChapterDraft(nextChapter, it.chapterDraft?.revision ?: 0)
                                        )
                                    }
                                    context.showToast("Series deleted locally")
                                }
                                ConfirmKind.PUSH_DURING_HOLD -> launchMangaDexPush(confirm.chapterIds, bypassHold = true)
                                ConfirmKind.LOAD_REMOTE_PAGES -> {
                                    val chId = confirm.chapterIds.firstOrNull() ?: return@collect
                                    val src = confirm.source ?: selectedRemoteSource(chId)
                                    launchFetchRemotePages(chId, src, UploaderWorkers.MODE_LOAD, replace = true)
                                }
                                ConfirmKind.BACKFILL_ALL_MISSING -> {
                                    val sid = confirm.seriesId ?: return@collect
                                    launchMangaDexSync(sid, "", scanOnly = false, backfillAll = true)
                                }
                                ConfirmKind.RESTORE_GIT -> launch {
                                    val ok = UploaderActions(context, services).restoreCubariRepo()
                                    reloadFromDisk()
                                    stateFlow.update { st ->
                                        st.copy(
                                            diagnostics = diagnosticsStore.loadAll(),
                                            settingsRevision = st.settingsRevision + 1
                                        )
                                    }
                                    if (ok) {
                                        context.launchCapability(
                                            capabilityName = "syncCubariGitRepo",
                                            parameters = mapOf("push" to JsonPrimitive(false)),
                                            runInBackgroundQueue = true
                                        )
                                    }
                                }
                                ConfirmKind.FORCE_CANCEL_MD_SESSION -> launch {
                                    UploaderActions(context, services).forceCancelMangaDexUploadSession()
                                    reloadFromDisk()
                                    stateFlow.update { st ->
                                        st.copy(
                                            diagnostics = diagnosticsStore.loadAll(),
                                            settingsRevision = st.settingsRevision + 1
                                        )
                                    }
                                }
                                ConfirmKind.REVERT_LOCAL_CHAPTER -> {
                                    val chId = confirm.chapterIds.firstOrNull() ?: return@collect
                                    val updatedLib = store.mutate { lib ->
                                        val existing = lib.chapterById(chId) ?: return@mutate lib
                                        val snap = existing.cubariSnapshot ?: return@mutate lib
                                        val reverted = existing.copy(
                                            number = snap.key,
                                            volume = snap.volume,
                                            title = snap.title,
                                            cubariPageUrls = snap.pageUrls,
                                            pages = emptyList()
                                        )
                                        LibraryOps.upsertChapter(lib, reverted)
                                    }
                                    val revertedCh = updatedLib.chapterById(chId)
                                    stateFlow.update {
                                        it.copy(
                                            library = updatedLib,
                                            chapterDraft = if (it.activeChapterId == chId) {
                                                openChapterDraft(revertedCh, it.chapterDraft?.revision ?: 0)
                                            } else it.chapterDraft
                                        )
                                    }
                                    context.showToast("Reverted Ch. ${revertedCh?.number ?: chId} to last synced Cubari state")
                                }
                                ConfirmKind.REVERT_LOCAL_SERIES -> {
                                    val sid = confirm.seriesId ?: return@collect
                                    val updatedLib = store.mutate { lib ->
                                        val existing = lib.seriesById(sid) ?: return@mutate lib
                                        val snap = existing.cubariSnapshot ?: return@mutate lib
                                        LibraryOps.upsertSeries(
                                            lib,
                                            existing.copy(
                                                title = snap.title,
                                                description = snap.description,
                                                author = snap.author,
                                                artist = snap.artist
                                            )
                                        )
                                    }
                                    val reverted = updatedLib.seriesById(sid)
                                    stateFlow.update {
                                        it.copy(
                                            library = updatedLib,
                                            seriesDraft = if (it.seriesDraft?.series?.id == sid && reverted != null) {
                                                it.seriesDraft.copy(
                                                    series = reverted,
                                                    revision = it.seriesDraft.revision + 1
                                                )
                                            } else it.seriesDraft
                                        )
                                    }
                                    context.showToast("Reverted '${reverted?.title ?: sid}' to last synced Cubari metadata")
                                }
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    combine(stateFlow, context.capabilityJobs, context.settingsFlow, context.locksFlow) { st, jobs, settings, locks ->
        MangaUploaderRenderer.render(
            RenderModel(
                state = st,
                jobs = jobs,
                settings = settings,
                locks = locks,
                nowEpochSec = services.clock.nowEpochSec()
            )
        )
    }.collect { send(it) }
}
