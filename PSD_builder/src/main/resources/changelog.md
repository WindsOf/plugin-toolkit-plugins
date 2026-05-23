Version: 2.0.0
Date: 2026-05-23
Added:
  - Batch capability to process entire chapters concurrently (max 4 processes via Semaphore).
  - Smart word-wrapping text generation to fit bounding boxes.
  - Set "Anime ACE 2.0" as default fallback font.
Changed:
  - Refactored `executeBuilder` to be a coroutine suspend function.
  - Improved cleanup with safe `try-finally` blocks for temporary JSON files.
  - Added option `leaveIntermediateFiles` for advanced debugging.

Version: 1.0.0
Date: 2026-05-23
Initial:
  - Initial release with PSD Builder capabilities
