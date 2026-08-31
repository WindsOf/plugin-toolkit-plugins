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
    MIGAN("migan_traced", "MIGAN"),

    @RequiresLock(locks = ["model:Places_512_FullData_G", "model:mat"])
    MAT("Places_512_FullData_G", "MAT (Mask-Aware Transformer)"),

    @RequiresLock(locks = ["model:zits-inpaint-0717", "model:zits"])
    ZITS("zits-inpaint-0717", "ZITS (Structure-Guided)"),

    @RequiresLock(locks = ["model:diffusion", "model:ldm"])
    DIFFUSION_OVERKILL("diffusion", "Overkill Latent Diffusion");

    companion object {
        fun fromModelId(id: String): InpaintingModel? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modelId.equals(clean, ignoreCase = true) ||
                (clean == "lama" && it == LAMA) ||
                (clean == "manga" && it == MANGA) ||
                (clean == "migan" && it == MIGAN) ||
                (clean == "mat" && it == MAT) ||
                (clean == "zits" && it == ZITS) ||
                (clean == "diffusion" && it == DIFFUSION_OVERKILL) ||
                (clean == "big-lama" && it == LAMA) ||
                (clean == "anime-manga-big-lama" && it == MANGA) ||
                (clean == "migan_traced" && it == MIGAN) ||
                (clean == "places_512_fulldata_g" && it == MAT) ||
                (clean == "zits-inpaint-0717" && it == ZITS)
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
    MIGAN("migan_traced", "MIGAN"),
    MAT("Places_512_FullData_G", "MAT (Mask-Aware Transformer)"),
    ZITS("zits-inpaint-0717", "ZITS (Structure-Guided)"),
    DIFFUSION_OVERKILL("diffusion", "Overkill Latent Diffusion");

    companion object {
        fun fromModelId(id: String): InpaintingDownloadModel? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modelId.equals(clean, ignoreCase = true) ||
                (clean == "lama" && it == LAMA) ||
                (clean == "manga" && it == MANGA) ||
                (clean == "migan" && it == MIGAN) ||
                (clean == "mat" && it == MAT) ||
                (clean == "zits" && it == ZITS) ||
                (clean == "diffusion" && it == DIFFUSION_OVERKILL) ||
                (clean == "big-lama" && it == LAMA) ||
                (clean == "anime-manga-big-lama" && it == MANGA) ||
                (clean == "migan_traced" && it == MIGAN) ||
                (clean == "places_512_fulldata_g" && it == MAT) ||
                (clean == "zits-inpaint-0717" && it == ZITS)
            }
        }
    }
}

/**
 * Strategy mode for Cleaner capabilities.
 */
enum class CleaningStrategy(val strategyId: String, val displayName: String) {
    AUTO_HYBRID("auto_hybrid", "Auto Hybrid (Deterministic Balloons + Neural Fallback)"),
    NEURAL_ONLY("neural_only", "Neural Only (Always Invoke Inpainting Model)"),
    DETERMINISTIC_ONLY("deterministic_only", "Deterministic Only (Solid & Gradient Balloons)");

    companion object {
        fun fromStrategyId(id: String): CleaningStrategy? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.strategyId.equals(clean, ignoreCase = true) ||
                (clean == "auto" && it == AUTO_HYBRID) ||
                (clean == "hybrid" && it == AUTO_HYBRID) ||
                (clean == "neural" && it == NEURAL_ONLY) ||
                (clean == "deterministic" && it == DETERMINISTIC_ONLY)
            }
        }
    }
}
