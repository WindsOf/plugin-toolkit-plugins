# KPsd Porting Status & TODO

This document tracks which features from `ag-psd` have been implemented in `KPsd` (Kotlin) and which are missing or pending.

## Current Port Status
* **Version:** 1.0.0-SNAPSHOT
* **Last Updated:** 2026-05-23
* **Status:** **Core features for MVP (PSD layouts, Image Layers, Masks, Folders, and editable Text Layers) are fully implemented, debugged, and roundtrip testing is passing successfully.**

---

## 1. Core Structures & Types (`psd.ts` / `PsdTypes.kt`)
- [x] Document Header configurations (`Psd`) — **Implemented**
- [x] Layer Record configurations (`Layer`) — **Implemented**
- [x] Channel structures & IDs (`ChannelID`, `ChannelData`) — **Implemented**
- [x] Color structures (RGB, Grayscale, CMYK, HSB, Lab) — **Implemented**
- [x] Layer mask definitions (`LayerMaskData`) — **Implemented**
- [x] Blending range structures — **Implemented**
- [x] Section dividers (Open/Closed folders, terminations) — **Implemented**

---

## 2. Image Compression & Processing (`helpers.ts` / `PsdHelpers.kt`)
- [x] Packbits RLE Encoder (`writeDataRLE`) — **Implemented**
- [x] Packbits RLE Decoder (`readDataRLE`) — **Implemented**
- [x] Zip/Deflate Channel compression (using native JVM `java.util.zip.Deflater`) — **Implemented**
- [x] Zip/Inflate Channel decompression (using native JVM `java.util.zip.Inflater`) — **Implemented**
- [x] Alpha transparency checks (`hasAlpha`) — **Implemented**
- [x] Color Mode conversions (e.g., Grayscale/RGB setup) — **Implemented**
- [x] Bidirectional Blend Mode Mapping (converting verbose string names to 4-character signatures) — **Implemented & Verified**

---

## 3. Photoshop Descriptors (`descriptor.ts` / `PsdDescriptor.kt`)
Photoshop descriptors are key-value structures storing typed properties.
- [x] Version and Descriptor envelope (version 16) — **Implemented**
- [x] Descriptor structures (Key-Value mappings) — **Implemented**
- [x] Reference structures (`obj ` / layers references) — **Implemented**
- [x] List structures (`VlLs`) — **Implemented**
- [x] Base OSTypes:
  - [x] `long` (32-bit Integer) — **Implemented**
  - [x] `doub` (64-bit Double) — **Implemented**
  - [x] `bool` (Boolean) — **Implemented**
  - [x] `TEXT` (Unicode String) — **Implemented**
  - [x] `enum` (Enumerated value) — **Implemented**
  - [x] `tdta` (Raw Byte Data) — **Implemented**
  - [ ] `ObAr` (Object Array) — **Missing / Out of Scope**
  - [ ] `Pth ` (File Path) — **Missing / Out of Scope**

---

## 4. EngineData & Text Mappings (`engineData.ts`, `text.ts` / `EngineData.kt` / `TextLayer.kt`)
Photoshop uses EngineData to format text layers (fonts, paragraph runs, styling runs).
- [x] EngineData Parser (`parseEngineData`) — **Implemented**
- [x] EngineData Serializer (`serializeEngineData`) — **Implemented**
- [x] Text styling mapper (`decodeEngineData`) — **Implemented**
- [x] Text styling encoder (`encodeEngineData`) — **Implemented**
- [x] Text style/paragraph style deduplication (`deduplicateStyle`, `deduplicateParagraphStyle`) — **Implemented & Verified** (pulls up identical run properties to the base style to match `ag-psd` output layout)

---

## 5. Reader (`psdReader.ts` / `PsdReader.kt`)
- [x] Header Section reader (dimensions, channels, bit depth) — **Implemented**
- [x] Color Mode Data reader — **Implemented**
- [x] Image Resources section reader — **Implemented**
- [x] Layer and Mask Info section reader:
  - [x] Layer Records (bounds, blend mode, opacity, flags) — **Implemented**
  - [x] Layer channel image data (supports Raw, RLE, and Zip modes) — **Implemented**
  - [x] Folder hierarchies (nesting and grouping) — **Implemented**
  - [x] Global layer mask info — **Implemented**
  - [x] Text layers (`TySh` additional info handler) — **Implemented**
- [x] Composite Image Data reader (final flattened image, with support for Grayscale/RGB RLE streams with lengths pre-read for all channels) — **Implemented & Verified**

---

## 6. Writer (`psdWriter.ts` / `PsdWriter.kt`)
- [x] Header Section writer — **Implemented**
- [x] Color Mode Data writer — **Implemented**
- [x] Image Resources section writer — **Implemented**
- [x] Layer and Mask Info section writer:
  - [x] Layer Records (bounds, channels, flags, names) — **Implemented**
  - [x] Layer channel image data — **Implemented**
  - [x] Layer blending ranges — **Implemented**
  - [x] Folder dividers — **Implemented**
  - [x] Text layers (`TySh` additional info writer) — **Implemented**
- [x] Composite Image Data writer — **Implemented**

---

## 7. Missing Features (Out of Scope for initial MVP Library)
The following advanced `ag-psd` features are not implemented and are out of scope for the current Kotlin port:
- [ ] Smart Objects (`SoLd` / `PlLd`)
- [ ] Vector Masks & Paths (`vmsk` / `vsms`)
- [ ] Detailed Layer Effects / Blending styles (`lrFX` / `lfx2` / `lmfx`)
- [ ] Timeline Animations (`tmln` / `mlst`)
- [ ] Pattern overlays (`PtFl` / `patternFill`)
- [ ] Smart Filters / Adjustment Layers (Curves, Levels, Selective Color, etc.)
- [ ] 16-bit and 32-bit pixel depth support (current implementation is optimized for 8-bit RGBA channels)
