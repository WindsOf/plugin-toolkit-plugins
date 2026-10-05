Version: 1.1.1
Date: 2026-10-04
Added:
  - Added `saveReclassificationCrops` parameter to `Detect and Segment` and `Detect and Segment Chapter` capabilities to save text ROI crops to disk for debugging and prompt refinement.
  - Implemented container-aware ROI expansion: text elements contained within speech balloons (smooth, jagged, spiky, circular, etc.) have their crop expanded to encompass the entire container contour, and receive structural context hints to enforce correct `speech` classification.
  - Increased general text crop padding from 10% to 35% to provide sufficient surrounding context to the vision LLM.
-------------------------------------------------------------------------------------------------
Version: 1.1.0
Date: 2026-10-03
Added:
  - Added optional 2nd-stage crop-based text reclassification supporting Qwen3-VL to classify detected text into speech, sfx, or non_text.
  - Added Qwen3-VL models to VisionDownloadModel action for downloading vision LLMs directly from Vision plugin.
  - Added @RequiresLock constraint on VisionReclassificationMode.QWEN locked behind Qwen model presence in checkLocks.
-------------------------------------------------------------------------------------------------
Version: 1.0.4
Date: 2026-09-27
Added:
  - Automated models folder organization in @PluginUpdate hook to move loose model files into dedicated subfolders.
-------------------------------------------------------------------------------------------------
Version: 1.0.3
Date: 2026-09-10
Changes:
  - Added advanced parameters tag to some capabilities properties
  - renamed plugin from `vision` to `WOM Vision`
-------------------------------------------------------------------------------------------------
Version: 1.0.2
Date: 2026-09-05
Added:
  - Added Toolkit 2.0.2 toast notifications to downloadModel and downloadAllModels plugin actions.
-------------------------------------------------------------------------------------------------
Version: 1.0.1
Date: 2026-08-29
Fixed:
  - Standardized image sorting using shared NaturalOrderComparator.
  - Added dynamic ROI expansion and tiling offset.
-------------------------------------------------------------------------------------------------
Version: 1.0.0
Date: 2026-08-28
Added:
  - Initial release of Vision plugin with YOLO object detection and RF-DETR segmentation.
