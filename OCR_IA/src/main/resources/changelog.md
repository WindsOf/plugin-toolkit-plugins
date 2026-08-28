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