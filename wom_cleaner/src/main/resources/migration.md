# Cleaner Plugin Migration Guide

## Upgrading to Inpainting Engine 1.3.0 (Version 1.3.0)

Version 1.3.0 replaces the legacy boolean `usePoisson` parameter with a comprehensive **`BlendingMode`** dropdown across all 8 cleaner capabilities, and introduces mathematical gradient domain solvers and multi-band frequency pyramid blending.

---

### Breaking Changes & Automated Migration

* **`usePoisson` Replaced by `blendingMode`**:
  * In earlier versions, `usePoisson: Boolean = false` was dormant and did not execute a Poisson solver.
  * In version 1.3.0, `usePoisson` is removed in favor of `blendingMode: BlendingMode = BlendingMode.FEATHER`.
  * **Automated Flow Migration:** User flow graphs from `1.2.0` automatically migrate via `migrations.json` port mapping (`usePoisson` $\to$ `blendingMode`) without user intervention.

### Supported Blending Modes

| Mode (`BlendingMode`) | Display Name | Mathematical Foundation | Best Used For |
| :--- | :--- | :--- | :--- |
| `FEATHER` | **Alpha Feathering** | Euclidean distance transform with linear boundary fade | Standard text hole cleaning with smooth edges (Default) |
| `POISSON` | **Poisson Gradient Blending** | Gauss-Seidel Successive Over-Relaxation solving discrete $\Delta d = 0$ with Dirichlet boundary $d|_{\partial\Omega} = T - S$ | Matching lighting and eliminating hard seams across variable gradient backgrounds |
| `MODIFIED_POISSON` | **Modified Poisson (Alpha Matting)** | Poisson solver + distance-based smoothstep/cosine boundary attenuation | Eliminating boundary seams without color bleeding or tint shifts into the hole interior |
| `LAPLACIAN_PYRAMID` | **Laplacian Pyramid Blending** | Burt & Adelson multi-resolution Gaussian/Laplacian decomposition and octave blending | Seamless frequency fusion across large complex photographic/manga textures |
| `NONE` | **None (Direct Paste)** | Binary pixel replacement where mask $> 128$ | Crisp pixel-art, comic panels, or benchmarking |

---

## Upgrading to Inpainting Engine 1.2.0 (Version 1.2.0)

Version 1.2.0 overhauls neural inpainting model support and introduces dynamic, model-aware advanced parameter controls using **Plugin API 2.1.0**.

---

### Maintained Inpainting Models

We officially support and optimize 5 neural inpainting models retrieved from remote storage:

1. **`LAMA`** (`big-lama`): Fast, versatile Large Mask Inpainting via Fast Fourier Convolutions.
2. **`MANGA`** (`anime-manga-big-lama`): Specialized LaMa fine-tuned for screentones, halftones, and anime/manga artwork.
3. **`MIGAN`** (`migan_traced`): Manga Inpainting GAN utilizing an end-to-end 4-channel NCHW tensor architecture `[masked_image (BGR [-1, 1]), known_mask ([0, 1])]`.
4. **`ZITS`** (`zits`): Structure-guided inpainting with MPE, Canny edge detection, Structure-Upsample, and Fourier generator synthesis. Stored in dedicated subfolder (`models/zits/`) from `https://www.windsofresub.cloud/models/zits/zits.yaml`.
5. **`ZITSPP`** (`zitspp` - **NEW SOTA**): State-of-the-art multi-stage transformer pipeline orchestrating EdgeLine TSR (256x256), Edge-NMS filtering, SSU (Structure-Upsample), MPE wavefront dilation, and Generator synthesis. Stored in dedicated subfolder (`models/zitspp/`) from `https://www.windsofresub.cloud/models/zitspp/zitspp.yaml`.

---

### Deprecated Models & Migration Plan

* **`zits-inpaint-0717`**: Legacy monolithic ZITS export is deprecated and replaced by multi-component **`ZITS`** (`https://www.windsofresub.cloud/models/zits/zits.yaml`). Existing automations or presets requesting `"zits-inpaint-0717"` are automatically routed to `ZITS`.
* **`MAT` (`Places_512_FullData_G`)** and **`DIFFUSION_OVERKILL` (`diffusion`)**: Deprecated and removed from active selection to streamline memory footprints and execution reliability. Existing presets automatically fall back to `LAMA`.
* **Subfolder Storage Strategy:** Multi-component models (`ZITS` and `ZITSPP`) are downloaded into isolated subfolders (`models/zits/` and `models/zitspp/`) to prevent file collisions (e.g. `generator.onnx`, `structure_upsample.onnx`) and ensure reliable model management.

---

### Dynamic & Advanced Parameter Controls

Using **Plugin API 2.1.0**, capabilities dynamically update which advanced configuration parameters are displayed in the host application UI based on the selected `model`:

| Parameter | Type | Default | Availability / Condition | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| `featherRadius` | Int | `2` | All Models (`isAdvanced = true`) | Boundary alpha blending radius in pixels |
| `usePoisson` | Boolean | `false` | All Models (`isAdvanced = true`) | Poisson gradient reconstruction |
| `cropMargin` | Int | `32` | All Models (`isAdvanced = true`) | Context expansion margin (px) around mask |
| `iterations` | Int | `5` | `model in [ZITS, ZITSPP]` | TSR sampling iterations |
| `addV` | Double | `0.0` | `model in [ZITS, ZITSPP]` | Additive color offset correction `[-1.0, 1.0]` |
| `mulV` | Double | `1.0` | `model in [ZITS, ZITSPP]` | Multiplicative contrast scaling `[0.0, 2.0]` |
| `sigma256` | Double | `1.5` | `model in [ZITS, ZITSPP]` | Gaussian smoothing sigma for edge detection |
| `maskTh` | Double | `0.85` | `model in [ZITS, ZITSPP]` | Wireframe proposal acceptance threshold |
| `objRemoval` | Boolean | `false` | `model in [ZITS, ZITSPP]` | Suppress line hallucination inside hole to clean objects |
| `binaryThreshold`| Int | `50` | `model == ZITSPP` | Edge-NMS binarization threshold `[0, 255]` |

---

## Upgrading to Production Hybrid Cleaning (Version 1.1.0)

Version 1.1.0 introduced the **Production Hybrid Cleaning Pipeline**, adding deterministic background filling for solid/gradient speech balloons, context-aware adaptive ROI expansion for complex neural redraws, and support for expanded neural architectures.

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
