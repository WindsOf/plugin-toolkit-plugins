package com.wip.cleaner

import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Enumeration of inpainting models for Cleaner plugin capabilities with UI lock requirements.
 */
enum class InpaintingModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:big-lama", "model:lama"])
    LAMA("big-lama", "LaMa"),

    @RequiresLock(locks = ["model:anime-manga-big-lama", "model:manga"])
    MANGA("anime-manga-big-lama", "Manga (Anime LaMa)"),

    @RequiresLock(locks = ["model:migan_traced", "model:migan"])
    MIGAN("migan_traced", "MIGAN");

    companion object {
        fun fromModelId(id: String): InpaintingModel? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modelId.equals(clean, ignoreCase = true) ||
                (clean == "lama" && it == LAMA) ||
                (clean == "manga" && it == MANGA) ||
                (clean == "migan" && it == MIGAN) ||
                (clean == "big-lama" && it == LAMA) ||
                (clean == "anime-manga-big-lama" && it == MANGA) ||
                (clean == "migan_traced" && it == MIGAN)
            }
        }
    }
}

/**
 * Enumeration of inpainting models for download actions without lock requirements.
 */
enum class InpaintingDownloadModel(val modelId: String, val displayName: String) {
    LAMA("big-lama", "LaMa"),
    MANGA("anime-manga-big-lama", "Manga (Anime LaMa)"),
    MIGAN("migan_traced", "MIGAN");

    companion object {
        fun fromModelId(id: String): InpaintingDownloadModel? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modelId.equals(clean, ignoreCase = true) ||
                (clean == "lama" && it == LAMA) ||
                (clean == "manga" && it == MANGA) ||
                (clean == "migan" && it == MIGAN) ||
                (clean == "big-lama" && it == LAMA) ||
                (clean == "anime-manga-big-lama" && it == MANGA) ||
                (clean == "migan_traced" && it == MIGAN)
            }
        }
    }
}
