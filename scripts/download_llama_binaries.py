#!/usr/bin/env python3
"""
Downloads precompiled llama.cpp / llama-server release binaries from GitHub Releases
and packages them into the canonical format required by WindsOf Plugin Toolkit (LlamaBinaryDownloader).

Canonical output filenames:
  - llama-server-win-cuda-cu12.zip   (Windows CUDA 12.x + CUDA runtime DLLs)
  - llama-server-win-vulkan.zip      (Windows Vulkan)
  - llama-server-win-cpu.zip         (Windows CPU / AVX2)
  - llama-server-linux-cuda-cu12.zip (Linux CUDA 12.x)
  - llama-server-linux-cpu.zip       (Linux CPU / Ubuntu x64)

Usage examples:
  python scripts/download_llama_binaries.py --version b10655
  python scripts/download_llama_binaries.py --version b10655 --output-dir remote/binaries/llama-server
  python scripts/download_llama_binaries.py --from-folder "C:\\Users\\sgroo\\Desktop\\llama" --output-dir remote/binaries/llama-server
"""

import argparse
import fnmatch
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

GITHUB_API_BASE = "https://api.github.com/repos/ggml-org/llama.cpp/releases"

# Mapping of canonical package names to search patterns in llama.cpp release assets
PACKAGE_DEFINITIONS = [
    {
        "canonical_name": "llama-server-win-cuda-cu12.zip",
        "description": "Windows NVIDIA CUDA (cu12.x)",
        "patterns": ["llama-*bin-win-cuda-12*.zip", "llama-*bin-win-cuda*.zip", "*bin-win-cuda-cu12*.zip", "*bin-win-cuda*.zip"],
        "companion_patterns": ["cudart-llama-bin-win-cuda-12*.zip", "cudart-llama-bin-win-cu12*.zip", "*cudart*.zip"],
        "exclude_patterns": ["*arm64*"],
        "is_windows": True
    },
    {
        "canonical_name": "llama-server-win-vulkan.zip",
        "description": "Windows Vulkan (AMD/Intel)",
        "patterns": ["*bin-win-vulkan-x64*.zip", "*bin-win-vulkan*.zip"],
        "companion_patterns": [],
        "exclude_patterns": ["*arm64*"],
        "is_windows": True
    },
    {
        "canonical_name": "llama-server-win-cpu.zip",
        "description": "Windows CPU (AVX2 / x64)",
        "patterns": ["*bin-win-cpu-x64*.zip", "*bin-win-avx2*.zip", "*bin-win-x64*.zip"],
        "companion_patterns": [],
        "exclude_patterns": ["*arm64*"],
        "is_windows": True
    },
    {
        "canonical_name": "llama-server-linux-cuda-cu12.zip",
        "description": "Linux NVIDIA CUDA (cu12.x)",
        "patterns": ["*bin-ubuntu-cuda*.tar.gz", "*bin-ubuntu-cuda*.zip", "*bin-linux-cuda*.tar.gz", "*bin-linux-cuda*.zip"],
        "companion_patterns": ["*cudart-llama-bin-ubuntu*.tar.gz", "*cudart-llama-bin-ubuntu*.zip"],
        "exclude_patterns": ["*arm64*"],
        "is_windows": False
    },
    {
        "canonical_name": "llama-server-linux-vulkan.zip",
        "description": "Linux Vulkan (Ubuntu / x64 GPU)",
        "patterns": ["*bin-ubuntu-vulkan-x64*.tar.gz", "*bin-ubuntu-vulkan*.tar.gz", "*bin-ubuntu-vulkan*.zip"],
        "companion_patterns": [],
        "exclude_patterns": ["*arm64*"],
        "is_windows": False
    },
    {
        "canonical_name": "llama-server-linux-cpu.zip",
        "description": "Linux CPU (Ubuntu / x64)",
        "patterns": ["*bin-ubuntu-x64*.tar.gz", "*bin-ubuntu-x64*.zip", "*bin-linux-x64*.tar.gz", "*bin-linux-x64*.zip"],
        "companion_patterns": [],
        "exclude_patterns": ["*arm64*"],
        "is_windows": False
    }
]


def fetch_release_info(version_tag=None):
    """Fetches release metadata from GitHub API."""
    if version_tag and version_tag.lower() != "latest":
        url = f"{GITHUB_API_BASE}/tags/{version_tag}"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "WindsOf-PluginToolkit-Downloader"}
        )
        print(f"Fetching release information for tag: {version_tag}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            print(f"[ERROR] Failed to query GitHub Release for tag {version_tag}: {e}")
            return None
    else:
        url = f"{GITHUB_API_BASE}?per_page=10"
        req = urllib.request.Request(
            url,
            headers={"User-Agent": "WindsOf-PluginToolkit-Downloader"}
        )
        print(f"Fetching latest releases list from GitHub...")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                releases = json.loads(resp.read().decode("utf-8"))
                for r in releases:
                    if len(r.get("assets", [])) >= 5:
                        return r
                if releases:
                    return releases[0]
        except Exception as e:
            print(f"[ERROR] Failed to query GitHub Releases: {e}")
            return None


def download_file(url, target_path):
    """Downloads a file with a simple progress reporter."""
    print(f"  Downloading: {url}")
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "WindsOf-PluginToolkit-Downloader"}
    )
    with urllib.request.urlopen(req, timeout=60) as resp, open(target_path, "wb") as f:
        total_size = resp.headers.get("Content-Length")
        total_size = int(total_size) if total_size else 0
        downloaded = 0
        block_size = 1024 * 1024  # 1MB

        while True:
            chunk = resp.read(block_size)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if total_size > 0:
                percent = (downloaded / total_size) * 100
                sys.stdout.write(f"\r    -> {downloaded / (1024*1024):.1f} MB / {total_size / (1024*1024):.1f} MB ({percent:.1f}%)")
                sys.stdout.flush()
        print()


def find_matching_file(files_list, patterns, exclude_patterns=None):
    """Finds first filename in files_list matching any pattern in patterns."""
    exclude_patterns = exclude_patterns or []
    for pattern in patterns:
        for f in files_list:
            fname = f["name"] if isinstance(f, dict) else (f.name if isinstance(f, Path) else str(f))
            if any(fnmatch.fnmatch(fname.lower(), ep.lower()) for ep in exclude_patterns):
                continue
            if fnmatch.fnmatch(fname.lower(), pattern.lower()):
                return f
    return None


def extract_archive_contents(archive_path, target_dir, is_windows=True):
    """Extracts binaries and shared libraries from either .zip or .tar.gz archive into target_dir."""
    path_str = str(archive_path).lower()
    if path_str.endswith(".tar.gz") or path_str.endswith(".tgz"):
        with tarfile.open(archive_path, "r:gz") as tar:
            for member in tar.getmembers():
                if not member.isfile():
                    continue
                base_name = Path(member.name).name
                lower_name = base_name.lower()
                if is_windows:
                    if lower_name.endswith(".exe") or lower_name.endswith(".dll"):
                        f = tar.extractfile(member)
                        if f:
                            (target_dir / base_name).write_bytes(f.read())
                else:
                    if base_name in ("llama-server", "libllama.so", "libggml.so") or base_name.endswith(".so") or base_name.endswith(".dylib") or "llama-server" in base_name:
                        f = tar.extractfile(member)
                        if f:
                            (target_dir / base_name).write_bytes(f.read())
    else:
        with zipfile.ZipFile(archive_path, "r") as z:
            for member in z.namelist():
                base_name = Path(member).name
                lower_name = base_name.lower()
                if not base_name:
                    continue
                if is_windows:
                    if lower_name.endswith(".exe") or lower_name.endswith(".dll"):
                        (target_dir / base_name).write_bytes(z.read(member))
                else:
                    if base_name in ("llama-server", "libllama.so", "libggml.so") or base_name.endswith(".so") or base_name.endswith(".dylib") or "llama-server" in base_name:
                        (target_dir / base_name).write_bytes(z.read(member))


def extract_and_repack(primary_archive, companion_archives, out_zip_path, is_windows=True):
    """
    Extracts binary executable and dynamic libraries from raw zip/tar.gz
    and repacks them into a clean, flat canonical zip for WindsOf Plugin Toolkit.
    """
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)

        # 1. Extract primary archive
        extract_archive_contents(primary_archive, tmp_path, is_windows=is_windows)

        # 2. Extract companion archives (e.g. cudart runtime DLLs)
        for comp_archive in companion_archives:
            extract_archive_contents(comp_archive, tmp_path, is_windows=is_windows)

        # 3. Create final zip archive
        out_zip_path.parent.mkdir(parents=True, exist_ok=True)
        if out_zip_path.exists():
            out_zip_path.unlink()

        collected_files = list(tmp_path.iterdir())
        if not collected_files:
            print(f"  [WARN] No relevant binaries found in {Path(primary_archive).name}")
            return False

        with zipfile.ZipFile(out_zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for item in collected_files:
                zout.write(item, arcname=item.name)

        size_mb = out_zip_path.stat().st_size / (1024 * 1024)
        print(f"  [OK] Created {out_zip_path.name} ({size_mb:.2f} MB with {len(collected_files)} files)")
        return True


def main():
    parser = argparse.ArgumentParser(description="Download and package llama-server precompiled binaries for WindsOf Plugin Toolkit.")
    parser.add_argument("--version", "-v", default="latest", help="llama.cpp release tag (e.g. 'b10655', 'b4850', or 'latest')")
    parser.add_argument("--output-dir", "-o", default="remote/binaries/llama-server", help="Output directory for canonical packages")
    parser.add_argument("--from-folder", "-i", default=None, help="Local directory containing previously downloaded llama.cpp raw archives")
    args = parser.parse_args()

    out_dir = Path(args.output_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print("  WindsOf llama-server Binary Packager")
    print("=" * 60)
    print(f"Target Output Directory: {out_dir}\n")

    # Mode 1: Repackage from local folder
    if args.from_folder:
        local_dir = Path(args.from_folder).resolve()
        if not local_dir.exists():
            print(f"[ERROR] Local directory does not exist: {local_dir}")
            sys.exit(1)

        print(f"Scanning local directory: {local_dir}")
        local_files = list(local_dir.glob("*.zip")) + list(local_dir.glob("*.tar.gz")) + list(local_dir.glob("*.tgz"))
        print(f"Found {len(local_files)} archives.\n")

        processed_count = 0
        for pkg in PACKAGE_DEFINITIONS:
            canonical_name = pkg["canonical_name"]
            exclude = list(pkg.get("exclude_patterns", []))
            if pkg["companion_patterns"]:
                exclude.append("*cudart*")

            matched_primary = find_matching_file(
                local_files,
                pkg["patterns"],
                exclude_patterns=exclude
            )
            if not matched_primary:
                print(f"[SKIP] {pkg['description']} ({canonical_name}): No matching raw archive found.")
                continue

            companion_files = []
            if pkg["companion_patterns"]:
                comp = find_matching_file(
                    local_files,
                    pkg["companion_patterns"],
                    exclude_patterns=pkg.get("exclude_patterns", [])
                )
                if comp:
                    companion_files.append(comp)

            print(f"[BUILD] {pkg['description']} -> {canonical_name}")
            print(f"  Source: {matched_primary.name}" + (f" + {companion_files[0].name}" if companion_files else ""))
            out_file = out_dir / canonical_name
            if extract_and_repack(matched_primary, companion_files, out_file, is_windows=pkg["is_windows"]):
                processed_count += 1

        print(f"\n[DONE] Successfully packaged {processed_count} binary archives in {out_dir}")
        return

    # Mode 2: Download directly from GitHub
    release_info = fetch_release_info(args.version)
    if not release_info:
        sys.exit(1)

    tag_name = release_info.get("tag_name", "unknown")
    assets = release_info.get("assets", [])
    print(f"Release: {release_info.get('name', tag_name)} (Tag: {tag_name})")
    print(f"Available assets: {len(assets)}\n")

    with tempfile.TemporaryDirectory() as download_temp:
        temp_dir_path = Path(download_temp)
        processed_count = 0

        for pkg in PACKAGE_DEFINITIONS:
            canonical_name = pkg["canonical_name"]
            exclude = list(pkg.get("exclude_patterns", []))
            if pkg["companion_patterns"]:
                exclude.append("*cudart*")

            # Find primary asset
            primary_asset = find_matching_file(
                assets,
                pkg["patterns"],
                exclude_patterns=exclude
            )

            if not primary_asset:
                print(f"[SKIP] {pkg['description']} ({canonical_name}): No matching release asset found for patterns {pkg['patterns']}")
                continue

            # Download primary asset
            primary_file_path = temp_dir_path / primary_asset["name"]
            download_file(primary_asset["browser_download_url"], primary_file_path)

            # Find and download companion assets (e.g. cudart)
            companion_paths = []
            if pkg["companion_patterns"]:
                comp_asset = find_matching_file(
                    assets,
                    pkg["companion_patterns"],
                    exclude_patterns=pkg.get("exclude_patterns", [])
                )
                if comp_asset:
                    comp_file_path = temp_dir_path / comp_asset["name"]
                    download_file(comp_asset["browser_download_url"], comp_file_path)
                    companion_paths.append(comp_file_path)

            print(f"[BUILD] Packaging {pkg['description']} -> {canonical_name}")
            out_file = out_dir / canonical_name
            if extract_and_repack(primary_file_path, companion_paths, out_file, is_windows=pkg["is_windows"]):
                processed_count += 1
            print("-" * 50)

        print(f"\n[DONE] Successfully downloaded and packaged {processed_count} binary archives in {out_dir}")


if __name__ == "__main__":
    main()
