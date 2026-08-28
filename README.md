# Plugin Toolkit Plugins

A collection of high-performance Kotlin plugins for manhwa and webtoon processing, OCR, translation, vision segmentation, image inpainting, and typesetting.

## Modules and Plugins

### Shared Architecture Libraries
- **`common-models`**: Pure data transfer objects, bounding boxes, OCR/segmentation data models, and NMS calculation utilities without any ONNX Runtime or heavy dependencies.
- **`common-inference`**: High-performance ONNX Runtime inference engine with dynamic GPU/CPU provider configuration (`-PonnxVariant=gpu|cpu`), SAHI sliding window pipeline, YOLO postprocessors, and model management.

### Plugins Catalog

| Plugin | GPU + CPU ID | CPU-Only ID | Version | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Vision** | `com.wip.vision` | `com.wip.vision.cpu` | `1.0.0` | Object detection (YOLO) and instance segmentation (RF-DETR) with SAHI sliding window for arbitrary image sizes. |
| **Cleaner** | `com.wip.cleaner` | `com.wip.cleaner.cpu` | `1.0.0` | Image inpainting and text eraser plugin using segmentation maps and ROI patch-based background restoration. |
| **Slicer** | `com.wip.slicer` | `com.wip.slicer.cpu` | `1.4.1` | Intelligent vertical image slicing with YOLO object detection boundary protection. |
| **OCR IA** | `com.wip.ocr_ia` | `com.wip.ocr_ia.cpu` | `2.6.0` | Advanced OCR text extraction with bounding boxes, speech bubble classification, and font style detection. |
| **PSD Builder Native** | `com.wip.psdbuilder.native` | — | `5.3.1` | Native PSD generation from images, typography, and OCR data using KPsd (pure library, zero ONNX runtime bundled). |
| **BetterIMG** | `com.wip.betterimg` | — | `2.1.0` | Image upscaling and grain enhancement powered by VapourSynth and vsmlrt. |
| **Manhwa Translator AI** | `com.wip.manhwa_translator_ai` | — | `1.4.0` | Contextual comic translation pipeline using LLM integrations. |

---

## Build, Test & Repository Generation

```bash
# Build all plugins (defaults to GPU + CPU)
./gradlew build

# Build specific ONNX variant (e.g. CPU-only)
./gradlew build -PonnxVariant=cpu

# Run unit tests across all modules
./gradlew test

# Generate and sign the complete plugin repository (both GPU and CPU variants)
python generate_repo.py --force

# Verify repository signatures, hashes, and metadata
python verify_repo.py
```
