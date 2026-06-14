# PSD Builder Native

`psd_builder_native` is a plugin for generating layered Photoshop Documents (PSD) natively in Kotlin. It utilizes `KPsd` to create detailed PSD files from source images, text strings, and bounding box coordinates.

## Features
- **Native PSD Generation**: No external dependencies like Photoshop required. Builds PSD completely from Kotlin.
- **Bounding Box Interpolation**: Intelligently combines Balloon Bounding Boxes and Text Bounding Boxes (e.g., from OCR) to create perfectly centered text layers, mimicking the layout logic of your OCR tools.
- **Fail-Safes**: Automatically detects anomalies in OCR bounding boxes (e.g., box too large) and falls back to text bounds.
- **Debug Mode**: Renders the internal bounding boxes directly onto the PSD background for visual debugging:
  - **Magenta**: Original Balloon Bounding Box
  - **Green**: Text Bounding Box
  - **Orange**: Final Interpolated Bounding Box
  - **Red**: Center crosshair

## Settings

### `debugMode`
A boolean setting (`false` by default). When enabled, the plugin draws its bounding box calculations directly onto the base image, making it easy to see how the interpolated centers and boundaries were derived.

## Usage

When invoked via `buildPsdFromInputs` or `buildPsdForChapter`, the plugin handles the creation of a full PSD. It places text into a `"Testi"` folder, generates appropriate text layers with strokes/effects, and optionally applies the visual debug boxes if `debugMode` is enabled.
