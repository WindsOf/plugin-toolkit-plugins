# Plugin Toolkit Plugins

A collection of high-performance Kotlin plugins for manhwa and webtoon processing, OCR, translation, vision segmentation, and image inpainting.

## Modules and Plugins

| Plugin / Module | ID | Version | Description |
| :--- | :--- | :--- | :--- |
| **Vision** | `com.wip.vision` | `1.0.0` | Object detection (YOLO) and instance segmentation (RF-DETR) with SAHI sliding window for arbitrary image sizes. |
| **Cleaner** | `com.wip.cleaner` | `1.0.0` | Image inpainting and text eraser plugin using segmentation maps and ROI patch-based background restoration. |
| **Slicer** | `com.wip.slicer` | `1.4.0` | Intelligent vertical image slicing with YOLO object detection boundary protection. |
| **OCR IA** | `com.wip.ocr_ia` | `2.6.0` | Advanced OCR text extraction with bounding boxes, speech bubble classification, and font style detection. |
| **PSD Builder Native** | `com.wip.psdbuilder.native` | `5.3.0` | Native PSD generation from images, typography, and OCR data using KPsd. |
| **BetterIMG** | `com.wip.betterimg` | `2.1.0` | Image upscaling and grain enhancement powered by VapourSynth and vsmlrt. |
| **Manhwa Translator AI** | `com.wip.manhwa_translator_ai` | `1.4.0` | Contextual comic translation pipeline using LLM integrations. |
| **common-models** | `com.wip.common.models` | — | Shared models, ONNX runtime engine, SAHI slicing, and inpainting utilities. |

---

## Build & Test

```bash
# Build all plugins
./gradlew build

# Run unit tests across all modules
./gradlew test

# Package all plugin JARs
./gradlew jar
```
