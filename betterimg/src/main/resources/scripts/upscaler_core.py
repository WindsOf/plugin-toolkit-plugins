import argparse
import os
import sys
from pathlib import Path
import numpy as np
from PIL import Image

current_dir = os.path.dirname(os.path.abspath(__file__))
os.chdir(current_dir)
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

portable_dir = os.path.dirname(sys.executable)

if portable_dir not in os.environ.get("PATH", ""):
    os.environ["PATH"] = f"{portable_dir};{os.environ.get('PATH', '')}"

import site

possible_plugin_dirs = [
    os.path.join(portable_dir, "Lib", "site-packages", "vapoursynth", "plugins"),
    os.path.join(portable_dir, "Lib", "site-packages", "vapoursynth"),
    os.path.join(portable_dir, "vs-plugins"),
]

# Discover plugin directories across all site-packages and user-site locations
site_dirs = []
if hasattr(site, "getsitepackages"):
    try:
        site_dirs.extend(site.getsitepackages())
    except Exception:
        pass
if hasattr(site, "getusersitepackages"):
    try:
        u_site = site.getusersitepackages()
        if u_site:
            site_dirs.append(u_site)
    except Exception:
        pass

for s_dir in site_dirs:
    for sub in [os.path.join("vapoursynth", "plugins"), "vapoursynth", "vs-plugins"]:
        cand = os.path.join(s_dir, sub)
        if os.path.isdir(cand) and cand not in possible_plugin_dirs:
            possible_plugin_dirs.append(cand)

for p_dir in [portable_dir] + possible_plugin_dirs:
    if os.path.isdir(p_dir):
        if p_dir not in os.environ.get("PATH", ""):
            os.environ["PATH"] = f"{p_dir};{os.environ.get('PATH', '')}"
        if hasattr(os, "add_dll_directory"):
            try:
                os.add_dll_directory(p_dir)
            except Exception:
                pass

import vapoursynth as vs  # noqa

# Autoload all plugin DLLs to ensure edgemasks, bestsource, etc. are always registered
loaded_dlls = set()
for p_dir in possible_plugin_dirs:
    if os.path.isdir(p_dir):
        for root, _, files in os.walk(p_dir):
            for file in files:
                if file.lower().endswith(".dll"):
                    dll_path = os.path.normcase(os.path.normpath(os.path.join(root, file)))
                    if dll_path not in loaded_dlls:
                        loaded_dlls.add(dll_path)
                        try:
                            vs.core.std.LoadPlugin(dll_path)
                        except Exception:
                            pass

from vsscale import ArtCNN  # noqa
from vsscale.onnx import Backend  # noqa
from vsdeband import placebo_deband, Grainer  # noqa
from vsdehalo import fine_dehalo  # noqa
from vskernels import Bicubic  # noqa
from vstools import depth, DitherType  # noqa

# Monkey-patch TRT.version in vsscale to prevent fatal access violation (0xC0000005)
# in vstrt.dll when processing multiple images in the same process.
# We derive the version tuple directly from the tensorrt Python bindings package
# instead of calling self.plugin.Version() on vstrt.dll which causes access violations.
try:
    from vsscale.mlrt.backend.trt import TRT
    import tensorrt
    from packaging.version import Version

    _trt_ver = Version(tensorrt.__version__).release[:3]
    TRT.version = property(lambda self: _trt_ver)
except Exception:
    pass

core = vs.core
try:
    core.max_cache_size = 2048
except Exception:
    pass


def get_image_files(input_path):
    valid_extensions = (".png", ".jpg", ".jpeg", ".webp", ".bmp")
    if os.path.isfile(input_path):
        if input_path.lower().endswith(valid_extensions):
            return [input_path]
        return []
    elif os.path.isdir(input_path):
        files = []
        for item in os.listdir(input_path):
            if item.lower().endswith(valid_extensions):
                full_path = os.path.join(input_path, item)
                if os.path.isfile(full_path):
                    files.append(full_path)
        return files
    return []


def ensure_artcnn_model():
    from vsscale.mlrt.settings import get_model_path
    try:
        model_path = get_model_path("artcnn", "ArtCNN_R8F64_JPEG444")
        if model_path.exists():
            return
    except Exception:
        pass

    target_dir = os.path.join(current_dir, ".vsjet", "vsscale", "onnx", "artcnn", "v1.6.2")
    target_file = os.path.join(target_dir, "ArtCNN_R8F64_JPEG444.onnx")
    if os.path.exists(target_file) and os.path.getsize(target_file) > 100000:
        return

    os.makedirs(target_dir, exist_ok=True)
    print("[AI] Downloading ArtCNN JPEG444 model...")

    # Method 1: Direct download of the exact model file from GitHub releases
    url = "https://github.com/Artoriuz/ArtCNN/releases/download/v1.6.2/ArtCNN_R8F64_JPEG444.onnx"
    try:
        import urllib.request
        urllib.request.urlretrieve(url, target_file)
        if os.path.exists(target_file) and os.path.getsize(target_file) > 100000:
            print("[AI] ArtCNN JPEG444 model downloaded successfully.")
            return
    except Exception as e:
        print(f"[AI] Direct download failed ({e}), trying vsscale CLI...")

    # Method 2: Fallback via vsscale CLI entrypoint
    try:
        from vsscale.mlrt.cli import app
        old_argv = sys.argv
        sys.argv = ["vsscale", "onnx", "download", "ArtCNN", "--latest", "-y"]
        try:
            app.meta()
        finally:
            sys.argv = old_argv
    except Exception as e:
        print(f"[AI] Warning: vsscale CLI download failed: {e}")

    # Remove all models except ArtCNN_R8F64_JPEG444.onnx to save space
    import glob
    for root_dir in [current_dir, os.getcwd()]:
        for f in glob.glob(os.path.join(root_dir, ".vsjet", "vsscale", "onnx", "artcnn", "**", "*.onnx"), recursive=True):
            if not f.endswith("ArtCNN_R8F64_JPEG444.onnx"):
                try:
                    os.remove(f)
                except Exception:
                    pass


import threading

_gpu_lock = threading.Lock()
_backend_cache = None

TILE_SIZE = (128, 128)
OVERLAP = (8, 8)


def get_backend():
    global _backend_cache
    if _backend_cache is None:
        _backend_cache = Backend.autoselect(fp16=True)
        print(f"[AI] Selected backend: {_backend_cache.__class__.__name__}")
    return _backend_cache


def warmup_engine(backend):
    """Warm up the ArtCNN TensorRT engine on a dummy clip single-threaded.
    Ensures FP16 ONNX conversion and TensorRT engine compilation are done safely
    before any multi-threaded processing begins, eliminating file write collisions.
    """
    try:
        dummy = core.std.BlankClip(format=vs.RGBS, width=128, height=128, length=1)
        scaled = ArtCNN.R8F64_JPEG444(
            kernel=Bicubic(b=0, c=0),
            tilesize=TILE_SIZE,
            overlap=OVERLAP,
            backend=backend,
        ).scale(dummy, width=256, height=256)
        scaled.get_frame(0)
        del dummy, scaled
    except Exception as e:
        sys.stderr.write(f"[AI] Warmup warning: {e}\n")


def process_image(
    input_file,
    output_file,
    img_type="manwha",
    output_format="WEBP",
    target_width=1000,
    grain=1,
    backend_cfg=None,
):
    if backend_cfg is None:
        backend_cfg = get_backend()

    with _gpu_lock:
        clip = core.bs.VideoSource(input_file)
        clip = core.resize.Point(clip, format=vs.RGBS)

        MULTI_SCALE_MAP = {
            "4x": 4,
            "x4": 4,
            "8x": 8,
            "x8": 8,
            "16x": 16,
            "x16": 16,
        }

        if isinstance(target_width, str):
            tw_lower = target_width.lower()
            if tw_lower in ["1x", "x1"]:
                target_width, target_height, ai_scaled_clip = clip.width, clip.height, clip
            elif tw_lower in ["2x", "x2"]:
                target_width, target_height = clip.width * 2, clip.height * 2
                ai_scaled_clip = ArtCNN.R8F64_JPEG444(
                    kernel=Bicubic(b=0, c=0),
                    tilesize=TILE_SIZE,
                    overlap=OVERLAP,
                    backend=backend_cfg,
                ).scale(clip, width=target_width, height=target_height)
            elif tw_lower in MULTI_SCALE_MAP:
                passes = MULTI_SCALE_MAP[tw_lower].bit_length() - 1
                ai_scaled_clip = clip
                for _ in range(passes):
                    ai_scaled_clip = ArtCNN.R8F64_JPEG444(
                        kernel=Bicubic(b=0, c=0),
                        tilesize=TILE_SIZE,
                        overlap=OVERLAP,
                        backend=backend_cfg,
                    ).scale(
                        ai_scaled_clip,
                        width=ai_scaled_clip.width * 2,
                        height=ai_scaled_clip.height * 2,
                    )
                target_width = ai_scaled_clip.width
                target_height = ai_scaled_clip.height
            else:
                target_width = int(target_width) if target_width.isdigit() else 1000
                target_height = int(round(clip.height * (target_width / clip.width)))
                ai_scaled_clip = ArtCNN.R8F64_JPEG444(
                    kernel=Bicubic(b=0, c=0),
                    tilesize=TILE_SIZE,
                    overlap=OVERLAP,
                    backend=backend_cfg,
                ).scale(clip, width=target_width, height=target_height)
        else:
            target_height = int(round(clip.height * (target_width / clip.width)))
            ai_scaled_clip = ArtCNN.R8F64_JPEG444(
                kernel=Bicubic(b=0, c=0),
                tilesize=TILE_SIZE,
                overlap=OVERLAP,
                backend=backend_cfg,
            ).scale(clip, width=target_width, height=target_height)

        dehaloed = [
            fine_dehalo(
                core.std.ShufflePlanes(ai_scaled_clip, planes=[i], colorfamily=vs.GRAY),
                brightstr=1,
                exclude=False,
                planes=[0],
            )
            for i in range(3)
        ]
        dehalo = core.std.ShufflePlanes(
            clips=dehaloed, planes=[0, 0, 0], colorfamily=vs.RGB
        ).resize.Point(format=vs.YUV444PS, matrix_s="709", range_s="full")

        is_manga = str(img_type).lower() == "manga"

        if is_manga:
            cas = core.cas.CAS(dehalo, sharpness=0.8, opt=0)
            grained = Grainer.GAUSS(
                cas,
                strength=(grain, grain),
                static=False,
                luma_scaling=1,
                scale=2,
                temporal=0,
                planes=[0, 1, 2],
            ).resize.Point(
                format=vs.RGBS, matrix_in_s="709", range_in_s="full", range_s="full"
            )
        else:
            deband = placebo_deband(
                dehalo,
                radius=14.0,
                thr=1,
                iterations=3,
                grain=[2, 2, 2],
                planes=[0, 1, 2],
            )
            cas = core.cas.CAS(deband, sharpness=0.8, opt=0)
            grained = Grainer.PERLIN(
                cas,
                strength=(grain * 2, 1),
                static=False,
                luma_scaling=1,
                scale=1,
                temporal=0,
                planes=[0, 1, 2],
            ).resize.Point(
                format=vs.RGBS, matrix_in_s="709", range_in_s="full", range_s="full"
            )

        clip_out = depth(grained, 10, dither_type=DitherType.NONE)
        frame = clip_out.get_frame(0)
        r = np.asarray(frame[0]).copy()
        g = np.asarray(frame[1]).copy()
        b = np.asarray(frame[2]).copy()

        del frame, clip_out, grained, cas, dehalo, dehaloed, ai_scaled_clip, clip
        if not is_manga:
            del deband

    # CPU image conversion and compression (runs in parallel across threads)
    rgb_10 = np.dstack((r, g, b))
    rgb_8 = (rgb_10 >> 2).astype(np.uint8)
    img = Image.fromarray(rgb_8)

    fmt = output_format.upper()
    if fmt == "WEBP":
        img.save(output_file, format="WEBP", quality=100, lossless=True)
    elif fmt == "PNG":
        img.save(output_file, format="PNG")
    else:
        img.save(output_file)

    del img, rgb_8, rgb_10, r, g, b


def run_processing(
    INPUT_PATH,
    target_width,
    grain,
    output_format,
    img_type="manwha",
    on_complete=None,
    output_path=None,
    cli_mode=False,
):
    if output_path:
        OUTPUT_FOLDER = output_path
    else:
        base_dir = (
            os.path.dirname(INPUT_PATH) if os.path.isfile(INPUT_PATH) else INPUT_PATH
        )
        OUTPUT_FOLDER = os.path.join(base_dir, "out")
    os.makedirs(OUTPUT_FOLDER, exist_ok=True)

    images = get_image_files(INPUT_PATH)

    if not images:
        print("No images found to process.")
        if on_complete:
            on_complete(False)
        return

    ensure_artcnn_model()
    backend = get_backend()
    warmup_engine(backend)

    total = len(images)
    max_workers = min(4, total)

    if not cli_mode:
        print(
            f"\nStarting VapourSynth on {len(images)} images (workers: {max_workers})..."
        )

    import concurrent.futures
    import threading

    completed = 0
    lock = threading.Lock()

    def _worker(img_path):
        nonlocal completed
        out_path = os.path.join(
            OUTPUT_FOLDER, f"{Path(img_path).stem}_upscaled.{output_format.lower()}"
        )
        try:
            process_image(
                img_path,
                out_path,
                img_type=img_type,
                output_format=output_format,
                target_width=target_width,
                grain=grain,
                backend_cfg=backend,
            )
        except Exception as e:
            import traceback
            traceback.print_exc()
            sys.stderr.write(f"Error processing {img_path}: {e}\n")
        finally:
            with lock:
                completed += 1
                print(f"{completed / total:.2f}", flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
        list(executor.map(_worker, images))

    if not cli_mode:
        print(f"\nFinished! {len(images)} images processed.\n")
    if on_complete:
        on_complete(True)


def run_cli():
    print("Upscaler Configuration (CLI)\n" + "-" * 30)
    while True:
        INPUT_PATH = (
            input(
                "Enter the image or folder PATH (with quotes). You can also drag and drop the file/folder: "
            )
            .strip()
            .strip('"')
        )
        if INPUT_PATH and os.path.exists(INPUT_PATH):
            break
        print("[ERROR] Invalid path.")

    w_in = (
        input(
            "Width [1x, 2x, 4x, 8x, or number - default 1000]\nWARNING: I am not responsible if the final image is too large and crashes your computer, calculate before using 4x: "
        )
        .strip()
        .lower()
    )
    t_width = (
        w_in
        if w_in in ["1x", "2x", "4x", "8x", "x1", "x2", "x4", "x8"]
        else (int(w_in) if w_in.isdigit() else 1000)
    )

    g_in = input("Grain [Press ENTER for default: 1]: ").strip()
    grain = int(g_in) if g_in.isdigit() else 1

    t_in = input("Image type (manga/manwha) [default: manwha]: ").strip().lower()
    img_type = "manga" if t_in == "manga" else "manwha"

    o_fmt = input("Format (webp/png) [default: webp]: ").strip().lower()
    o_fmt = o_fmt if o_fmt in ["webp", "png"] else "webp"

    print("-" * 30)
    run_processing(INPUT_PATH, t_width, grain, o_fmt, img_type=img_type)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        prog="upscaler_core",
        description="BetterIMG Upscaler",
    )
    parser.add_argument(
        "input",
        nargs="?",
        metavar="INPUT",
        help="Path to the image or folder to process.",
    )
    parser.add_argument(
        "-t",
        "--type",
        choices=["manwha", "manga"],
        default="manwha",
        dest="img_type",
        metavar="TYPE",
        help="Image type: manwha or manga. [default: manwha]",
    )
    parser.add_argument(
        "-w",
        "--width",
        default="1000",
        metavar="WIDTH",
        help="Target width: number of pixels (e.g. 1000) or multiplier (1x, 2x, 4x, 8x). [default: 1000]",
    )
    parser.add_argument(
        "-g",
        "--grain",
        type=int,
        default=1,
        metavar="GRAIN",
        help="Intensity of grain to add (integer). [default: 1]",
    )
    parser.add_argument(
        "-f",
        "--format",
        choices=["webp", "png"],
        default="webp",
        dest="fmt",
        metavar="FORMAT",
        help="Output format: webp or png. [default: webp]",
    )
    parser.add_argument(
        "-o",
        "--output",
        default=None,
        metavar="OUTPUT",
        help="Destination folder for processed images. [default: <input>/upscaled]",
    )

    args = parser.parse_args()

    if args.input:
        if not os.path.exists(args.input):
            parser.error(f"Invalid path: {args.input}")

        if args.output and not os.path.exists(args.output):
            try:
                os.makedirs(args.output, exist_ok=True)
            except Exception as e:
                parser.error(f"Unable to create output folder: {e}")

        w_in = args.width.strip().lower()
        SCALE_KEYWORDS = ["1x", "2x", "4x", "8x", "x1", "x2", "x4", "x8"]
        t_width = (
            w_in if w_in in SCALE_KEYWORDS else (int(w_in) if w_in.isdigit() else 1000)
        )

        run_processing(
            args.input,
            t_width,
            args.grain,
            args.fmt,
            img_type=args.img_type,
            output_path=args.output,
            cli_mode=True,
        )
    else:
        run_cli()
