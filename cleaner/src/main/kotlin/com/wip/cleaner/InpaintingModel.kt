package com.wip.cleaner

import org.wip.plugintoolkit.api.annotations.RequiresLock

/**
 * Enumeration of inpainting models for Cleaner plugin capabilities with UI lock requirements.
 */
enum class InpaintingModel(val modelId: String, val displayName: String) {
    @RequiresLock(locks = ["model:big-lama", "model:lama"])
    LAMA("big-lama", "LaMa"),

    @RequiresLock(locks = ["model:Places_512_FullData_G", "model:places_512_fulldata_g", "model:mat"])
    MAT("Places_512_FullData_G", "MAT (Places 512)"),

    @RequiresLock(locks = ["model:anime-manga-big-lama", "model:manga"])
    MANGA("anime-manga-big-lama", "Manga (Anime LaMa)"),

    @RequiresLock(locks = ["model:diffusion", "model:ldm"])
    LDM("diffusion", "Diffusion (LDM)"),

    @RequiresLock(locks = ["model:zits-inpaint-0717", "model:zits"])
    ZITS("zits-inpaint-0717", "ZITS (0717)"),

    @RequiresLock(locks = ["model:places_512_G", "model:places_512_g", "model:fcf"])
    FCF("places_512_G", "FCF (Places 512)"),

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
                (clean == "ldm" && it == LDM) ||
                (clean == "diffusion" && it == LDM) ||
                (clean == "mat" && it == MAT) ||
                (clean == "places_512_fulldata_g" && it == MAT) ||
                (clean == "fcf" && it == FCF) ||
                (clean == "places_512_g" && it == FCF) ||
                (clean == "zits" && it == ZITS) ||
                (clean == "zits-inpaint-0717" && it == ZITS) ||
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
    MAT("Places_512_FullData_G", "MAT (Places 512)"),
    MANGA("anime-manga-big-lama", "Manga (Anime LaMa)"),
    LDM("diffusion", "Diffusion (LDM)"),
    ZITS("zits-inpaint-0717", "ZITS (0717)"),
    FCF("places_512_G", "FCF (Places 512)"),
    MIGAN("migan_traced", "MIGAN");

    companion object {
        fun fromModelId(id: String): InpaintingDownloadModel? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modelId.equals(clean, ignoreCase = true) ||
                (clean == "lama" && it == LAMA) ||
                (clean == "manga" && it == MANGA) ||
                (clean == "migan" && it == MIGAN) ||
                (clean == "ldm" && it == LDM) ||
                (clean == "diffusion" && it == LDM) ||
                (clean == "mat" && it == MAT) ||
                (clean == "places_512_fulldata_g" && it == MAT) ||
                (clean == "fcf" && it == FCF) ||
                (clean == "places_512_g" && it == FCF) ||
                (clean == "zits" && it == ZITS) ||
                (clean == "zits-inpaint-0717" && it == ZITS) ||
                (clean == "big-lama" && it == LAMA) ||
                (clean == "anime-manga-big-lama" && it == MANGA) ||
                (clean == "migan_traced" && it == MIGAN)
            }
        }
    }
}
