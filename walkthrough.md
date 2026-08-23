# Walkthrough: Inpainting ONNX Export & Multi-Component Pipeline Upgrade

## Problem Summary & Root Cause
When exporting `diffusion.pt` (1.48 GB checkpoint) to ONNX, the resulting model was only 26.9 MB.
Investigation revealed:
1. `LDMInpaintGenerator` was a toy 6.7M parameter placeholder network, and `strict=False` in checkpoint loading silently ignored the 387M-parameter checkpoint (matching 0 / 416 keys).
2. PyTorch TorchScript checkpoint `diffusion.pt` contained an OpenAI Guided Diffusion UNet requiring inputs `sample` (B, 3, H, W), `timestep_embed` (B, 256), and `condition_concat` (B, 4, H, W), producing `noise_pred` (B, 3, H, W).
3. Similar mismatches affected other models (`LaMa`, `Manga`, `MIGAN`) where custom Fourier layers or TorchScript wrapper formats required architecture restructuring and direct TorchScript module exports.

---

## Key Accomplishments

### 1. Architecture Overhaul & 100% Weight Verification
- **LaMa (`big-lama.pt`) & Manga (`anime-manga-big-lama.pt`)**:
  - Implemented exact 18-block FFC ResNet generator with `ratio_gout=0.75` and matrix-based DFT Fourier units (`ffc.py`).
  - Achieved **989 / 989 (100.0%) parameter match** from official checkpoints.
  - Successfully exported `big-lama.onnx` and `anime-manga-big-lama.onnx` (`205.6 MB` each).
- **MIGAN (`migan_traced.pt`)**:
  - Added direct TorchScript module loading and export preserving 100% of the `26.6 MB` weights.
- **Diffusion / LDM (`diffusion.pt`)**:
  - Upgraded [`repaint/architectures/ldm.py`](file:///d:/Programming/WOModels/repaint/architectures/ldm.py) to directly wrap the full 387M parameter TorchScript UNet.
  - Added sinusoidal timestep embedding generator `get_sinusoidal_timestep_embedding(timesteps, dim=256)`.
  - Added multi-step diffusion sampling loop in `LDMModel.forward()`.
  - Successfully exported full `diffusion.onnx` (**1.55 GB** / 387M parameters).

### 2. Upgraded Companion `.yaml` Descriptor Schema
Companion YAML descriptors now support both single-pass models and multi-component diffusion pipelines while retaining backward compatibility:

#### Single-Pass Model (`big-lama.yaml`):
```yaml
model_type: lama
pipeline_type: single_pass
description: Resolution-robust Large Mask Inpainting with Fast Fourier Convolutions
repo: https://github.com/saic-mdal/lama
input_resolution: [512, 512]
dynamic_shape: true
norm_mode: zero_to_one
mask_mode: zero_to_one
input_names: [image, mask]
output_names: [output]
opset_version: 17
files:
  model: big-lama.onnx
inputs:
  - name: image
    shape: [1, 3, height, width]
    type: float32
  - name: mask
    shape: [1, 1, height, width]
    type: float32
outputs:
  - name: output
    shape: [1, 3, height, width]
    type: float32
```

#### Diffusion Pipeline (`diffusion.yaml`):
```yaml
model_type: ldm
pipeline_type: diffusion_pipeline
description: Latent Diffusion Models for High-Resolution Inpainting
repo: https://github.com/CompVis/latent-diffusion
input_resolution: [256, 256]
dynamic_shape: true
norm_mode: neg_one_to_one
mask_mode: zero_to_one
input_names: [sample, timestep_embed, condition_concat]
output_names: [noise_pred]
opset_version: 17
components:
  unet:
    file: diffusion.onnx
    inputs:
      - name: sample
        shape: [1, 3, height, width]
        type: float32
        description: Noisy input tensor
      - name: timestep_embed
        shape: [1, 256]
        type: float32
        description: Sinusoidal timestep embedding
      - name: condition_concat
        shape: [1, 4, height, width]
        type: float32
        description: Concatenated [masked_image, mask]
    outputs:
      - name: noise_pred
        shape: [1, 3, height, width]
        type: float32
        description: Predicted noise score
pipeline_config:
  num_timesteps: 1000
  default_inference_steps: 50
  beta_schedule: linear
  channels: 3
  embed_dim: 256
```

### 3. Unified Inference Pipeline
- [`repaint/infer_onnx.py`](file:///d:/Programming/WOModels/repaint/infer_onnx.py) now automatically detects `pipeline_type == "diffusion_pipeline"` or input signatures and executes the iterative diffusion reverse sampling loop in ONNXRuntime.

---

## Test Verification
All 30 unit and integration tests passed:
```
============================== 30 passed in 342.83s ==============================
- repaint/tests/test_architectures.py (7 passed)
- repaint/tests/test_blending.py (3 passed)
- repaint/tests/test_cli.py (2 passed)
- repaint/tests/test_export_onnx.py (7 passed)
- repaint/tests/test_infer_onnx.py (7 passed)
```

---

## Exported Model Artifacts Summary
| Architecture | Exported ONNX File | Size | Parameters / Match | Descriptor YAML |
| :--- | :--- | :--- | :--- | :--- |
| **Diffusion (LDM)** | `diffusion.onnx` | **1.55 GB** | **387.2M params (100%)** | `diffusion.yaml` |
| **LaMa** | `big-lama.onnx` | **205.6 MB** | **989 / 989 keys (100%)** | `big-lama.yaml` |
| **Manga** | `anime-manga-big-lama.onnx` | **205.6 MB** | **989 / 989 keys (100%)** | `anime-manga-big-lama.yaml` |
| **MIGAN** | `migan_traced.onnx` | **26.6 MB** | **Traced Module (100%)** | `migan_traced.yaml` |
