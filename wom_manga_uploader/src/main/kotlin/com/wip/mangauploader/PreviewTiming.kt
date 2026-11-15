package com.wip.mangauploader

enum class PreviewAnchor { LATEST_MANGADEX, CUBARI_PUBLISH }

/** Effective preview-hold policy after merging the global setting with a per-series override. */
data class PreviewPolicy(val hours: Int, val anchor: PreviewAnchor, val hoursOverridden: Boolean, val anchorOverridden: Boolean)

enum class HoldState {
    /** Chapter not on Cubari yet: no hold window running. */
    NOT_PUBLISHED,
    HOLDING,
    RELEASABLE
}

data class PreviewStatus(
    val policy: PreviewPolicy,
    val state: HoldState,
    val holdUntilEpochSec: Long?,
    val remainingSec: Long
)

/** Evaluates how long a Cubari-published chapter must be held before it goes to MangaDex. */
object PreviewTiming {

    fun resolvePolicy(series: Series?, config: MangaUploaderConfig): PreviewPolicy {
        val override = series?.overridePreviewHours
        val hours = (override ?: config.defaultPreviewHours).coerceAtLeast(0)
        val anchorOverride = series?.overridePreviewAnchor ?: PreviewAnchorOverride.INHERIT
        val anchor = when (anchorOverride) {
            PreviewAnchorOverride.LATEST_MANGADEX -> PreviewAnchor.LATEST_MANGADEX
            PreviewAnchorOverride.CUBARI_PUBLISH -> PreviewAnchor.CUBARI_PUBLISH
            PreviewAnchorOverride.INHERIT ->
                if (config.previewAnchorLatestMangaDex) PreviewAnchor.LATEST_MANGADEX else PreviewAnchor.CUBARI_PUBLISH
        }
        return PreviewPolicy(
            hours = hours,
            anchor = anchor,
            hoursOverridden = override != null,
            anchorOverridden = anchorOverride != PreviewAnchorOverride.INHERIT
        )
    }

    /**
     * @param siblings all chapters of the same series (used to find the latest MangaDex timestamp).
     */
    fun evaluate(
        series: Series?,
        chapter: Chapter,
        siblings: List<Chapter>,
        config: MangaUploaderConfig,
        nowEpochSec: Long
    ): PreviewStatus {
        val policy = resolvePolicy(series, config)
        val cubariTs = chapter.cubariPublishedAtEpochSec
            ?: return PreviewStatus(policy, HoldState.NOT_PUBLISHED, null, 0L)
        if (chapter.isOnMangaDex) return PreviewStatus(policy, HoldState.RELEASABLE, null, 0L)

        val anchorTs = when (policy.anchor) {
            PreviewAnchor.CUBARI_PUBLISH -> cubariTs
            PreviewAnchor.LATEST_MANGADEX ->
                siblings.asSequence()
                    .filter { it.id != chapter.id }
                    .mapNotNull { it.mangadexPublishedAtEpochSec }
                    .maxOrNull() ?: cubariTs
        }
        val holdUntil = anchorTs + policy.hours * 3600L
        val remaining = holdUntil - nowEpochSec
        return if (remaining > 0) {
            PreviewStatus(policy, HoldState.HOLDING, holdUntil, remaining)
        } else {
            PreviewStatus(policy, HoldState.RELEASABLE, holdUntil, 0L)
        }
    }

    /** `"5h 12m"`, `"42m"`, `"<1m"`. */
    fun formatRemaining(seconds: Long): String {
        if (seconds <= 0) return "0m"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "<1m"
        }
    }
}
