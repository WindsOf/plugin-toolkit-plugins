Version: 2.2.0
Date: 2026-05-23
Added:
  - Parallel execution support for batch OCR processing (max 5 concurrent requests).
  - Rate Limiting logic to respect API quotas (max 13 requests per minute).
  - Enhanced Thread-safety using Mutex for shared resources.
Changed:
  - Updated `retryWithBackoff` to use a robust, pre-defined interval scale (5s, 30s, 2m, 5m).
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