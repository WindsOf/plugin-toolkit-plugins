Version: 2.9.3
Date: 2026-10-04
Added:
  - Added `classify_text` parameter (default: `false`) across `ocr` and `advanced_ocr` to make OCR text classification optional. When disabled, models run with cleaner, focused recognition prompts and inherit categories directly from Vision segmentation crops.
Fixed:
  - Removed canvas edge text dropping rule in OCR prompts that caused Vision crops to lose legitimate dialogue touching crop borders.
  - Added robust parser support for untagged coordinate format `[ymin, xmin, ymax, xmax] text` with category inheritance from crop metadata.
  - Fixed `computeCropRegions` in `VisionCutoutHelper` to process all non-ignored Vision objects (`speech`, `sfx`, `balloon`, etc.) rather than discarding reclassified speech/sfx detections.
  - Updated `OcrVisionMerger.filterValidVisionObjects` to preserve reclassified Vision objects (`speech`, `sfx`) and shape labels (`circular`, `irregular`, `jagged`, `rectangular`, `spiky`).
-------------------------------------------------------------------------------------------------
Version: 2.9.2
Date: 2026-10-04
Added:
  - Centralized OCR classification instructions into `OcrClassificationInstructions` to modularize prompts across Qwen and Koog/Gemini/Anthropic pipelines.
  - Enforced strict balloon container rule: dialogue, moans, groans, screams, gasps, and non-word vocal utterances enclosed within speech bubbles or containers are strictly classified as `speech` instead of being misidentified as `sfx`.
-------------------------------------------------------------------------------------------------
Version: 2.9.1
Date: 2026-10-03
Fixed:
  - Enhanced Qwen OCR parsing to robustly extract tagged category instances (`{speech}` and `{sfx}`) with optional delimiters/colons and inverted coordinate orders.
  - Fixed false-positive hallucination filtering for legitimate standalone comic punctuation dialogue (e.g. `?!`, `...`, `?`, `!`, `—`), preventing Qwen and local OCR models from leaking raw `{category} [coords]` prefixes into extracted dialogue texts.
  - Implemented category and rectangular shape inference in fallback plain text extraction when model output contains SFX indicators.
-------------------------------------------------------------------------------------------------
Version: 2.9.0
Date: 2026-10-03
Added:
  - Integrated automatic bounding box merging into 'ocr' and 'advanced_ocr' via 'merge_nearby_with_vision' (default: true when Vision segmentation is provided).
  - Added classification categories ('speech', 'sfx', 'non_text', 'none') across OCRResult and AdvancedOCRResult.
  - Standardized coordinate normalization to float [0.0, 1.0] across all models and crops.
  - Added AdvancedAIModel enum for 'advanced_ocr', filtering out incompatible Unlimited-OCR models.
  - Added specialized ROI crop system prompts with edge text filtering when Vision cutout data is provided.
Removed:
  - Removed deprecated standalone merger capabilities ('merge_ocr_with_vision', 'merge_advanced_ocr_with_vision', 'merge_single_ocr_with_vision', 'merge_single_advanced_ocr_with_vision').
-------------------------------------------------------------------------------------------------
Version: 2.8.1
Date: 2026-09-27
Added:
  - Automated models folder organization in @PluginUpdate hook to move loose model files into dedicated subfolders.
Fixed:
  - Enforced strict fallback to 'speech' category for OCR models and outputs that do not specify an explicit 'sfx' category.
-------------------------------------------------------------------------------------------------
Version: 2.8.0
Date: 2026-09-27
Added:
  - Added local Qwen Vision model support (`Qwen3-VL-4B-Instruct` and `Qwen3-VL-8B-Instruct`) for OCR execution via `llama-server`.
  - Added dynamic `quantization` dropdown parameter.
  - Implemented translated structured OCR prompt and output parser for `{speech|sfx} [top_left_x, top_left_y, bottom_right_x, bottom_right_y] text` with bounding box scaling and balloon containment rules.
-------------------------------------------------------------------------------------------------
Version: 2.7.5
Date: 2026-09-24
Fixed:
  - Centralized OCR hallucination detection via shared `OcrTextFilter` to filter out non-text tokens (`[Non-Text]`) and degenerate model repetition loops (e.g. repeating digit sequences like `1.1.1.1.1...`).
-------------------------------------------------------------------------------------------------
Version: 2.7.4
Date: 2026-09-19
Added:
  - Integrated centralized `retryWithBackoff` from common-inference with automatic HTTP 429 Rate Limit / Quota Exhaustion detection.
  - Automatically waits at least 1 minute (or server-requested retryDelay) plus jitter when rate-limited before retrying.
-------------------------------------------------------------------------------------------------
Version: 2.7.3
Date: 2026-09-10
Changes:
  - Renamed plugin from `OCR IA` to `WOM OCR`
-------------------------------------------------------------------------------------------------
Version: 2.7.2
Date: 2026-09-05
Added:
  - Integrated Toolkit 2.0.2 toast notifications across model download, server detection/installation, and server lifecycle actions.
  - Added 'Test LM Studio Connection' action to verify connectivity and display the currently loaded model.
  - Added support for auto-resolving active LM Studio models via /v1/models when model setting is set to "default" or left empty.
  - Enabled discovering local GGUF weights and mmproj files across standard LM Studio cache paths.
Fixed:
  - Robust llama-server lifecycle handling: Windows process tree termination via taskkill (/T /F), JVM shutdown hook cleanup, and stopping lingering server instances.
-------------------------------------------------------------------------------------------------
Version: 2.7.1
Date: 2026-08-29
Fixed:
  - Sorted folder image inputs naturally using NaturalOrderComparator.
-------------------------------------------------------------------------------------------------
Version: 2.7.0
Date: 2026-08-27
Added:
  - Added 'Check Installed Models' action to scan plugin storage and report the exact installation status of all OCR models.
  - Added 'Install Llama Server' action to download and install precompiled llama-server binaries (CUDA, Vulkan, CPU) locally and to the system PATH.
  - Added 'Detect Llama Server' action to automatically discover existing llama-server installations across system PATH, standard directories, and plugin storage.
  - Updated AIModel capability dropdown to cleanly expose individual GGUF quantized models (UNLIMITED_OCR_BF16, UNLIMITED_OCR_Q8_0, UNLIMITED_OCR_Q4_K_M, UNLIMITED_OCR_IQ2_M) and removed obsolete UNLIMITED_OCR entry.
  - Fixed model lock evaluation in ModelManager to strictly inspect plugin storage, preventing external LM Studio models from unlocking uninstalled plugin models.
  - Enhanced lock resolution in checkLocks to populate all casing variants ensuring UI unlocks trigger reliably upon model download.
-------------------------------------------------------------------------------------------------
Version: 2.6.0
Date: 2026-08-25
Added:
  - Implemented local ONNX inference runner (UnlimitedOcrRunner) for Baidu Unlimited-OCR and DeepSeek-OCR models.
  - Added support for bounding box scaling, DeepSeek/Baidu tag parsing (<|ref|>, <|box|>, <|det|>), and JSON fallbacks.
  - Resolved UnsupportedOperationException when selecting UNLIMITED_OCR in ocr and advanced_ocr capabilities.
  - Integrated @RequiresLock on AIModel options to unlock models when downloaded.
  - Implemented @PluginLocks checkLocks and @PluginAction downloadModel/downloadAllModels for retrieving ONNX models.
-------------------------------------------------------------------------------------------------
Version: 2.5.0
Date: 2026-08-22
Added:
  - Upgraded to plugin-api 2.0.0.
  - Declared supported operating systems: WINDOWS, LINUX, MACOS.
  - Implemented @PluginLoad lifecycle hook.
  - Resolved namespace collision by moving OCR_IA specific settings into com.wip.ocrAI.models.OcrIASettings.
  - Bound complex objects (OCRResult, AdvancedOCRResult) from common-models annotated with @ComplexObject.
  - Added unit test suite.
-------------------------------------------------------------------------------------------------
Version: 2.4.8
Date: 2026-07-14
Changes:
	- Moved some settings to not be required anymore
-------------------------------------------------------------------------------------------------
Version: 2.4.7
Date: 2026-07-13
Added:
  - Support for 1.7.1
-------------------------------------------------------------------------------------------------
Version: 2.4.6
Date: 2026-06-29
Added:
  - Support for 1.7.0
-------------------------------------------------------------------------------------------------
Version: 2.4.5
Date: 2026-06-19
Added:
  - Added complex objesct support 
-------------------------------------------------------------------------------------------------
Version: 2.4.4
Date: 2026-06-13
Added:
  - Support for Gemini 3.1 Flash Lite.
-------------------------------------------------------------------------------------------------
Version: 2.4.3
Date: 2026-06-13
Changes:
  - saving thinking to json is now disabled by default
-------------------------------------------------------------------------------------------------
Version: 2.4.2
Date: 2026-06-12
Added:
  - Support for toolkit 1.6.0
  - Advancer ocr
-------------------------------------------------------------------------------------------------
Version: 2.3.5
Date: 2026-05-29
Changed:
  - Aggiornato il prompt per ignorare gli SFX/onomatopee disegnati direttamente sull'artwork e fuori dai balloon.
-------------------------------------------------------------------------------------------------
Version: 2.3.4
Date: 2026-05-25
Changed:
  - Portato il numero di retry a 7 tentativi con un ritardo fisso di 10 secondi per ciascuno.
-------------------------------------------------------------------------------------------------
Version: 2.3.3
Date: 2026-05-25
Changed:
  - Aumentato il limite massimo di retry a 5 tentativi consecutivi (con ritardi progressivi).
-------------------------------------------------------------------------------------------------
Version: 2.3.2
Date: 2026-05-24
Changed:
  - Esteso il meccanismo di retry (massimo 3 tentativi) anche alla fase di parsing e decodifica JSON per gestire le hallucination dell'intelligenza artificiale in modo automatico senza scartare subito la pagina.
-------------------------------------------------------------------------------------------------
Version: 2.3.1
Date: 2026-05-23
Changed:
  - Changed 'model' capability parameter type from String to AIModel Enum for better UI integration.
-------------------------------------------------------------------------------------------------
Version: 2.3.0
Date: 2026-05-23
Added:
  - Added 'modelId' capability parameter to allow dynamic model selection via UI. Default is gemma-4-26b-a4b-it.
-------------------------------------------------------------------------------------------------
Version: 2.2.2
Date: 2026-05-23
Changed:
  - Switched model from gemma-4-31b-it to gemma-4-26b-a4b-it to resolve continuous Google API 500 Internal Server Errors.
-------------------------------------------------------------------------------------------------
Version: 2.2.1
Date: 2026-05-23
Added:
  - Parallel execution support for batch OCR processing (max 5 concurrent requests).
  - Rate Limiting logic to respect API quotas (max 13 requests per minute).
  - Enhanced Thread-safety using Mutex for shared resources.
Changed:
  - Updated `retryWithBackoff` to use an optimized, pre-defined interval scale (5s, 10s, 10s, 10s, 2m).
  - Relocated system instruction prompt block to standard user text prompt to prevent Google API role strictness 500 errors.
-------------------------------------------------------------------------------------------------
Version: 2.1.0
Date: 2026-05-20
Added:
  - Support for toolkit 1.5.1
Changes:
  - Now oce uses a new prompt and returns the list of text and the list of bounding boxes
-------------------------------------------------------------------------------------------------
Version: 2.0.5
Date: 2026-05-12
Added:
  - Support for toolkit 1.5.0
-------------------------------------------------------------------------------------------------
Version: 2.0.3
Date: 2026-05-12
Added:
  - Support for toolkit 1.4.0
-------------------------------------------------------------------------------------------------
Version: 1.0.0
Date: 2026-05-10
Initial:
  - Initial release of OCR IA plugin
  - AI-powered OCR using Google GenAI (Gemma-4-31b-it)
  - Support for single image and batch folder processing
  - Automatic results saving to .txt files
  - Customizable output directory and API key support
  - Progress reporting and cancellation handling
  - Automatic setup and resource extraction