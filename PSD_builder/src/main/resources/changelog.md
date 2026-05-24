Version: 3.1.1
Date: 2026-05-24
Changed:
  - Fixed an issue where intermediate debugging files were saved into the invisible internal plugin workspace folder. They are now saved directly into the user-specified `outputDir` (or alongside the source images), making the "Keep intermediate files" option actually usable.
  - Rewrote and expanded all UI parameter descriptions in English to be much clearer and more descriptive for end users.
-------------------------------------------------------------------------------------------------
Version: 3.1.0
Date: 2026-05-24
Added:
  - Added native support for `.webp` images. The plugin now uses the TwelveMonkeys ImageIO extension in Kotlin to read WebP files, calculate bounding boxes correctly, and seamlessly passes a temporary `.png` conversion to the Node.js PSD Builder to maintain compatibility without heavy native C++ dependencies.
-------------------------------------------------------------------------------------------------
Version: 3.0.10
Date: 2026-05-23
Changed:
  - Removed the simulated vertical centering logic to leave the Paragraph text box strictly top-aligned inside the bounds, as requested.
-------------------------------------------------------------------------------------------------
Version: 3.0.9
Date: 2026-05-23
Fixed:
  - Fixed an orientation bug where `boxBounds` coordinates were passed in the wrong order. The engine strictly requires `[Left, Top, Right, Bottom]`, but the width and height values were swapped, causing paragraph bounds to render vertically instead of horizontally. The axes are now correctly assigned.
-------------------------------------------------------------------------------------------------
Version: 3.0.8
Date: 2026-05-23
Changed:
  - Reintroduced approximate vertical centering for Paragraph text boxes. `ag-psd` natively top-aligns paragraph text, so `main.js` now estimates the number of lines required (without destructively modifying the native wrapping) and shifts both the text `transform` and the internal `boxBounds` downwards. This visually centers the text while maintaining the structural boundaries of the Bounding Box.
-------------------------------------------------------------------------------------------------
Version: 3.0.7
Date: 2026-05-23
Changed:
  - Completely removed the custom Javascript manual word-wrapping logic. Text layers are now created natively as Photoshop Paragraph Text Boxes (`shapeType: 'box'`) with their sizes rigorously bound to the defined bounding box dimensions. Photoshop will now natively handle the text wrapping, justification, and layout boundaries, drastically improving accuracy and reducing bugs.
-------------------------------------------------------------------------------------------------
Version: 3.0.6
Date: 2026-05-23
Changed:
  - Replaced the `fontName` free-text field with a strict dropdown Enum (`PsdFont`) allowing selection between `ANIME_ACE_2_0_BB` and `ARIAL`. This ensures no typos can be made in the UI and inherently guarantees valid PostScript fonts are passed to Photoshop.
-------------------------------------------------------------------------------------------------
Version: 3.0.5
Date: 2026-05-23
Fixed:
  - Fixed font mapping issue where Photoshop did not recognize "Anime Ace 2.0 BB" because it strictly requires the internal PostScript name. `main.js` now maps the human-readable string to the formal PostScript string (`AnimeAce2.0BB`) automatically.
-------------------------------------------------------------------------------------------------
Version: 3.0.4
Date: 2026-05-23
Removed:
  - Removed the `Build PSD from JSON` capability from the frontend to declutter the UI. The core Javascript logic remains accessible to the other capabilities (`Build PSD from Image and Texts`, `Build PSD for Chapter`), which both inherently benefit from the same robust safety checks and data parsing validations.
-------------------------------------------------------------------------------------------------
Version: 3.0.3
Date: 2026-05-23
Changed:
  - Made bounding box variables strictly mandatory. The script will now throw a descriptive Error and exit cleanly instead of falling back to default 0 coordinates if `left`, `top`, `right`, or `bottom` are missing.
-------------------------------------------------------------------------------------------------
Version: 3.0.2
Date: 2026-05-23
Fixed:
  - Made Javascript JSON parsing more robust, adding fallbacks for missing text properties, empty boundary coordinates (preventing NaN crashes), and graceful error throwing for missing background images.
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
