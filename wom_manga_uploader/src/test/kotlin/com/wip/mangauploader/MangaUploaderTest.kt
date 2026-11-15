package com.wip.mangauploader

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.wip.plugintoolkit.api.ExecutionResult
import org.wip.plugintoolkit.api.HttpMethod
import org.wip.plugintoolkit.api.HttpRequest
import org.wip.plugintoolkit.api.HttpResponse
import org.wip.plugintoolkit.api.PluginContext
import org.wip.plugintoolkit.api.PluginNotificationType
import org.wip.plugintoolkit.api.PluginSignal
import org.wip.plugintoolkit.api.sdui.AlertBannerNode
import org.wip.plugintoolkit.api.sdui.AssetReference
import org.wip.plugintoolkit.api.sdui.BadgeNode
import org.wip.plugintoolkit.api.sdui.ButtonGroupNode
import org.wip.plugintoolkit.api.sdui.ButtonNode
import org.wip.plugintoolkit.api.sdui.ColumnNode
import org.wip.plugintoolkit.api.sdui.DialogNode
import org.wip.plugintoolkit.api.sdui.GridNode
import org.wip.plugintoolkit.api.sdui.ImageNode
import org.wip.plugintoolkit.api.sdui.ScrollableColumnNode
import org.wip.plugintoolkit.api.sdui.SemanticColor
import org.wip.plugintoolkit.api.sdui.SplitButtonNode
import org.wip.plugintoolkit.api.sdui.TextInputNode
import org.wip.plugintoolkit.api.sdui.TextNode
import org.wip.plugintoolkit.api.sdui.UiEvent
import org.wip.plugintoolkit.api.sdui.UiNodeTree
import org.wip.plugintoolkit.api.sdui.UiShortcut
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MangaUploaderTest {

    private lateinit var services: FakeServiceFactory
    private lateinit var pluginFs: FakePluginFs

    @Before
    fun setUp() {
        pluginFs = FakePluginFs()
        services = FakeServiceFactory(pluginFs = pluginFs)
        MangaUploaderServices.factory = services
    }

    @After
    fun tearDown() {
        MangaUploaderServices.factory = DefaultServiceFactory
    }

    private fun configuredSettings(authenticated: Boolean = true): Map<String, JsonPrimitive> = buildMap {
        put(SettingKeys.CUBARI_USERNAME, JsonPrimitive("scanteam"))
        put(SettingKeys.CUBARI_TOKEN, JsonPrimitive("ghp_secret"))
        put(SettingKeys.CUBARI_REMOTE_URL, JsonPrimitive("https://github.com/scanteam/cubari.git"))
        put(SettingKeys.CUBARI_SERIES_DIR, JsonPrimitive("series"))
        put(SettingKeys.ARUBA_HOST, JsonPrimitive("ftp.aruba.test"))
        put(SettingKeys.ARUBA_USERNAME, JsonPrimitive("uploader"))
        put(SettingKeys.ARUBA_PASSWORD, JsonPrimitive("pw"))
        put(SettingKeys.ARUBA_CHAPTERS_PATH, JsonPrimitive("/chapters"))
        put(SettingKeys.ARUBA_PUBLIC_URL, JsonPrimitive("https://cdn.aruba.test/chapters"))
        put(SettingKeys.ARUBA_PAGE_PADDING, JsonPrimitive("3"))
        put(SettingKeys.CONCURRENT_UPLOADS, JsonPrimitive(2))
        put(SettingKeys.CONCURRENT_DOWNLOADS, JsonPrimitive(2))
        put(SettingKeys.MD_CLIENT_ID, JsonPrimitive("cid"))
        put(SettingKeys.MD_CLIENT_SECRET, JsonPrimitive("csec"))
        put(SettingKeys.MD_USERNAME, JsonPrimitive("scanlead"))
        put(SettingKeys.MD_PASSWORD, JsonPrimitive("pass"))
        put(SettingKeys.MD_GROUP, JsonPrimitive("https://mangadex.org/group/11111111-1111-1111-1111-111111111111/test"))
        if (authenticated) {
            put(SettingKeys.MD_ACCESS_TOKEN, JsonPrimitive("access-1"))
            put(SettingKeys.MD_REFRESH_TOKEN, JsonPrimitive("refresh-1"))
            put(SettingKeys.MD_PROFILE_NAME, JsonPrimitive("ScanLead"))
        }
    }

    // ─────────────────── Cubari JSON & sync tests ───────────────────

    @Test
    fun `cubari json preserves unknown fields updates chapter and bumps last_updated`() {
        val raw = """
            {
              "title": "Obsidian Tower",
              "description": "Old",
              "custom_note": "keep-me",
              "last_updated": 100,
              "chapters": {
                "1": {
                  "title": "One",
                  "volume": "1",
                  "last_updated": 100,
                  "groups": {
                    "OtherGroup": ["https://other/1.png"],
                    "Uploader": ["https://old/1.png"]
                  }
                }
              }
            }
        """.trimIndent()
        val parsed = CubariJson.parse(raw)
        val updated = CubariJson.upsertChapter(
            existing = parsed,
            chapterKey = "1",
            title = "One Revised",
            volume = "1",
            group = "Uploader",
            pageUrls = listOf("https://cdn/1.png", "https://cdn/2.png"),
            nowEpochSec = 2500L
        )
        assertEquals("keep-me", CubariJson.string(updated, "custom_note"))
        assertEquals(2500L, CubariJson.lastUpdated(updated))
        assertEquals(2500L, CubariJson.chapterLastUpdated(updated, "1"))
        assertEquals(listOf("https://cdn/1.png", "https://cdn/2.png"), CubariJson.pageUrls(updated, "1", "Uploader"))
        assertEquals(listOf("https://other/1.png"), CubariJson.pageUrls(updated, "1", "OtherGroup"))

        val afterRemove = CubariJson.removeChapter(updated, "1", "Uploader", 2600L)
        assertEquals(2600L, CubariJson.lastUpdated(afterRemove))
        assertTrue("1" in CubariJson.chapterKeys(afterRemove)) // OtherGroup still remains
        val afterSecondRemove = CubariJson.removeChapter(afterRemove, "1", "OtherGroup", 2700L)
        assertFalse("1" in CubariJson.chapterKeys(afterSecondRemove))
    }

    @Test
    fun `publishToArubaAndCubari uploads pages updates JSON last_updated removes stale pages and updates published chapters`() = runTest {
        val series = Series(id = "sr_1", title = "Obsidian Tower", cubariSlug = "obsidian-tower")
        val pages = listOf(
            PageRef("p1", "chapters/ch_1/p1.png", "001.png"),
            PageRef("p2", "chapters/ch_1/p2.png", "002.png"),
            PageRef("p3", "chapters/ch_1/p3.png", "003.png")
        )
        val chapter = Chapter(id = "ch_1", seriesId = "sr_1", volume = "1", number = "12.0", title = "Breach", pages = pages)
        val store = LibraryStore(pluginFs)
        store.mutate { it.copy(series = listOf(series), chapters = listOf(chapter)) }
        pages.forEach { pluginFs.writeFile(relPath(it.storedPath), "page-${it.id}".encodeToByteArray()) }

        // Pre-existing stale 4th page on Aruba
        services.aruba.files["/chapters/Obsidian Tower/ch12/004.png"] = "stale".encodeToByteArray()

        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        services.clock.epochSec = 1_700_001_000L
        val res = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_1", "ch_1", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(res is ExecutionResult.Success)

        // Stale page deleted, 3 pages uploaded
        assertTrue("/chapters/Obsidian Tower/ch12/004.png" in services.aruba.deleted)
        assertEquals(3, services.aruba.files.size)
        assertTrue(services.aruba.files.containsKey("/chapters/Obsidian Tower/ch12/001.png"))
        assertTrue(services.aruba.files.containsKey("/chapters/Obsidian Tower/ch12/003.png"))

        // Cubari JSON written and pushed
        val repoJsonText = pluginFs.readTextFile(relPath("cubari-repo/series/obsidian-tower.json"))
        assertNotNull(repoJsonText)
        val parsed = CubariJson.parse(repoJsonText)
        assertEquals(1_700_001_000L, CubariJson.lastUpdated(parsed))
        assertEquals(1_700_001_000L, CubariJson.chapterLastUpdated(parsed, "12"))
        assertEquals(
            listOf(
                "https://cdn.aruba.test/chapters/Obsidian%20Tower/ch12/001.png",
                "https://cdn.aruba.test/chapters/Obsidian%20Tower/ch12/002.png",
                "https://cdn.aruba.test/chapters/Obsidian%20Tower/ch12/003.png"
            ),
            CubariJson.pageUrls(parsed, "12", "Uploader")
        )
        assertEquals(1, services.git.pushedCount)

        // Update already published chapter (e.g. re-titled) bumps last_updated and keeps original publish timestamp
        services.clock.epochSec = 1_700_002_000L
        store.mutate { lib -> LibraryOps.upsertChapter(lib, lib.chapterById("ch_1")!!.copy(title = "Breach v2")) }
        val res2 = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_1", "ch_1", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(res2 is ExecutionResult.Success)
        val updatedChapter = store.load().chapterById("ch_1")!!
        assertEquals(1_700_001_000L, updatedChapter.cubariPublishedAtEpochSec)
        assertEquals(1_700_002_000L, updatedChapter.cubariUpdatedAtEpochSec)
        assertEquals(1_700_002_000L, store.load().seriesById("sr_1")!!.lastUpdatedEpochSec)
    }

    @Test
    fun `syncCubariGitRepo imports series JSON and builds local index`() = runTest {
        services.git.remoteSeries["crimson-gate"] = CubariJson.parse(
            """
            {
              "title": "Crimson Gate",
              "author": "A. Author",
              "last_updated": 1700000123,
              "chapters": {
                "5": {
                  "title": "Signal",
                  "volume": "2",
                  "last_updated": 1700000123,
                  "groups": { "Uploader": ["https://cdn.aruba.test/chapters/crimson-gate/5/001.png"] }
                }
              }
            }
            """.trimIndent()
        )
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val res = UploaderWorkers(ctx, services).syncCubariGitRepo(push = false, resume = null)
        assertTrue(res is ExecutionResult.Success)
        val lib = LibraryStore(pluginFs).load()
        assertEquals(1, lib.series.size)
        assertEquals("Crimson Gate", lib.series.first().title)
        assertEquals(1, lib.chapters.size)
        assertTrue(lib.chapters.first().isOnCubari)
        assertNotNull(ctx.storage.get(UploaderWorkers.INDEX_KEY))
    }

    @Test
    fun `syncMangaDexToCubari detects missing chapters downloads pages uploads to Aruba and updates Cubari JSON`() = runTest {
        val series = Series(
            id = "sr_1",
            title = "Crimson Gate",
            cubariSlug = "crimson-gate",
            mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series)) }
        services.git.remoteSeries["crimson-gate"] = CubariJson.parse(
            """
            {
              "title": "Crimson Gate",
              "chapters": {
                "1": { "title": "Init", "volume": "1", "groups": { "Uploader": ["https://cdn/1.png"] } }
              }
            }
            """.trimIndent()
        )
        services.mangadex.feed = listOf(
            MdChapter("md_1", "1", "1", "Init", 1_700_000_000L, 1, 1),
            MdChapter("md_2", "2", "1", "Missing Two", 1_700_000_200L, 1, 2)
        )
        services.mangadex.pageUrlsByChapter["md_2"] = listOf("https://athome.test/p1.webp", "https://athome.test/p2.webp")

        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val res = UploaderWorkers(ctx, services).syncMangaDexToCubari("sr_1", "", scanOnly = false, backfillAll = true, resume = null)
        assertTrue(res is ExecutionResult.Success)

        val repoJson = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/series/crimson-gate.json"))!!)
        assertEquals(setOf("1", "2"), CubariJson.chapterKeys(repoJson))
        assertEquals(
            listOf(
                "https://cdn.aruba.test/chapters/Crimson%20Gate/ch2/001.webp",
                "https://cdn.aruba.test/chapters/Crimson%20Gate/ch2/002.webp"
            ),
            CubariJson.pageUrls(repoJson, "2", "Uploader")
        )
        assertEquals(0, LibraryStore(pluginFs).load().missingCubariCount("sr_1"))
    }

    @Test
    fun `uploadOrRemoveCubariCover uploads and removes cover field in Cubari JSON`() = runTest {
        val series = Series(id = "sr_1", title = "Obsidian", cubariSlug = "obsidian")
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series)) }
        pluginFs.writeFile(relPath("covers/sr_1.webp"), "cover-bytes".encodeToByteArray())
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())

        val uploadRes = UploaderWorkers(ctx, services).uploadOrRemoveCubariCover("sr_1", UploaderWorkers.OP_PUBLISH, "covers/sr_1.webp")
        assertTrue(uploadRes is ExecutionResult.Success)
        val jsonAfterUpload = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/series/obsidian.json"))!!)
        assertEquals("https://cdn.aruba.test/chapters/covers/Obsidian.webp", CubariJson.string(jsonAfterUpload, "cover"))
        assertTrue("/chapters/covers/Obsidian.webp" in services.aruba.files)

        val removeRes = UploaderWorkers(ctx, services).uploadOrRemoveCubariCover("sr_1", UploaderWorkers.OP_REMOVE, "")
        assertTrue(removeRes is ExecutionResult.Success)
        val jsonAfterRemove = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/series/obsidian.json"))!!)
        assertNull(CubariJson.string(jsonAfterRemove, "cover"))
        assertFalse("/chapters/covers/Obsidian.webp" in services.aruba.files)
    }

    // ─────────────────── Preview Timing tests ───────────────────

    @Test
    fun `preview timing evaluates global 24h and per-series hour and anchor overrides`() {
        val hour = 3600L
        val baseTime = 1_700_000_000L
        val cfg = MangaUploaderConfig(defaultPreviewHours = 24, previewAnchorLatestMangaDex = true)
        val seriesDefault = Series(id = "s1", title = "Default")
        val prevMd = Chapter(id = "c1", seriesId = "s1", number = "1", mangadexChapterId = "m1", mangadexPublishedAtEpochSec = baseTime)
        val staged = Chapter(id = "c2", seriesId = "s1", number = "2", cubariPublishedAtEpochSec = baseTime + 10 * hour)

        // Anchored on latest MangaDex (baseTime) -> hold until baseTime + 24h
        val holding = PreviewTiming.evaluate(seriesDefault, staged, listOf(prevMd, staged), cfg, baseTime + 20 * hour)
        assertEquals(HoldState.HOLDING, holding.state)
        assertEquals(4 * hour, holding.remainingSec)

        val released = PreviewTiming.evaluate(seriesDefault, staged, listOf(prevMd, staged), cfg, baseTime + 24 * hour)
        assertEquals(HoldState.RELEASABLE, released.state)

        // Per-series override: 48h anchored on Cubari publish time (baseTime + 10h -> hold until baseTime + 58h)
        val seriesOverride = seriesDefault.copy(overridePreviewHours = 48, overridePreviewAnchor = PreviewAnchorOverride.CUBARI_PUBLISH)
        val customHolding = PreviewTiming.evaluate(seriesOverride, staged, listOf(prevMd, staged), cfg, baseTime + 50 * hour)
        assertEquals(HoldState.HOLDING, customHolding.state)
        assertEquals(8 * hour, customHolding.remainingSec)
        assertTrue(customHolding.policy.hoursOverridden)
        assertTrue(customHolding.policy.anchorOverridden)

        // Unpublished chapter -> NOT_PUBLISHED
        val draft = Chapter(id = "c3", seriesId = "s1", number = "3")
        assertEquals(HoldState.NOT_PUBLISHED, PreviewTiming.evaluate(seriesDefault, draft, listOf(draft), cfg, baseTime).state)
    }

    // ─────────────────── Locks & Actions tests ───────────────────

    @Test
    fun `locks transition on MangaDex login and logout actions`() = runTest {
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings(authenticated = false))

        val initialLocks = plugin.checkLocks(ctx)
        assertEquals(true, initialLocks[LockKeys.CUBARI])
        assertEquals(true, initialLocks[LockKeys.ARUBA])
        assertEquals(true, initialLocks[LockKeys.STAGE1])
        assertEquals(false, initialLocks[LockKeys.MANGADEX])
        assertEquals(false, initialLocks[LockKeys.STAGE2])
        assertEquals(false, initialLocks[LockKeys.SYNC])

        UploaderActions(ctx, services).loginMangaDex()
        val afterLogin = plugin.checkLocks(ctx)
        assertEquals(true, afterLogin[LockKeys.MANGADEX])
        assertEquals(true, afterLogin[LockKeys.STAGE2])
        assertEquals(true, afterLogin[LockKeys.SYNC])
        assertEquals("ScanLead", MangaUploaderConfig.from(ctx.settings).mdProfileName)
        assertFalse(ctx.settings.containsKey(SettingKeys.MD_PASSWORD))

        UploaderActions(ctx, services).logoutMangaDex()
        val afterLogout = plugin.checkLocks(ctx)
        assertEquals(false, afterLogout[LockKeys.MANGADEX])
        assertEquals(false, afterLogout[LockKeys.STAGE2])
    }

    // ─────────────────── Pausable @Capability tests ───────────────────

    @Test
    fun `publishToArubaAndCubari pauses mid-batch and resumes from resumeState`() = runTest {
        val series = Series(id = "sr_1", title = "Obsidian", cubariSlug = "obsidian")
        val pages = (1..4).map { i -> PageRef("p$i", "chapters/ch_1/p$i.png", "00$i.png") }
        val chapter = Chapter(id = "ch_1", seriesId = "sr_1", number = "1", pages = pages)
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series), chapters = listOf(chapter)) }
        pages.forEach { pluginFs.writeFile(relPath(it.storedPath), "p".encodeToByteArray()) }

        val signals = FakeSignals()
        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            signals = signals,
            initialSettings = configuredSettings() + (SettingKeys.CONCURRENT_UPLOADS to JsonPrimitive(2))
        )
        var uploads = 0
        services.aruba.onUpload = {
            uploads++
            if (uploads == 2) signals.sendSignal(PluginSignal.PAUSE)
        }

        val paused = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_1", "ch_1", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(paused is ExecutionResult.Paused)
        assertEquals(2, services.aruba.files.size)

        // Resume with a clean signal manager
        val resumeCtx = FakePluginContext(fileSystem = pluginFs, initialSettings = ctx.settings)
        services.aruba.onUpload = {}
        val completed = UploaderWorkers(resumeCtx, services).publishToArubaAndCubari("sr_1", "ch_1", UploaderWorkers.OP_PUBLISH, paused.resumeState)
        assertTrue(completed is ExecutionResult.Success)
        assertEquals(4, services.aruba.files.size)
    }

    @Test
    fun `publishToMangaDex pauses mid-upload and resumes using saved sessionId`() = runTest {
        val series = Series(id = "sr_1", title = "Obsidian", mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val pages = (1..12).map { i -> PageRef("p$i", "chapters/ch_1/p$i.png", "%03d.png".format(i)) }
        val chapter = Chapter(id = "ch_1", seriesId = "sr_1", number = "5", title = "Five", pages = pages)
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series), chapters = listOf(chapter)) }
        pages.forEach { pluginFs.writeFile(relPath(it.storedPath), "p".encodeToByteArray()) }

        val signals = FakeSignals()
        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            signals = signals,
            initialSettings = configuredSettings() + (SettingKeys.MD_UPLOAD_INTERVAL to JsonPrimitive(0))
        )
        services.mangadex.onUploadBatch = { signals.sendSignal(PluginSignal.PAUSE) }

        val paused = UploaderWorkers(ctx, services).publishToMangaDex("sr_1", "ch_1", null)
        assertTrue(paused is ExecutionResult.Paused)
        assertEquals(10, services.mangadex.uploadedFiles.size)

        val resumeCtx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings() + (SettingKeys.MD_UPLOAD_INTERVAL to JsonPrimitive(0))
        )
        services.mangadex.onUploadBatch = {}
        val completed = UploaderWorkers(resumeCtx, services).publishToMangaDex("sr_1", "ch_1", paused.resumeState)
        assertTrue(completed is ExecutionResult.Success)
        assertEquals(12, services.mangadex.uploadedFiles.size)
        assertEquals("5", services.mangadex.committedDrafts.single().chapter)
        assertTrue(LibraryStore(pluginFs).load().chapterById("ch_1")!!.isOnMangaDex)
    }

    // ─────────────────── Interactive UI Session tests ───────────────────

    @Test
    fun `interactive session handles bidirectional settings sync reorder shortcuts drops and hold dialogs`() = runTest {
        val hostFs = FakeHostFs()
        hostFs.files["/drop/003.png"] = "img3".encodeToByteArray()
        hostFs.files["/drop/004.png"] = "img4".encodeToByteArray()

        val series1 = Series(id = "sr_1", title = "Obsidian", order = 0, mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val series2 = Series(id = "sr_2", title = "Crimson", order = 1)
        val ch1 = Chapter(
            id = "ch_1",
            seriesId = "sr_1",
            volume = "1",
            number = "10",
            title = "Draft",
            cubariPublishedAtEpochSec = services.clock.epochSec - 3600L, // inside 24h hold
            pages = listOf(
                PageRef("p1", "chapters/ch_1/p1.png", "001.png"),
                PageRef("p2", "chapters/ch_1/p2.png", "002.png")
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series1, series2), chapters = listOf(ch1)) }

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(
            fileSystem = pluginFs,
            hostFileSystem = hostFs,
            initialSettings = configuredSettings()
        )
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
        var latestTree: UiNodeTree? = null
        val collectJob = launch {
            plugin.openUiSession(sessionCtx, events).collect { latestTree = it }
        }
        advanceUntilIdle()

        // TopBar uses ButtonGroup + SplitButton, no 3rd left sidebar
        assertNotNull(latestTree?.findById(Ids.NAV_VIEWS) as? ButtonGroupNode)
        assertNotNull(latestTree?.findById(Ids.NAV_TARGETS) as? ButtonGroupNode)
        assertNotNull(latestTree?.findById(Ids.SPLIT_PUSH) as? SplitButtonNode)

        // 1. Drag-and-drop page reordering
        events.emit(UiEvent.ItemsReordered(Ids.PAGE_GRID, listOf("p2", "p1"), fromIndex = 0, toIndex = 1))
        advanceUntilIdle()
        val grid = latestTree!!.findById(Ids.PAGE_GRID) as GridNode
        assertEquals(listOf("p2", "p1"), grid.children.map { it.id })
        assertEquals(listOf("p2", "p1"), LibraryStore(pluginFs).load().chapterById("ch_1")!!.pages.map { it.id })

        // 2. Drag-and-drop series reordering
        events.emit(UiEvent.ItemsReordered(Ids.SERIES_LIST, listOf("${Ids.SERIES_PREFIX}sr_2", "${Ids.SERIES_PREFIX}sr_1"), 0, 1))
        advanceUntilIdle()
        val seriesList = latestTree!!.findById(Ids.SERIES_LIST) as ScrollableColumnNode
        assertEquals(listOf("${Ids.SERIES_PREFIX}sr_2", "${Ids.SERIES_PREFIX}sr_1"), seriesList.children.map { it.id })

        // 3. OS File drop appends pages
        events.emit(UiEvent.FilesDropped("pages_pane", Ids.ACT_DROP_PAGES, listOf("/drop/003.png", "/drop/004.png")))
        advanceUntilIdle()
        assertEquals(4, LibraryStore(pluginFs).load().chapterById("ch_1")!!.pages.size)

        // 4. Ctrl+S shortcut saves edited title locally
        events.emit(UiEvent.TextChanged(Ids.CH_TITLE, "Edited Title"))
        events.emit(UiEvent.ShortcutTriggered(Ids.ROOT, Ids.ACT_SAVE_DRAFT, "S", ctrl = true))
        advanceUntilIdle()
        assertEquals("Edited Title", LibraryStore(pluginFs).load().chapterById("ch_1")!!.title)

        // 5. Click page -> preview DialogNode -> Escape closes it, Delete removes selected page
        events.emit(UiEvent.Click("p2_img", Ids.ACT_PREVIEW_PAGE + "p2"))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_PREVIEW) as? DialogNode)
        events.emit(UiEvent.ShortcutTriggered(Ids.ROOT, Ids.ACT_DISMISS, UiShortcut.KEY_ESCAPE))
        advanceUntilIdle()
        assertNull(latestTree!!.findById(Ids.DLG_PREVIEW))

        events.emit(UiEvent.ShortcutTriggered(Ids.ROOT, Ids.ACT_DELETE_PAGE, UiShortcut.KEY_DELETE))
        advanceUntilIdle()
        assertEquals(3, LibraryStore(pluginFs).load().chapterById("ch_1")!!.pages.size)

        // 6. Single-chapter Push to MangaDex during 24h hold triggers confirmation DialogNode, confirming queues job
        events.emit(UiEvent.Click("btn_push_mangadex", Ids.ACT_CH_PUSH_MANGADEX))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM) as? DialogNode)
        events.emit(UiEvent.DialogAction(Ids.DLG_CONFIRM, isConfirmed = true))
        advanceUntilIdle()
        assertTrue(sessionCtx.launchedCapabilities.any { it.second == "publishToMangaDex" })

        // 7. Navigate to Settings via target pill & test bidirectional updateSetting / clearSetting + locks
        events.emit(UiEvent.Click(Ids.NAV_TARGETS, Ids.ACT_GOTO_TARGET + "mangadex"))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.card("mangadex")))

        events.emit(UiEvent.TextChanged(Ids.SETTING_PREFIX + SettingKeys.PREVIEW_HOURS, "48"))
        advanceUntilIdle()
        assertEquals(JsonPrimitive(48), base.mutableSettings[SettingKeys.PREVIEW_HOURS])

        events.emit(UiEvent.Click("btn_reset_preview", Ids.ACT_RESET_PREVIEW))
        advanceUntilIdle()
        assertFalse(base.mutableSettings.containsKey(SettingKeys.PREVIEW_HOURS))

        // External host setting change updates UI and bumps revision
        val revBefore = (latestTree!!.findById(Ids.SETTING_PREFIX + SettingKeys.PREVIEW_HOURS) as TextInputNode).revision
        base.mutableSettings[SettingKeys.PREVIEW_HOURS] = JsonPrimitive(12)
        sessionCtx.settingsState.value = base.mutableSettings.toMap()
        advanceUntilIdle()
        val inputAfter = latestTree!!.findById(Ids.SETTING_PREFIX + SettingKeys.PREVIEW_HOURS) as TextInputNode
        assertEquals("12", inputAfter.text)
        assertTrue(inputAfter.revision > revBefore)

        // Log out updates locksFlow and disables Stage 2
        events.emit(UiEvent.Click("btn_md_logout", Ids.ACT_MD_LOGOUT))
        advanceUntilIdle()
        assertEquals(false, sessionCtx.locksFlow.value[LockKeys.STAGE2])
        val mdTargetGroup = latestTree!!.findById(Ids.NAV_TARGETS) as ButtonGroupNode
        assertEquals(SemanticColor.ERROR_CONTAINER, mdTargetGroup.items.first { it.key == "mangadex" }.color)

        // Queue view shows running/completed jobs
        events.emit(UiEvent.Click(Ids.NAV_VIEWS, Ids.ACT_VIEW + MainView.QUEUE.key))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById("queue_title") as? TextNode)

        collectJob.cancel()
    }

    // ─────────────────── v1.0.1 Tests ───────────────────

    @Test
    fun `empty cubariSeriesDir uses repo root skips non-Cubari json and infers Aruba folder and padding`() = runTest {
        assertEquals("", MangaUploaderConfig.normalizeRepoDir(""))
        assertEquals("", MangaUploaderConfig.normalizeRepoDir("/"))
        assertEquals("", MangaUploaderConfig.normalizeRepoDir("."))
        assertEquals("", MangaUploaderConfig.normalizeRepoDir("./"))
        assertEquals("nested/series", MangaUploaderConfig.normalizeRepoDir("/nested/series/"))

        services.git.remoteSeries["Solo Leveling Ragnarok"] = CubariJson.parse(
            """
            {
              "title": "Solo Leveling Ragnarok",
              "last_updated": 1700000100,
              "chapters": {
                "1": {
                  "title": "Prologue",
                  "volume": "1",
                  "last_updated": 1700000100,
                  "groups": {
                    "WindsUI": [
                      "https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch1/01.png",
                      "https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch1/02.png"
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )
        // Non-Cubari JSON at repo root must be ignored safely
        pluginFs.writeTextFile(relPath("cubari-repo/package.json"), """{"name":"manga-repo","private":true}""")

        val rootSettings = configuredSettings() + mapOf(
            SettingKeys.CUBARI_USERNAME to JsonPrimitive("Wip-Sama"),
            SettingKeys.CUBARI_REMOTE_URL to JsonPrimitive("https://github.com/WindsOfOfficial/Manga"),
            SettingKeys.CUBARI_BRANCH to JsonPrimitive("master"),
            SettingKeys.CUBARI_SERIES_DIR to JsonPrimitive(""),
            SettingKeys.CUBARI_GROUP to JsonPrimitive("Uploader"),
            SettingKeys.ARUBA_PAGE_PADDING to JsonPrimitive("auto")
        )
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = rootSettings)
        val syncRes = UploaderWorkers(ctx, services).syncCubariGitRepo(push = false, resume = null)
        assertTrue(syncRes is ExecutionResult.Success)

        val lib = LibraryStore(pluginFs).load()
        assertEquals(1, lib.series.size)
        val series = lib.series.single()
        assertEquals("Solo Leveling Ragnarok", series.cubariSlug)
        assertEquals("Solo leveling ragnarok", series.arubaFolder)
        assertNull(series.arubaZeroPadDigits)
        val cfg = MangaUploaderConfig.from(ctx.settings)
        assertEquals(2, ArubaPaths.resolvePadDigits(ArubaConfig.from(cfg), series, lib.chaptersOf(series.id)))

        // Verify Cubari reader URL uses repository owner ("WindsOfOfficial") and root JSON filename
        val owner = CubariJson.repoOwnerFromUrl(cfg.cubariRemoteUrl, cfg.cubariUsername)
        assertEquals("WindsOfOfficial", owner)
        val readerUrl = CubariJson.readerUrl(owner, CubariJson.repoNameFromUrl(cfg.cubariRemoteUrl), cfg.cubariBranch, "${series.cubariSlug}.json")
        val encodedPayload = readerUrl.removePrefix("https://cubari.moe/read/gist/").removeSuffix("/")
        val decodedPayload = java.util.Base64.getUrlDecoder().decode(encodedPayload).decodeToString()
        assertEquals("raw/WindsOfOfficial/Manga/refs/heads/master/Solo%20Leveling%20Ragnarok.json", decodedPayload)

        // Publish a decimal chapter ch12.5 -> infers 2-digit left padding (01.png) and Aruba folder ("Solo leveling ragnarok")
        val ch = Chapter(
            id = "ch_12_5",
            seriesId = series.id,
            number = "12.50",
            title = "Side",
            pages = listOf(PageRef("pg1", "chapters/ch_12_5/p1.png", "raw1.png"))
        )
        LibraryStore(pluginFs).mutate { LibraryOps.upsertChapter(it, ch) }
        pluginFs.writeFile(relPath("chapters/ch_12_5/p1.png"), "img".encodeToByteArray())

        val pubRes = UploaderWorkers(ctx, services).publishToArubaAndCubari(series.id, "ch_12_5", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(pubRes is ExecutionResult.Success)
        assertTrue(services.aruba.files.containsKey("/chapters/Solo leveling ragnarok/ch12.5/01.png"))
        val updatedRootJson = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/Solo Leveling Ragnarok.json"))!!)
        assertEquals(
            listOf("https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch12.5/01.png"),
            CubariJson.pageUrls(updatedRootJson, "12.5", "Uploader")
        )
    }

    @Test
    fun `aruba unpadded images decimal chapters and stale page cleanup work for 1-digit and 2-digit files`() = runTest {
        assertEquals("12", normalizeChapterKey("ch12.0"))
        assertEquals("12.5", normalizeChapterKey("Ch. 12.50"))
        assertEquals("100", normalizeChapterKey("100"))

        val series = Series(id = "sr_p", title = "A pervert's daily life", cubariSlug = "A pervert's daily life")
        val pages = listOf(
            PageRef("p1", "chapters/ch_p/p1.png", "1.png"),
            PageRef("p2", "chapters/ch_p/p2.png", "2.png")
        )
        val ch = Chapter(id = "ch_p", seriesId = "sr_p", number = "ch1.5", pages = pages)
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series), chapters = listOf(ch)) }
        pages.forEach { pluginFs.writeFile(relPath(it.storedPath), "page".encodeToByteArray()) }

        // Pre-existing stale unpadded and 2-digit pages on Aruba
        services.aruba.files["/chapters/A pervert's daily life/ch1.5/02.png"] = "stale2".encodeToByteArray()
        services.aruba.files["/chapters/A pervert's daily life/ch1.5/3.png"] = "stale3".encodeToByteArray()

        val settings = configuredSettings() + mapOf(
            SettingKeys.CUBARI_SERIES_DIR to JsonPrimitive(""),
            SettingKeys.ARUBA_PAGE_PADDING to JsonPrimitive("auto")
        )
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = settings)
        val res = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_p", "ch_p", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(res is ExecutionResult.Success)

        assertTrue("/chapters/A pervert's daily life/ch1.5/02.png" in services.aruba.deleted)
        assertTrue("/chapters/A pervert's daily life/ch1.5/3.png" in services.aruba.deleted)
        assertEquals(2, services.aruba.files.size)
        assertTrue(services.aruba.files.containsKey("/chapters/A pervert's daily life/ch1.5/1.png"))
        assertTrue(services.aruba.files.containsKey("/chapters/A pervert's daily life/ch1.5/2.png"))

        val savedCh = LibraryStore(pluginFs).load().chapterById("ch_p")!!
        assertEquals(
            listOf(
                "https://cdn.aruba.test/chapters/A%20pervert%27s%20daily%20life/ch1.5/1.png",
                "https://cdn.aruba.test/chapters/A%20pervert%27s%20daily%20life/ch1.5/2.png"
            ),
            savedCh.cubariPageUrls
        )
    }

    @Test
    fun `mangadex browser oauth pkce flow custom endpoints and verify token work without client secret`() = runTest {
        val network = FakeNetwork()
        var step = 0
        network.responder = { req ->
            when {
                req.url == "https://auth.custom.test/realms/mangadex/protocol/openid-connect/token" -> {
                    val form = req.body!!.decodeToString()
                    assertFalse(form.contains("client_secret="))
                    HttpResponse(
                        statusCode = 200,
                        body = """{"access_token":"tok_${++step}","refresh_token":"ref_new","expires_in":900}""".encodeToByteArray()
                    )
                }
                req.url == "https://api.custom.test/user/me" && req.headers["Authorization"] == "Bearer expired-tok" -> {
                    HttpResponse(statusCode = 401, body = """{"result":"error"}""".encodeToByteArray())
                }
                req.url == "https://api.custom.test/user/me" -> {
                    HttpResponse(
                        statusCode = 200,
                        body = """{"data":{"id":"u-99","attributes":{"username":"OAuthUser"}}}""".encodeToByteArray()
                    )
                }
                else -> HttpResponse(statusCode = 404)
            }
        }
        val realHttpApi = HttpMangaDexApi(
            network = network,
            apiBaseUrl = "https://api.custom.test",
            authBaseUrl = "https://auth.custom.test",
            usingProxy = true
        )
        val oauthServices = object : ServiceFactory by services {
            override fun mangadex(context: PluginContext, config: MangaUploaderConfig): MangaDexApi = realHttpApi
        }
        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings(authenticated = false) + mapOf(
                SettingKeys.MD_CLIENT_ID to JsonPrimitive("public-pkce-client"),
                SettingKeys.MD_CLIENT_SECRET to JsonPrimitive("")
            )
        )

        var capturedAuthUrl = ""
        UploaderActions(ctx, oauthServices).loginMangaDexBrowser { authUrl ->
            capturedAuthUrl = authUrl
            val query = OAuthLoopbackReceiver.parseQuery(URI(authUrl).rawQuery)
            val redirect = query["redirect_uri"]!!
            val state = query["state"]!!
            assertTrue(capturedAuthUrl.startsWith("https://auth.custom.test/"))
            assertEquals("S256", query["code_challenge_method"])
            val conn = URI("$redirect?code=pkce-code-777&state=$state").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        }

        val afterBrowser = MangaUploaderConfig.from(ctx.settings)
        assertEquals("tok_1", afterBrowser.mdAccessToken)
        assertEquals("ref_new", afterBrowser.mdRefreshToken)
        assertEquals("OAuthUser", afterBrowser.mdProfileName)
        assertFalse(ctx.settings.containsKey(SettingKeys.MD_PASSWORD))

        // Expired token + Verify Token triggers refresh without client_secret
        ctx.updateSetting(SettingKeys.MD_ACCESS_TOKEN, "expired-tok")
        UploaderActions(ctx, oauthServices).verifyMangaDexSession()
        val afterVerify = MangaUploaderConfig.from(ctx.settings)
        assertEquals("tok_2", afterVerify.mdAccessToken)
        assertEquals("OAuthUser", afterVerify.mdProfileName)
    }

    @Test
    fun `chapters column background matches sibling columns and settings actions refresh diagnostics in session`() = runTest {
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        val tree = latestTree!!
        val colSeries = tree.findById("col_series") as ColumnNode
        val colChapters = tree.findById("col_chapters") as ColumnNode
        val colInspector = tree.findById("col_inspector_empty") as ColumnNode
        assertEquals(SemanticColor.SURFACE_CONTAINER_LOW, colSeries.modifier.backgroundColor)
        assertEquals(SemanticColor.SURFACE_CONTAINER_LOW, colChapters.modifier.backgroundColor)
        assertEquals(SemanticColor.SURFACE_CONTAINER_LOW, colInspector.modifier.backgroundColor)

        events.emit(UiEvent.Click(Ids.NAV_VIEWS, Ids.ACT_VIEW + MainView.SETTINGS.key))
        events.emit(UiEvent.Click("btn_test_repo", Ids.ACT_TEST_REPO))
        events.emit(UiEvent.Click("btn_check_group", Ids.ACT_CHECK_GROUP))
        advanceUntilIdle()

        val updatedTree = latestTree!!
        assertNotNull(updatedTree.findById("diag_cubari"))
        assertNotNull(updatedTree.findById("diag_group"))

        job.cancel()
    }

    @Test
    fun `syncCubariGitRepo skips ellipsis filenames rejected by host RelativePath and syncs remaining series`() = runTest {
        services.git.seriesSubdir = ""
        pluginFs.files["cubari-repo/Alya sometimes....json"] =
            """{"title":"Alya sometimes","chapters":{"1":{"title":"Ch1","groups":{"Uploader":["https://cdn.aruba.test/a/1.png"]}}}}""".encodeToByteArray()
        pluginFs.writeTextFile(
            relPath("cubari-repo/Valid Series.json"),
            """{"title":"Valid Series","chapters":{"1":{"title":"Ch1","groups":{"Uploader":["https://cdn.aruba.test/v/1.png"]}}}}"""
        )

        val settings = configuredSettings() + mapOf(SettingKeys.CUBARI_SERIES_DIR to JsonPrimitive(""))
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = settings)
        val res = UploaderWorkers(ctx, services).syncCubariGitRepo(push = false, resume = null)

        assertTrue(res is ExecutionResult.Success)
        val resultJson = res.response.result!!.jsonObject
        assertEquals(1, resultJson["series"]!!.jsonPrimitive.int)
        assertEquals(1, resultJson["skipped"]!!.jsonPrimitive.int)
        val lib = LibraryStore(pluginFs).load()
        assertEquals(listOf("Valid Series"), lib.series.map { it.title })
        assertTrue(ctx.notifications.any { it.third == PluginNotificationType.WARNING && "Alya sometimes....json" in it.second })
    }

    @Test
    fun `oauth loopback receiver supports custom fixed redirect uri and guards personal clients`() = runTest {
        val freePort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val customRedirect = "http://localhost:$freePort/auth/md"
        val network = FakeNetwork()
        network.responder = { req ->
            when {
                req.url.endsWith("/token") -> {
                    val form = req.body!!.decodeToString()
                    assertTrue("redirect_uri=http%3A%2F%2Flocalhost%3A$freePort%2Fauth%2Fmd" in form)
                    HttpResponse(statusCode = 200, body = """{"access_token":"tok_custom","refresh_token":"ref_custom","expires_in":900}""".encodeToByteArray())
                }
                req.url.endsWith("/user/me") ->
                    HttpResponse(statusCode = 200, body = """{"data":{"id":"u-5","attributes":{"username":"CustomOAuth"}}}""".encodeToByteArray())
                else -> HttpResponse(statusCode = 404)
            }
        }
        val api = HttpMangaDexApi(network = network, apiBaseUrl = "https://api.custom.test", authBaseUrl = "https://auth.custom.test", usingProxy = true)
        val customServices = object : ServiceFactory by services {
            override fun mangadex(context: PluginContext, config: MangaUploaderConfig): MangaDexApi = api
        }

        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings(authenticated = false) + mapOf(
                SettingKeys.MD_CLIENT_ID to JsonPrimitive("third-party-client"),
                SettingKeys.MD_CLIENT_SECRET to JsonPrimitive(""),
                SettingKeys.MD_OAUTH_REDIRECT_URI to JsonPrimitive(customRedirect)
            )
        )
        UploaderActions(ctx, customServices).loginMangaDexBrowser { authUrl ->
            val q = OAuthLoopbackReceiver.parseQuery(URI(authUrl).rawQuery)
            assertEquals(customRedirect, q["redirect_uri"])
            val conn = URI("${q["redirect_uri"]}?code=fixed-code&state=${q["state"]}").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            conn.disconnect()
        }
        assertEquals("tok_custom", MangaUploaderConfig.from(ctx.settings).mdAccessToken)

        // Personal client with blank redirect_uri fails fast before opening browser
        val personalCtx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings(authenticated = false) + mapOf(
                SettingKeys.MD_CLIENT_ID to JsonPrimitive("personal-client-xyz"),
                SettingKeys.MD_OAUTH_REDIRECT_URI to JsonPrimitive("")
            )
        )
        var browserOpened = false
        UploaderActions(personalCtx, customServices).loginMangaDexBrowser { browserOpened = true }
        assertFalse(browserOpened)
        val diag = DiagnosticsStore(personalCtx.storage).loadAll()["mangadex"]!!
        assertFalse(diag.ok)
        assertTrue("Password Login" in diag.message)
    }

    @Test
    fun `smart network client parses DoH A records bypassing CNAME and routes sinkholed host via relay`() = runTest {
        val dohJson = """
            {"Status":0,"Answer":[
              {"name":"api.mangadex.org","type":5,"TTL":300,"data":"dxlb.mangadex.org."},
              {"name":"dxlb.mangadex.org","type":1,"TTL":60,"data":"127.0.0.1"},
              {"name":"dxlb.mangadex.org","type":1,"TTL":262,"data":"45.129.229.2"}
            ]}
        """.trimIndent()
        val parsed = DohResolver.parseDohIPv4(dohJson)
        assertEquals(DohRecord("45.129.229.2", 262L), parsed)

        val localServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        localServer.createContext("/ping") { ex ->
            val bytes = "pong".encodeToByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        localServer.start()
        val relay = LoopbackConnectRelay()
        try {
            val delegate = FakeNetwork()
            val smart = SmartMangaDexNetworkClient(
                delegate = delegate,
                autoDoh = true,
                relay = relay,
                dnsLookup = { listOf(InetAddress.getByName("127.0.0.1")) },
                dohResolve = { DohRecord("127.0.0.1", 60L) }
            )
            val res = smart.request(HttpRequest(url = "http://sinkholed.mangadex.test:${localServer.address.port}/ping"))
            assertEquals(200, res.statusCode)
            assertEquals("pong", res.bodyAsText())
            assertTrue(delegate.requests.isEmpty())
        } finally {
            relay.close()
            localServer.stop(0)
        }

        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val proxyErr = ProxyNetworkClient("http://127.0.0.1:$closedPort")
            .request(HttpRequest(url = "https://api.mangadex.org/ping"))
        assertEquals(0, proxyErr.statusCode)
        assertTrue(proxyErr.errorMessage.orEmpty().contains("no local proxy is listening", ignoreCase = true))
    }

    @Test
    fun `fetchChapterPages checks and loads Cubari and MangaDex images into editable draft and UI`() = runTest {
        val sr = Series(id = "sr1", title = "My Series", cubariSlug = "My Series", mangadexId = "650dbb60-013a-4b77-b0d8-a546ecdce20d")
        val ch = Chapter(
            id = "ch1",
            seriesId = "sr1",
            number = "1",
            title = "Start",
            cubariPublishedAtEpochSec = 1_699_990_000L,
            cubariPageUrls = listOf("https://cdn.aruba.test/ch1/1.png", "https://cdn.aruba.test/ch1/2.png")
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        services.mangadex.urlStatuses["https://cdn.aruba.test/ch1/2.png"] = 404
        services.mangadex.feed = listOf(MdChapter("md-ch-1", "1", "1", "Start", 1_699_995_000L, 1, 2))
        services.mangadex.pageUrlsByChapter["md-ch-1"] = listOf("https://at.home/md-a.png", "https://at.home/md-b.png")
        services.mangadex.downloadedBytes["https://at.home/md-a.png"] = "png-a".encodeToByteArray()
        services.mangadex.downloadedBytes["https://at.home/md-b.png"] = "png-b".encodeToByteArray()

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        // 1. Unified Pages grid renders cached Cubari remote URLs immediately without downloading
        val initialGrid = latestTree!!.findById(Ids.PAGE_GRID) as GridNode
        assertEquals(2, initialGrid.children.size)

        // Check Cubari images (1 OK, 1 404)
        events.emit(UiEvent.Click("btn_check_remote_pages", Ids.ACT_CHECK_REMOTE_PAGES))
        advanceUntilIdle()
        val cubariBanner = latestTree!!.findById("remote_pages_status") as AlertBannerNode
        assertEquals(SemanticColor.WARNING, cubariBanner.severity)
        assertTrue("1/2 reachable" in cubariBanner.message)
        assertNotNull(latestTree!!.findById(Ids.PAGE_GRID) as GridNode)

        // 2. Select remote page -> shows View Full & Download Local actions; View Full uses AssetReference.Uri
        events.emit(UiEvent.Click("cubari_pg_0_img", Ids.ACT_SELECT_PAGE + "cubari_pg_0"))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById("btn_download_single_page"))
        events.emit(UiEvent.Click("btn_view_full_page", Ids.ACT_VIEW_FULL_PAGE))
        advanceUntilIdle()
        val dlgPreview = latestTree!!.findById(Ids.DLG_PREVIEW) as DialogNode
        val previewImg = dlgPreview.findNodeById("preview_image") as ImageNode
        assertEquals(AssetReference.Uri("https://cdn.aruba.test/ch1/1.png"), previewImg.asset)
        events.emit(UiEvent.DialogAction(Ids.DLG_PREVIEW, isConfirmed = true))
        advanceUntilIdle()

        // 3. Toggle grid expand mode cleanly
        events.emit(UiEvent.Click("btn_toggle_expand_grid", Ids.ACT_TOGGLE_EXPAND_GRID))
        advanceUntilIdle()

        // 4. Switch source dropdown to MangaDex, check images (links md-ch-1), then download all into local draft
        events.emit(UiEvent.OptionSelected(Ids.REMOTE_SOURCE, UploaderWorkers.SOURCE_MANGADEX))
        events.emit(UiEvent.Click("btn_check_remote_pages", Ids.ACT_CHECK_REMOTE_PAGES))
        advanceUntilIdle()
        val mdBanner = latestTree!!.findById("remote_pages_status") as AlertBannerNode
        assertEquals(SemanticColor.SUCCESS, mdBanner.severity)
        assertTrue("2/2 reachable" in mdBanner.message)
        assertEquals("md-ch-1", LibraryStore(pluginFs).load().chapterById("ch1")!!.mangadexChapterId)

        events.emit(UiEvent.Click("btn_load_remote_pages", Ids.ACT_LOAD_REMOTE_PAGES))
        advanceUntilIdle()
        val reloadedPages = LibraryStore(pluginFs).load().chapterById("ch1")!!.pages
        assertEquals(2, reloadedPages.size)
        val localGrid = latestTree!!.findById(Ids.PAGE_GRID) as GridNode
        assertEquals(2, localGrid.children.size)

        // 5. Subsequent Download All with local pages prompts before replacing
        events.emit(UiEvent.Click("btn_load_remote_pages", Ids.ACT_LOAD_REMOTE_PAGES))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM))

        job.cancel()
    }

    @Test
    fun `mangadex feed omits groups query param and filters locally while checkUrl falls back to ranged GET`() = runTest {
        val network = FakeNetwork()
        val seenUrls = mutableListOf<String>()
        val targetGroup = "fcec5639-806a-45f8-ac9d-b9e5e37655b9"
        network.responder = { req ->
            seenUrls += req.url
            when {
                req.url.startsWith("https://api.custom.test/manga/m1/feed") -> HttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "result": "ok",
                          "total": 3,
                          "data": [
                            {
                              "id": "ch-keep",
                              "attributes": {"chapter": "1", "volume": "1", "title": "Ours", "externalUrl": null, "version": 2, "pages": 5},
                              "relationships": [{"id": "$targetGroup", "type": "scanlation_group"}]
                            },
                            {
                              "id": "ch-external",
                              "attributes": {"chapter": "1.5", "volume": "1", "title": "External", "externalUrl": "https://external.example/ch", "version": 1, "pages": 0},
                              "relationships": [{"id": "$targetGroup", "type": "scanlation_group"}]
                            },
                            {
                              "id": "ch-skip",
                              "attributes": {"chapter": "1", "volume": "1", "title": "Other", "version": 1, "pages": 5},
                              "relationships": [{"id": "00000000-0000-0000-0000-000000000000", "type": "scanlation_group"}]
                            }
                          ]
                        }
                    """.trimIndent().encodeToByteArray()
                )
                req.url == "https://cdn.aruba.test/head-blocked.png" && req.method == HttpMethod.HEAD ->
                    HttpResponse(statusCode = 403)
                req.url == "https://cdn.aruba.test/head-blocked.png" && req.method == HttpMethod.GET -> {
                    assertEquals("bytes=0-0", req.headers["Range"])
                    HttpResponse(statusCode = 206, body = byteArrayOf(1))
                }
                req.url == "https://cdn.aruba.test/head-zero.png" && req.method == HttpMethod.HEAD ->
                    HttpResponse(statusCode = 0, errorMessage = "Reset")
                req.url == "https://cdn.aruba.test/head-zero.png" && req.method == HttpMethod.GET ->
                    HttpResponse(statusCode = 200, body = byteArrayOf(1))
                else -> HttpResponse(statusCode = 404)
            }
        }
        val api = HttpMangaDexApi(network = network, apiBaseUrl = "https://api.custom.test")
        val filtered = api.listChapters("m1", groupId = targetGroup, language = "en")
        assertTrue(seenUrls.first().contains("includes[]=scanlation_group"))
        assertTrue(seenUrls.first().contains("contentRating[]=pornographic"))
        assertFalse(seenUrls.first().contains("groups[]="))
        assertFalse(seenUrls.first().contains("includeExternalUrl="))
        assertFalse(seenUrls.first().contains("includeFuturePublishAt="))
        assertEquals(listOf("ch-keep"), filtered.map { it.id })

        val blankLang = api.listChapters("m1", groupId = targetGroup, language = "")
        assertFalse(seenUrls.last().contains("translatedLanguage[]="))
        assertEquals(listOf("ch-keep"), blankLang.map { it.id })

        assertEquals(206, api.checkUrl("https://cdn.aruba.test/head-blocked.png"))
        assertEquals(200, api.checkUrl("https://cdn.aruba.test/head-zero.png"))
        assertEquals(-1, api.checkUrl(""))

        // SmartMangaDexNetworkClient falls back to directFallback when host delegate returns statusCode == 0
        val delegate = FakeNetwork().apply { responder = { HttpResponse(statusCode = 0, errorMessage = "Ktor error") } }
        val smart = SmartMangaDexNetworkClient(
            delegate = delegate,
            autoDoh = true,
            dnsLookup = { listOf(InetAddress.getByName("93.184.216.34")) },
            dohResolve = { null },
            directFallback = { HttpResponse(statusCode = 200, body = "ok".encodeToByteArray()) }
        )
        assertEquals(200, smart.request(HttpRequest(url = "https://cdn.aruba.test/p.png")).statusCode)
    }

    @Test
    fun `scanArubaFolders matches existing folders and selective publish reuses unchanged remote pages`() = runTest {
        services.aruba.directories += listOf("/chapters/Solo leveling ragnarok", "/chapters/xuejie")
        val sr = Series(
            id = "sr1",
            title = "Solo Leveling Ragnarok",
            cubariSlug = "Solo Leveling Ragnarok",
            mangadexId = "650dbb60-013a-4b77-b0d8-a546ecdce20d"
        )
        val ch = Chapter(
            id = "ch1",
            seriesId = "sr1",
            number = "1",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariPageUrls = listOf(
                "https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch1/1.png",
                "https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch1/2.png"
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings() + (SettingKeys.ARUBA_PAGE_PADDING to JsonPrimitive("auto"))
        )

        val scanned = UploaderActions(ctx, services).scanArubaFolders()
        assertEquals(listOf("Solo leveling ragnarok", "xuejie"), scanned)
        val updatedSeries = LibraryStore(pluginFs).load().seriesById("sr1")!!
        assertEquals("Solo leveling ragnarok", updatedSeries.arubaFolder)

        // Download ONLY page index #1 cleanly
        services.mangadex.downloadedBytes["https://cdn.aruba.test/chapters/Solo%20leveling%20ragnarok/ch1/2.png"] =
            "old-2".encodeToByteArray()
        val singleRes = UploaderWorkers(ctx, services).fetchChapterPages(
            seriesId = "sr1",
            chapterId = "ch1",
            source = UploaderWorkers.SOURCE_CUBARI,
            mode = UploaderWorkers.MODE_LOAD,
            replace = false,
            pageIndex = 1,
            resume = null
        )
        assertTrue(singleRes is ExecutionResult.Success)
        val afterSingle = LibraryStore(pluginFs).load().chapterById("ch1")!!
        assertEquals(2, afterSingle.pages.size)
        assertTrue(afterSingle.pages[0].isRemoteOnly)
        assertTrue(afterSingle.pages[1].isLocal)

        // Replace page #2 locally & pre-seed page #1 on Aruba + stale page #3: publish only uploads page #2!
        services.aruba.files["/chapters/Solo leveling ragnarok/ch1/1.png"] = "unchanged-1".encodeToByteArray()
        services.aruba.files["/chapters/Solo leveling ragnarok/ch1/3.png"] = "stale-3".encodeToByteArray()
        val uploadedPaths = mutableListOf<String>()
        services.aruba.onUpload = { uploadedPaths += it }
        pluginFs.writeFile(relPath(afterSingle.pages[1].storedPath), "new-2".encodeToByteArray())

        val pubRes = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr1", "ch1", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(pubRes is ExecutionResult.Success)
        assertEquals(listOf("/chapters/Solo leveling ragnarok/ch1/2.png"), uploadedPaths)
        assertTrue("/chapters/Solo leveling ragnarok/ch1/3.png" in services.aruba.deleted)

        val cfg = MangaUploaderConfig.from(ctx.settings)
        assertEquals("https://mangadex.org/title/650dbb60-013a-4b77-b0d8-a546ecdce20d", UploaderLinks.mangadexSeriesUrl(updatedSeries))
        assertTrue(UploaderLinks.cubariChapterUrl(cfg, updatedSeries, afterSingle)!!.endsWith("/1/1/"))
    }

    @Test
    fun `cubari reader link matches refs heads format with literal apostrophe`() {
        val sr = Series(id = "sr1", title = "A pervert's daily life", cubariSlug = "A pervert's daily life")
        val ch = Chapter(id = "ch65", seriesId = "sr1", number = "65", cubariPublishedAtEpochSec = 1_700_000_000L)
        val cfg = MangaUploaderConfig.from(
            mapOf(
                SettingKeys.CUBARI_USERNAME to JsonPrimitive("Wip-Sama"),
                SettingKeys.CUBARI_REMOTE_URL to JsonPrimitive("https://github.com/WindsOfOfficial/Manga"),
                SettingKeys.CUBARI_BRANCH to JsonPrimitive("master"),
                SettingKeys.CUBARI_SERIES_DIR to JsonPrimitive("")
            )
        )
        val expectedSeries = "https://cubari.moe/read/gist/cmF3L1dpbmRzT2ZPZmZpY2lhbC9NYW5nYS9yZWZzL2hlYWRzL21hc3Rlci9BJTIwcGVydmVydCdzJTIwZGFpbHklMjBsaWZlLmpzb24/"
        assertEquals(expectedSeries, UploaderLinks.cubariSeriesUrl(cfg, sr))
        assertEquals("${expectedSeries}65/1/", UploaderLinks.cubariChapterUrl(cfg, sr, ch))
    }

    @Test
    fun `mangadex scan and image check fall back across languages and populate chapter badges`() = runTest {
        val sr = Series(
            id = "sr1",
            title = "A pervert's daily life",
            cubariSlug = "A pervert's daily life",
            mangadexId = "650dbb60-013a-4b77-b0d8-a546ecdce20d"
        )
        val ch = Chapter(
            id = "ch61",
            seriesId = "sr1",
            number = "61",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariPageUrls = listOf("https://cdn.aruba.test/61/1.png")
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        services.git.remoteSeries["A pervert's daily life"] = CubariJson.parse(
            """{"title":"A pervert's daily life","chapters":{"61":{"title":"Side"}}}"""
        )

        // Fake MangaDex returns chapters only when language="" (all languages fallback from default "en").
        val fakeMd = object : MangaDexApi by services.mangadex {
            override suspend fun listChapters(mangaId: String, groupId: String?, language: String): List<MdChapter> =
                if (language.isBlank() || language == "it") {
                    listOf(MdChapter("md-ch-61", "61", "1", "Side story 3", 1_700_000_500L, 1, 2))
                } else emptyList()
        }
        services.mangadex.pageUrlsByChapter["md-ch-61"] = listOf("https://at.home/61-1.png", "https://at.home/61-2.png")
        val customFactory = object : ServiceFactory by services {
            override fun mangadex(context: PluginContext, config: MangaUploaderConfig): MangaDexApi = fakeMd
        }

        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings() + (SettingKeys.MD_LANGUAGE to JsonPrimitive("en"))
        )
        val scanRes = UploaderWorkers(ctx, customFactory).syncMangaDexToCubari("sr1", "", scanOnly = true, resume = null)
        assertTrue(scanRes is ExecutionResult.Success)
        val afterScan = LibraryStore(pluginFs).load().chapterById("ch61")!!
        assertEquals("md-ch-61", afterScan.mangadexChapterId)
        assertTrue(afterScan.isOnMangaDex)

        // Single-chapter MangaDex image check also resolves & finds images
        val chReset = afterScan.copy(mangadexChapterId = null)
        LibraryStore(pluginFs).mutate { LibraryOps.upsertChapter(it, chReset) }
        val checkRes = UploaderWorkers(ctx, customFactory).fetchChapterPages("sr1", "ch61", UploaderWorkers.SOURCE_MANGADEX, UploaderWorkers.MODE_CHECK, false, -1, null)
        assertTrue(checkRes is ExecutionResult.Success)
        val mdReport = RemotePagesStore(ctx.storage).loadAll()["ch61:${UploaderWorkers.SOURCE_MANGADEX}"]!!
        assertEquals(2, mdReport.reachableCount)
    }

    @Test
    fun `source filters distinguish cubari and mangadex and compact page grid has no fixed height`() = runTest {
        val srBoth = Series(id = "sr_both", title = "Both", order = 0, mangadexId = "uuid-1", lastUpdatedEpochSec = 100L)
        val srCubari = Series(id = "sr_cubari", title = "CubariOnly", order = 1, lastUpdatedEpochSec = 100L)
        val srMd = Series(id = "sr_md", title = "MdOnly", order = 2, mangadexId = "uuid-2")
        val chBoth = Chapter(
            id = "c_both", seriesId = "sr_both", number = "10",
            cubariPublishedAtEpochSec = 10L, mangadexChapterId = "md1",
            pages = (1..12).map { PageRef("p$it", "chapters/c_both/$it.png", "$it.png") }
        )
        val chCubari = Chapter(id = "c_cubari", seriesId = "sr_both", number = "2", cubariPublishedAtEpochSec = 10L)
        val chMissingCubari = Chapter(id = "c_missing", seriesId = "sr_both", number = "3", mangadexChapterId = "md3")
        val chStaging = Chapter(id = "c_stage", seriesId = "sr_both", number = "4")
        val lib = Library(
            series = listOf(srBoth, srCubari, srMd),
            chapters = listOf(chBoth, chCubari, chMissingCubari, chStaging)
        )
        LibraryStore(pluginFs).mutate { lib }

        assertEquals(listOf("sr_both", "sr_cubari"), UiSelectors.visibleSeries(UiState(library = lib, seriesFilter = SeriesFilter.CUBARI)).map { it.id })
        assertEquals(listOf("sr_both", "sr_md"), UiSelectors.visibleSeries(UiState(library = lib, seriesFilter = SeriesFilter.MANGADEX)).map { it.id })
        assertEquals(listOf("sr_both"), UiSelectors.visibleSeries(UiState(library = lib, seriesFilter = SeriesFilter.BOTH)).map { it.id })
        assertEquals(listOf("sr_both"), UiSelectors.visibleSeries(UiState(library = lib, seriesFilter = SeriesFilter.MISSING_CUBARI)).map { it.id })

        fun chIds(filter: ChapterFilter) = UiSelectors.visibleChapters(UiState(library = lib, activeSeriesId = "sr_both", chapterFilter = filter)).map { it.id }.toSet()
        assertEquals(setOf("c_both", "c_cubari"), chIds(ChapterFilter.CUBARI))
        assertEquals(setOf("c_both", "c_missing"), chIds(ChapterFilter.MANGADEX))
        assertEquals(setOf("c_both"), chIds(ChapterFilter.BOTH))
        assertEquals(setOf("c_cubari"), chIds(ChapterFilter.CUBARI_ONLY))
        assertEquals(setOf("c_missing"), chIds(ChapterFilter.MISSING))
        assertEquals(setOf("c_stage"), chIds(ChapterFilter.STAGING))

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        val compactGrid = latestTree!!.findById(Ids.PAGE_GRID) as GridNode
        assertEquals(8, compactGrid.children.size)
        assertFalse(compactGrid.reorderable)
        assertNull(compactGrid.modifier.heightDp)
        assertNotNull(latestTree!!.findById("btn_expand_all_pages"))

        events.emit(UiEvent.Click("btn_expand_all_pages", Ids.ACT_TOGGLE_EXPAND_GRID))
        advanceUntilIdle()
        val expandedGrid = latestTree!!.findById(Ids.PAGE_GRID) as GridNode
        assertEquals(12, expandedGrid.children.size)
        assertTrue(expandedGrid.reorderable)
        assertNull(expandedGrid.modifier.heightDp)

        job.cancel()
    }

    @Test
    fun `pickWebrootDirectory resolves shared hosting webroot skipping backup dirs`() {
        val dirs = listOf(
            "windsofresub.cloud_Backup_Giornaliero",
            "windsofresub.cloud_Backup_Settimanale",
            "www.windsofresub.cloud"
        )
        assertEquals("www.windsofresub.cloud", pickWebrootDirectory(dirs) { it == "www.windsofresub.cloud" })
        assertEquals("www.windsofresub.cloud", pickWebrootDirectory(dirs) { false })
        assertNull(pickWebrootDirectory(listOf("windsofresub.cloud_Backup_Giornaliero")) { false })
    }

    @Test
    fun `platform timestamps track cubari and mangadex updates and highlight newer platform`() = runTest {
        val network = FakeNetwork().apply {
            responder = {
                HttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "result": "ok",
                          "total": 1,
                          "data": [
                            {
                              "id": "md-61",
                              "attributes": {
                                "chapter": "61",
                                "volume": "1",
                                "title": "Side 3",
                                "externalUrl": null,
                                "publishAt": "2025-02-14T20:30:11+00:00",
                                "updatedAt": "2025-02-14T20:32:04+00:00",
                                "version": 3,
                                "pages": 10
                              },
                              "relationships": []
                            }
                          ]
                        }
                    """.trimIndent().encodeToByteArray()
                )
            }
        }
        val mdList = HttpMangaDexApi(network = network, apiBaseUrl = "https://api.custom.test").listChapters("m1", null, "")
        assertEquals(1, mdList.size)
        val parsedMd = mdList.first()
        assertEquals(1_739_565_011L, parsedMd.publishAtEpochSec)
        assertEquals(1_739_565_124L, parsedMd.updatedAtEpochSec)

        val baseCh = Chapter(
            id = "ch61",
            seriesId = "sr1",
            number = "61",
            cubariPublishedAtEpochSec = 1_703_011_680L,
            cubariPageUrls = listOf("https://www.resub.cloud/MangaKoFi/s/ch61/1.png")
        )
        val mergedMd = LibraryOps.mergeMangaDexChapters(
            Library(series = listOf(Series(id = "sr1", title = "Test", cubariSlug = "test")), chapters = listOf(baseCh)),
            "sr1",
            mdList,
            1_750_000_000L
        )
        val chMdNewer = mergedMd.chapterById("ch61")!!
        assertEquals(1_739_565_124L, chMdNewer.mangadexUpdatedAtEpochSec)
        assertEquals(PlatformUpdateStatus.MANGADEX_NEWER, chMdNewer.updateStatus())

        // External Cubari JSON update with newer last_updated flips status to CUBARI_NEWER
        val newerCubariJson = CubariJson.parse("""{"title":"Test","chapters":{"61":{"last_updated":"1760000000"}}}""")
        val mergedCubari = LibraryOps.mergeCubariSeries(mergedMd, "test", newerCubariJson, "WindsOfManga", 1_760_000_000L, "")
        val chCubariNewer = mergedCubari.chapterById("ch61")!!
        assertEquals(1_760_000_000L, chCubariNewer.cubariUpdatedAtEpochSec)
        assertEquals(PlatformUpdateStatus.CUBARI_NEWER, chCubariNewer.updateStatus())

        // UI renders both platform timestamps and the newer badge in row & inspector
        LibraryStore(pluginFs).mutate { mergedMd }
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        val rowNewerBadge = latestTree!!.findById("pill_newer_ch61") as BadgeNode
        assertEquals("MD newer", rowNewerBadge.text)
        val inspectorCubariTs = latestTree!!.findById("ch_ts_cubari") as TextNode
        val inspectorMdTs = latestTree!!.findById("ch_ts_mangadex") as TextNode
        val inspectorStatus = latestTree!!.findById("ch_ts_status") as BadgeNode
        assertTrue("2023-12-19" in inspectorCubariTs.text)
        assertTrue("2025-02-14" in inspectorMdTs.text && "(v3)" in inspectorMdTs.text)
        assertEquals("MD newer", inspectorStatus.text)

        job.cancel()
    }

    // ─────────────────── v1.0.3 Tests ───────────────────

    @Test
    fun `plugin version is bumped to 1_0_4`() {
        assertEquals("1.0.4", MangaUploaderPluginManifest.manifest.plugin.version)
    }

    @Test
    fun `syncMangaDexToCubari pulls only requested chapter and rejects accidental full backfill when backfillAll is false`() = runTest {
        val series = Series(
            id = "sr_1",
            title = "Crimson Gate",
            cubariSlug = "crimson-gate",
            mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series)) }
        services.git.remoteSeries["crimson-gate"] = CubariJson.parse(
            """{"title":"Crimson Gate","chapters":{"1":{"title":"One","volume":"1","groups":{"Uploader":["https://cdn/1.png"]}}}}"""
        )
        services.mangadex.feed = listOf(
            MdChapter("md_1", "1", "1", "One", 1_700_000_000L, 1, 1, updatedAtEpochSec = 1_700_000_000L),
            MdChapter("md_2", "2", "1", "Two", 1_700_000_100L, 1, 1, updatedAtEpochSec = 1_700_000_100L),
            MdChapter("md_3", "3", "1", "Three", 1_700_000_200L, 1, 1, updatedAtEpochSec = 1_700_000_200L)
        )
        services.mangadex.pageUrlsByChapter["md_2"] = listOf("https://athome.test/ch2_1.png")
        services.mangadex.pageUrlsByChapter["md_3"] = listOf("https://athome.test/ch3_1.png")

        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())

        // Empty chapterNumbers + backfillAll = false must fail instead of pulling ch 2 & ch 3
        val accidental = UploaderWorkers(ctx, services).syncMangaDexToCubari("sr_1", "", scanOnly = false, backfillAll = false, resume = null)
        assertTrue(accidental is ExecutionResult.Error)
        assertEquals(0, services.aruba.files.size)

        // Single-chapter request ("2") must pull ONLY chapter 2 and leave chapter 3 missing
        val singleRes = UploaderWorkers(ctx, services).syncMangaDexToCubari("sr_1", "2", scanOnly = false, backfillAll = false, resume = null)
        assertTrue(singleRes is ExecutionResult.Success)
        val repoJson = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/series/crimson-gate.json"))!!)
        assertEquals(setOf("1", "2"), CubariJson.chapterKeys(repoJson))
        assertEquals(1, LibraryStore(pluginFs).load().missingCubariCount("sr_1"))
        assertEquals(PlatformUpdateStatus.IN_SYNC, LibraryStore(pluginFs).load().chapters.first { it.cubariKey == "2" }.updateStatus())
    }

    @Test
    fun `bulk actions and single chapter inspector actions remain strictly separated`() = runTest {
        val series = Series(id = "sr_1", title = "Obsidian", order = 0, mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val ch61 = Chapter(id = "ch_61", seriesId = "sr_1", number = "61", title = "Sixty-One", mangadexChapterId = "md_61", mangadexPublishedAtEpochSec = 1_700_000_000L)
        val ch62 = Chapter(id = "ch_62", seriesId = "sr_1", number = "62", title = "Sixty-Two", mangadexChapterId = "md_62", mangadexPublishedAtEpochSec = 1_700_000_100L)
        val ch63 = Chapter(id = "ch_63", seriesId = "sr_1", number = "63", title = "Sixty-Three", mangadexChapterId = "md_63", mangadexPublishedAtEpochSec = 1_700_000_200L)
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(series), chapters = listOf(ch61, ch62, ch63)) }

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val collectJob = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        // Open ch_61 in the inspector while nothing is checked: bulk buttons stay at 0 checked and do NOT pull whole series
        events.emit(UiEvent.Click("ch_row_ch_61", Ids.ACT_OPEN_CHAPTER + "ch_61"))
        advanceUntilIdle()
        assertEquals("Bulk Push (0)", (latestTree!!.findById(Ids.SPLIT_PUSH) as SplitButtonNode).label)
        assertFalse((latestTree!!.findById("btn_pull_missing") as ButtonNode).modifier.enabled)

        events.emit(UiEvent.Click("btn_pull_missing", Ids.ACT_PULL_MISSING))
        advanceUntilIdle()
        assertTrue(sessionCtx.launchedCapabilities.isEmpty())

        // Check ch_62 ONLY, while inspector stays open on ch_61
        events.emit(UiEvent.ToggleChanged("${Ids.CHAPTER_CHECK_PREFIX}ch_62", checked = true))
        advanceUntilIdle()
        assertEquals("Push Checked (1)", (latestTree!!.findById(Ids.SPLIT_PUSH) as SplitButtonNode).label)
        assertEquals("Pull Checked (1)", (latestTree!!.findById("btn_pull_missing") as ButtonNode).label)

        // Single-chapter inspector Pull Ch. <- MD acts strictly on activeChapterId (61), NOT checked (62) or entire series!
        events.emit(UiEvent.Click("btn_pull_md", Ids.ACT_CH_PULL_MD))
        advanceUntilIdle()
        val singleLaunch = sessionCtx.launchedCapabilities.last()
        assertEquals("syncMangaDexToCubari", singleLaunch.second)
        assertEquals("61", singleLaunch.third["chapterNumbers"]?.jsonPrimitive?.content)
        assertEquals("false", singleLaunch.third["backfillAll"]?.jsonPrimitive?.content)

        // Bulk Pull Checked acts strictly on checked ch_62, ignoring active ch_61
        events.emit(UiEvent.Click("btn_pull_missing", Ids.ACT_PULL_MISSING))
        advanceUntilIdle()
        val bulkLaunch = sessionCtx.launchedCapabilities.last()
        assertEquals("62", bulkLaunch.third["chapterNumbers"]?.jsonPrimitive?.content)
        assertEquals("false", bulkLaunch.third["backfillAll"]?.jsonPrimitive?.content)

        // Full missing backfill requires confirmation dialog and sets backfillAll = true
        events.emit(UiEvent.Click("btn_backfill_all", Ids.ACT_BACKFILL_ALL))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM) as? DialogNode)
        events.emit(UiEvent.DialogAction(Ids.DLG_CONFIRM, isConfirmed = true))
        advanceUntilIdle()
        val backfillLaunch = sessionCtx.launchedCapabilities.last()
        assertEquals("", backfillLaunch.third["chapterNumbers"]?.jsonPrimitive?.content)
        assertEquals("true", backfillLaunch.third["backfillAll"]?.jsonPrimitive?.content)

        collectJob.cancel()
    }

    @Test
    fun `cross platform sync marks chapters IN_SYNC despite server clock skew until a platform receives a newer update`() = runTest {
        val series = Series(
            id = "sr_1",
            title = "Obsidian",
            cubariSlug = "obsidian",
            mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        )
        // Published to Cubari yesterday (T=1000)
        val ch1 = Chapter(
            id = "ch_1",
            seriesId = "sr_1",
            number = "1",
            pages = listOf(PageRef("p1", "chapters/ch_1/p1.png", "1.png")),
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariUpdatedAtEpochSec = 1_700_000_000L
        )
        pluginFs.writeFile(relPath("chapters/ch_1/p1.png"), "p1".encodeToByteArray())
        val store = LibraryStore(pluginFs)
        store.mutate { it.copy(series = listOf(series), chapters = listOf(ch1)) }

        // Push Cubari -> MangaDex later when MD server assigns commit timestamp +500s ahead
        services.clock.epochSec = 1_700_000_500L
        val ctx = FakePluginContext(
            fileSystem = pluginFs,
            initialSettings = configuredSettings() + (SettingKeys.MD_UPLOAD_INTERVAL to JsonPrimitive(0))
        )
        val mdRes = UploaderWorkers(ctx, services).publishToMangaDex("sr_1", "ch_1", null)
        assertTrue(mdRes is ExecutionResult.Success)
        val synced = store.load().chapterById("ch_1")!!
        assertEquals(PlatformUpdateStatus.IN_SYNC, synced.updateStatus())

        // Subsequent MangaDex scan with unchanged timestamps stays IN_SYNC
        services.mangadex.feed = listOf(
            MdChapter("md_ch_1", "1", "", "", 1_700_000_500L, 1, 1, updatedAtEpochSec = 1_700_000_500L)
        )
        val rescanned = LibraryOps.mergeMangaDexChapters(store.load(), "sr_1", services.mangadex.feed, 1_700_001_000L)
        assertEquals(PlatformUpdateStatus.IN_SYNC, rescanned.chapterById("ch_1")!!.updateStatus())

        // Later re-upload to Cubari advances Cubari past snapshot -> CUBARI_NEWER
        services.clock.epochSec = 1_700_005_000L
        val reupload = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_1", "ch_1", UploaderWorkers.OP_PUBLISH, null)
        assertTrue(reupload is ExecutionResult.Success)
        assertEquals(PlatformUpdateStatus.CUBARI_NEWER, store.load().chapterById("ch_1")!!.updateStatus())

        // Editing directly on MangaDex advances MD past snapshot -> MANGADEX_NEWER, then pulling MD -> Cubari returns to IN_SYNC
        store.mutate { lib ->
            LibraryOps.upsertChapter(lib, lib.chapterById("ch_1")!!.copy(cubariUpdatedAtEpochSec = 1_700_000_000L))
        }
        services.mangadex.feed = listOf(
            MdChapter("md_ch_1", "1", "", "Updated", 1_700_000_500L, 1, 2, updatedAtEpochSec = 1_700_009_000L)
        )
        services.mangadex.pageUrlsByChapter["md_ch_1"] = listOf("https://athome.test/1_updated.png")
        store.mutate { LibraryOps.mergeMangaDexChapters(it, "sr_1", services.mangadex.feed, 1_700_009_100L) }
        assertEquals(PlatformUpdateStatus.MANGADEX_NEWER, store.load().chapterById("ch_1")!!.updateStatus())

        val pullBack = UploaderWorkers(ctx, services).syncMangaDexToCubari("sr_1", "1", scanOnly = false, backfillAll = false, resume = null)
        assertTrue(pullBack is ExecutionResult.Success)
        assertEquals(PlatformUpdateStatus.IN_SYNC, store.load().chapterById("ch_1")!!.updateStatus())
    }

    @Test
    fun `CliGitClient resetHardFromRemote recovers locked dirty repo and UI Restore confirms and resyncs`() = runTest {
        val tempRoot = Files.createTempDirectory("wom-git-restore-test").toFile()
        try {
            val bareDir = File(tempRoot, "remote.git").apply { mkdirs() }
            val seedDir = File(tempRoot, "seed")
            val cloneDir = File(tempRoot, "clone")

            fun runGit(dir: File, vararg args: String) {
                val proc = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                assertEquals(0, proc.waitFor(), "git ${args.joinToString(" ")} failed: $out")
            }

            runGit(bareDir, "init", "--bare")
            runGit(tempRoot, "clone", bareDir.absolutePath, seedDir.name)
            runGit(seedDir, "config", "user.name", "Test")
            runGit(seedDir, "config", "user.email", "test@local")
            File(seedDir, "series.json").writeText("""{"title":"Clean"}""")
            runGit(seedDir, "add", "-A")
            runGit(seedDir, "commit", "-m", "init")
            runGit(seedDir, "branch", "-M", "main")
            runGit(seedDir, "push", "-u", "origin", "main")

            val client = CliGitClient("", "")
            client.ensureRepo(cloneDir.absolutePath, bareDir.absolutePath, "main")
            assertEquals("""{"title":"Clean"}""", File(cloneDir, "series.json").readText())

            // Simulate corrupted local state: stale index.lock + modified tracked file + untracked file
            File(cloneDir, ".git/index.lock").writeText("locked")
            File(cloneDir, "series.json").writeText("""{"title":"Corrupted"}""")
            File(cloneDir, "junk.tmp").writeText("leftover")

            val msg = client.resetHardFromRemote(cloneDir.absolutePath, bareDir.absolutePath, "main")
            assertTrue("origin/main" in msg)
            assertFalse(File(cloneDir, ".git/index.lock").exists())
            assertFalse(File(cloneDir, "junk.tmp").exists())
            assertEquals("""{"title":"Clean"}""", File(cloneDir, "series.json").readText())
        } finally {
            tempRoot.deleteRecursively()
        }

        // UI Settings -> Restore from Git prompts confirmation, calls GitClient.resetHardFromRemote, and re-syncs repo
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        events.emit(UiEvent.Click("btn_restore_git", Ids.ACT_RESTORE_GIT))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM) as? DialogNode)

        events.emit(UiEvent.DialogAction(Ids.DLG_CONFIRM, isConfirmed = true))
        advanceUntilIdle()
        assertEquals(1, services.git.resetCount)
        assertTrue(sessionCtx.launchedCapabilities.any { it.second == "syncCubariGitRepo" })

        job.cancel()
    }

    @Test
    fun `ArubaPaths preserves MangaKoFi subfolder when base URL is root domain and avoids duplicating existing prefix`() {
        val rootBaseCfg = ArubaConfig(
            protocol = "FTP",
            host = "ftp.windsofresub.cloud",
            port = 21,
            username = "u",
            password = "p",
            chaptersPath = "/MangaKoFi",
            publicBaseUrl = "https://www.windsofresub.cloud/",
            maxConnections = 2
        )
        assertEquals(
            "https://www.windsofresub.cloud/MangaKoFi/The%20Beginning%20After%20The%20End/ch242/007.png",
            ArubaPaths.publicUrl(rootBaseCfg, "/MangaKoFi/The Beginning After The End/ch242/007.png")
        )
        assertEquals(
            "https://www.windsofresub.cloud/MangaKoFi/covers/The%20Beginning%20After%20The%20End.webp",
            ArubaPaths.publicUrl(rootBaseCfg, ArubaPaths.coverPath(rootBaseCfg, "The Beginning After The End", "webp"))
        )

        val prefixedBaseCfg = rootBaseCfg.copy(
            chaptersPath = "/www.windsofresub.cloud/MangaKoFi",
            publicBaseUrl = "https://www.windsofresub.cloud/MangaKoFi"
        )
        assertEquals(
            "https://www.windsofresub.cloud/MangaKoFi/The%20Beginning%20After%20The%20End/ch242/007.png",
            ArubaPaths.publicUrl(prefixedBaseCfg, "/www.windsofresub.cloud/MangaKoFi/The Beginning After The End/ch242/007.png")
        )
        assertEquals(
            "The Beginning After The End",
            ArubaPaths.inferSeriesFolderFromUrls(
                rootBaseCfg.publicBaseUrl,
                listOf("https://www.windsofresub.cloud/MangaKoFi/The%20Beginning%20After%20The%20End/ch242/007.png")
            )
        )
        assertEquals(
            "/MangaKoFi/covers/Obsidian.webp",
            ArubaPaths.remotePathFromUrl(rootBaseCfg, "https://www.windsofresub.cloud/MangaKoFi/covers/Obsidian.webp")
        )
        assertTrue(ArubaPaths.isReservedSeriesFolder("covers"))
    }

    @Test
    fun `PageImporter expand naturally sorts picked file lists and UI sort button sorts draft pages`() = runTest {
        val hostFs = FakeHostFs()
        hostFs.files["/tmp/10.png"] = "p10".encodeToByteArray()
        hostFs.files["/tmp/2.png"] = "p2".encodeToByteArray()
        hostFs.files["/tmp/1.png"] = "p1".encodeToByteArray()
        val expanded = PageImporter(pluginFs, hostFs).expand(listOf("/tmp/10.png", "/tmp/2.png", "/tmp/1.png"))
        assertEquals(listOf("/tmp/1.png", "/tmp/2.png", "/tmp/10.png"), expanded)

        val sr = Series(id = "sr_sort", title = "Sort Test", cubariSlug = "sort-test")
        val ch = Chapter(
            id = "ch_sort",
            seriesId = "sr_sort",
            number = "1",
            pages = listOf(
                PageRef("p10", "chapters/ch_sort/p10.png", "10.png"),
                PageRef("p2", "chapters/ch_sort/p2.png", "2.png"),
                PageRef("p1", "chapters/ch_sort/p1.png", "1.png")
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        pluginFs.writeFile(relPath("chapters/ch_sort/p10.png"), "10".encodeToByteArray())
        pluginFs.writeFile(relPath("chapters/ch_sort/p2.png"), "2".encodeToByteArray())
        pluginFs.writeFile(relPath("chapters/ch_sort/p1.png"), "1".encodeToByteArray())

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        val job = launch { plugin.openUiSession(sessionCtx, events).collect {} }
        advanceUntilIdle()

        events.emit(UiEvent.Click("btn_sort_pages", Ids.ACT_SORT_PAGES))
        advanceUntilIdle()
        assertEquals(
            listOf("1.png", "2.png", "10.png"),
            LibraryStore(pluginFs).load().chapterById("ch_sort")!!.pages.map { it.originalName }
        )
        job.cancel()
    }

    @Test
    fun `MangaDex upload session check and force cancel close dangling session via API and UI confirmation`() = runTest {
        val network = FakeNetwork()
        val deletedIds = mutableListOf<String>()
        network.responder = { req ->
            when {
                req.url == "https://api.custom.test/upload" && req.method == HttpMethod.GET -> HttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "result": "ok",
                          "data": {
                            "id": "sess-999",
                            "attributes": {"isCommitted": false, "isProcessed": false, "isDeleted": false, "version": 1},
                            "relationships": [{"id": "manga-abc", "type": "manga"}]
                          }
                        }
                    """.trimIndent().encodeToByteArray()
                )
                req.url == "https://api.custom.test/upload/sess-999" && req.method == HttpMethod.DELETE -> {
                    deletedIds += "sess-999"
                    HttpResponse(statusCode = 200, body = """{"result":"ok"}""".encodeToByteArray())
                }
                else -> HttpResponse(statusCode = 404, body = "{}".encodeToByteArray())
            }
        }
        val api = HttpMangaDexApi(network = network, apiBaseUrl = "https://api.custom.test")
        val found = api.activeUploadSession("tok")
        assertEquals("sess-999", found?.id)
        api.closeUploadSession("tok", "sess-999")
        assertEquals(listOf("sess-999"), deletedIds)

        // UI flow via UploaderActions + FakeServiceFactory
        services.mangadex.activeSession = MdUploadSession(id = "sess-live-1", isCommitted = false, isProcessed = false, isDeleted = false)
        val settingsWithMd = configuredSettings().toMutableMap().apply {
            this[SettingKeys.MD_ACCESS_TOKEN] = JsonPrimitive("tok-valid")
        }
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = settingsWithMd)
        UploaderActions(base, services).checkMangaDexUploadSession()
        val diagCheck = DiagnosticsStore(base.storage).loadAll()["md_session"]
        assertNotNull(diagCheck)
        assertTrue(diagCheck.ok)
        assertTrue("sess-live-1" in diagCheck.message)

        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        events.emit(UiEvent.Click("btn_md_cancel_session", Ids.ACT_MD_FORCE_CANCEL_SESSION))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM))
        events.emit(UiEvent.DialogAction(Ids.DLG_CONFIRM, isConfirmed = true))
        advanceUntilIdle()
        assertEquals(listOf("sess-live-1"), services.mangadex.closedSessionIds)
        assertNull(services.mangadex.activeSession)
        job.cancel()
    }

    @Test
    fun `Copy Cubari and MangaDex URL actions copy expected URLs to clipboard for series and chapter`() = runTest {
        val mdSeriesUuid = "12345678-1234-1234-1234-1234567890ab"
        val sr = Series(id = "sr_copy", title = "Copy Manga", cubariSlug = "copy-manga", mangadexId = mdSeriesUuid)
        val ch = Chapter(
            id = "ch_copy",
            seriesId = "sr_copy",
            number = "65",
            title = "Finale",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            mangadexChapterId = "md-chap-65"
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        val settingsWithRepo = configuredSettings().toMutableMap().apply {
            this[SettingKeys.CUBARI_REMOTE_URL] = JsonPrimitive("https://github.com/WindsOfOfficial/Manga")
            this[SettingKeys.CUBARI_BRANCH] = JsonPrimitive("master")
            this[SettingKeys.CUBARI_SERIES_DIR] = JsonPrimitive("")
        }
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = settingsWithRepo)
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        val job = launch { plugin.openUiSession(sessionCtx, events).collect {} }
        advanceUntilIdle()

        events.emit(UiEvent.Click("btn_copy_cubari_chapter", Ids.ACT_COPY_CUBARI_CHAPTER))
        events.emit(UiEvent.Click("btn_copy_md_chapter", Ids.ACT_COPY_MANGADEX_CHAPTER))
        events.emit(UiEvent.Click("btn_series_edit", Ids.ACT_SERIES_EDIT))
        advanceUntilIdle()
        events.emit(UiEvent.Click("btn_copy_cubari_series", Ids.ACT_COPY_CUBARI_SERIES))
        events.emit(UiEvent.Click("btn_copy_md_series", Ids.ACT_COPY_MANGADEX_SERIES))
        advanceUntilIdle()

        assertEquals(4, services.copiedTexts.size)
        assertTrue(services.copiedTexts[0].startsWith("https://cubari.moe/read/gist/"))
        assertTrue(services.copiedTexts[0].endsWith("/65/1/"))
        assertEquals("https://mangadex.org/chapter/md-chap-65", services.copiedTexts[1])
        assertTrue(services.copiedTexts[2].startsWith("https://cubari.moe/read/gist/"))
        assertFalse(services.copiedTexts[2].endsWith("/65/1/"))
        assertEquals("https://mangadex.org/title/$mdSeriesUuid", services.copiedTexts[3])
        job.cancel()
    }

    // ─────────────────── v1.0.4 Tests ───────────────────

    @Test
    fun `publishToArubaAndCubari performs JSON-only metadata update and key rename without touching Aruba`() = runTest {
        val sr = Series(
            id = "sr_meta",
            title = "Meta Manga",
            cubariSlug = "meta-manga",
            cubariSnapshot = CubariSeriesSnapshot(title = "Meta Manga", description = "", author = "", artist = "")
        )
        val initialUrls = listOf("https://cdn.aruba.test/chapters/Meta%20Manga/ch10/001.png")
        // Chapter is edited locally to number "10.5", volume "3", title "Revised Title",
        // while remote snapshot still records key "10", volume "2", title "Old Title".
        val ch = Chapter(
            id = "ch_10",
            seriesId = "sr_meta",
            number = "10.5",
            volume = "3",
            title = "Revised Title",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariUpdatedAtEpochSec = 1_700_000_000L,
            cubariPageUrls = initialUrls,
            cubariSnapshot = CubariChapterSnapshot(
                key = "10",
                volume = "2",
                title = "Old Title",
                pageUrls = initialUrls
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        pluginFs.writeTextFile(
            relPath("cubari-repo/series/meta-manga.json"),
            """{"title":"Meta Manga","chapters":{"10":{"title":"Old Title","volume":"2","groups":{"Uploader":["https://cdn.aruba.test/chapters/Meta%20Manga/ch10/001.png"]}}}}"""
        )

        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        val plan = ChangeDetector.planCubariPush(sr, ch)
        assertEquals(TargetPushKind.METADATA_ONLY, plan.kind)

        services.clock.epochSec = 1_700_010_000L
        UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_meta", "ch_10", UploaderWorkers.OP_PUBLISH, resume = null)

        assertEquals(0, services.arubaCalls)
        assertTrue(services.aruba.files.isEmpty())
        assertEquals(1, services.git.pushedCount)

        val jsonText = pluginFs.readTextFile(relPath("cubari-repo/series/meta-manga.json"))!!
        val parsed = CubariJson.parse(jsonText)
        val chaptersObj = parsed["chapters"]!!.jsonObject
        assertFalse("10" in chaptersObj, "Old key '10' must be removed after key rename")
        assertTrue("10.5" in chaptersObj, "Renamed key '10.5' must exist")
        val updatedEntry = chaptersObj["10.5"]!!.jsonObject
        assertEquals("3", updatedEntry["volume"]?.jsonPrimitive?.content)
        assertEquals("Revised Title", updatedEntry["title"]?.jsonPrimitive?.content)

        val savedCh = LibraryStore(pluginFs).load().chapterById("ch_10")!!
        assertEquals("10.5", savedCh.cubariKey)
        assertEquals(initialUrls, savedCh.cubariPageUrls)
        assertEquals("10.5", savedCh.cubariSnapshot?.key)
        assertEquals("3", savedCh.cubariSnapshot?.volume)
        assertEquals("Revised Title", savedCh.cubariSnapshot?.title)
        assertEquals(TargetPushKind.UP_TO_DATE, ChangeDetector.planCubariPush(sr, savedCh).kind)
    }

    @Test
    fun `publishToMangaDex updates metadata via PUT chapter without creating upload sessions`() = runTest {
        val sr = Series(id = "sr_md_meta", title = "MD Meta", cubariSlug = "md-meta", mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val ch = Chapter(
            id = "ch_md_meta",
            seriesId = "sr_md_meta",
            number = "42",
            volume = "5",
            title = "Edited MD Title",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            mangadexChapterId = "md-chap-42",
            mangadexVersion = 2,
            mangadexPublishedAtEpochSec = 1_700_000_100L,
            mangadexUpdatedAtEpochSec = 1_700_000_100L,
            mdSnapshot = MangaDexChapterSnapshot(
                chapterId = "md-chap-42",
                version = 2,
                number = "42",
                volume = "4",
                title = "Old MD Title",
                language = "en"
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        services.clock.epochSec = 1_700_020_000L
        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())

        UploaderWorkers(ctx, services).publishToMangaDex("sr_md_meta", "ch_md_meta", resume = null)

        assertEquals(0, services.mangadex.sessionCounter)
        assertTrue(services.mangadex.uploadedFiles.isEmpty())
        assertTrue(services.mangadex.committedDrafts.isEmpty())
        assertEquals(1, services.mangadex.updatedMetadataCalls.size)
        val (id, ver, draft) = services.mangadex.updatedMetadataCalls.single()
        assertEquals("md-chap-42", id)
        assertEquals(2, ver)
        assertEquals("5", draft.volume)
        assertEquals("42", draft.chapter)
        assertEquals("Edited MD Title", draft.title)

        val savedCh = LibraryStore(pluginFs).load().chapterById("ch_md_meta")!!
        assertEquals(3, savedCh.mangadexVersion)
        assertEquals("5", savedCh.mdSnapshot?.volume)
        assertEquals("Edited MD Title", savedCh.mdSnapshot?.title)
        assertEquals(TargetPushKind.UP_TO_DATE, ChangeDetector.planMangaDexPush(savedCh, "en").kind)
    }

    @Test
    fun `failed Cubari push keeps remote snapshot unchanged and Resync Cubari merges safely without losing local edits`() = runTest {
        val sr = Series(
            id = "sr_resync",
            title = "Resync Title",
            cubariSlug = "resync-title",
            cubariSnapshot = CubariSeriesSnapshot(title = "Resync Title", description = "", author = "", artist = "")
        )
        val ch1Urls = listOf("https://cdn.aruba.test/chapters/Resync%20Title/ch1/001.png")
        val ch2Urls = listOf("https://cdn.aruba.test/chapters/Resync%20Title/ch2/001.png")
        // ch_1 has local-only edits (volume "2", renamed key "1.5", new title) whose push will fail.
        val ch1 = Chapter(
            id = "ch_1",
            seriesId = "sr_resync",
            number = "1.5",
            volume = "2",
            title = "Local Edited Title",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariUpdatedAtEpochSec = 1_700_000_000L,
            cubariPageUrls = ch1Urls,
            cubariSnapshot = CubariChapterSnapshot(
                key = "1",
                volume = "1",
                title = "Original Remote Title",
                pageUrls = ch1Urls
            )
        )
        // ch_2 was removed remotely on Cubari before resync.
        val ch2 = Chapter(
            id = "ch_2",
            seriesId = "sr_resync",
            number = "2",
            volume = "1",
            title = "Deleted Remotely",
            cubariPublishedAtEpochSec = 1_700_000_000L,
            cubariUpdatedAtEpochSec = 1_700_000_000L,
            cubariPageUrls = ch2Urls,
            cubariSnapshot = CubariChapterSnapshot(
                key = "2",
                volume = "1",
                title = "Deleted Remotely",
                pageUrls = ch2Urls
            )
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch1, ch2)) }
        pluginFs.writeTextFile(
            relPath("cubari-repo/series/resync-title.json"),
            """{"title":"Resync Title","chapters":{"1":{"title":"Original Remote Title","volume":"1","groups":{"Uploader":["https://cdn.aruba.test/chapters/Resync%20Title/ch1/001.png"]}}}}"""
        )

        val ctx = FakePluginContext(fileSystem = pluginFs, initialSettings = configuredSettings())
        services.git.failNextPush = "Git push rejected (non-fast-forward)"

        val pushResult = UploaderWorkers(ctx, services).publishToArubaAndCubari("sr_resync", "ch_1", UploaderWorkers.OP_PUBLISH, resume = null)
        assertTrue(pushResult is ExecutionResult.Error)
        assertTrue(ctx.logger.entries.any { "Git push rejected" in it })

        // Snapshot remains at remote "1" while local edits stay in chapter fields.
        val afterFail = LibraryStore(pluginFs).load().chapterById("ch_1")!!
        assertEquals("1.5", afterFail.number)
        assertEquals("Local Edited Title", afterFail.title)
        assertEquals("1", afterFail.cubariSnapshot?.key)
        assertEquals("Original Remote Title", afterFail.cubariSnapshot?.title)

        // Upstream repo now has an extra remote chapter "3" (and chapter "2" is gone), while "1" updated its remote pages.
        services.git.overwriteOnPull = true
        services.git.remoteSeries["resync-title"] = CubariJson.parse(
            """{
                "title":"Resync Title",
                "description":"Upstream Desc",
                "chapters":{
                    "1":{"title":"Original Remote Title","volume":"1","groups":{"Uploader":["https://cdn.aruba.test/chapters/Resync%20Title/ch1/001.png","https://cdn.aruba.test/chapters/Resync%20Title/ch1/002.png"]}},
                    "3":{"title":"Brand New Ch 3","volume":"1","groups":{"Uploader":["https://cdn.aruba.test/chapters/Resync%20Title/ch3/001.png"]}}
                }
            }"""
        )

        UploaderWorkers(ctx, services).syncCubariGitRepo(push = false, resume = null)

        val libAfterResync = LibraryStore(pluginFs).load()
        val seriesChapters = libAfterResync.chaptersOf("sr_resync")
        // Must NOT create a duplicate chapter "1" vs locally renamed "1.5"
        assertEquals(3, seriesChapters.size)
        val mergedCh1 = libAfterResync.chapterById("ch_1")!!
        assertEquals("1.5", mergedCh1.number)
        assertEquals("2", mergedCh1.volume)
        assertEquals("Local Edited Title", mergedCh1.title)
        assertEquals(2, mergedCh1.cubariPageUrls.size)
        assertEquals("1", mergedCh1.cubariSnapshot?.key)
        assertEquals(2, mergedCh1.cubariSnapshot?.pageUrls?.size)

        val mergedCh2 = libAfterResync.chapterById("ch_2")!!
        assertFalse(mergedCh2.isOnCubari, "Chapter absent on remote JSON must clear Cubari status")
        assertNull(mergedCh2.cubariSnapshot)
        assertTrue(seriesChapters.any { it.cubariKey == "3" })
        assertEquals("Upstream Desc", libAfterResync.seriesById("sr_resync")?.description)
    }

    @Test
    fun `series metadata push, change-aware UI badges and buttons, MD hold skip for metadata-only, and Revert to Cubari`() = runTest {
        val now = services.clock.epochSec
        val sr = Series(
            id = "sr_ui",
            title = "Edited Series Name",
            description = "Fresh description",
            cubariSlug = "test-series",
            mangadexId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            cubariSnapshot = CubariSeriesSnapshot(title = "Original Series Name", description = "Old desc", author = "", artist = "")
        )
        val urls = listOf("https://cdn.aruba.test/chapters/test/ch1/001.png")
        // Chapter is within active 12h preview hold AND has metadata-only edit on both Cubari and MD.
        val ch = Chapter(
            id = "ch_ui",
            seriesId = "sr_ui",
            number = "1",
            volume = "9",
            title = "Edited Chapter Title",
            cubariPublishedAtEpochSec = now - 600L,
            cubariUpdatedAtEpochSec = now - 600L,
            cubariPageUrls = urls,
            cubariSnapshot = CubariChapterSnapshot(key = "1", volume = "1", title = "Old Title", pageUrls = urls),
            mangadexChapterId = "md-ui-1",
            mangadexVersion = 1,
            mangadexPublishedAtEpochSec = now - 500L,
            mangadexUpdatedAtEpochSec = now - 500L,
            mdSnapshot = MangaDexChapterSnapshot(chapterId = "md-ui-1", version = 1, number = "1", volume = "1", title = "Old Title", language = "en")
        )
        LibraryStore(pluginFs).mutate { it.copy(series = listOf(sr), chapters = listOf(ch)) }
        pluginFs.writeTextFile(
            relPath("cubari-repo/series/test-series.json"),
            """{"title":"Original Series Name","description":"Old desc","chapters":{"1":{"title":"Old Title","volume":"1","groups":{"Uploader":["https://cdn.aruba.test/chapters/test/ch1/001.png"]}}}}"""
        )

        val settings = configuredSettings()
        val plugin = MangaUploaderPlugin(MangaUploaderSettings())
        val base = FakePluginContext(fileSystem = pluginFs, initialSettings = settings)
        val sessionCtx = FakeSessionContext(
            entry = MangaUploaderPluginPluginEntry(plugin),
            scope = this,
            base = base,
            locksState = MutableStateFlow(plugin.checkLocks(base))
        )
        val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        var latestTree: UiNodeTree? = null
        val job = launch { plugin.openUiSession(sessionCtx, events).collect { latestTree = it } }
        advanceUntilIdle()

        val pushCubariBtn = latestTree!!.findById("btn_push_cubari") as ButtonNode
        val pushMdBtn = latestTree!!.findById("btn_push_mangadex") as ButtonNode
        assertEquals("Push Meta -> Cubari", pushCubariBtn.label)
        assertEquals("Push Meta -> MD", pushMdBtn.label)
        assertEquals("Local edit", (latestTree!!.findById("pill_local_ch_ui") as BadgeNode).text)
        val localBanner = latestTree!!.findById("chapter_local_changes_banner") as AlertBannerNode
        assertTrue("Local-only changes" in localBanner.message)
        assertNotNull(latestTree!!.findById("btn_sync_repo_ws"))

        // Clicking Push Meta -> MD while hold is active should queue directly (NO hold confirmation dialog for metadata-only edit!)
        events.emit(UiEvent.Click("btn_push_mangadex", Ids.ACT_CH_PUSH_MANGADEX))
        advanceUntilIdle()
        assertNull(latestTree!!.findById(Ids.DLG_CONFIRM))
        assertEquals("publishToMangaDex", sessionCtx.launchedCapabilities.last().second)

        // Revert chapter back to remote Cubari state via confirmation dialog
        events.emit(UiEvent.Click("btn_revert_ch_cubari", Ids.ACT_REVERT_CHAPTER_CUBARI))
        advanceUntilIdle()
        assertNotNull(latestTree!!.findById(Ids.DLG_CONFIRM))
        events.emit(UiEvent.DialogAction(Ids.DLG_CONFIRM, isConfirmed = true))
        advanceUntilIdle()

        val revertedCh = LibraryStore(pluginFs).load().chapterById("ch_ui")!!
        assertEquals("Old Title", revertedCh.title)
        assertEquals("1", revertedCh.volume)
        assertEquals("Push Ch. -> Cubari (Synced)", (latestTree!!.findById("btn_push_cubari") as ButtonNode).label)

        // Series editor: shows local-only series banner and pushes OP_SERIES_META directly
        events.emit(UiEvent.Click("btn_series_edit", Ids.ACT_SERIES_EDIT))
        advanceUntilIdle()
        val seriesBanner = latestTree!!.findById("series_local_changes_banner") as AlertBannerNode
        assertTrue("title" in seriesBanner.message && "description" in seriesBanner.message)
        events.emit(UiEvent.Click("btn_push_series_meta", Ids.ACT_PUSH_SERIES_META))
        advanceUntilIdle()
        val lastCap = sessionCtx.launchedCapabilities.last()
        assertEquals("publishToArubaAndCubari", lastCap.second)
        assertEquals(UploaderWorkers.OP_SERIES_META, lastCap.third["operation"]?.jsonPrimitive?.content)

        // Verify OP_SERIES_META worker updates series JSON without touching Aruba
        UploaderWorkers(base, services).publishToArubaAndCubari("sr_ui", "", UploaderWorkers.OP_SERIES_META, resume = null)
        assertEquals(0, services.arubaCalls)
        val updatedJson = CubariJson.parse(pluginFs.readTextFile(relPath("cubari-repo/series/test-series.json"))!!)
        assertEquals("Edited Series Name", updatedJson["title"]?.jsonPrimitive?.content)
        assertEquals("Fresh description", updatedJson["description"]?.jsonPrimitive?.content)

        // Smart bulk push (ACT_PUSH_SELECTED) routes Cubari-synced chapters with local MD edits directly to Stage 2 MangaDex
        job.cancel()
        val chSynced = revertedCh.copy(
            volume = "7",
            cubariSnapshot = CubariChapterSnapshot(key = "1", volume = "7", title = "Old Title", pageUrls = urls),
            mdSnapshot = MangaDexChapterSnapshot(chapterId = "md-ui-1", version = 1, number = "1", volume = "1", title = "Old Title", language = "en")
        )
        LibraryStore(pluginFs).mutate { LibraryOps.upsertChapter(it, chSynced) }
        val events2 = MutableSharedFlow<UiEvent>(extraBufferCapacity = 32)
        val job2 = launch { plugin.openUiSession(sessionCtx, events2).collect {} }
        advanceUntilIdle()
        events2.emit(UiEvent.Click("btn_select_all", Ids.ACT_SELECT_ALL))
        advanceUntilIdle()
        val beforeBulkCount = sessionCtx.launchedCapabilities.size
        events2.emit(UiEvent.Click(Ids.SPLIT_PUSH, Ids.ACT_PUSH_SELECTED))
        advanceUntilIdle()
        val bulkQueued = sessionCtx.launchedCapabilities.drop(beforeBulkCount)
        assertEquals(listOf("publishToMangaDex"), bulkQueued.map { it.second })

        job2.cancel()
    }
}
