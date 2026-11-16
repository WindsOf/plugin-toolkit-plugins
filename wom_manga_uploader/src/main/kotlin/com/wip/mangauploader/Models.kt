package com.wip.mangauploader

import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * Per-series override of the preview-hold anchor ("when does the MangaDex hold window start?").
 */
@Serializable
enum class PreviewAnchorOverride(val label: String) {
    INHERIT("Inherit Global"),
    LATEST_MANGADEX("Latest MangaDex Chapter"),
    CUBARI_PUBLISH("Cubari Publish Timestamp")
}

/** Publication status values offered in the series editor. */
val SERIES_STATUSES: Map<String, String> = linkedMapOf(
    "ongoing" to "Ongoing",
    "completed" to "Completed",
    "hiatus" to "Hiatus",
    "cancelled" to "Cancelled"
)

/**
 * One page image of a chapter. Either stored locally inside the plugin data directory
 * (`storedPath`, relative to [org.wip.plugintoolkit.api.PluginFileSystem]) or referencing
 * an already-hosted image (`remoteUrl`, e.g. on Aruba/Cubari) until downloaded or replaced.
 */
@Serializable
data class PageRef(
    val id: String,
    val storedPath: String = "",
    val originalName: String,
    val remoteUrl: String? = null,
    val contentHash: String? = null
) {
    val isLocal: Boolean get() = storedPath.isNotBlank()
    val isRemoteOnly: Boolean get() = !isLocal && !remoteUrl.isNullOrBlank()
}

@Serializable
data class CubariSeriesSnapshot(
    val title: String = "",
    val description: String = "",
    val author: String = "",
    val artist: String = "",
    val coverUrl: String? = null
)

@Serializable
data class CubariChapterSnapshot(
    val key: String,
    val volume: String = "",
    val title: String = "",
    val pageUrls: List<String> = emptyList(),
    val pageSignature: List<String>? = null,
    val pageHashes: List<String>? = null
)

@Serializable
data class MangaDexChapterSnapshot(
    val chapterId: String,
    val version: Int = 1,
    val number: String = "",
    val volume: String = "",
    val title: String = "",
    val language: String = "en",
    val pageSignature: List<String>? = null,
    val pageCount: Int? = null,
    val pageHashes: List<String>? = null
)

enum class TargetPushKind {
    NEW_PUBLISH,
    PAGES_AND_META,
    METADATA_ONLY,
    UP_TO_DATE
}

data class ChapterPushPlan(
    val kind: TargetPushKind,
    val changedFields: List<String> = emptyList(),
    val previousRemoteKey: String? = null
)

@Serializable
data class Series(
    val id: String,
    val title: String = "",
    val description: String = "",
    val author: String = "",
    val artist: String = "",
    val status: String = "ongoing",
    /** Relative path inside the plugin data dir of the locally stored cover (if any). */
    val coverPath: String? = null,
    /** Public Aruba URL of the cover as written into the Cubari JSON. */
    val coverUrl: String? = null,
    /** MangaDex manga UUID this series is linked to. */
    val mangadexId: String? = null,
    /** Per-series override of `defaultPreviewHours` (null = inherit global). */
    val overridePreviewHours: Int? = null,
    val overridePreviewAnchor: PreviewAnchorOverride = PreviewAnchorOverride.INHERIT,
    /** Position used by the "Custom Order" sort. */
    val order: Int = 0,
    val lastUpdatedEpochSec: Long? = null,
    /** File name (without extension) of the Cubari JSON inside the repo series directory. */
    val cubariSlug: String = sanitizeFolderName(title.ifBlank { id }),
    /** Remote folder name under Aruba `chaptersPath` (defaults to [title] / [cubariSlug]). */
    val arubaFolder: String = "",
    /** Per-series zero-padding override (`0` = unpadded `1.png`, `2`/`3`/`4` = left padded, `null` = use global setting). */
    val arubaZeroPadDigits: Int? = null,
    /** Last confirmed remote Cubari series metadata snapshot used to detect local-only edits. */
    val cubariSnapshot: CubariSeriesSnapshot? = null
) {
    /** Remote series folder name used under Aruba `chaptersPath`. */
    val effectiveArubaFolder: String
        get() = arubaFolder.trim().takeIf { it.isNotEmpty() }
            ?.let(::sanitizeFolderName)
            ?: sanitizeFolderName(title.ifBlank { cubariSlug.ifBlank { id } })
}

@Serializable
data class Chapter(
    val id: String,
    val seriesId: String,
    val volume: String = "",
    val number: String = "",
    val title: String = "",
    val pages: List<PageRef> = emptyList(),
    val createdAtEpochSec: Long = 0L,
    val cubariPublishedAtEpochSec: Long? = null,
    val cubariUpdatedAtEpochSec: Long? = null,
    val cubariPageUrls: List<String> = emptyList(),
    val mangadexChapterId: String? = null,
    val mangadexPublishedAtEpochSec: Long? = null,
    val mangadexUpdatedAtEpochSec: Long? = null,
    val mangadexVersion: Int? = null,
    val lastSyncedCubariTs: Long? = null,
    val lastSyncedMdTs: Long? = null,
    /** Last confirmed remote Cubari chapter snapshot used to separate metadata-only edits from page changes. */
    val cubariSnapshot: CubariChapterSnapshot? = null,
    /** Last confirmed remote MangaDex chapter snapshot used to separate metadata-only edits from page changes. */
    val mdSnapshot: MangaDexChapterSnapshot? = null
) {
    val isOnCubari: Boolean get() = cubariPublishedAtEpochSec != null
    val isOnMangaDex: Boolean get() = mangadexChapterId != null

    /** Latest known update/publish timestamp on Cubari. */
    val effectiveCubariTimestampSec: Long? get() = cubariUpdatedAtEpochSec ?: cubariPublishedAtEpochSec

    /** Latest known update/publish timestamp on MangaDex. */
    val effectiveMangaDexTimestampSec: Long? get() = mangadexUpdatedAtEpochSec ?: mangadexPublishedAtEpochSec

    /** Number as sortable value; unparsable numbers sort last. */
    val numberValue: Double get() = number.trim().toDoubleOrNull() ?: Double.MAX_VALUE

    /** Cubari JSON chapter key (normalised so `"12.0"` and `"12"` collide). */
    val cubariKey: String get() = normalizeChapterKey(number)

    val syncState: ChapterSyncState
        get() = when {
            isOnCubari && isOnMangaDex -> ChapterSyncState.SYNCED
            isOnMangaDex -> ChapterSyncState.MISSING_CUBARI
            isOnCubari -> ChapterSyncState.CUBARI_ONLY
            else -> ChapterSyncState.STAGING
        }

    /**
     * Compares Cubari vs. MangaDex timestamps when both platforms exist.
     * When a chapter was copied across platforms inside this plugin, [lastSyncedCubariTs] and
     * [lastSyncedMdTs] record both sides' timestamps at transfer time so clock/post skew does not
     * trigger false "newer" warnings until a side actually advances beyond its synced snapshot.
     */
    fun updateStatus(toleranceSec: Long = 60L): PlatformUpdateStatus {
        val cubariTs = effectiveCubariTimestampSec
        val mdTs = effectiveMangaDexTimestampSec
        if (cubariTs == null || mdTs == null) return PlatformUpdateStatus.SINGLE_PLATFORM
        val syncedC = lastSyncedCubariTs
        val syncedM = lastSyncedMdTs
        if (syncedC != null && syncedM != null) {
            val cubariAdvanced = cubariTs > syncedC + toleranceSec
            val mdAdvanced = mdTs > syncedM + toleranceSec
            if (!cubariAdvanced && !mdAdvanced) return PlatformUpdateStatus.IN_SYNC
            if (cubariAdvanced && !mdAdvanced) return PlatformUpdateStatus.CUBARI_NEWER
            if (mdAdvanced && !cubariAdvanced) return PlatformUpdateStatus.MANGADEX_NEWER
        }
        val diff = cubariTs - mdTs
        return when {
            diff > toleranceSec -> PlatformUpdateStatus.CUBARI_NEWER
            diff < -toleranceSec -> PlatformUpdateStatus.MANGADEX_NEWER
            else -> PlatformUpdateStatus.IN_SYNC
        }
    }
}

enum class PlatformUpdateStatus { SINGLE_PLATFORM, IN_SYNC, CUBARI_NEWER, MANGADEX_NEWER }

enum class ChapterSyncState { STAGING, MISSING_CUBARI, CUBARI_ONLY, SYNCED }

/** Reachability and display metadata for one remote page checked on Cubari/Aruba or MangaDex. */
@Serializable
data class RemotePageStatus(
    val index: Int,
    val url: String,
    val fileName: String,
    val statusCode: Int
) {
    val isReachable: Boolean get() = statusCode in 200..399
    val statusLabel: String
        get() = when {
            statusCode in 200..399 -> "OK"
            statusCode > 0 -> "HTTP $statusCode"
            else -> "Unreachable"
        }
}

/** Result of inspecting a chapter's uploaded images on `cubari` or `mangadex`. */
@Serializable
data class RemoteChapterPages(
    val chapterId: String,
    val source: String,
    val checkedAtEpochSec: Long,
    val pages: List<RemotePageStatus> = emptyList(),
    val errorMessage: String? = null
) {
    val reachableCount: Int get() = pages.count { it.isReachable }
}

/** Whole local library persisted as `library.json` in the plugin data dir. */
@Serializable
data class Library(
    val series: List<Series> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val schemaVersion: Int = 1
) {
    fun chaptersOf(seriesId: String): List<Chapter> =
        chapters.filter { it.seriesId == seriesId }.sortedByDescending { it.numberValue }

    fun missingCubariCount(seriesId: String): Int =
        chapters.count { it.seriesId == seriesId && it.syncState == ChapterSyncState.MISSING_CUBARI }

    fun seriesById(id: String?): Series? = series.firstOrNull { it.id == id }
    fun chapterById(id: String?): Chapter? = chapters.firstOrNull { it.id == id }
}

/** Lower-case ASCII slug helper kept for canonical IDs / fallback formatting. */
fun slugify(text: String): String {
    val slug = text.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return slug.ifEmpty { "series" }
}

/**
 * Sanitizes a series title into a filesystem- and FTP/Git-safe folder or JSON basename
 * while preserving readable casing, spaces, and apostrophes (e.g. `Solo Leveling Ragnarok`).
 */
fun sanitizeFolderName(text: String): String {
    val cleaned = text.trim()
        .replace(Regex("[\\u0000-\\u001F/\\\\<>:\"|?*]+"), "-")
        .trim('.')
        .trim()
    return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "series" else cleaned
}

/**
 * Pure diffing rules comparing local chapter/series state against confirmed remote snapshots
 * so push actions can update only metadata when pages have not changed and surface local-only edits.
 */
object ChangeDetector {
    fun pageListSignature(pages: List<PageRef>): List<String> = pages.map { p ->
        if (p.isLocal) "local:${p.storedPath}:${p.originalName}"
        else "remote:${p.remoteUrl.orEmpty()}"
    }

    fun hasPageChanges(
        pages: List<PageRef>,
        snapshotPageSig: List<String>?,
        isPublishedOnTarget: Boolean,
        snapshotPageHashes: List<String>? = null
    ): Boolean {
        if (!isPublishedOnTarget) return true
        if (pages.isEmpty()) return false
        if (snapshotPageSig == null) return pages.any { it.isLocal }
        if (pageListSignature(pages) != snapshotPageSig) return true
        if (snapshotPageHashes != null && snapshotPageHashes.size == pages.size) {
            val currentHashes = pages.map { it.contentHash }
            if (currentHashes.all { !it.isNullOrBlank() } && currentHashes != snapshotPageHashes) return true
        }
        return false
    }

    fun cubariSeriesSnapshot(series: Series): CubariSeriesSnapshot = CubariSeriesSnapshot(
        title = series.title.trim(),
        description = series.description.trim(),
        author = series.author.trim(),
        artist = series.artist.trim(),
        coverUrl = series.coverUrl
    )

    fun seriesMetaChanges(series: Series): List<String> {
        val snap = series.cubariSnapshot ?: return emptyList()
        return buildList {
            if (series.title.trim() != snap.title) add("title")
            if (series.description.trim() != snap.description) add("description")
            if (series.author.trim() != snap.author) add("author")
            if (series.artist.trim() != snap.artist) add("artist")
        }
    }

    fun planCubariPush(
        series: Series?,
        chapter: Chapter,
        effective: Chapter = chapter,
        includeSeriesMeta: Boolean = true
    ): ChapterPushPlan {
        if (!chapter.isOnCubari) {
            return ChapterPushPlan(TargetPushKind.NEW_PUBLISH, changedFields = listOf("unpublished"))
        }
        val snap = chapter.cubariSnapshot
        val pagesChanged = hasPageChanges(effective.pages, snap?.pageSignature, isPublishedOnTarget = true, snap?.pageHashes)
        val changes = buildList {
            if (pagesChanged) add("pages")
            if (snap != null) {
                if (effective.cubariKey != snap.key) add("number")
                if (effective.volume.trim() != snap.volume) add("volume")
                if (effective.title.trim() != snap.title) add("title")
            }
            if (includeSeriesMeta && series != null && seriesMetaChanges(series).isNotEmpty()) {
                add("series meta")
            }
        }
        val kind = when {
            pagesChanged -> TargetPushKind.PAGES_AND_META
            changes.isNotEmpty() -> TargetPushKind.METADATA_ONLY
            else -> TargetPushKind.UP_TO_DATE
        }
        return ChapterPushPlan(kind = kind, changedFields = changes, previousRemoteKey = snap?.key)
    }

    fun planMangaDexPush(
        chapter: Chapter,
        language: String,
        effective: Chapter = chapter
    ): ChapterPushPlan {
        if (!chapter.isOnMangaDex) {
            return ChapterPushPlan(TargetPushKind.NEW_PUBLISH, changedFields = listOf("unpublished"))
        }
        val snap = chapter.mdSnapshot
        val cubariPageCountMismatch = effective.pages.isEmpty() &&
            effective.cubariPageUrls.isNotEmpty() &&
            snap?.pageCount != null &&
            effective.cubariPageUrls.size != snap.pageCount
        val pagesChanged = hasPageChanges(effective.pages, snap?.pageSignature, isPublishedOnTarget = true, snap?.pageHashes) ||
            cubariPageCountMismatch
        val changes = buildList {
            if (pagesChanged) add("pages")
            if (snap != null) {
                if (normalizeChapterKey(effective.number) != normalizeChapterKey(snap.number)) add("number")
                if (effective.volume.trim() != snap.volume) add("volume")
                if (effective.title.trim() != snap.title) add("title")
                if (language.trim() != snap.language) add("language")
            }
        }
        val kind = when {
            pagesChanged -> TargetPushKind.PAGES_AND_META
            changes.isNotEmpty() -> TargetPushKind.METADATA_ONLY
            else -> TargetPushKind.UP_TO_DATE
        }
        return ChapterPushPlan(kind = kind, changedFields = changes)
    }
}

/**
 * `"ch12.0"` / `"12.0"` -> `"12"`, `"ch12.5"` / `" 7.50 "` -> `"7.5"`;
 * non-numeric values have any leading `ch` prefix stripped and are trimmed only.
 */
fun normalizeChapterKey(number: String): String {
    val stripped = number.trim().replaceFirst(Regex("^(?i)ch\\.?\\s*"), "").trim()
    val decimal = runCatching { BigDecimal(stripped) }.getOrNull() ?: return stripped
    val canonical = decimal.stripTrailingZeros()
    return if (canonical.scale() <= 0) canonical.toBigInteger().toString() else canonical.toPlainString()
}

/** Lowercase SHA-256 hex digest of [bytes]. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        hex.append("0123456789abcdef"[v ushr 4])
        hex.append("0123456789abcdef"[v and 0x0F])
    }
    return hex.toString()
}

/**
 * Pure incremental page matching and cycle-safe rename planning shared by Aruba/Cubari and MangaDex publishers.
 */
object IncrementalPagePlanner {

    /**
     * Matches each page in [currentPages] to at most one unused prior slot index in
     * `0 until maxOf(previousSignatures.size, previousHashes.size, previousUrls.size)` that satisfies
     * [isPreviousEligible]. Returns `null` at indices whose bytes must be freshly uploaded.
     */
    fun matchExistingPages(
        currentPages: List<PageRef>,
        currentHashes: List<String?>,
        previousSignatures: List<String>,
        previousHashes: List<String> = emptyList(),
        previousUrls: List<String> = emptyList(),
        isPreviousEligible: (Int) -> Boolean = { true },
        requireSameExtension: Boolean = false
    ): List<Int?> {
        val totalPrev = maxOf(previousSignatures.size, previousHashes.size, previousUrls.size)
        if (currentPages.isEmpty() || totalPrev == 0) return List(currentPages.size) { null }

        val curSigs = ChangeDetector.pageListSignature(currentPages)
        val matched = arrayOfNulls<Int>(currentPages.size)
        val usedPrev = BooleanArray(totalPrev)

        fun extCompatible(curIdx: Int, prevIdx: Int): Boolean {
            if (!requireSameExtension) return true
            val curExt = currentPages[curIdx].originalName.substringAfterLast('.', "").lowercase()
            val prevUrlExt = previousUrls.getOrNull(prevIdx)
                ?.substringBefore('?')?.substringBefore('#')
                ?.substringAfterLast('/')?.substringAfterLast('.', "")
                ?.lowercase().orEmpty()
            return curExt.isEmpty() || prevUrlExt.isEmpty() || curExt == prevUrlExt
        }

        fun hashCompatible(curIdx: Int, prevIdx: Int): Boolean {
            val ch = currentHashes.getOrNull(curIdx)
            val ph = previousHashes.getOrNull(prevIdx)
            return ch.isNullOrBlank() || ph.isNullOrBlank() || ch == ph
        }

        fun matchesSig(curIdx: Int, prevIdx: Int): Boolean {
            val prevSig = previousSignatures.getOrNull(prevIdx)
            return !prevSig.isNullOrBlank() && curSigs[curIdx] == prevSig && hashCompatible(curIdx, prevIdx)
        }

        fun matchesUrl(curIdx: Int, prevIdx: Int): Boolean {
            val curUrl = currentPages[curIdx].remoteUrl
            val prevUrl = previousUrls.getOrNull(prevIdx)
            return !curUrl.isNullOrBlank() && curUrl == prevUrl && hashCompatible(curIdx, prevIdx)
        }

        fun matchesHash(curIdx: Int, prevIdx: Int): Boolean {
            val ch = currentHashes.getOrNull(curIdx)
            val ph = previousHashes.getOrNull(prevIdx)
            return !ch.isNullOrBlank() && ch == ph
        }

        fun claim(curIdx: Int, prevIdx: Int) {
            matched[curIdx] = prevIdx
            usedPrev[prevIdx] = true
        }

        // Pass 1: exact same-index match avoids unnecessary renames.
        for (i in currentPages.indices) {
            if (i < totalPrev && !usedPrev[i] && isPreviousEligible(i) && extCompatible(i, i)) {
                if (matchesSig(i, i) || matchesUrl(i, i) || matchesHash(i, i)) {
                    claim(i, i)
                }
            }
        }

        // Pass 2: match shifted/reordered pages in strict reliability priority order.
        val matchers: List<(Int, Int) -> Boolean> = listOf(::matchesSig, ::matchesUrl, ::matchesHash)
        for (matcher in matchers) {
            for (i in currentPages.indices) {
                if (matched[i] != null) continue
                for (j in 0 until totalPrev) {
                    if (!usedPrev[j] && isPreviousEligible(j) && extCompatible(i, j) && matcher(i, j)) {
                        claim(i, j)
                        break
                    }
                }
            }
        }

        return matched.toList()
    }

    /**
     * Orders `(sourcePath -> targetPath)` file moves without clobbering any still-unread source file,
     * inserting a temporary staging rename only when breaking an actual permutation cycle.
     */
    fun planCycleSafeRenames(
        moves: List<Pair<String, String>>,
        tempPathFor: (sourcePath: String, seq: Int) -> String
    ): List<Pair<String, String>> {
        val pending = LinkedHashMap<String, String>()
        for ((src, dst) in moves) {
            if (src != dst) pending[src] = dst
        }
        val ops = mutableListOf<Pair<String, String>>()
        var seq = 0
        while (pending.isNotEmpty()) {
            val ready = pending.entries.firstOrNull { it.value !in pending.keys }
            if (ready != null) {
                val (src, dst) = ready
                ops += src to dst
                pending.remove(src)
            } else {
                val (cycleSrc, cycleDst) = pending.entries.first()
                var tmp = tempPathFor(cycleSrc, seq++)
                while (tmp in pending.keys || tmp in pending.values) {
                    tmp = tempPathFor(cycleSrc, seq++)
                }
                ops += cycleSrc to tmp
                pending.remove(cycleSrc)
                pending[tmp] = cycleDst
            }
        }
        return ops
    }
}
