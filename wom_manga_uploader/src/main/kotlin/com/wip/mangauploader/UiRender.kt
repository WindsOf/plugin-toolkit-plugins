package com.wip.mangauploader

import kotlinx.serialization.json.JsonElement
import org.wip.plugintoolkit.api.ProgressData
import org.wip.plugintoolkit.api.sdui.AssetReference
import org.wip.plugintoolkit.api.sdui.CapabilityJobSnapshot
import org.wip.plugintoolkit.api.sdui.CapabilityJobStatus
import org.wip.plugintoolkit.api.sdui.ImageScale
import org.wip.plugintoolkit.api.sdui.SemanticColor
import org.wip.plugintoolkit.api.sdui.SpacingSize
import org.wip.plugintoolkit.api.sdui.TypographyRole
import org.wip.plugintoolkit.api.sdui.UiContainerBuilder
import org.wip.plugintoolkit.api.sdui.UiDropTarget
import org.wip.plugintoolkit.api.sdui.UiModifier
import org.wip.plugintoolkit.api.sdui.UiNodeTree
import org.wip.plugintoolkit.api.sdui.UiShortcut
import org.wip.plugintoolkit.api.sdui.uiTree
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Stable node ids / action names shared by the renderer and the event handler. */
object Ids {
    const val ROOT = "manga_uploader_root"
    const val NAV_VIEWS = "nav_views"
    const val NAV_TARGETS = "nav_targets"
    const val SPLIT_PUSH = "split_push"
    const val SERIES_SEARCH = "series_search"
    const val SERIES_SORT = "series_sort"
    const val SERIES_FILTER = "series_filter"
    const val SERIES_LIST = "series_list"
    const val SERIES_PREFIX = "series_"
    const val CHAPTER_SEARCH = "chapter_search"
    const val CHAPTER_FILTER = "chapter_filter"
    const val CHAPTER_CHECK_PREFIX = "chk_"
    const val CH_VOLUME = "ch_volume"
    const val CH_NUMBER = "ch_number"
    const val CH_TITLE = "ch_title"
    const val PAGE_GRID = "page_grid"
    const val REMOTE_SOURCE = "remote_pages_source"
    const val REMOTE_PAGE_GRID = "remote_page_grid"
    const val SD_TITLE = "sd_title"
    const val SD_DESCRIPTION = "sd_description"
    const val SD_AUTHOR = "sd_author"
    const val SD_ARTIST = "sd_artist"
    const val SD_STATUS = "sd_status"
    const val SD_MANGADEX = "sd_mangadex"
    const val SD_CUBARI_SLUG = "sd_cubari_slug"
    const val SD_ARUBA_FOLDER = "sd_aruba_folder"
    const val SD_ARUBA_FOLDER_SELECT = "sd_aruba_folder_select"
    const val SD_PAGE_PADDING = "sd_page_padding"
    const val SD_HOURS = "sd_hours"
    const val SD_ANCHOR = "sd_anchor"
    const val SETTING_PREFIX = "set:"
    const val DLG_CONFIRM = "dlg_confirm"
    const val DLG_PREVIEW = "dlg_page_preview"

    // Shortcut / drop actions
    const val ACT_SAVE_DRAFT = "save_draft"
    const val ACT_DISMISS = "dismiss_or_back"
    const val ACT_DELETE_PAGE = "delete_selected_page"
    const val ACT_DROP_PAGES = "drop_page_files"
    const val ACT_REPLACE_PAGES = "replace_page_files"
    const val ACT_DROP_REPO = "drop_repo_folder"

    // Button actions (bulk vs single-chapter actions kept strictly separate)
    const val ACT_VIEW = "view:"
    const val ACT_GOTO_TARGET = "goto_target:"
    const val ACT_NEW_CHAPTER = "new_chapter"
    const val ACT_PUSH_SELECTED = "push_selected"
    const val ACT_PUSH_CUBARI = "push_cubari"
    const val ACT_PUSH_MANGADEX = "push_mangadex"
    const val ACT_PULL_MISSING = "pull_missing"
    const val ACT_BACKFILL_ALL = "backfill_all_missing"
    const val ACT_REMOVE_CUBARI = "remove_cubari"
    const val ACT_SCAN_MANGADEX = "scan_mangadex"
    const val ACT_CH_PUSH_CUBARI = "ch_push_cubari"
    const val ACT_CH_PUSH_MANGADEX = "ch_push_mangadex"
    const val ACT_CH_PULL_MD = "ch_pull_md"
    const val ACT_CH_REMOVE_CUBARI = "ch_remove_cubari"
    const val ACT_SELECT_SERIES = "select_series:"
    const val ACT_OPEN_CHAPTER = "open_chapter:"
    const val ACT_PREVIEW_PAGE = "preview_page:"
    const val ACT_SELECT_PAGE = "select_page:"
    const val ACT_VIEW_FULL_PAGE = "view_full_page"
    const val ACT_DOWNLOAD_SINGLE_PAGE = "download_single_page"
    const val ACT_REPLACE_SINGLE_PAGE = "replace_single_page"
    const val ACT_TOGGLE_EXPAND_GRID = "toggle_expand_grid"
    const val ACT_SORT_PAGES = "sort_pages_natural"
    const val ACT_SERIES_NEW = "series_new"
    const val ACT_SERIES_EDIT = "series_edit"
    const val ACT_SERIES_DELETE = "series_delete"
    const val ACT_SERIES_SAVE = "series_save"
    const val ACT_SERIES_CANCEL = "series_cancel"
    const val ACT_COVER_UPLOAD = "series_cover_upload"
    const val ACT_COVER_REMOVE = "series_cover_remove"
    const val ACT_APPEND_PAGES = "append_pages"
    const val ACT_REPLACE_PICK = "replace_pages_pick"
    const val ACT_CHECK_REMOTE_PAGES = "check_remote_pages"
    const val ACT_LOAD_REMOTE_PAGES = "load_remote_pages"
    const val ACT_PREVIEW_REMOTE = "preview_remote:"
    const val ACT_SCAN_ARUBA_FOLDERS = "scan_aruba_folders"
    const val ACT_OPEN_CUBARI_SERIES = "open_cubari_series"
    const val ACT_OPEN_MANGADEX_SERIES = "open_mangadex_series"
    const val ACT_COPY_CUBARI_SERIES = "copy_cubari_series"
    const val ACT_COPY_MANGADEX_SERIES = "copy_mangadex_series"
    const val ACT_OPEN_CUBARI_CHAPTER = "open_cubari_chapter"
    const val ACT_OPEN_MANGADEX_CHAPTER = "open_mangadex_chapter"
    const val ACT_COPY_CUBARI_CHAPTER = "copy_cubari_chapter"
    const val ACT_COPY_MANGADEX_CHAPTER = "copy_mangadex_chapter"
    const val ACT_SELECT_ALL = "select_all_chapters"
    const val ACT_CLEAR_SELECTION = "clear_chapter_selection"
    const val ACT_TEST_REPO = "test_repo"
    const val ACT_SYNC_REPO = "sync_cubari_repo"
    const val ACT_RESTORE_GIT = "restore_cubari_git"
    const val ACT_PICK_REPO = "pick_repo_folder"
    const val ACT_MD_LOGIN = "mangadex_login"
    const val ACT_MD_LOGIN_BROWSER = "mangadex_login_browser"
    const val ACT_MD_VERIFY = "mangadex_verify"
    const val ACT_MD_LOGOUT = "mangadex_logout"
    const val ACT_MD_CHECK_SESSION = "mangadex_check_upload_session"
    const val ACT_MD_FORCE_CANCEL_SESSION = "mangadex_force_cancel_upload_session"
    const val ACT_CHECK_GROUP = "check_group"
    const val ACT_TEST_STORAGE = "test_storage"
    const val ACT_PRUNE_CACHE = "prune_local_cache"
    const val ACT_RESET_PREVIEW = "reset_preview_defaults"
    const val ACT_PUSH_SERIES_META = "push_series_meta"
    const val ACT_REVERT_CHAPTER_CUBARI = "revert_chapter_cubari"
    const val ACT_REVERT_SERIES_CUBARI = "revert_series_cubari"
    const val ACT_LOAD_REMOTE_META = "load_remote_meta"
    const val ACT_ANCHOR = "set_anchor:"
    const val ACT_JOB_PAUSE = "job_pause:"
    const val ACT_JOB_RESUME = "job_resume:"
    const val ACT_JOB_CANCEL = "job_cancel:"
    const val ACT_CLEAR_JOB_HISTORY = "clear_job_history"

    fun card(target: String) = "card_$target"
}

/** Everything the renderer needs; a pure function of this model yields the UI tree. */
data class RenderModel(
    val state: UiState,
    val jobs: List<CapabilityJobSnapshot>,
    val settings: Map<String, JsonElement>,
    val locks: Map<String, Boolean>,
    val nowEpochSec: Long
) {
    val config: MangaUploaderConfig = MangaUploaderConfig.from(settings)
    val report: LockReport = LockEvaluator.evaluate(config)

    /** Live `locksFlow` value when present, otherwise the locally evaluated one. */
    fun isUnlocked(key: String): Boolean = locks[key] ?: report.toLockMap()[key] ?: false
}

private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

fun formatTimestamp(epochSec: Long?): String = epochSec?.let { TS_FORMAT.format(Instant.ofEpochSecond(it)) } ?: "never"

private fun Readiness.color(): SemanticColor = when (this) {
    Readiness.READY -> SemanticColor.SUCCESS_CONTAINER
    Readiness.PARTIAL -> SemanticColor.WARNING_CONTAINER
    Readiness.LOCKED -> SemanticColor.ERROR_CONTAINER
}

private fun CapabilityJobStatus.color(): SemanticColor = when (this) {
    CapabilityJobStatus.QUEUED -> SemanticColor.SECONDARY_CONTAINER
    CapabilityJobStatus.RUNNING -> SemanticColor.INFO_CONTAINER
    CapabilityJobStatus.PAUSE_REQUESTED, CapabilityJobStatus.PAUSED -> SemanticColor.WARNING_CONTAINER
    CapabilityJobStatus.COMPLETED -> SemanticColor.SUCCESS_CONTAINER
    CapabilityJobStatus.FAILED -> SemanticColor.ERROR_CONTAINER
    CapabilityJobStatus.CANCELLED -> SemanticColor.SURFACE_CONTAINER_HIGHEST
}

/** Builds the complete SDUI tree for the Manga Uploader plugin. */
object MangaUploaderRenderer {

    fun render(m: RenderModel): UiNodeTree = uiTree(version = 1) {
        scaffold(
            id = Ids.ROOT,
            modifier = UiModifier.fillMaxSize()
                .background(SemanticColor.SURFACE)
                .shortcut(key = "S", action = Ids.ACT_SAVE_DRAFT, ctrl = true, allowWhileTyping = true, label = "Ctrl+S")
                .shortcut(key = UiShortcut.KEY_ESCAPE, action = Ids.ACT_DISMISS, allowWhileTyping = true, label = "Esc")
                .shortcut(key = UiShortcut.KEY_DELETE, action = Ids.ACT_DELETE_PAGE, label = "Del")
        ) {
            topBar { topBarContent(m) }
            content {
                column(id = "content_root", spacing = SpacingSize.NONE, modifier = UiModifier.fillMaxSize()) {
                    when (m.state.view) {
                        MainView.WORKSPACE -> workspace(m)
                        MainView.SETTINGS -> settingsView(m)
                        MainView.QUEUE -> queueView(m)
                    }
                    dialogs(m)
                }
            }
        }
    }

    // ───────────────────────── top bar ─────────────────────────

    private fun UiContainerBuilder.topBarContent(m: RenderModel) {
        row(
            id = "top_bar", spacing = SpacingSize.MEDIUM_SMALL,
            modifier = UiModifier.fillMaxWidth().padding(SpacingSize.SMALL).background(SemanticColor.SURFACE_CONTAINER)
        ) {
            text("Manga Uploader", id = "brand_title", role = TypographyRole.TITLE_MEDIUM)
            val activeJobList = m.jobs.filter { !it.isTerminal }
            val activeJobs = activeJobList.size
            val mdUploadBusy = m.jobs.any { it.capabilityName == "publishToMangaDex" && !it.isTerminal }
            val queueTooltip = if (activeJobList.isEmpty()) {
                "Monitor background jobs, byte/speed/ETA progress, and logs"
            } else {
                val summary = activeJobList.take(2).joinToString("; ") { j ->
                    val p = j.primaryProgress
                    val pct = p?.fraction?.let { "${(it * 100).toInt()}%" } ?: p?.message ?: j.status.name
                    "${j.capabilityName} ($pct)"
                }
                val more = if (activeJobList.size > 2) " +${activeJobList.size - 2} more" else ""
                "Active ($activeJobs): $summary$more — click to open Queue sliders & logs"
            }
            buttonGroup(id = Ids.NAV_VIEWS) {
                MainView.entries.forEach { view ->
                    val label = if (view == MainView.QUEUE && activeJobs > 0) "${view.label} ($activeJobs)" else view.label
                    val tip = when (view) {
                        MainView.WORKSPACE -> "Browse series, edit chapter metadata & pages, and push/sync"
                        MainView.SETTINGS -> "Configure Cubari/GitHub, Aruba FTP/SFTP, MangaDex OAuth, and upload limits"
                        MainView.QUEUE -> queueTooltip
                    }
                    item(key = view.key, label = label, action = Ids.ACT_VIEW + view.key, selected = view == m.state.view, tooltip = tip)
                }
            }
            // Dotless, colour-coded readiness group: clicking jumps to the matching settings card.
            buttonGroup(id = Ids.NAV_TARGETS, selectionMode = false) {
                m.report.targets.forEach {
                    item(
                        key = it.id,
                        label = it.label.substringBefore(' ').substringBefore('/'),
                        action = Ids.ACT_GOTO_TARGET + it.id,
                        color = it.readiness.color(),
                        tooltip = it.message
                    )
                }
            }
            spacer(id = "top_bar_spacer", modifier = UiModifier.weight(1f))
            val checkedCount = UiSelectors.checkedChapterIds(m.state).size
            val hasSeries = m.state.activeSeriesId != null
            button("+ New Chapter", id = "btn_new_chapter", action = Ids.ACT_NEW_CHAPTER, isTonal = true, isPrimary = false, iconName = "add")
            splitButton(
                label = if (checkedCount > 0) "Push Checked ($checkedCount)" else "Bulk Push (0)",
                primaryAction = Ids.ACT_PUSH_SELECTED, id = Ids.SPLIT_PUSH,
                iconName = "publish", menuTooltip = "Bulk actions for checked chapters"
            ) {
                item("push_cubari", "Push Checked ($checkedCount) -> Cubari", Ids.ACT_PUSH_CUBARI, "cloud_upload", enabled = checkedCount > 0 && m.isUnlocked(LockKeys.STAGE1))
                item(
                    "push_mangadex",
                    if (mdUploadBusy) "Push Checked -> MangaDex (Uploading...)" else "Push Checked ($checkedCount) -> MangaDex",
                    Ids.ACT_PUSH_MANGADEX, "upload",
                    enabled = checkedCount > 0 && !mdUploadBusy && m.isUnlocked(LockKeys.STAGE2)
                )
                item("pull_missing", "Pull Checked ($checkedCount) <- MangaDex", Ids.ACT_PULL_MISSING, "cloud_download", enabled = checkedCount > 0 && m.isUnlocked(LockKeys.SYNC), startsSection = true)
                item("backfill_all", "Backfill All Missing <- MangaDex...", Ids.ACT_BACKFILL_ALL, "sync", enabled = hasSeries && m.isUnlocked(LockKeys.SYNC))
                item("resync_cubari", "Resync Cubari Repo", Ids.ACT_SYNC_REPO, "sync", enabled = m.isUnlocked(LockKeys.CUBARI), startsSection = true)
                item("remove_cubari", "Remove Checked ($checkedCount) from Cubari", Ids.ACT_REMOVE_CUBARI, "delete", isDestructive = true, enabled = checkedCount > 0 && m.isUnlocked(LockKeys.STAGE1), startsSection = true)
            }
        }
    }

    // ───────────────────────── workspace ─────────────────────────

    private fun UiContainerBuilder.workspace(m: RenderModel) {
        splitPane(
            id = "ws_split_outer", splitFraction = 0.24f, modifier = UiModifier.fillMaxSize().weight(1f),
            first = { seriesColumn(m) },
            second = {
                splitPane(
                    id = "ws_split_inner", splitFraction = 0.46f, modifier = UiModifier.fillMaxSize(),
                    first = { chaptersColumn(m) },
                    second = { inspector(m) }
                )
            }
        )
    }

    private fun seriesCover(series: Series): AssetReference? = when {
        series.coverPath != null -> AssetReference.Relative(series.coverPath)
        !series.coverUrl.isNullOrBlank() -> AssetReference.Uri(series.coverUrl)
        else -> null
    }

    private fun UiContainerBuilder.seriesColumn(m: RenderModel) {
        val s = m.state
        column(
            id = "col_series", spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxSize().padding(SpacingSize.SMALL).background(SemanticColor.SURFACE_CONTAINER_LOW)
        ) {
            val syncing = m.jobs.any {
                it.capabilityName == "syncCubariGitRepo" &&
                    (it.status == CapabilityJobStatus.RUNNING || it.status == CapabilityJobStatus.QUEUED)
            }
            row(id = "series_header_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                text("Series (${s.library.series.size})", id = "series_title", role = TypographyRole.TITLE_MEDIUM)
                if (syncing) badge("Syncing...", id = "badge_syncing", color = SemanticColor.INFO_CONTAINER)
                spacer(id = "series_header_spacer", modifier = UiModifier.weight(1f))
                iconButton(
                    "sync", id = "btn_sync_repo_ws", action = Ids.ACT_SYNC_REPO,
                    tooltip = "Resync Cubari Git repository and Aruba series folders",
                    modifier = UiModifier.enabled(m.isUnlocked(LockKeys.CUBARI))
                )
            }
            textInput(id = Ids.SERIES_SEARCH, text = s.seriesSearch, label = "Search series", modifier = UiModifier.fillMaxWidth())
            row(id = "series_sort_filter_row", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth()) {
                dropdown(
                    id = Ids.SERIES_SORT, selectedKey = s.seriesSort.key,
                    options = SeriesSort.entries.associate { it.key to it.label }, label = "Sort",
                    modifier = UiModifier.weight(1f)
                )
                dropdown(
                    id = Ids.SERIES_FILTER, selectedKey = s.seriesFilter.key,
                    options = SeriesFilter.entries.associate { it.key to it.label }, label = "Filter",
                    modifier = UiModifier.weight(1f)
                )
            }
            val visible = UiSelectors.visibleSeries(s)
            if (visible.isEmpty()) {
                emptyState(
                    id = "series_empty", title = "No series", iconName = "book",
                    description = if (s.library.series.isEmpty()) "Create one with + New" else "No series match the current filter"
                )
            }
            scrollableColumn(
                id = Ids.SERIES_LIST, spacing = SpacingSize.EXTRA_SMALL,
                reorderable = s.seriesSort == SeriesSort.CUSTOM && s.seriesFilter == SeriesFilter.ALL && s.seriesSearch.isBlank(),
                modifier = UiModifier.fillMaxWidth().weight(1f)
            ) {
                visible.forEach { series -> seriesRow(m, series) }
            }
            row(id = "series_footer", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth()) {
                button("+ New", id = "btn_series_new", action = Ids.ACT_SERIES_NEW, isTonal = true, isPrimary = false, iconName = "add")
                button("Edit", id = "btn_series_edit", action = Ids.ACT_SERIES_EDIT, isTonal = true, isPrimary = false, iconName = "edit",
                    modifier = UiModifier.enabled(s.activeSeriesId != null))
                button("Delete", id = "btn_series_delete", action = Ids.ACT_SERIES_DELETE, isTonal = true, isPrimary = false, isDestructive = true,
                    iconName = "delete", modifier = UiModifier.enabled(s.activeSeriesId != null))
            }
        }
    }

    private fun UiContainerBuilder.seriesRow(m: RenderModel, series: Series) {
        val library = m.state.library
        val missing = library.missingCubariCount(series.id)
        val chapters = library.chaptersOf(series.id)
        listItem(
            id = Ids.SERIES_PREFIX + series.id,
            title = series.title.ifBlank { "Untitled" },
            subtitle = "${chapters.size} chapters · updated ${formatTimestamp(series.lastUpdatedEpochSec)}",
            leadingAsset = seriesCover(series),
            leadingIcon = if (seriesCover(series) == null) "book" else null,
            selected = series.id == m.state.activeSeriesId,
            action = Ids.ACT_SELECT_SERIES + series.id
        ) {
            if (chapters.any { it.isOnCubari } || series.lastUpdatedEpochSec != null) {
                badge("Cubari", id = "badge_cubari_${series.id}", color = SemanticColor.SUCCESS_CONTAINER)
            }
            if (!series.mangadexId.isNullOrBlank()) badge("MD", id = "badge_md_${series.id}", color = SemanticColor.INFO_CONTAINER)
            if (missing > 0) badge("$missing missing", id = "badge_missing_${series.id}", color = SemanticColor.WARNING_CONTAINER)
            if (series.overridePreviewHours != null) {
                badge("${series.overridePreviewHours}h", id = "badge_hold_${series.id}", color = SemanticColor.TERTIARY_CONTAINER)
            }
        }
    }

    private fun UiContainerBuilder.chaptersColumn(m: RenderModel) {
        val s = m.state
        val series = s.library.seriesById(s.activeSeriesId)
        column(
            id = "col_chapters", spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxSize().padding(SpacingSize.SMALL).background(SemanticColor.SURFACE_CONTAINER_LOW)
        ) {
            text(series?.title?.let { "Chapters — $it" } ?: "Chapters", id = "chapters_title", role = TypographyRole.TITLE_MEDIUM)
            textInput(id = Ids.CHAPTER_SEARCH, text = s.chapterSearch, label = "Search chapters", modifier = UiModifier.fillMaxWidth())
            dropdown(
                id = Ids.CHAPTER_FILTER, selectedKey = s.chapterFilter.key,
                options = ChapterFilter.entries.associate { it.key to it.label }, label = "Filter",
                modifier = UiModifier.fillMaxWidth()
            )
            // Bulk actions bar
            val checkedCount = UiSelectors.checkedChapterIds(s).size
            val missingCount = series?.id?.let { s.library.missingCubariCount(it) } ?: 0
            row(id = "chapters_bulk_bar", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth().background(SemanticColor.SURFACE_CONTAINER_HIGH).padding(SpacingSize.EXTRA_SMALL)) {
                text("$checkedCount checked", id = "selection_count", role = TypographyRole.LABEL)
                spacer(id = "bulk_spacer", modifier = UiModifier.weight(1f))
                button("All", id = "btn_select_all", action = Ids.ACT_SELECT_ALL, isTonal = true, isPrimary = false)
                button("Clear", id = "btn_clear_selection", action = Ids.ACT_CLEAR_SELECTION, isTonal = true, isPrimary = false,
                    modifier = UiModifier.enabled(s.checkedChapterIds.isNotEmpty()))
                iconButton("refresh", id = "btn_scan_md", action = Ids.ACT_SCAN_MANGADEX, tooltip = "Scan MangaDex for missing chapters")
                button("Pull Checked ($checkedCount)", id = "btn_pull_missing", action = Ids.ACT_PULL_MISSING, isTonal = true, isPrimary = false,
                    iconName = "cloud_download", modifier = UiModifier.enabled(checkedCount > 0 && m.isUnlocked(LockKeys.SYNC)))
                button("Backfill Missing ($missingCount)", id = "btn_backfill_all", action = Ids.ACT_BACKFILL_ALL, isTonal = true, isPrimary = false,
                    iconName = "sync", modifier = UiModifier.enabled(series != null && m.isUnlocked(LockKeys.SYNC)))
            }
            val chapters = UiSelectors.visibleChapters(s)
            if (s.activeSeriesId == null) {
                emptyState(id = "chapters_no_series", title = "Select a series", iconName = "book")
            } else if (chapters.isEmpty()) {
                emptyState(id = "chapters_empty", title = "No chapters", iconName = "chapter", description = "Use + New Chapter or Pull Missing")
            }
            scrollableColumn(id = "chapter_list", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth().weight(1f)) {
                chapters.forEach { chapterRow(m, series, it) }
            }
        }
    }

    private fun UiContainerBuilder.chapterRow(m: RenderModel, series: Series?, chapter: Chapter) {
        val siblings = m.state.library.chaptersOf(chapter.seriesId)
        val hold = PreviewTiming.evaluate(series, chapter, siblings, m.config, m.nowEpochSec)
        val cubariTs = chapter.effectiveCubariTimestampSec
        val mdTs = chapter.effectiveMangaDexTimestampSec
        val updateStatus = chapter.updateStatus()
        val subtitle = buildList {
            if (chapter.volume.isNotBlank()) add("Vol. ${chapter.volume}")
            add("${chapter.pages.size.takeIf { it > 0 } ?: chapter.cubariPageUrls.size} pages")
            if (cubariTs != null) add("Cubari ${formatTimestamp(cubariTs)}")
            if (mdTs != null) add("MD ${formatTimestamp(mdTs)}")
            if (cubariTs == null && mdTs == null) {
                chapter.createdAtEpochSec.takeIf { it > 0 }?.let { add("Draft ${formatTimestamp(it)}") }
            }
        }.joinToString(" · ")
        val cubariPlan = ChangeDetector.planCubariPush(series, chapter, includeSeriesMeta = false)
        val mdPlan = ChangeDetector.planMangaDexPush(chapter, m.config.mdLanguage)
        val hasLocalEdit = (chapter.isOnCubari && cubariPlan.kind != TargetPushKind.UP_TO_DATE) ||
            (chapter.isOnMangaDex && mdPlan.kind != TargetPushKind.UP_TO_DATE)
        listItem(
            id = "chapter_${chapter.id}",
            title = "Ch. ${chapter.number.ifBlank { "?" }}" + chapter.title.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
            subtitle = subtitle,
            selected = chapter.id == m.state.activeChapterId,
            action = Ids.ACT_OPEN_CHAPTER + chapter.id
        ) {
            badge(
                if (chapter.isOnCubari) "Cubari" else "Cubari ✕", id = "pill_cubari_${chapter.id}",
                color = if (chapter.isOnCubari) SemanticColor.SUCCESS_CONTAINER else SemanticColor.SURFACE_CONTAINER_HIGHEST
            )
            badge(
                if (chapter.isOnMangaDex) "MD" else "MD ✕", id = "pill_md_${chapter.id}",
                color = if (chapter.isOnMangaDex) SemanticColor.INFO_CONTAINER else SemanticColor.SURFACE_CONTAINER_HIGHEST
            )
            when (updateStatus) {
                PlatformUpdateStatus.CUBARI_NEWER ->
                    badge("Cubari newer", id = "pill_newer_${chapter.id}", color = SemanticColor.PRIMARY_CONTAINER)
                PlatformUpdateStatus.MANGADEX_NEWER ->
                    badge("MD newer", id = "pill_newer_${chapter.id}", color = SemanticColor.SECONDARY_CONTAINER)
                PlatformUpdateStatus.IN_SYNC, PlatformUpdateStatus.SINGLE_PLATFORM -> Unit
            }
            if (hasLocalEdit) {
                badge("Local edit", id = "pill_local_${chapter.id}", color = SemanticColor.WARNING_CONTAINER)
            }
            if (chapter.syncState == ChapterSyncState.MISSING_CUBARI) {
                badge("Missing Cubari", id = "pill_missing_${chapter.id}", color = SemanticColor.WARNING_CONTAINER)
            }
            if (hold.state == HoldState.HOLDING) {
                badge("Hold ${PreviewTiming.formatRemaining(hold.remainingSec)}", id = "pill_hold_${chapter.id}", color = SemanticColor.TERTIARY_CONTAINER)
            }
            checkbox(checked = chapter.id in m.state.checkedChapterIds, id = Ids.CHAPTER_CHECK_PREFIX + chapter.id)
        }
    }

    // ───────────────────────── inspector ─────────────────────────

    private fun UiContainerBuilder.inspector(m: RenderModel) {
        val s = m.state
        when {
            s.inspector == InspectorMode.SERIES_EDIT && s.seriesDraft != null -> seriesEditor(m, s.seriesDraft)
            s.chapterDraft != null -> chapterInspector(m, s.chapterDraft)
            else -> column(id = "col_inspector_empty", modifier = UiModifier.fillMaxSize().background(SemanticColor.SURFACE_CONTAINER_LOW)) {
                emptyState(id = "inspector_empty", title = "Nothing selected", iconName = "info", description = "Open a chapter or edit a series")
            }
        }
    }

    private fun UiContainerBuilder.chapterInspector(m: RenderModel, draft: ChapterDraft) {
        val s = m.state
        val chapter = s.library.chapterById(draft.chapterId)
        val series = s.library.seriesById(chapter?.seriesId)
        val selectedSource = chapter?.let { s.remoteSourceByChapter[it.id] ?: UiSelectors.defaultRemoteSource(it, series) }
            ?: UploaderWorkers.SOURCE_CUBARI
        scrollableColumn(
            id = "col_inspector", spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxSize().padding(SpacingSize.SMALL).background(SemanticColor.SURFACE_CONTAINER_LOW)
        ) {
            card(id = "card_chapter_meta", title = "Chapter", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
                column(id = "chapter_meta_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.SMALL)) {
                    dropdown(
                        id = Ids.REMOTE_SOURCE,
                        selectedKey = selectedSource,
                        options = linkedMapOf(
                            UploaderWorkers.SOURCE_CUBARI to "Cubari / Aruba",
                            UploaderWorkers.SOURCE_MANGADEX to "MangaDex"
                        ),
                        label = "Remote Source (Metadata & Pages)",
                        modifier = UiModifier.fillMaxWidth()
                    )
                    row(id = "chapter_meta_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                        textInput(id = Ids.CH_VOLUME, text = draft.volume, label = "Volume", revision = draft.revision, modifier = UiModifier.weight(1f))
                        textInput(id = Ids.CH_NUMBER, text = draft.number, label = "Number", revision = draft.revision, modifier = UiModifier.weight(1f))
                    }
                    textInput(id = Ids.CH_TITLE, text = draft.title, label = "Chapter Title", revision = draft.revision, modifier = UiModifier.fillMaxWidth())
                    row(id = "chapter_save_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                        button("Save draft", id = "btn_save_draft", action = Ids.ACT_SAVE_DRAFT, isTonal = true, isPrimary = false, iconName = "save")
                        if (draft.dirty) badge("Unsaved", id = "badge_unsaved", color = SemanticColor.WARNING_CONTAINER)
                    }
                    val cubariChUrl = UploaderLinks.cubariChapterUrl(m.config, series, chapter)
                    val mdChUrl = UploaderLinks.mangadexChapterUrl(chapter)
                    buttonGroup(id = "chapter_links_group", selectionMode = false, modifier = UiModifier.fillMaxWidth()) {
                        item(
                            key = "open_cubari_ch", label = "Open Cubari", action = Ids.ACT_OPEN_CUBARI_CHAPTER,
                            iconName = "open_in_browser", enabled = cubariChUrl != null,
                            tooltip = "Open this chapter in the Cubari reader"
                        )
                        item(
                            key = "copy_cubari_ch", label = "Copy Cubari URL", action = Ids.ACT_COPY_CUBARI_CHAPTER,
                            iconName = "content_copy", enabled = cubariChUrl != null,
                            tooltip = "Copy Cubari chapter reader link to clipboard"
                        )
                        item(
                            key = "open_md_ch", label = "Open MangaDex", action = Ids.ACT_OPEN_MANGADEX_CHAPTER,
                            iconName = "open_in_browser", enabled = mdChUrl != null,
                            tooltip = "Open this chapter on MangaDex in your browser"
                        )
                        item(
                            key = "copy_md_ch", label = "Copy MD URL", action = Ids.ACT_COPY_MANGADEX_CHAPTER,
                            iconName = "content_copy", enabled = mdChUrl != null,
                            tooltip = "Copy MangaDex chapter link to clipboard"
                        )
                    }
                    text("Edits stay local until you push", id = "local_hint", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                    if (chapter != null) {
                        platformTimestampsBar(chapter)
                        remoteSourceMetadataBar(m, chapter, draft, selectedSource)
                        chapterLocalChangesBanner(m, series, chapter, draft)
                    }
                }
            }
            if (chapter != null) holdCard(m, series, chapter)
            targetActions(m, series, chapter, draft)
            pagesSection(m, draft)
        }
    }

    private fun UiContainerBuilder.remoteSourceMetadataBar(
        m: RenderModel,
        chapter: Chapter,
        draft: ChapterDraft,
        selectedSource: String
    ) {
        val isMd = selectedSource == UploaderWorkers.SOURCE_MANGADEX
        val summary: String
        val hasSnapshot: Boolean
        if (isMd) {
            val snap = chapter.mdSnapshot
            hasSnapshot = snap != null
            summary = if (snap == null) {
                "MangaDex remote metadata: not synced yet (run Scan MangaDex)"
            } else {
                val pagesTxt = snap.pageCount?.let { " · $it pages" }.orEmpty()
                "MangaDex remote: Ch. ${snap.number.ifBlank { "?" }} · Vol. ${snap.volume.ifBlank { "-" }} · \"${snap.title.ifBlank { "(untitled)" }}\" · ${snap.language}$pagesTxt"
            }
        } else {
            val snap = chapter.cubariSnapshot
            hasSnapshot = snap != null
            summary = if (snap == null) {
                "Cubari remote metadata: not published yet"
            } else {
                "Cubari remote: Ch. ${snap.key} · Vol. ${snap.volume.ifBlank { "-" }} · \"${snap.title.ifBlank { "(untitled)" }}\" · ${snap.pageUrls.size} pages"
            }
        }
        row(
            id = "chapter_remote_meta_row",
            spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxWidth().background(SemanticColor.SURFACE_CONTAINER).padding(SpacingSize.EXTRA_SMALL)
        ) {
            text(summary, id = "ch_remote_meta_summary", role = TypographyRole.BODY_SMALL, modifier = UiModifier.weight(1f))
            if (hasSnapshot) {
                val btnLabel = if (isMd) "Load Meta <- MD" else "Load Meta <- Cubari"
                val tip = if (isMd) "Copy MangaDex remote title, volume, and number into the editable draft" else "Copy Cubari remote title, volume, and number into the editable draft"
                iconButton("cloud_download", id = "btn_load_remote_meta", action = Ids.ACT_LOAD_REMOTE_META, tooltip = tip)
                text(btnLabel, id = "lbl_load_remote_meta", role = TypographyRole.LABEL, color = SemanticColor.PRIMARY)
            }
        }
        if (isMd && chapter.isOnCubari && chapter.isOnMangaDex) {
            val mdSnap = chapter.mdSnapshot
            val cubSnap = chapter.cubariSnapshot
            if (mdSnap != null && cubSnap != null) {
                val crossDiffs = buildList {
                    if (normalizeChapterKey(cubSnap.key) != normalizeChapterKey(mdSnap.number)) add("number")
                    if (cubSnap.volume.trim() != mdSnap.volume.trim()) add("volume")
                    if (cubSnap.title.trim() != mdSnap.title.trim()) add("title")
                    if (mdSnap.pageCount != null && cubSnap.pageUrls.isNotEmpty() && cubSnap.pageUrls.size != mdSnap.pageCount) add("page count")
                }
                if (crossDiffs.isNotEmpty()) {
                    text(
                        "Cubari vs MangaDex differ on: ${crossDiffs.joinToString()} — Push/Pull syncs both metadata & pages.",
                        id = "ch_cross_platform_diff",
                        role = TypographyRole.BODY_SMALL,
                        color = SemanticColor.TERTIARY
                    )
                }
            }
        }
        val _unused = m.config.mdLanguage + draft.chapterId // keep clean signature symmetry
    }

    private fun UiContainerBuilder.chapterLocalChangesBanner(m: RenderModel, series: Series?, chapter: Chapter, draft: ChapterDraft) {
        val effective = UiSelectors.effectiveChapter(chapter, draft)
        val cubariPlan = ChangeDetector.planCubariPush(series, chapter, effective, includeSeriesMeta = false)
        val mdPlan = ChangeDetector.planMangaDexPush(chapter, m.config.mdLanguage, effective)
        val notes = buildList {
            if (chapter.isOnCubari && cubariPlan.kind != TargetPushKind.UP_TO_DATE) {
                add("Cubari: ${cubariPlan.changedFields.joinToString().ifBlank { "pending push" }}")
            }
            if (chapter.isOnMangaDex && mdPlan.kind != TargetPushKind.UP_TO_DATE) {
                add("MD: ${mdPlan.changedFields.joinToString().ifBlank { "pending push" }}")
            }
        }
        if (notes.isEmpty()) return
        alertBanner(
            id = "chapter_local_changes_banner",
            message = "Local-only changes (not on remote): ${notes.joinToString(" | ")}",
            severity = SemanticColor.WARNING,
            iconName = "warning"
        )
        if (chapter.cubariSnapshot != null && cubariPlan.kind != TargetPushKind.UP_TO_DATE) {
            button(
                "Revert to Cubari", id = "btn_revert_ch_cubari",
                action = Ids.ACT_REVERT_CHAPTER_CUBARI, iconName = "undo", isTonal = true, isPrimary = false
            )
        }
    }

    private fun UiContainerBuilder.platformTimestampsBar(chapter: Chapter) {
        val cubariLabel = chapter.effectiveCubariTimestampSec?.let { "${formatTimestamp(it)} UTC" } ?: "Not published"
        val mdVersionSuffix = chapter.mangadexVersion?.let { " (v$it)" }.orEmpty()
        val mdLabel = chapter.effectiveMangaDexTimestampSec?.let { "${formatTimestamp(it)} UTC$mdVersionSuffix" } ?: "Not published"
        row(
            id = "chapter_platform_timestamps",
            spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxWidth().background(SemanticColor.SURFACE_CONTAINER_HIGHEST).padding(SpacingSize.EXTRA_SMALL)
        ) {
            text("Cubari: $cubariLabel", id = "ch_ts_cubari", role = TypographyRole.BODY_SMALL, modifier = UiModifier.weight(1f))
            text("MangaDex: $mdLabel", id = "ch_ts_mangadex", role = TypographyRole.BODY_SMALL, modifier = UiModifier.weight(1f))
            when (chapter.updateStatus()) {
                PlatformUpdateStatus.CUBARI_NEWER -> badge("Cubari newer", id = "ch_ts_status", color = SemanticColor.PRIMARY_CONTAINER)
                PlatformUpdateStatus.MANGADEX_NEWER -> badge("MD newer", id = "ch_ts_status", color = SemanticColor.SECONDARY_CONTAINER)
                PlatformUpdateStatus.IN_SYNC -> badge("In sync", id = "ch_ts_status", color = SemanticColor.SUCCESS_CONTAINER)
                PlatformUpdateStatus.SINGLE_PLATFORM -> Unit
            }
        }
    }

    private fun UiContainerBuilder.holdCard(m: RenderModel, series: Series?, chapter: Chapter) {
        val siblings = m.state.library.chaptersOf(chapter.seriesId)
        val status = PreviewTiming.evaluate(series, chapter, siblings, m.config, m.nowEpochSec)
        val anchor = if (status.policy.anchor == PreviewAnchor.LATEST_MANGADEX) "latest MangaDex chapter" else "Cubari publish time"
        val (label, role) = when (status.state) {
            HoldState.NOT_PUBLISHED -> "Not on Cubari yet - no hold running" to SemanticColor.SURFACE_CONTAINER_HIGH
            HoldState.HOLDING -> "Preview hold: ${PreviewTiming.formatRemaining(status.remainingSec)} left (until ${formatTimestamp(status.holdUntilEpochSec)} UTC)" to SemanticColor.TERTIARY_CONTAINER
            HoldState.RELEASABLE -> "Preview window elapsed - ready for MangaDex" to SemanticColor.SUCCESS_CONTAINER
        }
        card(id = "card_hold", surfaceRole = role, modifier = UiModifier.fillMaxWidth()) {
            column(id = "hold_col", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.padding(SpacingSize.SMALL)) {
                text(label, id = "hold_label", role = TypographyRole.LABEL)
                text(
                    "${status.policy.hours}h window" +
                        (if (status.policy.hoursOverridden) " (series override)" else " (global)") +
                        " · anchored on $anchor",
                    id = "hold_policy", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT
                )
            }
        }
    }

    private fun UiContainerBuilder.targetActions(m: RenderModel, series: Series?, chapter: Chapter?, draft: ChapterDraft) {
        val effective = chapter?.let { UiSelectors.effectiveChapter(it, draft) }
        val cubariPlan = if (chapter != null && effective != null) ChangeDetector.planCubariPush(series, chapter, effective, includeSeriesMeta = false) else null
        val mdPlan = if (chapter != null && effective != null) ChangeDetector.planMangaDexPush(chapter, m.config.mdLanguage, effective) else null
        val cubariPushLabel = when (cubariPlan?.kind) {
            TargetPushKind.METADATA_ONLY -> "Push Meta -> Cubari"
            TargetPushKind.PAGES_AND_META -> "Push Pages -> Cubari"
            TargetPushKind.UP_TO_DATE -> "Push Ch. -> Cubari (Synced)"
            TargetPushKind.NEW_PUBLISH, null -> "Push Ch. -> Cubari"
        }
        val mdPushLabel = when (mdPlan?.kind) {
            TargetPushKind.METADATA_ONLY -> "Push Meta -> MD"
            TargetPushKind.PAGES_AND_META -> "Push Pages -> MD"
            TargetPushKind.UP_TO_DATE -> "Push Ch. -> MD (Synced)"
            TargetPushKind.NEW_PUBLISH, null -> "Push Ch. -> MD"
        }
        val chLabel = chapter?.number?.takeIf { it.isNotBlank() }?.let { "Ch. $it" } ?: "Chapter"
        val mdUploadActive = m.jobs.any { it.capabilityName == "publishToMangaDex" && !it.isTerminal }
        val activeMdLabel = if (mdUploadActive) "Uploading to MD..." else mdPushLabel
        column(id = "chapter_actions_box", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth()) {
            text(
                if (mdUploadActive) "Single Chapter Actions ($chLabel only) · MangaDex upload active (1 session max)"
                else "Single Chapter Actions ($chLabel only)",
                id = "ch_actions_header", role = TypographyRole.LABEL, color = SemanticColor.ON_SURFACE_VARIANT
            )
            row(id = "chapter_target_actions", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.fillMaxWidth()) {
                button(cubariPushLabel, id = "btn_push_cubari", action = Ids.ACT_CH_PUSH_CUBARI, iconName = "cloud_upload",
                    modifier = UiModifier.enabled(m.isUnlocked(LockKeys.STAGE1)))
                button(activeMdLabel, id = "btn_push_mangadex", action = Ids.ACT_CH_PUSH_MANGADEX, iconName = "upload", isTonal = true, isPrimary = false,
                    modifier = UiModifier.enabled(!mdUploadActive && m.isUnlocked(LockKeys.STAGE2)))
                button("Pull Ch. <- MD", id = "btn_pull_md", action = Ids.ACT_CH_PULL_MD, iconName = "cloud_download", isTonal = true, isPrimary = false,
                    modifier = UiModifier.enabled(m.isUnlocked(LockKeys.SYNC)))
                button("Remove Ch...", id = "btn_remove", action = Ids.ACT_CH_REMOVE_CUBARI, iconName = "delete", isDestructive = true, isTonal = true, isPrimary = false,
                    modifier = UiModifier.enabled(m.isUnlocked(LockKeys.STAGE1)))
            }
        }
        if (!m.isUnlocked(LockKeys.STAGE1) || !m.isUnlocked(LockKeys.STAGE2)) {
            val reasons = buildList {
                if (!m.isUnlocked(LockKeys.STAGE1)) add("Stage 1 locked (${(m.report.cubari.missing + m.report.aruba.missing).joinToString()})")
                if (!m.isUnlocked(LockKeys.STAGE2)) add("Stage 2 locked (${m.report.mangadex.missing.joinToString()})")
            }
            alertBanner(id = "lock_banner", message = reasons.joinToString(" · "), severity = SemanticColor.WARNING, iconName = "lock")
        }
    }

    private fun UiContainerBuilder.pagesSection(m: RenderModel, draft: ChapterDraft) {
        val s = m.state
        val chapter = s.library.chapterById(draft.chapterId)
        val series = s.library.seriesById(chapter?.seriesId)
        val selectedSource = s.remoteSourceByChapter[draft.chapterId] ?: UiSelectors.defaultRemoteSource(chapter, series)
        val sourceLabel = UiSelectors.remoteSourceLabel(selectedSource)
        val report = s.remotePages["${draft.chapterId}:$selectedSource"]
        val displayPages = UiSelectors.unifiedPages(draft, chapter, report, selectedSource)
        val canQuery = when (selectedSource) {
            UploaderWorkers.SOURCE_MANGADEX -> !chapter?.mangadexChapterId.isNullOrBlank() || !series?.mangadexId.isNullOrBlank()
            else -> chapter?.cubariPageUrls?.isNotEmpty() == true || m.isUnlocked(LockKeys.CUBARI)
        }
        val selectedPage = displayPages.firstOrNull { it.id == s.selectedPageId }
        val localCount = displayPages.count { it.isLocal }
        val remoteCount = displayPages.size - localCount
        val countsNote = when {
            displayPages.isEmpty() -> "No pages"
            localCount > 0 && remoteCount > 0 -> "$localCount local · $remoteCount remote"
            localCount > 0 -> "$localCount local"
            else -> "$remoteCount remote ($sourceLabel)"
        }

        card(id = "card_pages", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
            column(id = "pages_card_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.SMALL)) {
                row(id = "pages_header", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    text("Pages (${displayPages.size})", id = "pages_title", role = TypographyRole.TITLE_MEDIUM)
                    text(countsNote, id = "pages_counts", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                    badge(sourceLabel, id = "pages_source_badge", color = SemanticColor.SURFACE_CONTAINER_HIGHEST)
                    spacer(id = "pages_header_spacer", modifier = UiModifier.weight(1f))
                    buttonGroup(id = "pages_header_actions", selectionMode = false) {
                        item(
                            key = "sort_pages", label = "Sort (1..9)", action = Ids.ACT_SORT_PAGES,
                            iconName = "swap_vert", enabled = displayPages.size > 1,
                            tooltip = "Naturally sort pages by filename (1, 2, ..., 10)"
                        )
                        item(
                            key = "toggle_grid",
                            label = if (s.gridExpanded) "Compact Grid" else "Expand Grid",
                            action = Ids.ACT_TOGGLE_EXPAND_GRID,
                            iconName = if (s.gridExpanded) "collapse" else "expand",
                            enabled = s.gridExpanded || displayPages.size > COMPACT_PAGE_LIMIT,
                            tooltip = if (s.gridExpanded) "Show compact preview grid (first $COMPACT_PAGE_LIMIT pages)" else "Show all pages and enable drag-and-drop reorder"
                        )
                    }
                }

                buttonGroup(id = "pages_toolbar", selectionMode = false, modifier = UiModifier.fillMaxWidth()) {
                    item(
                        key = "check_remote", label = "Check ($sourceLabel)", action = Ids.ACT_CHECK_REMOTE_PAGES,
                        iconName = "search", enabled = canQuery,
                        tooltip = "Verify remote image URLs & availability on $sourceLabel"
                    )
                    item(
                        key = "download_remote", label = "Download All", action = Ids.ACT_LOAD_REMOTE_PAGES,
                        iconName = "cloud_download", enabled = canQuery,
                        tooltip = "Download all remote images from $sourceLabel into the editable local draft"
                    )
                    item(
                        key = "append_pages", label = "+ Append...", action = Ids.ACT_APPEND_PAGES,
                        iconName = "add",
                        tooltip = "Append local page image files to the end of this chapter"
                    )
                    item(
                        key = "replace_pages", label = "Replace All...", action = Ids.ACT_REPLACE_PICK,
                        iconName = "swap_vert",
                        tooltip = "Pick image files to replace all current pages in this chapter"
                    )
                }

                if (report != null) {
                    val severity = when {
                        report.errorMessage != null -> SemanticColor.ERROR
                        report.pages.isEmpty() -> SemanticColor.WARNING
                        report.reachableCount < report.pages.size -> SemanticColor.WARNING
                        else -> SemanticColor.SUCCESS
                    }
                    val statusText = report.errorMessage
                        ?: "$sourceLabel: ${report.pages.size} image(s) · ${report.reachableCount}/${report.pages.size} reachable (checked ${formatTimestamp(report.checkedAtEpochSec)} UTC)"
                    alertBanner(id = "remote_pages_status", message = statusText, severity = severity,
                        iconName = if (severity == SemanticColor.SUCCESS) "check_circle" else "info")
                }

                if (selectedPage != null) {
                    val selIdx = displayPages.indexOfFirst { it.id == selectedPage.id }
                    row(
                        id = "selected_page_bar",
                        spacing = SpacingSize.EXTRA_SMALL,
                        modifier = UiModifier.fillMaxWidth().background(SemanticColor.SURFACE_CONTAINER_HIGHEST).padding(SpacingSize.EXTRA_SMALL)
                    ) {
                        text(
                            "#${selIdx + 1} ${selectedPage.originalName}",
                            id = "sel_page_label", role = TypographyRole.LABEL
                        )
                        spacer(id = "sel_page_spacer", modifier = UiModifier.weight(1f))
                        button("View Full", id = "btn_view_full_page", action = Ids.ACT_VIEW_FULL_PAGE, isTonal = true, isPrimary = false, iconName = "image")
                        if (selectedPage.isRemoteOnly) {
                            button("Download Local", id = "btn_download_single_page", action = Ids.ACT_DOWNLOAD_SINGLE_PAGE, isTonal = true, isPrimary = false, iconName = "cloud_download")
                        }
                        button("Replace Page...", id = "btn_replace_single_page", action = Ids.ACT_REPLACE_SINGLE_PAGE, isTonal = true, isPrimary = false, iconName = "swap_vert")
                        button("Delete", id = "btn_delete_page", action = Ids.ACT_DELETE_PAGE, isTonal = true, isPrimary = false, isDestructive = true, iconName = "trash")
                    }
                }

                val statusByUrl = report?.pages?.associateBy { it.url }.orEmpty()
                val compactMode = !s.gridExpanded && displayPages.size > COMPACT_PAGE_LIMIT
                val visiblePages = if (compactMode) displayPages.take(COMPACT_PAGE_LIMIT) else displayPages

                column(
                    id = "pages_pane", spacing = SpacingSize.SMALL,
                    modifier = UiModifier.fillMaxWidth().onFileDrop(
                        action = Ids.ACT_DROP_PAGES, allowedExtensions = PageImporter.IMAGE_EXTENSIONS,
                        acceptDirectories = true, maxFiles = 500, hint = "Drop page images or a folder to append"
                    )
                ) {
                    if (displayPages.isEmpty()) {
                        emptyState(
                            id = "pages_empty", title = "No pages", iconName = "image",
                            description = "Drop images/folder here, append files, or Check/Download from $sourceLabel"
                        )
                    } else {
                        grid(
                            id = Ids.PAGE_GRID,
                            minItemWidthDp = 132f,
                            spacing = SpacingSize.SMALL,
                            reorderable = !compactMode,
                            modifier = UiModifier.fillMaxWidth()
                        ) {
                            visiblePages.forEachIndexed { index, page ->
                                val asset = if (page.isLocal) AssetReference.Relative(page.storedPath)
                                else AssetReference.Uri(page.remoteUrl.orEmpty())
                                val remoteStatus = page.remoteUrl?.let { statusByUrl[it] } ?: report?.pages?.getOrNull(index)
                                val subLabel = when {
                                    page.isLocal -> "${page.originalName} · Local"
                                    remoteStatus != null -> "${page.originalName} · ${remoteStatus.statusLabel}"
                                    else -> "${page.originalName} · Remote"
                                }
                                val labelColor = if (!page.isLocal && remoteStatus != null && !remoteStatus.isReachable) {
                                    SemanticColor.ERROR
                                } else {
                                    SemanticColor.ON_SURFACE_VARIANT
                                }
                                card(id = page.id, modifier = UiModifier.fillMaxWidth()) {
                                    column(id = "${page.id}_col", spacing = SpacingSize.EXTRA_SMALL, modifier = UiModifier.padding(SpacingSize.EXTRA_SMALL)) {
                                        image(
                                            asset = asset,
                                            id = "${page.id}_img",
                                            contentDescription = page.originalName,
                                            contentScale = ImageScale.CROP,
                                            clickable = true,
                                            action = Ids.ACT_SELECT_PAGE + page.id,
                                            selected = page.id == s.selectedPageId,
                                            badgeText = "#${index + 1}",
                                            modifier = UiModifier.fillMaxWidth().aspectRatio(0.72f)
                                        )
                                        text(subLabel, id = "${page.id}_lbl", role = TypographyRole.BODY_SMALL, color = labelColor)
                                    }
                                }
                            }
                        }
                        if (compactMode) {
                            button(
                                "Show all ${displayPages.size} pages (enable drag reorder)",
                                id = "btn_expand_all_pages",
                                action = Ids.ACT_TOGGLE_EXPAND_GRID,
                                isTonal = true,
                                isPrimary = false,
                                iconName = "expand",
                                modifier = UiModifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
        card(
            id = "replace_drop_zone", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGHEST,
            modifier = UiModifier.fillMaxWidth().onFileDrop(
                action = Ids.ACT_REPLACE_PAGES, allowedExtensions = PageImporter.IMAGE_EXTENSIONS,
                acceptDirectories = true, maxFiles = 500, hint = "Drop to REPLACE all pages"
            )
        ) {
            text("Drop files here to replace all pages", id = "replace_drop_hint", role = TypographyRole.BODY_SMALL,
                color = SemanticColor.ON_SURFACE_VARIANT, modifier = UiModifier.padding(SpacingSize.MEDIUM))
        }
    }

    private fun UiContainerBuilder.seriesEditor(m: RenderModel, draft: SeriesDraft) {
        val series = draft.series
        val previewSlug = series.cubariSlug.ifBlank { sanitizeFolderName(series.title.ifBlank { series.id }) }
        val inferredUrlFolder = ArubaPaths.inferSeriesFolderFromUrls(
            m.config.arubaPublicBaseUrl,
            m.state.library.chaptersOf(series.id).flatMap { it.cubariPageUrls }
        )
        val folderOptions = linkedMapOf("" to "Auto (${sanitizeFolderName(series.title.ifBlank { previewSlug })})")
        if (!inferredUrlFolder.isNullOrBlank()) {
            folderOptions[inferredUrlFolder] = "$inferredUrlFolder (matched from Cubari URLs)"
        }
        m.state.arubaFolders.forEach { folder ->
            if (folder !in folderOptions) folderOptions[folder] = folder
        }
        val selectedFolderKey = when {
            series.arubaFolder.isBlank() -> ""
            series.arubaFolder in folderOptions -> series.arubaFolder
            else -> "__custom__"
        }
        if (selectedFolderKey == "__custom__") {
            folderOptions["__custom__"] = "Custom: ${series.arubaFolder}"
        }
        val cubariLink = UploaderLinks.cubariSeriesUrl(m.config, series.copy(cubariSlug = previewSlug))
        val mdLink = UploaderLinks.mangadexSeriesUrl(series)

        scrollableColumn(
            id = "col_series_editor", spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxSize().padding(SpacingSize.SMALL).background(SemanticColor.SURFACE_CONTAINER_LOW)
        ) {
            text(if (draft.isNew) "New series" else "Edit series", id = "series_editor_title", role = TypographyRole.TITLE_MEDIUM)
            card(id = "card_series_meta", title = "Details", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
                column(id = "series_meta_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.SMALL)) {
                    textInput(id = Ids.SD_TITLE, text = series.title, label = "Title", revision = draft.revision, modifier = UiModifier.fillMaxWidth())
                    textInput(id = Ids.SD_DESCRIPTION, text = series.description, label = "Description", singleLine = false, revision = draft.revision, modifier = UiModifier.fillMaxWidth())
                    row(id = "series_people_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                        textInput(id = Ids.SD_AUTHOR, text = series.author, label = "Author", revision = draft.revision, modifier = UiModifier.weight(1f))
                        textInput(id = Ids.SD_ARTIST, text = series.artist, label = "Artist", revision = draft.revision, modifier = UiModifier.weight(1f))
                    }
                    dropdown(id = Ids.SD_STATUS, selectedKey = series.status, options = SERIES_STATUSES, label = "Status", modifier = UiModifier.fillMaxWidth())
                    textInput(id = Ids.SD_MANGADEX, text = series.mangadexId.orEmpty(), label = "MangaDex manga ID or URL", revision = draft.revision, modifier = UiModifier.fillMaxWidth())
                    row(id = "series_aruba_picker_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                        dropdown(
                            id = Ids.SD_ARUBA_FOLDER_SELECT,
                            selectedKey = selectedFolderKey,
                            options = folderOptions,
                            label = "Detected Aruba folder",
                            modifier = UiModifier.weight(1f)
                        )
                        button(
                            "Scan Aruba", id = "btn_scan_aruba_series",
                            action = Ids.ACT_SCAN_ARUBA_FOLDERS,
                            isTonal = true, isPrimary = false, iconName = "folder_open",
                            modifier = UiModifier.enabled(m.config.arubaHost.isNotBlank())
                        )
                    }
                    textInput(
                        id = Ids.SD_ARUBA_FOLDER,
                        text = series.arubaFolder,
                        label = "Aruba folder override (empty = Title)",
                        placeholder = series.effectiveArubaFolder,
                        revision = draft.revision,
                        modifier = UiModifier.fillMaxWidth()
                    )
                    textInput(
                        id = Ids.SD_CUBARI_SLUG,
                        text = series.cubariSlug,
                        label = "Cubari JSON filename (without .json)",
                        placeholder = previewSlug,
                        revision = draft.revision,
                        modifier = UiModifier.fillMaxWidth()
                    )
                    dropdown(
                        id = Ids.SD_PAGE_PADDING,
                        selectedKey = series.arubaZeroPadDigits?.toString() ?: "inherit",
                        options = linkedMapOf(
                            "inherit" to "Inherit global (${m.config.arubaPagePadding})",
                            "0" to "Unpadded (1.png, 2.png)",
                            "2" to "Zero-padded 2 digits (01.png)",
                            "3" to "Zero-padded 3 digits (001.png)",
                            "4" to "Zero-padded 4 digits (0001.png)"
                        ),
                        label = "Aruba page numbering",
                        modifier = UiModifier.fillMaxWidth()
                    )
                    if (cubariLink != null) {
                        text("Cubari URL: $cubariLink", id = "series_cubari_url", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                    }
                    buttonGroup(id = "series_links_group", selectionMode = false, modifier = UiModifier.fillMaxWidth()) {
                        item(
                            key = "open_cubari_series", label = "Open on Cubari", action = Ids.ACT_OPEN_CUBARI_SERIES,
                            iconName = "open_in_browser", enabled = cubariLink != null,
                            tooltip = "Open this series in the Cubari reader"
                        )
                        item(
                            key = "copy_cubari_series", label = "Copy Cubari URL", action = Ids.ACT_COPY_CUBARI_SERIES,
                            iconName = "content_copy", enabled = cubariLink != null,
                            tooltip = "Copy Cubari series URL to clipboard"
                        )
                        item(
                            key = "open_md_series", label = "Open on MangaDex", action = Ids.ACT_OPEN_MANGADEX_SERIES,
                            iconName = "open_in_browser", enabled = mdLink != null,
                            tooltip = "Open this series page on MangaDex"
                        )
                        item(
                            key = "copy_md_series", label = "Copy MD URL", action = Ids.ACT_COPY_MANGADEX_SERIES,
                            iconName = "content_copy", enabled = mdLink != null,
                            tooltip = "Copy MangaDex series URL to clipboard"
                        )
                    }
                }
            }
            card(id = "card_series_cover", title = "Cover (Cubari only)", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
                column(id = "series_cover_col", spacing = SpacingSize.SMALL, modifier = UiModifier.padding(SpacingSize.SMALL)) {
                    seriesCover(series)?.let {
                        image(asset = it, id = "series_cover_img", contentScale = ImageScale.CROP, modifier = UiModifier.size(120, 170).clipRounded())
                    }
                    row(id = "series_cover_actions", spacing = SpacingSize.SMALL) {
                        button("Upload Cover", id = "btn_cover_upload", action = Ids.ACT_COVER_UPLOAD, isTonal = true, isPrimary = false, iconName = "image",
                            modifier = UiModifier.enabled(!draft.isNew && m.isUnlocked(LockKeys.STAGE1)))
                        button("Remove Cover", id = "btn_cover_remove", action = Ids.ACT_COVER_REMOVE, isTonal = true, isPrimary = false, isDestructive = true,
                            iconName = "delete", modifier = UiModifier.enabled(!draft.isNew && series.coverUrl != null && m.isUnlocked(LockKeys.STAGE1)))
                    }
                    if (draft.isNew) text("Save the series first to manage its cover.", id = "cover_hint", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                }
            }
            card(id = "card_series_preview", title = "Preview Window Override", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
                column(id = "series_preview_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.SMALL)) {
                    textInput(
                        id = Ids.SD_HOURS, text = draft.hoursText, label = "Override hours", revision = draft.revision,
                        placeholder = "Inherit global (${m.config.defaultPreviewHours}h)", regexPattern = "\\d{0,4}",
                        modifier = UiModifier.fillMaxWidth()
                    )
                    dropdown(
                        id = Ids.SD_ANCHOR, selectedKey = series.overridePreviewAnchor.name,
                        options = PreviewAnchorOverride.entries.associate { it.name to it.label }, label = "Anchor",
                        modifier = UiModifier.fillMaxWidth()
                    )
                }
            }
            val localSeriesChanges = if (!draft.isNew && series.cubariSnapshot != null) ChangeDetector.seriesMetaChanges(series) else emptyList()
            if (localSeriesChanges.isNotEmpty()) {
                alertBanner(
                    id = "series_local_changes_banner",
                    message = "Local-only series edits not yet on Cubari: ${localSeriesChanges.joinToString()}",
                    severity = SemanticColor.WARNING,
                    iconName = "warning"
                )
            }
            row(id = "series_editor_actions", spacing = SpacingSize.SMALL) {
                button("Save", id = "btn_series_save", action = Ids.ACT_SERIES_SAVE, iconName = "save")
                if (!draft.isNew && series.cubariSlug.isNotBlank()) {
                    button("Push Meta -> Cubari", id = "btn_push_series_meta", action = Ids.ACT_PUSH_SERIES_META, iconName = "cloud_upload",
                        isTonal = true, isPrimary = false, modifier = UiModifier.enabled(m.isUnlocked(LockKeys.CUBARI)))
                }
                if (localSeriesChanges.isNotEmpty()) {
                    button("Revert to Cubari", id = "btn_revert_series_cubari", action = Ids.ACT_REVERT_SERIES_CUBARI, iconName = "undo", isTonal = true, isPrimary = false)
                }
                button("Cancel", id = "btn_series_cancel", action = Ids.ACT_SERIES_CANCEL, isTonal = true, isPrimary = false)
            }
        }
    }

    // ───────────────────────── settings & locks ─────────────────────────

    private fun UiContainerBuilder.settingsView(m: RenderModel) {
        val s = m.state
        scrollableColumn(
            id = "settings_root", spacing = SpacingSize.MEDIUM,
            modifier = UiModifier.fillMaxSize().weight(1f).padding(SpacingSize.MEDIUM).background(SemanticColor.SURFACE_CONTAINER_LOWEST)
        ) {
            lockStatusCard(m)
            settingsCard(m, "cubari", "Cubari & GitHub Repo",
                modifier = UiModifier.fillMaxWidth().dropTarget(
                    UiDropTarget(action = Ids.ACT_DROP_REPO, acceptFiles = false, acceptDirectories = true, maxFiles = 1, hint = "Drop repository folder")
                )
            ) {
                settingInput(m, SettingKeys.CUBARI_USERNAME, "GitHub username", m.config.cubariUsername)
                settingInput(m, SettingKeys.CUBARI_TOKEN, "GitHub token (PAT)", m.config.cubariToken, secret = true)
                settingInput(m, SettingKeys.CUBARI_REMOTE_URL, "Remote repository URL", m.config.cubariRemoteUrl)
                settingInput(m, SettingKeys.CUBARI_LOCAL_PATH, "Local clone path (empty = managed)", m.config.cubariLocalPath)
                row(id = "cubari_branch_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.CUBARI_BRANCH, "Branch (empty = repo default, e.g. master)", m.config.cubariBranch, UiModifier.weight(1f))
                    settingInput(m, SettingKeys.CUBARI_SERIES_DIR, "Series directory (empty = repo root)", m.config.cubariSeriesDir, UiModifier.weight(1f))
                    settingInput(m, SettingKeys.CUBARI_GROUP, "Group name", m.config.cubariGroupName, UiModifier.weight(1f))
                }
                row(id = "cubari_actions", spacing = SpacingSize.SMALL) {
                    buttonGroup(id = "cubari_actions_group", selectionMode = false) {
                        item(
                            key = "test_repo", label = "Test Repo", action = Ids.ACT_TEST_REPO, iconName = "sync",
                            tooltip = "Test Git credentials and remote Cubari repository reachability"
                        )
                        item(
                            key = "sync_repo", label = "Sync Repo", action = Ids.ACT_SYNC_REPO, iconName = "cloud_download",
                            enabled = m.isUnlocked(LockKeys.CUBARI),
                            tooltip = "Pull Cubari repository JSON files and refresh local library"
                        )
                        item(
                            key = "restore_git", label = "Restore from Git", action = Ids.ACT_RESTORE_GIT, iconName = "restore",
                            color = SemanticColor.ERROR_CONTAINER, enabled = m.isUnlocked(LockKeys.CUBARI),
                            tooltip = "Clear stuck Git locks and hard-reset local repo clone to origin"
                        )
                        item(
                            key = "pick_repo", label = "Choose folder...", action = Ids.ACT_PICK_REPO, iconName = "folder_open",
                            tooltip = "Pick an existing local Cubari Git repository folder"
                        )
                    }
                    text("or drop a folder on this card", id = "cubari_drop_hint", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                }
                diagnostic(s, "cubari")
            }
            settingsCard(m, "mangadex", "MangaDex OAuth Account & Scanlation Group") {
                settingInput(m, SettingKeys.MD_CLIENT_ID, "OAuth / API client id", m.config.mdClientId, secret = false)
                settingInput(m, SettingKeys.MD_CLIENT_SECRET, "API client secret (Personal Client password flow only)", m.config.mdClientSecret, secret = true)
                row(id = "md_credentials_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.MD_USERNAME, "Username (password flow)", m.config.mdUsername, UiModifier.weight(1f))
                    settingInput(m, SettingKeys.MD_PASSWORD, "Password (password flow)", m.config.mdPassword, UiModifier.weight(1f), secret = true)
                }
                buttonGroup(id = "md_auth_actions", selectionMode = false) {
                    item(
                        key = "md_oauth", label = "Browser OAuth", action = Ids.ACT_MD_LOGIN_BROWSER, iconName = "open_in_browser",
                        tooltip = "Authenticate with MangaDex OAuth2 PKCE in your browser via loopback callback server"
                    )
                    item(
                        key = "md_login", label = "Password Login", action = Ids.ACT_MD_LOGIN, iconName = "key",
                        tooltip = "Authenticate using MangaDex Personal Client credentials and username/password"
                    )
                    item(
                        key = "md_verify", label = "Verify Token", action = Ids.ACT_MD_VERIFY, iconName = "check_circle",
                        tooltip = "Verify current MangaDex OAuth access/refresh tokens and fetch profile"
                    )
                    item(
                        key = "md_logout", label = "Log Out", action = Ids.ACT_MD_LOGOUT, iconName = "lock",
                        enabled = m.config.isMangaDexAuthenticated,
                        tooltip = "Clear stored MangaDex OAuth tokens and profile"
                    )
                }
                row(id = "md_tokens_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.MD_ACCESS_TOKEN, "OAuth access token", m.config.mdAccessToken, UiModifier.weight(1f), secret = true)
                    settingInput(m, SettingKeys.MD_REFRESH_TOKEN, "OAuth refresh token", m.config.mdRefreshToken, UiModifier.weight(1f), secret = true)
                }
                row(id = "md_endpoints_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.MD_API_BASE_URL, "API base URL", m.config.mdApiBaseUrl, UiModifier.weight(1f))
                    settingInput(m, SettingKeys.MD_AUTH_BASE_URL, "Auth base URL", m.config.mdAuthBaseUrl, UiModifier.weight(1f))
                }
                settingInput(m, SettingKeys.MD_OAUTH_REDIRECT_URI, "OAuth redirect URI (empty = dynamic 127.0.0.1 port)", m.config.mdOAuthRedirectUri)
                settingInput(m, SettingKeys.MD_PROXY_URL, "HTTP/SOCKS proxy URL (optional, e.g. http://127.0.0.1:7890)", m.config.mdProxyUrl)
                toggle(
                    id = Ids.SETTING_PREFIX + SettingKeys.MD_AUTO_DOH,
                    checked = m.config.mdAutoDoh,
                    label = "Bypass sinkholed OS DNS via DNS-over-HTTPS (1.1.1.1 / 8.8.8.8)"
                )
                text(
                    "Personal Clients (personal-client-...) use Password Login + client secret; Third-Party OAuth clients use Browser OAuth.",
                    id = "md_auth_hint",
                    role = TypographyRole.BODY_SMALL,
                    color = SemanticColor.ON_SURFACE_VARIANT
                )
                text("Profile: ${m.config.mdProfileName.ifBlank { "not logged in" }}", id = "md_profile_name", role = TypographyRole.BODY_MEDIUM)
                text(m.config.mdProfileUrl.ifBlank { "-" }, id = "md_profile_url", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                settingInput(m, SettingKeys.MD_GROUP, "Scanlation group UUID or URL", m.config.mdGroupRaw)
                row(id = "md_group_actions", spacing = SpacingSize.SMALL) {
                    button("Check Group", id = "btn_check_group", action = Ids.ACT_CHECK_GROUP, isTonal = true, isPrimary = false, iconName = "check_circle")
                    settingInput(m, SettingKeys.MD_LANGUAGE, "Language (blank = all)", m.config.mdLanguage, UiModifier.width(180))
                }
                buttonGroup(id = "md_upload_session_actions", selectionMode = false) {
                    item(
                        key = "check_session", label = "Check Upload Session", action = Ids.ACT_MD_CHECK_SESSION, iconName = "fact_check",
                        tooltip = "Check if MangaDex currently has an open upload session (sessions >= 2h old auto-close on upload)"
                    )
                    item(
                        key = "cancel_session", label = "Force Cancel Upload Session", action = Ids.ACT_MD_FORCE_CANCEL_SESSION, iconName = "cancel",
                        color = SemanticColor.ERROR_CONTAINER,
                        tooltip = "Immediately close any open/stuck MangaDex upload session on your account"
                    )
                }
                diagnostic(s, "mangadex")
                diagnostic(s, "group")
                diagnostic(s, "md_session")
            }
            settingsCard(m, "aruba", "Aruba FTP/SFTP Storage & Concurrency") {
                dropdown(
                    id = Ids.SETTING_PREFIX + SettingKeys.ARUBA_PROTOCOL, selectedKey = m.config.arubaProtocol,
                    options = linkedMapOf("SFTP" to "SFTP", "FTP" to "FTP", "FTPS" to "FTPS (explicit TLS)"),
                    label = "Protocol", modifier = UiModifier.fillMaxWidth()
                )
                row(id = "aruba_host_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.ARUBA_HOST, "Host", m.config.arubaHost, UiModifier.weight(3f))
                    settingInput(m, SettingKeys.ARUBA_PORT, "Port", m.config.arubaPort.toString(), UiModifier.weight(1f))
                }
                row(id = "aruba_user_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.ARUBA_USERNAME, "Username", m.config.arubaUsername, UiModifier.weight(1f))
                    settingInput(m, SettingKeys.ARUBA_PASSWORD, "Password", m.config.arubaPassword, UiModifier.weight(1f), secret = true)
                }
                settingInput(m, SettingKeys.ARUBA_CHAPTERS_PATH, "Chapters FTP path (e.g. /MangaKoFi)", m.config.arubaChaptersPath)
                settingInput(m, SettingKeys.ARUBA_PUBLIC_URL, "Public base URL (e.g. https://www.windsofresub.cloud/)", m.config.arubaPublicBaseUrl)
                val previewUrl = ArubaPaths.publicUrl(
                    ArubaConfig.from(m.config),
                    ArubaPaths.chapterDir(ArubaConfig.from(m.config), "Series", "1") + "/1.png"
                )
                text(
                    "Generated page URL format: $previewUrl",
                    id = "aruba_url_preview",
                    role = TypographyRole.BODY_SMALL,
                    color = SemanticColor.ON_SURFACE_VARIANT
                )
                dropdown(
                    id = Ids.SETTING_PREFIX + SettingKeys.ARUBA_PAGE_PADDING, selectedKey = m.config.arubaPagePadding,
                    options = linkedMapOf(
                        "auto" to "Auto (detect from Cubari URLs, else unpadded 1.png)",
                        "0" to "Unpadded (1.png, 2.png, ...)",
                        "2" to "Zero-padded 2 digits (01.png, 02.png, ...)",
                        "3" to "Zero-padded 3 digits (001.png, 002.png, ...)",
                        "4" to "Zero-padded 4 digits (0001.png, 0002.png, ...)"
                    ),
                    label = "Page image numbering (Series/ch<number>/<page>)",
                    modifier = UiModifier.fillMaxWidth()
                )
                row(id = "aruba_concurrency_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.CONCURRENT_UPLOADS, "Uploads", m.config.concurrentUploads.toString(), UiModifier.weight(1f))
                    settingInput(m, SettingKeys.CONCURRENT_DOWNLOADS, "Downloads", m.config.concurrentDownloads.toString(), UiModifier.weight(1f))
                    settingInput(m, SettingKeys.CONCURRENT_CONNECTIONS, "Connections", m.config.concurrentConnections.toString(), UiModifier.weight(1f))
                }
                buttonGroup(id = "aruba_actions_row", selectionMode = false) {
                    item(
                        key = "test_storage", label = "Test Storage", action = Ids.ACT_TEST_STORAGE, iconName = "sync",
                        tooltip = "Verify Aruba FTP/SFTP/FTPS credentials and chapters directory access"
                    )
                    item(
                        key = "scan_folders", label = "Scan Folders", action = Ids.ACT_SCAN_ARUBA_FOLDERS, iconName = "folder_open",
                        tooltip = "Discover existing series folders under Aruba chapters path"
                    )
                }
                diagnostic(s, "aruba")
            }
            settingsCard(m, "preview", "Preview Hold & Transmission Limits") {
                row(id = "preview_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.PREVIEW_HOURS, "Default preview hold (hours)", m.config.defaultPreviewHours.toString(), UiModifier.weight(1f))
                    buttonGroup(id = "preview_anchor_group") {
                        item(
                            "mangadex", "MangaDex timestamp", Ids.ACT_ANCHOR + "mangadex",
                            selected = m.config.previewAnchorLatestMangaDex,
                            tooltip = "Measure hold window from the latest published MangaDex chapter"
                        )
                        item(
                            "cubari", "Cubari timestamp", Ids.ACT_ANCHOR + "cubari",
                            selected = !m.config.previewAnchorLatestMangaDex,
                            tooltip = "Measure hold window from each chapter's Cubari publish timestamp"
                        )
                    }
                    button("Reset Defaults", id = "btn_reset_preview", action = Ids.ACT_RESET_PREVIEW, isTonal = true, isPrimary = false, iconName = "undo")
                }
                row(id = "limits_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.MAX_UPLOAD_KBPS, "Max upload KB/s (0 = unlimited)", m.config.maxUploadKbps.toString(), UiModifier.weight(1f))
                    settingInput(m, SettingKeys.MAX_DOWNLOAD_KBPS, "Max download KB/s (0 = unlimited)", m.config.maxDownloadKbps.toString(), UiModifier.weight(1f))
                }
                row(id = "cache_row", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                    settingInput(m, SettingKeys.MAX_CACHE_MB, "Max local cache MB (0 = unlimited)", m.config.maxCacheMb.toString(), UiModifier.weight(1f))
                    button("Prune Cache", id = "btn_prune_cache", action = Ids.ACT_PRUNE_CACHE, isTonal = true, isPrimary = false, iconName = "delete_sweep")
                }
                diagnostic(m.state, "cache")
            }
        }
    }

    private fun UiContainerBuilder.lockStatusCard(m: RenderModel) {
        card(id = "card_locks", title = "Lock & Auth Status", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGHEST, modifier = UiModifier.fillMaxWidth()) {
            column(id = "locks_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.MEDIUM_SMALL)) {
                m.report.targets.forEach { target ->
                    val unlocked = m.isUnlocked(LockKeys.CUBARI.takeIf { target.id == "cubari" } ?: if (target.id == "aruba") LockKeys.ARUBA else LockKeys.MANGADEX)
                    row(id = "lock_row_${target.id}", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                        badge(if (unlocked) "Unlocked" else "Locked", id = "lock_badge_${target.id}", color = target.readiness.color())
                        text(target.label, id = "lock_name_${target.id}", role = TypographyRole.TITLE_MEDIUM)
                        text(target.message, id = "lock_msg_${target.id}", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                    }
                }
                val stage1 = m.isUnlocked(LockKeys.STAGE1)
                val stage2 = m.isUnlocked(LockKeys.STAGE2)
                text(
                    "Stage 1 (Aruba + Cubari): ${if (stage1) "unlocked" else "locked"} · Stage 2 (MangaDex): ${if (stage2) "unlocked" else "locked"}",
                    id = "lock_stage_summary", role = TypographyRole.LABEL
                )
            }
        }
    }

    private fun UiContainerBuilder.settingsCard(
        m: RenderModel, target: String, title: String,
        modifier: UiModifier = UiModifier.fillMaxWidth(),
        block: UiContainerBuilder.() -> Unit
    ) {
        val focused = m.state.focusedCard == target
        card(
            id = Ids.card(target), title = title,
            surfaceRole = if (focused) SemanticColor.PRIMARY_CONTAINER else SemanticColor.SURFACE_CONTAINER_HIGH,
            outlined = focused, modifier = modifier
        ) {
            column(id = "${Ids.card(target)}_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.MEDIUM_SMALL)) { block() }
        }
    }

    private fun UiContainerBuilder.settingInput(
        m: RenderModel, key: String, label: String, value: String,
        modifier: UiModifier = UiModifier.fillMaxWidth(), secret: Boolean = false
    ) {
        textInput(
            id = Ids.SETTING_PREFIX + key, text = value, label = label,
            isSecret = secret, revision = m.state.settingsRevision, modifier = modifier
        )
    }

    private fun UiContainerBuilder.diagnostic(s: UiState, key: String) {
        val d = s.diagnostics[key] ?: return
        alertBanner(
            id = "diag_$key", message = "${d.message} (${formatTimestamp(d.atEpochSec)} UTC)",
            severity = if (d.ok) SemanticColor.SUCCESS else SemanticColor.ERROR, iconName = if (d.ok) "check_circle" else "error"
        )
    }

    // ───────────────────────── queue ─────────────────────────

    private fun UiContainerBuilder.queueView(m: RenderModel) {
        val hasTerminal = m.jobs.any { it.isTerminal }
        scrollableColumn(
            id = "queue_root", spacing = SpacingSize.SMALL,
            modifier = UiModifier.fillMaxSize().weight(1f).padding(SpacingSize.MEDIUM).background(SemanticColor.SURFACE_CONTAINER_LOWEST)
        ) {
            row(id = "queue_header", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth()) {
                text("Queue", id = "queue_title", role = TypographyRole.TITLE_LARGE)
                spacer(id = "queue_header_spacer", modifier = UiModifier.weight(1f))
                if (hasTerminal) {
                    button(
                        "Clear History",
                        id = "btn_clear_job_history",
                        action = Ids.ACT_CLEAR_JOB_HISTORY,
                        isTonal = true,
                        isPrimary = false,
                        iconName = "delete_sweep"
                    )
                }
            }
            if (m.jobs.isEmpty()) {
                emptyState(id = "queue_empty", title = "No jobs", iconName = "list", description = "Pushes and syncs show up here")
            }
            m.jobs.asReversed().forEach { job -> jobCard(job, m.nowEpochSec) }
        }
    }

    private fun UiContainerBuilder.jobCard(job: CapabilityJobSnapshot, nowEpochSec: Long) {
        val progress = job.primaryProgress
        val secondary = job.secondaryProgress
        val elapsed = formatElapsedJob(job, nowEpochSec)
        card(id = "job_${job.jobId}", surfaceRole = SemanticColor.SURFACE_CONTAINER_HIGH, modifier = UiModifier.fillMaxWidth()) {
            column(id = "job_${job.jobId}_col", spacing = SpacingSize.SMALL, modifier = UiModifier.fillMaxWidth().padding(SpacingSize.MEDIUM_SMALL)) {
                row(id = "job_${job.jobId}_head", spacing = SpacingSize.SMALL) {
                    text(job.capabilityName, id = "job_${job.jobId}_name", role = TypographyRole.TITLE_MEDIUM)
                    badge(job.status.name, id = "job_${job.jobId}_status", color = job.status.color())
                    if (elapsed != null) {
                        text("Elapsed: $elapsed", id = "job_${job.jobId}_elapsed", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                    }
                }
                progressBar(
                    id = "job_${job.jobId}_progress",
                    progress = when {
                        job.status == CapabilityJobStatus.COMPLETED -> 1f
                        job.status == CapabilityJobStatus.QUEUED -> 0f
                        else -> progress?.fraction
                    },
                    label = progress?.message,
                    statusText = progress?.let(::formatProgressStatus),
                    color = if (job.status == CapabilityJobStatus.FAILED) SemanticColor.ERROR else SemanticColor.PRIMARY,
                    modifier = UiModifier.fillMaxWidth()
                )
                if (secondary != null && !job.isTerminal) {
                    progressBar(
                        id = "job_${job.jobId}_secondary_progress",
                        progress = secondary.fraction,
                        label = secondary.message,
                        statusText = formatProgressStatus(secondary),
                        color = SemanticColor.SECONDARY,
                        modifier = UiModifier.fillMaxWidth()
                    )
                }
                job.subProgress.entries.forEach { (subKey, subProg) ->
                    progressBar(
                        id = "job_${job.jobId}_sub_${sanitizeFolderName(subKey)}",
                        progress = subProg.fraction,
                        label = subProg.message ?: subKey,
                        statusText = formatProgressStatus(subProg),
                        color = SemanticColor.TERTIARY,
                        modifier = UiModifier.fillMaxWidth()
                    )
                }
                job.errorMessage?.let { text(it, id = "job_${job.jobId}_error", role = TypographyRole.BODY_SMALL, color = SemanticColor.ERROR) }
                job.recentLogs.takeLast(MAX_LOG_LINES).forEachIndexed { i, line ->
                    text(line, id = "job_${job.jobId}_log_$i", role = TypographyRole.BODY_SMALL, color = SemanticColor.ON_SURFACE_VARIANT)
                }
                buttonGroup(id = "job_${job.jobId}_actions", selectionMode = false) {
                    item(
                        key = "pause", label = "Pause", action = Ids.ACT_JOB_PAUSE + job.jobId, iconName = "pause",
                        enabled = job.status == CapabilityJobStatus.RUNNING,
                        tooltip = "Pause this job at its next safe checkpoint"
                    )
                    item(
                        key = "resume", label = "Resume", action = Ids.ACT_JOB_RESUME + job.jobId, iconName = "play",
                        enabled = job.status == CapabilityJobStatus.PAUSED,
                        tooltip = "Resume paused job from its saved checkpoint"
                    )
                    item(
                        key = "cancel", label = "Cancel", action = Ids.ACT_JOB_CANCEL + job.jobId, iconName = "stop",
                        enabled = !job.isTerminal, color = SemanticColor.ERROR_CONTAINER,
                        tooltip = "Cancel and abort this job"
                    )
                }
            }
        }
    }

    // ───────────────────────── dialogs ─────────────────────────

    private fun UiContainerBuilder.dialogs(m: RenderModel) {
        val s = m.state
        val ch = s.chapterDraft?.let { s.library.chapterById(it.chapterId) }
        val series = s.library.seriesById(ch?.seriesId)
        val src = ch?.let { s.remoteSourceByChapter[it.id] ?: UiSelectors.defaultRemoteSource(it, series) }
            ?: UploaderWorkers.SOURCE_CUBARI
        val unified = UiSelectors.unifiedPages(s.chapterDraft, ch, ch?.let { s.remotePages["${it.id}:$src"] }, src)
        val preview = s.previewPageId?.let { id ->
            unified.firstOrNull { it.id == id }?.let { pg ->
                val ref = if (pg.isLocal) AssetReference.Relative(pg.storedPath) else AssetReference.Uri(pg.remoteUrl.orEmpty())
                pg.originalName to ref
            }
        } ?: s.previewRemoteUrl?.let { url ->
            (s.previewRemoteLabel ?: url.substringAfterLast('/')) to AssetReference.Uri(url)
        }
        if (preview != null) {
            dialog(title = preview.first, id = Ids.DLG_PREVIEW, confirmLabel = "Close", dismissLabel = null) {
                image(asset = preview.second, id = "preview_image", contentScale = ImageScale.FIT,
                    modifier = UiModifier.fillMaxWidth().height(560))
            }
        }
        s.confirm?.let { c ->
            dialog(
                title = when (c.kind) {
                    ConfirmKind.REMOVE_CUBARI -> "Remove from Cubari?"
                    ConfirmKind.DELETE_SERIES -> "Delete series?"
                    ConfirmKind.PUSH_DURING_HOLD -> "Preview hold still active"
                    ConfirmKind.LOAD_REMOTE_PAGES -> "Load uploaded images into draft?"
                    ConfirmKind.BACKFILL_ALL_MISSING -> "Backfill all missing chapters?"
                    ConfirmKind.RESTORE_GIT -> "Restore local repo from Git?"
                    ConfirmKind.FORCE_CANCEL_MD_SESSION -> "Force cancel active MangaDex upload session?"
                    ConfirmKind.REVERT_LOCAL_CHAPTER -> "Revert chapter to last synced Cubari state?"
                    ConfirmKind.REVERT_LOCAL_SERIES -> "Revert series metadata to last synced Cubari state?"
                },
                id = Ids.DLG_CONFIRM,
                confirmLabel = when (c.kind) {
                    ConfirmKind.REMOVE_CUBARI -> "Remove"
                    ConfirmKind.DELETE_SERIES -> "Delete"
                    ConfirmKind.PUSH_DURING_HOLD -> "Push anyway"
                    ConfirmKind.LOAD_REMOTE_PAGES -> "Load & Replace"
                    ConfirmKind.BACKFILL_ALL_MISSING -> "Backfill All"
                    ConfirmKind.RESTORE_GIT -> "Restore"
                    ConfirmKind.FORCE_CANCEL_MD_SESSION -> "Cancel Session"
                    ConfirmKind.REVERT_LOCAL_CHAPTER, ConfirmKind.REVERT_LOCAL_SERIES -> "Revert"
                },
                isDestructive = c.kind == ConfirmKind.REMOVE_CUBARI || c.kind == ConfirmKind.DELETE_SERIES ||
                    c.kind == ConfirmKind.RESTORE_GIT || c.kind == ConfirmKind.FORCE_CANCEL_MD_SESSION ||
                    c.kind == ConfirmKind.REVERT_LOCAL_CHAPTER || c.kind == ConfirmKind.REVERT_LOCAL_SERIES
            ) {
                text(c.message, id = "confirm_message", role = TypographyRole.BODY_MEDIUM)
            }
        }
    }

    private fun formatProgressStatus(p: ProgressData): String? {
        val curr = p.current
        val tot = p.total
        if (curr != null && tot != null) {
            return "${curr.toLong()} / ${tot.toLong()} ${p.unit.orEmpty()}".trim()
        }
        return p.fraction?.let { "${(it * 100).toInt()}%" }
    }

    private fun formatElapsedJob(job: CapabilityJobSnapshot, nowEpochSec: Long): String? {
        val startedMs = job.startedAtEpochMs ?: return null
        val endMs = job.completedAtEpochMs ?: (nowEpochSec * 1000L)
        val sec = ((endMs - startedMs) / 1000L).coerceAtLeast(0L)
        return when {
            sec >= 3600L -> "${sec / 3600L}h ${(sec % 3600L) / 60L}m"
            sec >= 60L -> "${sec / 60L}m ${sec % 60L}s"
            else -> "${sec}s"
        }
    }

    private const val COMPACT_PAGE_LIMIT = 8
    private const val MAX_LOG_LINES = 5
}
