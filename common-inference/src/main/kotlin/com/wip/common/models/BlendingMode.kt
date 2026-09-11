package com.wip.common.models

/**
 * Supported boundary blending algorithms for seamlessly fusing inpainting patches into target images.
 *
 * @param modeId Identifier string used in serialized configurations and presets.
 * @param displayName Human-readable label for UI selection dropdowns.
 */
enum class BlendingMode(val modeId: String, val displayName: String) {
    /**
     * Smooth boundary alpha feathering using Euclidean distance transform.
     * Linearly transitions between original background and inpainted patch within [featherRadius].
     */
    FEATHER("feather", "Alpha Feathering (Smooth Distance Fade)"),

    /**
     * Standard Poisson gradient reconstruction (Perez et al. 2003).
     * Solves the discrete Poisson PDE (\Delta d = 0) with Dirichlet boundary conditions
     * matching the unmasked target pixels along the hole contour.
     */
    POISSON("poisson", "Poisson Gradient Blending (Dirichlet PDE Solver)"),

    /**
     * Modified Poisson blending with soft alpha matting / boundary attenuation.
     * Restricts Poisson gradient adjustment to the boundary transition zone, preventing color
     * bleeding and tint shifts in the hole interior while eliminating edge seams.
     */
    MODIFIED_POISSON("modified_poisson", "Modified Poisson (Alpha Matting - Prevents Bleed)"),

    /**
     * Laplacian pyramid multi-band frequency blending (Burt & Adelson 1983).
     * Decomposes the patch, background, and mask into multi-resolution frequency octaves,
     * blending low spatial frequencies broadly and high frequencies crisply.
     */
    LAPLACIAN_PYRAMID("laplacian_pyramid", "Laplacian Pyramid Blending (Multi-Band Frequency Spline)"),

    /**
     * Direct hard paste of masked pixels without edge smoothing or gradient filtering.
     */
    NONE("none", "None (Direct Cut & Paste)");

    companion object {
        fun fromModeId(id: String): BlendingMode? {
            val clean = id.trim().lowercase()
            return entries.find {
                it.modeId.equals(clean, ignoreCase = true) ||
                        it.name.equals(clean, ignoreCase = true)
            }
        }
    }
}
