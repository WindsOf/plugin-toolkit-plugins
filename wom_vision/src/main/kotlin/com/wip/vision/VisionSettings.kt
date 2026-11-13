package com.wip.vision

import org.wip.plugintoolkit.api.annotations.PluginSetting

/**
 * Global persistent configuration settings for the Vision plugin.
 */
data class VisionSettings(
    @PluginSetting(
        description = "Enable Multi-Pass Phase-Shifted Tiling for Detection (improves bounding box recall for large balloons across tile seams)",
        defaultValue = "true",
        required = true
    )
    val enableShiftedTiling: Boolean = true,

    @PluginSetting(
        description = "Number of shifted tiling passes for detection (1 = standard SAHI, 2 = 50% offset grid, 3 = 33%/67% offset grids)",
        defaultValue = "2",
        required = true
    )
    val detectionTilingPasses: Int = 2,

    @PluginSetting(
        description = "Enable Adaptive Dynamic ROI Expansion for Stage 2 Segmentation (expands ROI if contour touches crop boundary)",
        defaultValue = "true",
        required = true
    )
    val enableDynamicRoiExpansion: Boolean = true,

    @PluginSetting(
        description = "Maximum dynamic ROI expansion retries per object (1 to 2)",
        defaultValue = "1",
        required = true
    )
    val maxRoiExpansionRetries: Int = 1,

    @PluginSetting(
        description = "Expansion margin ratio when a contour touches ROI boundary (e.g. 0.25 = +25% in touching directions)",
        defaultValue = "0.25",
        required = true
    )
    val roiExpansionRatio: Double = 0.25,

    @PluginSetting(
        description = "Threshold in pixels to consider a segmentation contour touching the ROI border",
        defaultValue = "3",
        required = true
    )
    val borderTouchThresholdPx: Int = 3
) {
    /**
     * Sanitizes settings against uninitialized or zero-defaulted values.
     * When settings are not passed by the host app or when KSP generates primitive fallbacks (0 / false),
     * this ensures the plugin falls back to safe and intended defaults rather than silently disabling features.
     */
    fun sanitize(): VisionSettings {
        val isEmptyKspDefaults = !enableShiftedTiling &&
            detectionTilingPasses == 0 &&
            !enableDynamicRoiExpansion &&
            maxRoiExpansionRetries == 0 &&
            roiExpansionRatio == 0.0 &&
            borderTouchThresholdPx == 0
        if (isEmptyKspDefaults) {
            return VisionSettings()
        }
        return copy(
            detectionTilingPasses = if (detectionTilingPasses <= 0) 2 else detectionTilingPasses,
            maxRoiExpansionRetries = if (maxRoiExpansionRetries <= 0) 1 else maxRoiExpansionRetries,
            roiExpansionRatio = if (roiExpansionRatio <= 0.0) 0.25 else roiExpansionRatio,
            borderTouchThresholdPx = if (borderTouchThresholdPx <= 0) 3 else borderTouchThresholdPx
        )
    }
}
