package com.wip.mangauploader

enum class MainView(val key: String, val label: String) {
    WORKSPACE("workspace", "Workspace"),
    SETTINGS("settings", "Settings & Locks"),
    QUEUE("queue", "Queue");

    companion object {
        fun fromKey(key: String): MainView? = entries.firstOrNull { it.key == key }
    }
}

enum class SeriesSort(val key: String, val label: String) {
    CUSTOM("custom", "Custom Order"),
    UPDATED("updated", "Last Updated"),
    MISSING("missing", "Missing Cubari");

    companion object {
        fun fromKey(key: String): SeriesSort = entries.firstOrNull { it.key == key } ?: CUSTOM
    }
}

enum class SeriesFilter(val key: String, val label: String) {
    ALL("all", "All Sources"),
    CUBARI("cubari", "Cubari"),
    MANGADEX("mangadex", "MangaDex"),
    BOTH("both", "Cubari + MD"),
    MISSING_CUBARI("missing_cubari", "Missing Cubari");

    companion object {
        fun fromKey(key: String): SeriesFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

enum class ChapterFilter(val key: String, val label: String) {
    ALL("all", "All"),
    CUBARI("cubari", "Cubari"),
    MANGADEX("mangadex", "MangaDex"),
    BOTH("both", "Cubari + MD"),
    CUBARI_ONLY("cubari_only", "Cubari Only (No MD)"),
    MISSING("missing", "Missing Cubari (On MD)"),
    STAGING("staging", "Staging");

    companion object {
        fun fromKey(key: String): ChapterFilter = when (key) {
            "synced" -> CUBARI
            else -> entries.firstOrNull { it.key == key } ?: ALL
        }
    }
}

enum class InspectorMode { CHAPTER, SERIES_EDIT }

enum class WorkspaceLayoutMode(val key: String, val label: String) {
    THREE_PANE("three_pane", "3-Pane"),
    TWO_PANE("two_pane", "2-Pane");

    companion object {
        fun fromKey(key: String?): WorkspaceLayoutMode =
            entries.firstOrNull { it.key == key } ?: THREE_PANE
    }
}

enum class TwoPaneLeftTab(val key: String, val label: String) {
    SERIES("series", "Series"),
    CHAPTERS("chapters", "Chapters");

    companion object {
        fun fromKey(key: String?): TwoPaneLeftTab =
            entries.firstOrNull { it.key == key } ?: CHAPTERS
    }
}

enum class ConfirmKind {
    REMOVE_CUBARI,
    DELETE_CHAPTER_DRAFT,
    DELETE_SERIES,
    PUSH_DURING_HOLD,
    LOAD_REMOTE_PAGES,
    BACKFILL_ALL_MISSING,
    RESTORE_GIT,
    FORCE_CANCEL_MD_SESSION,
    REVERT_LOCAL_CHAPTER,
    REVERT_LOCAL_SERIES
}

/** Human-readable target metadata for a launched or persisted capability job. */
data class JobTarget(
    val displayTitle: String = "",
    val seriesId: String? = null,
    val chapterId: String? = null
)

/** Pending modal confirmation (rendered as `DialogNode`). */
data class Confirm(
    val kind: ConfirmKind,
    val message: String,
    val chapterIds: List<String> = emptyList(),
    val seriesId: String? = null,
    val source: String? = null
)

/** Unsaved, local-only edits of the chapter shown in the inspector. */
data class ChapterDraft(
    val chapterId: String,
    val volume: String,
    val number: String,
    val title: String,
    val pages: List<PageRef>,
    val dirty: Boolean = false,
    /** Bumped whenever the draft is (re)loaded so text fields overwrite their content. */
    val revision: Int = 0
)

data class SeriesDraft(
    val isNew: Boolean,
    val series: Series,
    val hoursText: String = "",
    val revision: Int = 0
)

/** Complete local UI state of the interactive session. */
data class UiState(
    val view: MainView = MainView.WORKSPACE,
    val layoutMode: WorkspaceLayoutMode = WorkspaceLayoutMode.THREE_PANE,
    val twoPaneLeftTab: TwoPaneLeftTab = TwoPaneLeftTab.CHAPTERS,
    val loaded: Boolean = false,
    val library: Library = Library(),
    val arubaFolders: List<String> = emptyList(),
    val seriesSearch: String = "",
    val seriesSort: SeriesSort = SeriesSort.CUSTOM,
    val seriesFilter: SeriesFilter = SeriesFilter.ALL,
    val activeSeriesId: String? = null,
    val chapterSearch: String = "",
    val chapterFilter: ChapterFilter = ChapterFilter.ALL,
    val checkedChapterIds: Set<String> = emptySet(),
    val activeChapterId: String? = null,
    val inspector: InspectorMode = InspectorMode.CHAPTER,
    val chapterDraft: ChapterDraft? = null,
    val seriesDraft: SeriesDraft? = null,
    val selectedPageId: String? = null,
    val gridExpanded: Boolean = false,
    val previewPageId: String? = null,
    val previewRemoteUrl: String? = null,
    val previewRemoteLabel: String? = null,
    val remoteSourceByChapter: Map<String, String> = emptyMap(),
    val remotePages: Map<String, RemoteChapterPages> = emptyMap(),
    val jobTargets: Map<String, JobTarget> = emptyMap(),
    val confirm: Confirm? = null,
    val focusedCard: String? = null,
    val settingsRevision: Int = 0,
    val diagnostics: Map<String, Diagnostic> = emptyMap()
)

/** Shared external browser URL builders for Cubari.moe and MangaDex. */
object UploaderLinks {
    fun cubariSeriesUrl(config: MangaUploaderConfig, series: Series?): String? {
        series ?: return null
        if (config.cubariRemoteUrl.isBlank()) return null
        val owner = CubariJson.repoOwnerFromUrl(config.cubariRemoteUrl, fallback = config.cubariUsername)
        val repo = CubariJson.repoNameFromUrl(config.cubariRemoteUrl)
        if (owner.isBlank() || repo.isBlank()) return null
        val slug = series.cubariSlug.ifBlank { sanitizeFolderName(series.title.ifBlank { series.id }) }
        val jsonRelPath = listOf(config.cubariSeriesDir.trim('/'), "$slug.json").filter { it.isNotEmpty() }.joinToString("/")
        return CubariJson.readerUrl(owner, repo, config.cubariBranch, jsonRelPath)
    }

    fun cubariChapterUrl(config: MangaUploaderConfig, series: Series?, chapter: Chapter?): String? {
        val base = cubariSeriesUrl(config, series) ?: return null
        val key = chapter?.cubariKey?.takeIf { it.isNotBlank() && chapter.isOnCubari } ?: return base
        return "${base.trimEnd('/')}/$key/1/"
    }

    fun mangadexSeriesUrl(series: Series?): String? =
        series?.mangadexId?.let { MangaUploaderConfig.extractUuid(it) }?.let { "https://mangadex.org/title/$it" }

    fun mangadexChapterUrl(chapter: Chapter?): String? =
        chapter?.mangadexChapterId?.takeIf { it.isNotBlank() }?.let { "https://mangadex.org/chapter/$it" }
}

/** Pure list selectors (search / sort / filter) used by the renderer and unit tests. */
object UiSelectors {

    fun visibleSeries(state: UiState): List<Series> {
        val query = state.seriesSearch.trim()
        val filtered = state.library.series.filter { series ->
            val matchesQuery = query.isEmpty() || series.title.contains(query, ignoreCase = true)
            if (!matchesQuery) return@filter false
            val chapters by lazy { state.library.chaptersOf(series.id) }
            val hasCubari = chapters.any { it.isOnCubari } || series.lastUpdatedEpochSec != null
            val hasMd = !series.mangadexId.isNullOrBlank() || chapters.any { it.isOnMangaDex }
            when (state.seriesFilter) {
                SeriesFilter.ALL -> true
                SeriesFilter.CUBARI -> hasCubari
                SeriesFilter.MANGADEX -> hasMd
                SeriesFilter.BOTH -> hasCubari && hasMd
                SeriesFilter.MISSING_CUBARI -> state.library.missingCubariCount(series.id) > 0
            }
        }
        return when (state.seriesSort) {
            SeriesSort.CUSTOM -> filtered.sortedBy { it.order }
            SeriesSort.UPDATED -> filtered.sortedByDescending { it.lastUpdatedEpochSec ?: Long.MIN_VALUE }
            SeriesSort.MISSING -> filtered.sortedWith(
                compareByDescending<Series> { state.library.missingCubariCount(it.id) }.thenBy { it.order }
            )
        }
    }

    fun visibleChapters(state: UiState): List<Chapter> {
        val seriesId = state.activeSeriesId ?: return emptyList()
        val query = state.chapterSearch.trim()
        return state.library.chaptersOf(seriesId)
            .filter {
                when (state.chapterFilter) {
                    ChapterFilter.ALL -> true
                    ChapterFilter.CUBARI -> it.isOnCubari
                    ChapterFilter.MANGADEX -> it.isOnMangaDex
                    ChapterFilter.BOTH -> it.isOnCubari && it.isOnMangaDex
                    ChapterFilter.CUBARI_ONLY -> it.syncState == ChapterSyncState.CUBARI_ONLY
                    ChapterFilter.MISSING -> it.syncState == ChapterSyncState.MISSING_CUBARI
                    ChapterFilter.STAGING -> it.syncState == ChapterSyncState.STAGING
                }
            }
            .filter {
                query.isEmpty() || it.number.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true)
            }
    }

    /** Chapters explicitly checked in the list for bulk actions (never falls back to [UiState.activeChapterId]). */
    fun checkedChapterIds(state: UiState): List<String> =
        state.library.chapters.filter { it.id in state.checkedChapterIds }.map { it.id }

    /** Currently active single chapter opened in the inspector. */
    fun activeChapterId(state: UiState): String? =
        state.activeChapterId?.takeIf { state.library.chapterById(it) != null }

    /** Chapters a bulk action applies to; strictly the checked ones. */
    fun targetChapterIds(state: UiState): List<String> = checkedChapterIds(state)

    /** Returns [chapter] overlaid with unsaved [draft] edits when the draft belongs to [chapter]. */
    fun effectiveChapter(chapter: Chapter, draft: ChapterDraft?): Chapter =
        if (draft != null && draft.chapterId == chapter.id) {
            chapter.copy(
                volume = draft.volume.trim(),
                number = draft.number.trim(),
                title = draft.title.trim(),
                pages = draft.pages
            )
        } else chapter

    /** Picks the default source (`cubari` or `mangadex`) for inspecting/loading uploaded images. */
    fun defaultRemoteSource(chapter: Chapter?, series: Series?): String = when {
        chapter?.isOnCubari == true -> UploaderWorkers.SOURCE_CUBARI
        chapter?.isOnMangaDex == true -> UploaderWorkers.SOURCE_MANGADEX
        chapter == null && !series?.mangadexId.isNullOrBlank() -> UploaderWorkers.SOURCE_MANGADEX
        else -> UploaderWorkers.SOURCE_CUBARI
    }

    /** Human-readable name for a remote page source key. */
    fun remoteSourceLabel(source: String): String =
        if (source == UploaderWorkers.SOURCE_MANGADEX) "MangaDex" else "Cubari / Aruba"

    /**
     * Unified pages shown in the chapter inspector: local/materialized draft pages when present,
     * otherwise lightweight remote references from the inspected remote source or cached Cubari URLs.
     */
    fun unifiedPages(
        draft: ChapterDraft?,
        chapter: Chapter?,
        remoteReport: RemoteChapterPages?,
        selectedSource: String
    ): List<PageRef> {
        if (draft != null && draft.pages.isNotEmpty()) return draft.pages
        if (selectedSource == UploaderWorkers.SOURCE_MANGADEX && remoteReport?.pages?.isNotEmpty() == true) {
            return remoteReport.pages.map {
                PageRef(id = "md_pg_${it.index}", storedPath = "", originalName = it.fileName, remoteUrl = it.url)
            }
        }
        val urls = chapter?.cubariPageUrls.orEmpty().ifEmpty {
            remoteReport?.takeIf { it.source == UploaderWorkers.SOURCE_CUBARI }?.pages?.map { it.url }.orEmpty()
        }
        return urls.mapIndexed { idx, url ->
            val raw = ArubaPaths.decodeUrlSegment(url.substringBefore('?').substringAfterLast('/'))
            val name = raw.ifBlank { "${idx + 1}.png" }
            PageRef(id = "cubari_pg_$idx", storedPath = "", originalName = name, remoteUrl = url)
        }
    }
}
