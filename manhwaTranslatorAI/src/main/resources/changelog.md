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