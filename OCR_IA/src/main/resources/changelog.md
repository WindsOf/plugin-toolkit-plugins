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