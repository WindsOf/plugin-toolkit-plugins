# Cleaner Plugin Migration Guide

## Upgrading to Production Hybrid Cleaning (Version 1.1.0)

Version 1.1.0 introduces the **Production Hybrid Cleaning Pipeline**, adding deterministic background filling for solid/gradient speech balloons, context-aware adaptive ROI expansion for complex neural redraws, and support for expanded neural architectures (`MAT`, `ZITS`, `Overkill Diffusion`).

---

### Key Additions & New Capabilities

To maintain 100% backward compatibility with existing automated workflows and actions, all previous capabilities remain intact. New features are exposed through dedicated **Hybrid** capabilities:

| Existing Legacy Capability | New Production Hybrid Capability | Benefits |
| :--- | :--- | :--- |
| `Clean Image` | **`Clean Image (Production Hybrid)`** | Instant $<1\text{ms}$ deterministic solid/gradient balloon fills + adaptive $2.5\times$ context expansion for textured redraws. |
| `Clean Image (Patches Only)` | **`Clean Image (Patches Only - Production Hybrid)`** | Outputs transparent PNG patch layers using hybrid analysis with soft alpha feathering. |
| `Clean Chapter` | **`Clean Chapter (Production Hybrid)`** | Batch chapter cleaning with automatic strategy selection (`AUTO_HYBRID`, `NEURAL_ONLY`, `DETERMINISTIC_ONLY`). |
| `Clean Chapter (Patches Only)` | **`Clean Chapter (Patches Only - Production Hybrid)`** | Full chapter transparent patch extraction using the hybrid pipeline. |

---

### Migration Options for Users and Callers

1. **Seamless Drop-in:**
   * If you continue using `Clean Image` or `Clean Chapter`, your existing scripts will execute without modification.
   * To benefit from zero-noise speech balloons and superior redraw quality, switch the capability identifier to `Clean Image (Production Hybrid)` or `Clean Chapter (Production Hybrid)`.

2. **Selecting Cleaning Strategies:**
   * `AUTO_HYBRID` (Default): Analyzes background pixel variance. If variance $\sigma \le 4.0$, cleans mathematically in $<1\text{ms}$; if textured, dispatches to neural inpainting with $2.5\times$ context padding.
   * `DETERMINISTIC_ONLY`: Executes only mathematical solid/gradient fills without loading neural weights.
   * `NEURAL_ONLY`: Dispatches all mask regions to neural inpainting models.

3. **New Inpainting Models:**
   * `LAMA` (`big-lama`)
   * `MANGA` (`anime-manga-big-lama` - Recommended for manga & anime screentones)
   * `MIGAN` (`migan_traced` - Manga Inpainting GAN)
   * `MAT` (`Places_512_FullData_G` - Mask-Aware Transformer)
   * `ZITS` (`zits-inpaint-0717` - Structure-Guided Line Art Inpainting)
   * `DIFFUSION_OVERKILL` (`diffusion` - Multi-step Latent Diffusion)
