"""
Plugin Toolkit Repository Generator & Packager.

Compiles Kotlin plugin modules, collects flows and assets, normalizes and signs JAR archives,
generates the repository index.json catalog, and supports FTP/Git deployment.
"""

import argparse
import base64
import ftplib
import hashlib
import json
import os
import re
import shutil
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from dotenv import find_dotenv, load_dotenv

# Ensure root is in sys.path when running as script
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.sign_jar import (
    find_jdk_tool,
    get_detached_signature,
    has_local_header_mismatch,
    repack_jar_if_needed,
    run_command,
    sign_jar,
    verify_detached_signature,
    verify_jar_internal,
)

# Load environment variables
load_dotenv(find_dotenv(usecwd=True))

PRIVATE_KEY_B64 = os.getenv("PLUGIN_PRIVATE_SIGNING_KEY")
PUBLIC_KEY_B64 = os.getenv("PLUGIN_PUBLIC_SIGNING_KEY")

# Modules that are shared libraries and should not be published as standalone plugins
EXCLUDED_DIRS = {"build", "dist", "dist_backup", "gradle", "common-models", "common-inference", "commonMain", "ag-psd", "runs"}


def extract_plugin_info_from_source(plugin_dir: Path) -> dict | None:
    """
    Scans Kotlin source files in plugin_dir/src to extract plugin ID and version 
    from the @PluginInfo annotation.
    """
    plugin_info_pattern = re.compile(r'@PluginInfo\s*\((.*?)\)', re.DOTALL)
    id_pattern = re.compile(r'\bid\s*=\s*["\']([^"\']+)["\']')
    version_pattern = re.compile(r'\bversion\s*=\s*["\']([^"\']+)["\']')
    name_pattern = re.compile(r'\bname\s*=\s*["\']([^"\']+)["\']')
    description_pattern = re.compile(r'\bdescription\s*=\s*["\']([^"\']+)["\']')

    src_dir = plugin_dir / "src"
    if not src_dir.exists():
        return None

    for path in src_dir.rglob("*.kt"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception:
            continue

        match = plugin_info_pattern.search(content)
        if match:
            args_str = match.group(1)
            id_match = id_pattern.search(args_str)
            version_match = version_pattern.search(args_str)
            name_match = name_pattern.search(args_str)
            desc_match = description_pattern.search(args_str)

            if id_match and version_match:
                return {
                    "id": id_match.group(1),
                    "version": version_match.group(1),
                    "name": name_match.group(1) if name_match else plugin_dir.name,
                    "description": desc_match.group(1) if desc_match else ""
                }
    return None


def find_optional_settings(plugin_dir: Path) -> set:
    """
    Scans Kotlin source files for @PluginSetting annotations and returns a set of setting names
    that have required = false.
    """
    optional_settings = set()
    src_dir = plugin_dir / "src"
    if not src_dir.exists():
        return optional_settings

    setting_pattern = re.compile(r'@PluginSetting\s*\((.*?)\)\s*(?:private\s+)?val\s+(\w+)', re.DOTALL)
    required_false_pattern = re.compile(r'\brequired\s*=\s*false\b')

    for path in src_dir.rglob("*.kt"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()
        except Exception:
            continue

        for match in setting_pattern.finditer(content):
            args_str = match.group(1)
            var_name = match.group(2)
            if required_false_pattern.search(args_str):
                optional_settings.add(var_name)
                
    return optional_settings


def find_assets(plugin_path: Path) -> dict:
    assets = {}
    search_paths = [
        plugin_path / "src" / "main" / "resources",
        plugin_path / "src" / "main" / "kotlin" / "resources",
        plugin_path / "build" / "resources" / "main" / "META-INF",
    ]

    for path in search_paths:
        if not path.exists():
            continue

        if "changelog" not in assets:
            cl = path / "changelog.md"
            if cl.exists():
                assets["changelog"] = cl

        if "icon" not in assets:
            for ext in [".png", ".webp", ".svg", ".jpg"]:
                icon = path / f"icon{ext}"
                if icon.exists():
                    assets["icon"] = icon
                    break

        if "manifest" not in assets:
            manifest = path / "manifest.json"
            if manifest.exists():
                assets["manifest"] = manifest

    return assets


def generate_repo(name: str, url: str, output_dir: str, clean: bool = False, target_plugin: str | None = None) -> bool:
    root_path = Path(".").resolve()
    dist_path = root_path / output_dir
    plugins_dist_path = dist_path / "plugins"
    flows_dist_path = dist_path / "flows"

    cyan = "\033[96m"
    green = "\033[92m"
    yellow = "\033[93m"
    magenta = "\033[95m"
    bold = "\033[1m"
    reset = "\033[0m"

    print(f"\n{bold}{cyan}===================================================={reset}")
    print(f"{bold}{cyan}   Plugin Toolkit Repository Generator & Packager   {reset}")
    print(f"{bold}{cyan}===================================================={reset}\n")

    # Load default target version from libs.versions.toml
    default_target_version = "2.0.0"
    try:
        toml_path = root_path / "gradle" / "libs.versions.toml"
        if toml_path.exists():
            with open(toml_path, "r", encoding="utf-8") as tf:
                toml_content = tf.read()
            version_match = re.search(r'plugin-toolkit\s*=\s*["\']([^"\']+)["\']', toml_content)
            if version_match:
                default_target_version = version_match.group(1)
    except Exception as e:
        print(f"Warning: could not parse plugin-toolkit version from libs.versions.toml: {e}")

    previous_plugins = {}
    previous_index_path = dist_path / "index.json"
    should_load_index = not clean or target_plugin is not None
    if should_load_index and previous_index_path.exists():
        try:
            with open(previous_index_path, "r", encoding="utf-8") as f:
                previous_index = json.load(f)
                if "plugins" in previous_index:
                    for p in previous_index["plugins"]:
                        if "pkg" in p:
                            previous_plugins[p["pkg"]] = p
            print(f"Loaded {len(previous_plugins)} plugins from previous index.json.")
        except Exception as e:
            print(f"Warning: Failed to parse previous index.json: {e}")

    dist_path.mkdir(parents=True, exist_ok=True)
    plugins_dist_path.mkdir(exist_ok=True)

    repo_plugins = []

    # Plugin Discovery
    plugin_dirs = [
        d for d in root_path.iterdir()
        if d.is_dir()
        and d != root_path
        and (d / "build.gradle.kts").exists()
        and not d.name.startswith(".")
        and d.name not in EXCLUDED_DIRS
    ]

    print(f"{bold}Discovered Modules ({len(plugin_dirs)}):{reset}")
    for p_dir in plugin_dirs:
        print(f"  • {bold}{p_dir.name:<24}{reset} {green}[Standard Plugin]{reset}")
    print()

    for plugin_dir in plugin_dirs:
        info = extract_plugin_info_from_source(plugin_dir)
        base_pkg = info["id"] if info else plugin_dir.name
        base_version = info["version"] if info else "1.0.0"
        base_name = info.get("name", plugin_dir.name) if info else plugin_dir.name
        base_desc = info.get("description", "") if info else ""

        is_target = False
        if target_plugin:
            search_key = target_plugin.lower()
            if plugin_dir.name.lower() == search_key or (base_pkg and base_pkg.lower() == search_key) or (base_name.lower() == search_key):
                is_target = True

        if target_plugin and not is_target:
            # Preserve existing entry if available
            if base_pkg in previous_plugins:
                repo_plugins.append(previous_plugins[base_pkg])
            continue

        target_pkg = base_pkg
        display_name = base_name
        display_desc = base_desc
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        gradle_args = [gradle_wrapper, f":{plugin_dir.name}:clean", f":{plugin_dir.name}:build", "--no-configuration-cache"] if clean else [gradle_wrapper, f":{plugin_dir.name}:jar"]
        target_jar_filename = f"{plugin_dir.name}-{base_version}.jar"

        print(f"\n{bold}Processing {display_name}{reset} {green}[Standard Plugin]{reset}...")

        # Check if we can reuse previous build
        should_build = True
        if not clean and target_pkg in previous_plugins:
            prev_p = previous_plugins[target_pkg]
            if prev_p.get("version") == base_version:
                stored_file = prev_p.get("fileName")
                if stored_file:
                    target_jar_path = plugins_dist_path / target_pkg / stored_file
                    manifest_path = plugins_dist_path / target_pkg / "manifest.json"
                    if target_jar_path.exists() and manifest_path.exists():
                        # Validate cached JAR integrity before reusing it
                        jar_is_valid = False
                        try:
                            with zipfile.ZipFile(target_jar_path, 'r') as _zf:
                                bad_entry = _zf.testzip()
                                jar_is_valid = bad_entry is None
                            if not jar_is_valid:
                                print(f"  [WARN] Cached JAR for {target_pkg} is corrupted (bad entry: {bad_entry}). Forcing rebuild.")
                        except Exception as _zip_err:
                            print(f"  [WARN] Cached JAR for {target_pkg} failed integrity check ({_zip_err}). Forcing rebuild.")

                        if jar_is_valid:
                            print(f"  -> Reusing existing build for {target_pkg} v{base_version}")
                            repo_plugins.append(prev_p)
                            should_build = False

        if not should_build:
            continue

        # Execute Gradle build
        ok_build, _, _ = run_command(gradle_args, cwd=str(root_path))
        if not ok_build:
            print(f"  [ERROR] Failed to compile {plugin_dir.name}, skipping.")
            continue

        assets = find_assets(plugin_dir)

        # Load or construct manifest
        manifest_path = assets.get("manifest")
        build_manifest = plugin_dir / "build" / "resources" / "main" / "META-INF" / "manifest.json"
        if build_manifest.exists():
            manifest_path = build_manifest

        if not manifest_path or not manifest_path.exists():
            print(f"  [ERROR] No manifest.json found for {plugin_dir.name}, skipping.")
            continue

        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)

        # Fix optional settings in manifest
        optional_settings = find_optional_settings(plugin_dir)
        if "settings" in manifest and manifest["settings"] is not None:
            for sname, sdata in manifest["settings"].items():
                if sname in optional_settings:
                    sdata["required"] = False

        # Update manifest metadata
        if "plugin" not in manifest:
            manifest["plugin"] = {}

        manifest["plugin"]["id"] = target_pkg
        manifest["plugin"]["name"] = display_name
        manifest["plugin"]["version"] = base_version
        manifest["plugin"]["description"] = display_desc
        manifest["plugin"]["targetAppVersion"] = default_target_version
        if "requirements" not in manifest:
            manifest["requirements"] = {}
        manifest["requirements"]["targetAppVersion"] = default_target_version
        manifest["targetAppVersion"] = default_target_version

        # Find built JAR
        jar_dir = plugin_dir / "build" / "libs"
        primary_jar = jar_dir / f"{plugin_dir.name}.jar"
        if primary_jar.exists():
            source_jar = primary_jar
        else:
            jars = [j for j in jar_dir.glob("*.jar") if not "tmp" in j.name and not "sanitized" in j.name]
            if not jars:
                print(f"  [ERROR] No JAR found in {jar_dir}, skipping.")
                continue
            source_jar = jars[0]

        # Prepare dist directory for this package
        pkg_dist_path = plugins_dist_path / target_pkg
        pkg_dist_path.mkdir(parents=True, exist_ok=True)
        for item in pkg_dist_path.iterdir():
            try:
                if item.is_file() or item.is_symlink():
                    item.unlink()
                elif item.is_dir():
                    shutil.rmtree(item, ignore_errors=True)
            except Exception:
                pass

        target_jar_path = pkg_dist_path / target_jar_filename

        # Sign the JAR directly into dist using sign_jar
        sign_success = sign_jar(
            jar_path=source_jar,
            private_key_b64=PRIVATE_KEY_B64,
            output_path=target_jar_path,
            verbose=True
        )
        if not sign_success:
            print(f"  [WARN] Failed to sign {target_jar_filename}; copying raw jar as fallback.")
            shutil.copy2(source_jar, target_jar_path)

        # Compute detached signature and hash
        jar_hash, jar_sig = get_detached_signature(target_jar_path, PRIVATE_KEY_B64, verbose=True)

        # Copy assets to dist
        if "changelog" in assets:
            shutil.copy2(assets["changelog"], pkg_dist_path / "changelog.md")
        if "icon" in assets:
            shutil.copy2(assets["icon"], pkg_dist_path / f"icon{assets['icon'].suffix}")

        # Save manifest in dist folder
        with open(pkg_dist_path / "manifest.json", "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)

        file_size_mb = target_jar_path.stat().st_size / (1024 * 1024)

        plugin_entry = {
            "name": display_name,
            "pkg": target_pkg,
            "version": base_version,
            "fileName": target_jar_filename,
            "description": display_desc,
            "targetAppVersion": manifest["plugin"]["targetAppVersion"],
        }

        if jar_hash:
            plugin_entry["hash"] = jar_hash
        if jar_sig:
            plugin_entry["signature"] = jar_sig

        repo_plugins.append(plugin_entry)

        print(f"  -> {green}Added {display_name} v{base_version} ({target_pkg}) [{file_size_mb:.2f} MB]{reset}")

    # Clean up stale plugins in dist
    active_pkgs = {p["pkg"] for p in repo_plugins}
    if plugins_dist_path.exists():
        for d in plugins_dist_path.iterdir():
            if d.is_dir() and d.name not in active_pkgs:
                print(f"Removing stale plugin directory: {d.name}")
                shutil.rmtree(d)

    # Process Flows
    repo_flows = []
    source_flows_path = root_path / "flows"
    if source_flows_path.exists() and source_flows_path.is_dir():
        flows_dist_path.mkdir(exist_ok=True)
        for flow_file in source_flows_path.iterdir():
            if flow_file.is_file() and flow_file.suffix.lower() in ('.json', '.zip'):
                filename = flow_file.name
                target_flow_path = flows_dist_path / filename

                shutil.copy2(flow_file, target_flow_path)
                flow_hash, flow_sig = get_detached_signature(target_flow_path, PRIVATE_KEY_B64, verbose=True)

                flow_name = flow_file.stem
                flow_version = "1.0.0"
                flow_desc = ""

                if flow_file.suffix.lower() == '.json':
                    try:
                        with open(flow_file, 'r', encoding='utf-8') as f:
                            flow_data = json.load(f)
                            flow_name = flow_data.get('name', flow_name)
                            flow_version = flow_data.get('version', flow_version)
                            flow_desc = flow_data.get('description', flow_desc)
                    except Exception as e:
                        print(f"Warning: Failed to parse flow JSON metadata from {filename}: {e}")
                elif flow_file.suffix.lower() == '.zip':
                    try:
                        with zipfile.ZipFile(flow_file) as z:
                            json_files = [f for f in z.namelist() if f.endswith('.json')]
                            if json_files:
                                with z.open(json_files[0]) as jf:
                                    flow_data = json.loads(jf.read().decode('utf-8'))
                                    flow_name = flow_data.get('name', flow_name)
                                    flow_version = flow_data.get('version', flow_version)
                                    flow_desc = flow_data.get('description', flow_desc)
                    except Exception as e:
                        print(f"Warning: Failed to parse flow ZIP metadata from {filename}: {e}")

                flow_entry = {
                    "name": flow_name,
                    "fileName": filename,
                    "version": flow_version,
                    "description": flow_desc
                }
                if flow_hash:
                    flow_entry["hash"] = flow_hash
                if flow_sig:
                    flow_entry["signature"] = flow_sig

                repo_flows.append(flow_entry)
                print(f"  -> Added flow {flow_name} v{flow_version} ({filename})")

    # Clean up stale flow files in dist
    active_flows = {f["fileName"] for f in repo_flows}
    if flows_dist_path.exists():
        for f in flows_dist_path.iterdir():
            if f.is_file() and f.name not in active_flows:
                print(f"Removing stale flow file: {f.name}")
                try:
                    os.remove(f)
                except Exception as e:
                    print(f"Warning: Could not remove stale flow file {f.name}: {e}")

    # Generate index.json
    index = {
        "name": name,
        "url": f"{url.rstrip('/')}/index.json",
        "schemaVersion": 1,
        "pluginsFolder": "plugins",
        "flowsFolder": "flows",
        "plugins": repo_plugins,
        "flows": repo_flows,
    }

    if PUBLIC_KEY_B64:
        index["signPublicKey"] = PUBLIC_KEY_B64
        index["signAlgorithm"] = "SHA256"

    with open(dist_path / "index.json", "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2)

    # Print Summary
    print(f"\n{bold}{green}===================================================={reset}")
    print(f"{bold}{green}   Repository Generated Successfully in '{output_dir}/'   {reset}")
    print(f"{bold}{green}===================================================={reset}\n")

    print(f"{bold}{'Plugin Name':<32} {'Package ID':<28} {'Version':<10} {'Size':<10}{reset}")
    print("-" * 82)
    for p in repo_plugins:
        p_pkg = p["pkg"]
        p_file = p["fileName"]
        jar_path = plugins_dist_path / p_pkg / p_file
        size_str = f"{jar_path.stat().st_size / (1024*1024):.2f} MB" if jar_path.exists() else "N/A"
        print(f"{p['name']:<32} {p_pkg:<28} {p['version']:<10} {size_str:<10}")

    print("-" * 82)
    print(f"Total plugins: {len(repo_plugins)}")
    print(f"Total flows:   {len(repo_flows)}\n")
    return True


def connect_ftp(host, port, user, password, secure=True, timeout=60, passive=True):
    """
    Connects to the remote FTP/FTPS server.
    If secure=True, attempts FTPS (explicit TLS) first, falling back to plain FTP if TLS is not supported.
    """
    ftp = None
    if secure:
        try:
            ftp = ftplib.FTP_TLS(timeout=timeout)
            ftp.connect(host, port)
            ftp.login(user, password)
            ftp.prot_p()  # Switch data transfer channel to TLS
            ftp.set_pasv(passive)
            return ftp, "FTPS (Explicit TLS)"
        except Exception as e:
            print(f"  [FTP] FTPS failed ({e}). Falling back to standard FTP...")
            if ftp is not None:
                try:
                    ftp.close()
                except Exception:
                    pass

    ftp = ftplib.FTP(timeout=timeout)
    ftp.connect(host, port)
    ftp.login(user, password)
    ftp.set_pasv(passive)
    return ftp, "FTP (Plain)"


def ensure_remote_dir(ftp, remote_dir_path, start_from_root=False):
    """
    Recursively ensures that remote_dir_path exists on the FTP server and navigates into it.
    If start_from_root is True or remote_dir_path starts with '/', starts navigation from '/'.
    Otherwise, navigates relative to the current working directory.
    """
    norm_path = remote_dir_path.replace("\\", "/").strip()
    if not norm_path or norm_path == ".":
        return

    is_absolute = start_from_root or norm_path.startswith("/")
    clean_path = norm_path.strip("/")

    if is_absolute:
        try:
            ftp.cwd("/")
        except Exception:
            pass

    parts = [p for p in clean_path.split("/") if p]
    for part in parts:
        try:
            ftp.cwd(part)
        except ftplib.error_perm:
            try:
                ftp.mkd(part)
                ftp.cwd(part)
            except ftplib.error_perm as e:
                try:
                    ftp.cwd(part)
                except ftplib.error_perm:
                    curr_pwd = "?"
                    try:
                        curr_pwd = ftp.pwd()
                    except Exception:
                        pass
                    raise ftplib.error_perm(
                        f"Failed to access or create directory '{part}' in '{curr_pwd}'. Error: {e}"
                    )


def resolve_and_ensure_remote_base_dir(ftp, remote_base_dir):
    """
    Navigates to remote_base_dir. If direct navigation from '/' fails,
    checks if a domain directory (e.g. www.windsofresub.cloud) exists that contains remote_base_dir.
    """
    # 1. Try direct navigation from root
    try:
        ensure_remote_dir(ftp, remote_base_dir, start_from_root=True)
        return ftp.pwd()
    except Exception:
        pass

    # 2. Check if a domain folder exists in root (common on shared hosts like Aruba/cPanel)
    try:
        ftp.cwd("/")
        entries = []
        try:
            entries = ftp.nlst()
        except Exception:
            pass

        for entry in entries:
            clean_entry = entry.strip("/")
            candidate = f"{clean_entry}/{remote_base_dir.strip('/')}"
            try:
                ensure_remote_dir(ftp, candidate, start_from_root=True)
                print(f"  [FTP] Auto-detected domain directory: using '{candidate}'")
                return ftp.pwd()
            except Exception:
                continue
    except Exception:
        pass

    # 3. If auto-detection didn't succeed, re-try direct with full error message
    ensure_remote_dir(ftp, remote_base_dir, start_from_root=True)
    return ftp.pwd()


def get_remote_file_size(ftp, filename):
    """
    Returns remote file size in bytes using SIZE command, or None if unavailable/error.
    """
    try:
        return ftp.size(filename)
    except (ftplib.error_perm, ftplib.error_reply, Exception):
        return None


def upload_file_with_progress(ftp, local_file_path, remote_filename, force=False):
    """
    Uploads a single local file to the current remote directory with smart skip and progress output.
    """
    local_size = local_file_path.stat().st_size
    local_size_mb = local_size / (1024 * 1024)

    # Check if we can skip unchanged files (except metadata files like index.json and manifest.json)
    is_metadata = remote_filename in ("index.json", "manifest.json")
    if not force and not is_metadata:
        remote_size = get_remote_file_size(ftp, remote_filename)
        if remote_size is not None and remote_size == local_size:
            print(f"  -> [SKIP] {remote_filename} ({local_size_mb:.2f} MB) already up to date on server.")
            return True

    uploaded_bytes = 0
    last_reported_pct = -1
    start_time = time.time()
    large_file = local_size > 5 * 1024 * 1024  # > 5 MB

    def progress_callback(chunk):
        nonlocal uploaded_bytes, last_reported_pct
        uploaded_bytes += len(chunk)
        if large_file:
            pct = int((uploaded_bytes / local_size) * 100)
            if pct != last_reported_pct and (pct % 10 == 0 or pct == 100):
                elapsed = max(time.time() - start_time, 0.001)
                speed_mbps = (uploaded_bytes / (1024 * 1024)) / elapsed
                sys.stdout.write(f"\r     Uploading {remote_filename}: {pct}% ({uploaded_bytes / (1024*1024):.1f}/{local_size_mb:.1f} MB, {speed_mbps:.2f} MB/s)  ")
                sys.stdout.flush()
                last_reported_pct = pct

    with open(local_file_path, "rb") as f:
        ftp.storbinary(f"STOR {remote_filename}", f, blocksize=65536, callback=progress_callback)

    if large_file:
        print()  # Print newline after progress updates
    print(f"  -> [UPLOADED] {remote_filename} ({local_size_mb:.2f} MB)")
    return True


def upload_to_ftp(output_dir="dist", force=False, dry_run=False):
    """
    Synchronizes the output_dir to the configured FTP/FTPS server.
    """
    host = os.getenv("FTP_HOST")
    user = os.getenv("FTP_USER") or os.getenv("FTP_USERNAME")
    password = os.getenv("FTP_PASS") or os.getenv("FTP_PASSWORD")
    port_str = os.getenv("FTP_PORT", "21")
    remote_base_dir = os.getenv("FTP_DIR") or os.getenv("FTP_PATH") or os.getenv("FTP_REMOTE_DIR") or "plugins"
    secure_str = os.getenv("FTP_SECURE", os.getenv("FTP_TLS", "true")).lower()
    secure = secure_str not in ("0", "false", "no", "off")
    timeout = int(os.getenv("FTP_TIMEOUT", "60"))

    if not host or not user or not password:
        print("\n[ERROR] FTP credentials not configured in environment or .env file.")
        print("Please configure the following keys in your .env file:")
        print("  FTP_HOST=ftp.windsofresub.cloud")
        print("  FTP_USER=your_ftp_username")
        print("  FTP_PASS=your_ftp_password")
        print("  FTP_DIR=www.windsofresub.cloud/plugins  (or plugins)")
        print("  FTP_PORT=21                            (optional, default: 21)")
        print("  FTP_TLS=true                           (optional, default: true)")
        return False

    try:
        port = int(port_str)
    except ValueError:
        print(f"[ERROR] Invalid FTP_PORT: '{port_str}'. Must be an integer.")
        return False

    dist_path = Path(output_dir).resolve()
    if not dist_path.exists():
        print(f"[ERROR] Output directory '{output_dir}' does not exist.")
        return False

    prefix = "[DRY RUN] " if dry_run else ""
    print(f"\n{prefix}[FTP] Connecting to {host}:{port} as '{user}'...")

    try:
        ftp, mode_str = connect_ftp(host, port, user, password, secure=secure, timeout=timeout)
        print(f"{prefix}[FTP] Connected successfully via {mode_str}.")
    except Exception as e:
        print(f"[ERROR] Failed to connect or authenticate to FTP server ({host}:{port}): {e}")
        return False

    try:
        # Navigate or create remote base directory
        print(f"{prefix}[FTP] Navigating to remote directory: '{remote_base_dir}'")
        base_remote_pwd = resolve_and_ensure_remote_base_dir(ftp, remote_base_dir)
        print(f"{prefix}[FTP] Remote working directory is: '{base_remote_pwd}'")

        # Collect all files to upload from dist_path (excluding .git and backup/temp files)
        all_files = []
        for root, dirs, files in os.walk(dist_path):
            dirs[:] = [d for d in dirs if d != ".git"]
            for file in files:
                if file.startswith(".git") or file.endswith((".orig", ".bak", ".tmp", ".sig")):
                    continue
                full_path = Path(root) / file
                rel_path = full_path.relative_to(dist_path)
                all_files.append((full_path, rel_path))

        # Always upload index.json last to guarantee repository consistency
        all_files.sort(key=lambda item: 1 if item[1].name == "index.json" else 0)

        action_desc = "Simulating synchronization of" if dry_run else "Synchronizing"
        print(f"{prefix}[FTP] {action_desc} {len(all_files)} files from '{output_dir}/' to '{base_remote_pwd}'...\n")

        for local_file, rel_path in all_files:
            # Change back to base_remote_pwd
            ftp.cwd(base_remote_pwd)

            # If the file is in a subdirectory (e.g. plugins/com.wip.slicer/slicer.jar)
            rel_parent = rel_path.parent
            if str(rel_parent) != ".":
                ensure_remote_dir(ftp, str(rel_parent).replace("\\", "/"), start_from_root=False)

            if dry_run:
                local_size = local_file.stat().st_size
                local_size_mb = local_size / (1024 * 1024)
                is_metadata = rel_path.name in ("index.json", "manifest.json")
                remote_size = get_remote_file_size(ftp, rel_path.name)
                rel_str = str(rel_path).replace("\\", "/")
                if not force and not is_metadata and remote_size == local_size:
                    print(f"  -> [DRY RUN SKIP] {rel_str} ({local_size_mb:.2f} MB) - already up to date on server.")
                else:
                    print(f"  -> [DRY RUN UPLOAD] Would upload {rel_str} ({local_size_mb:.2f} MB)")
            else:
                upload_file_with_progress(ftp, local_file, rel_path.name, force=force)

        if dry_run:
            print(f"\n[DRY RUN] Verification successful! Connection, credentials, and '{base_remote_pwd}' were verified.")
        else:
            print(f"\n[FTP] Repository successfully deployed to {host}:{base_remote_pwd}!")
        return True

    except Exception as e:
        print(f"\n[ERROR] An error occurred during FTP operation: {e}")
        return False
    finally:
        try:
            ftp.quit()
        except Exception:
            try:
                ftp.close()
            except Exception:
                pass


def push_to_git(output_dir):
    dist_path = Path(output_dir).resolve()
    if not (dist_path / ".git").exists():
        print(f"Error: '{output_dir}' is not a git repository. Cannot push.")
        return False

    print(f"\nPushing changes in '{output_dir}' to remote git repository...")

    ok, _, _ = run_command(["git", "add", "."], cwd=str(dist_path))
    if not ok:
        print("Failed to add files.")
        return False

    status = subprocess.run(["git", "status", "--porcelain"], cwd=str(dist_path), capture_output=True, text=True)
    if not status.stdout.strip():
        print("No changes to commit in dist folder.")
        return True

    ok, _, _ = run_command(["git", "commit", "-m", "Auto-update plugins via generate_repo.py"], cwd=str(dist_path))
    if not ok:
        print("Failed to commit changes.")
        return False

    ok, _, _ = run_command(["git", "push"], cwd=str(dist_path))
    if not ok:
        print("Failed to push changes.")
        return False

    print("Successfully pushed changes to remote git repository.")
    return True


def deploy_repository(output_dir, force=False, dry_run=False):
    """
    Deploys repository: prefers FTP if FTP_HOST is configured in .env,
    otherwise falls back to git push if dist/.git exists.
    """
    has_ftp_config = bool(os.getenv("FTP_HOST"))
    dist_has_git = (Path(output_dir).resolve() / ".git").exists()

    if has_ftp_config:
        return upload_to_ftp(output_dir, force=force, dry_run=dry_run)
    elif dist_has_git:
        return push_to_git(output_dir)
    else:
        # Prompt user with FTP setup instructions
        return upload_to_ftp(output_dir, force=force, dry_run=dry_run)


def main():
    parser = argparse.ArgumentParser(description="Generate a static plugin repository.")
    parser.add_argument("plugin", nargs="?", help="Name of the plugin directory or ID to build (optional)")
    parser.add_argument("--name", help="Repository name")
    parser.add_argument("--url", help="Base URL of the repository")
    parser.add_argument("--out", help="Output directory")
    parser.add_argument("--clean", action="store_true", help="Force clean build (recompile target or all plugins)")
    parser.add_argument("--force", action="store_true", help="Force regenerate (recompile target or all plugins)")
    parser.add_argument("--push", action="store_true", help="Automatically upload/push the output directory (prefers FTP if configured, else Git)")
    parser.add_argument("--ftp", action="store_true", help="Upload the output directory directly to FTP server")
    parser.add_argument("--ftp-dry-run", action="store_true", help="Simulate FTP upload without sending files")

    args = parser.parse_args()

    config = {
        "name": "My Plugin Repository",
        "url": "http://localhost:8080",
        "out": "dist",
    }

    config_path = Path("repo_config.json")
    if config_path.exists():
        with open(config_path, "r") as f:
            file_config = json.load(f)
            config.update(file_config)

    if args.name:
        config["name"] = args.name
    if args.url:
        config["url"] = args.url
    if args.out:
        config["out"] = args.out

    clean_build = args.clean or args.force or False

    generate_repo(
        config["name"],
        config["url"],
        config["out"],
        clean=clean_build,
        target_plugin=args.plugin
    )

    if args.ftp or args.ftp_dry_run:
        upload_to_ftp(config["out"], force=clean_build, dry_run=args.ftp_dry_run)
    elif args.push:
        deploy_repository(config["out"], force=clean_build, dry_run=args.ftp_dry_run)


if __name__ == "__main__":
    main()
