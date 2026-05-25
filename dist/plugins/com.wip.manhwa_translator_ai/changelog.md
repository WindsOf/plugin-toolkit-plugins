Version: 1.3.1
Date: 2026-05-25
Fixed:
  - Aggiunta la capability `Vision.Image` al modello LLM quando vengono passate immagini in modalità Context Images (fix errore "non supporta le immagini").
-------------------------------------------------------------------------------------------------
Version: 1.3.0
Date: 2026-05-25
Added:
  - Introdotta la feature "Context Images": passando le immagini originali del manhwa all'intelligenza artificiale, essa potrà utilizzarle come contesto visivo per tradurre in modo più preciso.
  - La nuova modalità carica fino a 5 immagini in simultanea per richiesta ed è attivabile opzionalmente dalla UI.
-------------------------------------------------------------------------------------------------
Version: 1.2.4
Date: 2026-05-25
Added:
  - Aggiunto il supporto al modello `Gemini 3.5 Flash` come opzione IA.
-------------------------------------------------------------------------------------------------
Version: 1.2.1
Date: 2026-05-25
Changed:
  - Aumentato il limite massimo di retry a 5 tentativi consecutivi (con ritardi progressivi).
-------------------------------------------------------------------------------------------------
Version: 1.2.0
Date: 2026-05-24
Added:
  - Text inputs are now split into chunks of max 50 texts.
  - Implemented rate limiting (max 5 simultaneous requests, max 13 requests per minute).
  - Improved model prompt to strictly return a 1:1 mapped output array.
  - Added retry logic to discard and retry chunk translation (up to 3 times) if output size does not match input chunk size.
-------------------------------------------------------------------------------------------------
Version: 1.1.1
Date: 2026-05-23
Changed:
  - Changed 'model' capability parameter type from String to AIModel Enum for better UI integration.
-------------------------------------------------------------------------------------------------
Version: 1.1.0
Date: 2026-05-23
Added:
  - Added 'modelId' capability parameter to allow dynamic model selection via UI. Default is gemma-4-31b-it.
-------------------------------------------------------------------------------------------------
Version: 1.0.2
Date: 2026-05-23
Changed:
  - Switched model from gemma-4-31b-it to gemma-4-26b-a4b-it to resolve continuous Google API 500 Internal Server Errors.
-------------------------------------------------------------------------------------------------
Version: 1.0.1
Date: 2026-05-23
Changed:
  - Relocated system instruction prompt block to standard user text prompt to prevent Google API role strictness 500 errors.
  - Updated retryWithBackoff with optimized delay intervals (5s, 10s, 10s, 10s, 2m).
-------------------------------------------------------------------------------------------------
Version: 1.0.0
Date: 2026-05-23
Initial:
  - Initial release of manhwa Translator AI plugin