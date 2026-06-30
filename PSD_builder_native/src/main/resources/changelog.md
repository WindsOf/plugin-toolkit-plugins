Version: 4.4.11
Date: 2026-06-29
Added:
  - Support fot 1.7.0
Bugfix:
  - Workign with webp images would take ages
-------------------------------------------------------------------------------------------------
Version: 4.4.10
Date: 2026-06-22
Added:
  - setting for padding multiplier in baloons
-------------------------------------------------------------------------------------------------
Version: 4.4.9
Date: 2026-06-19
Added:
  - Added complex objesct support 
-------------------------------------------------------------------------------------------------
Version: 4.4.8
Date: 2026-06-19
Changes:
  - minor changes to the centering algorythm to be more accurate
-------------------------------------------------------------------------------------------------
Version: 4.4.7
Date: 2026-06-14
Changes:
  - minor changes to the centering algorythm to be more accurate
-------------------------------------------------------------------------------------------------
Version: 4.4.5
Date: 2026-06-13
Added:
  - baloon size estimation function
Changes:
	- build from json for chapter and single image update to the new features formats	
	- follow shapes no longer breaks world
Fixed:
  - problem with expected normalized input
  - build psd for chapters would build a single psd
-------------------------------------------------------------------------------------------------
Version: 4.4.4
Date: 2026-06-12
Added:
  - Support for toolkit 1.6.0
  - Text formatting
-------------------------------------------------------------------------------------------------
Version: 3.0.1
Date: 2026-05-23
Fixed:
  - Fixed a JSON decoding exception on plugin initialization by explicitly adding escaped double quotes to string default values (like "Anime Ace 2.0 BB") in capability parameters, ensuring compliance with kotlinx.serialization.
-------------------------------------------------------------------------------------------------
Version: 3.0.0
Date: 2026-05-23
Added:
  - Added new `borderSize` parameter to capabilities, exposing stroke thickness to the UI. Passing 0 removes the border completely.
-------------------------------------------------------------------------------------------------
Version: 2.1.4
Date: 2026-05-23
Added:
  - Added a 3-pixel white outline (stroke) layer effect to all generated text to improve readability over images.
-------------------------------------------------------------------------------------------------
Version: 2.1.3
Date: 2026-05-23
Added:
  - Added spatial text centering functionality to calculate horizontal and vertical offsets automatically inside the bounding boxes.
-------------------------------------------------------------------------------------------------
Version: 2.1.2
Date: 2026-05-23
Fixed:
  - Corrected default font name in PSDBuilderPlugin and main.js from 'Anime ACE 2.0'/'ArialMT' to exactly 'Anime Ace 2.0 BB'.
-------------------------------------------------------------------------------------------------
Version: 2.1.1
Date: 2026-05-23
Fixed:
  - Fixed 'Invalid host defined options' runtime error for ESM modules by pre-bundling with ncc before pkg compilation.
-------------------------------------------------------------------------------------------------
Version: 2.1.0
Date: 2026-05-23
Changed:
  - Automate pkg compilation directly via Gradle build scripts.
-------------------------------------------------------------------------------------------------
Version: 2.0.2
Date: 2026-05-23
Changed:
  - Fixed executable packaging by explicitly bundling Jimp assets to resolve MODULE_NOT_FOUND errors at runtime.
-------------------------------------------------------------------------------------------------
Version: 2.0.1
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
