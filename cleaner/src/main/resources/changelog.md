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
