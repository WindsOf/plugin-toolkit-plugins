Version: 1.2.0
Date: 2026-09-11
Added:
  - Added official support for ZITS++ (zitspp) multi-stage inpainting pipeline (TSR, Edge-NMS, SSU, MPE, and Generator) retrieved from remote.
  - Restored and maintained ZITS (zits) multi-component model with dedicated subfolder storage (models/zits/) retrieved from remote (https://www.windsofresub.cloud/models/zits/zits.yaml).
  - Implemented dynamic and advanced capability parameters via Plugin API 2.1.0 (@DependsOn, ConditionOperator, isAdvanced).
  - Added model-specific fine-tuning controls (iterations, addV, mulV, sigma256, maskTh, objRemoval) for ZITS and ZITS++, plus Edge-NMS binaryThreshold for ZITS++.
  - Added boundary alpha feathering (featherRadius) and Poisson blending (usePoisson) controls across all inpainting capabilities.
  - Added dedicated subfolder model storage for multi-component models (e.g. models/zits/ and models/zitspp/) to prevent file name collisions.
Changed:
  - Deprecated legacy monolithic zits-inpaint-0717 model; mapped legacy requests for "zits-inpaint-0717" to ZITS (zits).
  - Deprecated MAT and Overkill Diffusion models; mapped them to automatic, graceful fallback to LaMa.
  - Maintained 5 core remote inpainting models: LaMa, Manga (Anime LaMa), MIGAN, ZITS, and ZITS++.
  - Updated MIGAN tensor pipeline to 4-channel NCHW format with BGR [-1, 1] normalization and inverted hole mask.
-------------------------------------------------------------------------------------------------
Version: 1.1.2
Date: 2026-09-10
Changes:
  - Renamed plugin from `Cleaner` to `WOM Cleaner`
-------------------------------------------------------------------------------------------------
Version: 1.1.1
Date: 2026-09-05
Added:
  - Added Toolkit 2.0.2 toast notifications to downloadModel and downloadAllModels plugin actions.
-------------------------------------------------------------------------------------------------
Version: 1.1.0
Date: 2026-08-30
Added:
  - Implemented Production Hybrid Cleaning Pipeline in CleanerPlugin and InpaintingUtils.
  - Added new capabilities: 'Clean Image (Production Hybrid)', 'Clean Image (Patches Only - Production Hybrid)', 'Clean Chapter (Production Hybrid)', 'Clean Chapter (Patches Only - Production Hybrid)'.
  - Added Tier 0 background homogeneity analysis for instant (<1ms) mathematical filling of solid (#FFFFFF) and gradient speech balloons without neural noise.
  - Added adaptive 2.5x context ROI expansion with multiple-of-32 dimension snapping to eliminate FFC Fourier spectral ringing on complex redraws.
  - Added support for expanded inpainting models: MAT (Places_512_FullData_G), ZITS (zits-inpaint-0717), and Overkill Latent Diffusion (diffusion).
  - Added CleaningStrategy enum (AUTO_HYBRID, NEURAL_ONLY, DETERMINISTIC_ONLY).
  - Added migration guide in migration.md.
Fixed:
  - Resolved potential float-to-integer truncation static in ImageTensorUtils by enforcing explicit architecture-safe tensor output normalization.
-------------------------------------------------------------------------------------------------
Version: 1.0.1
Date: 2026-08-25
Added:
  - Initial release of Cleaner plugin with LaMa, Anime-Manga LaMa, and MIGAN support.
  - Added Clean Image, Clean Image (Patches Only), Clean Chapter, Clean Chapter (Patches Only), and Generate Mask capabilities.
