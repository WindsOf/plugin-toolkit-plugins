# PSD Builder Native

`psd_builder_native` is a plugin for generating layered Photoshop Documents (PSD) natively in Kotlin. It utilizes `KPsd`
to create detailed PSD files from source images, text strings, and bounding box coordinates.

## Features

- **Native PSD Generation**: No external dependencies like Photoshop required. Builds PSD completely from Kotlin.
- **Bounding Box Interpolation**: Intelligently combines Balloon Bounding Boxes and Text Bounding Boxes (e.g., from OCR)
  to create perfectly centered text layers, mimicking the layout logic of your OCR tools.
- **Fail-Safes**: Automatically detects anomalies in OCR bounding boxes (e.g., box too large) and falls back to text
  bounds.
- **Debug Mode**: Renders the internal bounding boxes directly onto the PSD background for visual debugging:
	- **Magenta**: Original Balloon Bounding Box
	- **Green**: Text Bounding Box
	- **Orange**: Final Interpolated Bounding Box
	- **Red**: Center crosshair

## Settings

### `debugMode`

A boolean setting (`false` by default). When enabled, the plugin draws its bounding box calculations directly onto the
base image, making it easy to see how the interpolated centers and boundaries were derived.

### `paddingPercentage`

A float setting (`0.10` by default) controlling the inset padding percentage relative to the bounding box size.

### `wordBreak`

Controls word breaking and hyphenation (`NONE`, `HYPHENATE`, `BREAK_WORD`; `NONE` by default) and synchronizes Photoshop's `autoHyphenate` paragraph flag. Can be overridden per execution via the advanced `wordBreak` capability parameter.

### `allowLineWrapping`

Boolean setting (`true` by default). When enabled, text wraps onto multiple lines inside the bounding box. When disabled, embedded newlines are collapsed and text stays on a single auto-fitted, centered line. Can be overridden per execution via the advanced `allowLineWrapping` capability parameter.

### `insertHardcodedNewlines`

Boolean setting (`true` by default). When enabled, KPsd's formatter inserts hardcoded `\r` line breaks to hug the balloon contour. When disabled, the original text string is preserved without hardcoded newlines while still applying AutoFit font sizing and vertical centering, allowing Photoshop to wrap text natively inside the text box. Can be overridden per execution via the advanced `insertHardcodedNewlines` capability parameter.

## Usage

When invoked via `buildPsdFromInputs`, `buildPsdFromOcrData`, `buildPsdFromAdvancedOcrData`, or `buildPsdForChapter`,
the plugin handles the creation of a structured multi-layer PSD:

- **`raw`**: Base original image layer at the bottom.
- **`clean`**: Layer group containing the clean / inpainted image or patch layer (populated when `cleanImagePath` or
  `cleanFolder` is provided).
- **`translation`**: Layer group containing all generated text layers with custom typography, auto-fitting, boundaries,
  and stroke effects.
- If `debugMode` is enabled, visual debug bounding boxes are rendered directly on the base artwork.
