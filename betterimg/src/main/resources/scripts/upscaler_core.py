import argparse
import os
import sys
import multiprocessing
from pathlib import Path
from time import sleep
from tqdm import tqdm

current_dir = os.path.dirname(os.path.abspath(__file__))
if current_dir not in sys.path:
    sys.path.insert(0, current_dir)

portable_dir = os.path.dirname(sys.executable)
core_plugins_dir = os.path.join(portable_dir, "vs-coreplugins")

if portable_dir not in os.environ.get("PATH", ""):
    os.environ["PATH"] = f"{portable_dir};{os.environ.get('PATH', '')}"

if hasattr(os, "add_dll_directory"):
    try:
        os.add_dll_directory(portable_dir)
        if os.path.exists(core_plugins_dir):
            os.add_dll_directory(core_plugins_dir)
    except Exception as e:
        print(f"DLL directory warning: {e}")

import vapoursynth as vs  # noqa
from vsscale import ArtCNN  # noqa
from vsdeband import placebo_deband  # noqa
from vsdehalo import fine_dehalo  # noqa
from vskernels import Bilinear  # noqa
from vstools import depth, DitherType  # noqa
from vsmlrt import BackendV2  # noqa

core = vs.core


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


def get_best_backend_name():
    dummy_clip = core.std.BlankClip(format=vs.RGBS, width=128, height=128, length=1)
    print("\nSearching for the best AI backend supported by the system...")
    try:
        backend = BackendV2.TRT(
            static_shape=True,
            fp16=True,
            use_cuda_graph=True,
            min_shapes=(128, 128),
            opt_shapes=(128, 128),
            max_shapes=(128, 128),
        )
        ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend
        ).scale(dummy_clip, width=128, height=128).get_frame(0)
        print("[AI] Selected backend: TensorRT RTX (NVIDIA RTX)")
        return "TRT_RTX"
    except Exception:
        pass

    try:
        backend = BackendV2.TRT(
            static_shape=True,
            fp16=True,
            min_shapes=(128, 128),
            opt_shapes=(128, 128),
            max_shapes=(128, 128),
        )
        ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend
        ).scale(dummy_clip, width=128, height=128).get_frame(0)
        print("[AI] Selected backend: TensorRT (NVIDIA)")
        return "TRT"
    except Exception:
        pass

    try:
        backend = BackendV2.ORT_CUDA(fp16=True)
        ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend
        ).scale(dummy_clip, width=128, height=128).get_frame(0)
        print("[AI] Selected backend: ONNX-CUDA (NVIDIA Fallback)")
        return "CUDA"
    except Exception:
        pass

    try:
        backend = BackendV2.ORT_DML(fp16=True)
        ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend
        ).scale(dummy_clip, width=128, height=128).get_frame(0)
        print("[AI] Selected backend: ONNX-DirectML (AMD/Intel GPU)")
        return "DML"
    except Exception:
        pass

    try:
        backend = BackendV2.NCNN_VK(fp16=True)
        ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend
        ).scale(dummy_clip, width=128, height=128).get_frame(0)
        print("[AI] Selected backend: NCNN-Vulkan (Universal GPU)")
        return "NCNN"
    except Exception:
        pass

    print("[AI] Selected backend: CPU (Slow but universal)")
    return "CPU"


def process_image(
    input_file,
    output_file,
    backend_name,
    output_format="WEBP",
    target_width=1000,
    grain=5,
):
    clip = core.imwri.Read(input_file)
    clip = core.resize.Point(clip, format=vs.RGBS)

    if backend_name == "TRT_RTX":
        backend_cfg = BackendV2.TRT(
            static_shape=True,
            fp16=True,
            use_cuda_graph=True,
            min_shapes=(128, 128),
            opt_shapes=(128, 128),
            max_shapes=(128, 128),
        )
    elif backend_name == "TRT":
        backend_cfg = BackendV2.TRT(
            static_shape=True,
            fp16=True,
            min_shapes=(128, 128),
            opt_shapes=(128, 128),
            max_shapes=(128, 128),
        )
    elif backend_name == "CUDA":
        backend_cfg = BackendV2.ORT_CUDA(fp16=True)
    elif backend_name == "DML":
        backend_cfg = BackendV2.ORT_DML(fp16=True)
    elif backend_name == "NCNN":
        backend_cfg = BackendV2.NCNN_VK(fp16=True)
    else:
        backend_cfg = BackendV2.ORT_CPU()

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
                kernel=Bilinear,
                tilesize=[128, 128],
                overlap=[8, 8],
                backend=backend_cfg,
            ).scale(clip, width=target_width, height=target_height)
        elif tw_lower in MULTI_SCALE_MAP:
            passes = MULTI_SCALE_MAP[tw_lower].bit_length() - 1
            ai_scaled_clip = clip
            for _ in range(passes):
                ai_scaled_clip = ArtCNN.R8F64_JPEG444(
                    kernel=Bilinear,
                    tilesize=[128, 128],
                    overlap=[8, 8],
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
                kernel=Bilinear,
                tilesize=[128, 128],
                overlap=[8, 8],
                backend=backend_cfg,
            ).scale(clip, width=target_width, height=target_height)
    else:
        target_height = int(round(clip.height * (target_width / clip.width)))
        ai_scaled_clip = ArtCNN.R8F64_JPEG444(
            kernel=Bilinear, tilesize=[128, 128], overlap=[8, 8], backend=backend_cfg
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
    )
    deband = placebo_deband(
        dehalo,
        radius=14.0,
        thr=2,
        iterations=3,
        grain=[grain, grain, grain],
        planes=[0, 1, 2],
    )
    cas = core.cas.CAS(deband, sharpness=0.8, opt=0)

    temp_output = f"{output_file}_%d.{output_format.lower()}"
    clip = core.imwri.Write(
        clip=depth(
            cas,
            10 if output_format.lower() == "webp" else 8,
            dither_type=DitherType.NONE,
        ),
        imgformat=output_format.upper(),
        filename=temp_output,
        firstnum=0,
        quality=100,
        dither=False,
    )
    clip.get_frame(0)
    sleep(2)

    file_creato = temp_output.replace("%d", "0")
    if os.path.exists(file_creato):
        if os.path.exists(output_file):
            os.remove(output_file)
        os.rename(file_creato, output_file)


def run_processing(
    INPUT_PATH,
    target_width,
    grain,
    output_format,
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
        OUTPUT_FOLDER = os.path.join(base_dir, "upscaled")
    os.makedirs(OUTPUT_FOLDER, exist_ok=True)

    vs_max_workers = (
        int(multiprocessing.cpu_count() / 2) if multiprocessing.cpu_count() > 2 else 1
    )
    images = get_image_files(INPUT_PATH)

    if not images:
        print("No images found to process.")
        if on_complete:
            on_complete(False)
        return

    best_backend_name = get_best_backend_name()
    if not cli_mode:
        print(
            f"\nStarting VapourSynth on {len(images)} images... ({vs_max_workers} workers)"
        )

    completed = [0]
    total = len(images)

    def _on_done(_):
        completed[0] += 1
        if cli_mode:
            print(f"{completed[0] / total:.2f}", flush=True)
        else:
            pbar.update(1)

    def _on_error(e):
        completed[0] += 1
        if cli_mode:
            sys.stderr.write(f"Error: {e}\n")
            print(f"{completed[0] / total:.2f}", flush=True)
        else:
            tqdm.write(f"Error: {e}")
            pbar.update(1)

    with multiprocessing.Pool(processes=vs_max_workers, maxtasksperchild=1) as pool:
        if not cli_mode:
            pbar = tqdm(total=total, desc="VapourSynth", unit="img")
        for img_path in images:
            out_path = os.path.join(
                OUTPUT_FOLDER, f"{Path(img_path).stem}_upscaled.{output_format.lower()}"
            )
            pool.apply_async(
                process_image,
                args=(
                    img_path,
                    out_path,
                    best_backend_name,
                    output_format,
                    target_width,
                    grain,
                ),
                callback=_on_done,
                error_callback=_on_error,
            )
        pool.close()
        pool.join()
        if not cli_mode:
            pbar.close()

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
            "Width [1x, 2x, 4x, 8x, or number - default 1000]\nWARNING: I am not responsible if the final image is too large and crashes your computer, calculate before using 8x: "
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

    o_fmt = input("Format (webp/png) [default: webp]: ").strip().lower()
    o_fmt = o_fmt if o_fmt in ["webp", "png"] else "webp"

    print("-" * 30)
    run_processing(INPUT_PATH, t_width, grain, o_fmt)


if __name__ == "__main__":
    multiprocessing.freeze_support()

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
            output_path=args.output,
            cli_mode=True,
        )
    else:
        run_cli()
