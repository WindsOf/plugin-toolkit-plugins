import json
import shutil
import subprocess
import argparse
import os
import hashlib
import base64
import tempfile
import re
import zipfile
import struct
import zlib
from pathlib import Path
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

PRIVATE_KEY_B64 = os.getenv("PLUGIN_PRIVATE_SIGNING_KEY")
PUBLIC_KEY_B64 = os.getenv("PLUGIN_PUBLIC_SIGNING_KEY")

# Set of plugins that utilize ONNX runtime and support both GPU+CPU and CPU-only variants
AI_INFERENCE_PLUGINS = {"vision", "cleaner", "slicer", "ocr_ia"}

# Modules that are shared libraries and should not be published as standalone plugins
EXCLUDED_DIRS = {"build", "dist", "dist_backup", "gradle", "common-models", "common-inference", "commonMain", "ag-psd", "runs"}


def extract_plugin_info_from_source(plugin_dir):
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


def find_optional_settings(plugin_dir):
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


def run_command(command, cwd=None):
    env = os.environ.copy()
    # Fix for broken OPENSSL_CONF on some systems
    if "OPENSSL_CONF" in env and not os.path.exists(env["OPENSSL_CONF"]):
        del env["OPENSSL_CONF"]

    # Try to use jarsigner if it's not in path but we know common JDK locations
    if command[0] == "jarsigner":
        common_paths = [
            r"C:\Program Files\Java\jdk-24\bin\jarsigner.exe",
            r"C:\Program Files\Java\jdk-26\bin\jarsigner.exe",
            r"C:\Program Files\Android\Android Studio\jbr\bin\jarsigner.exe"
        ]
        for p in common_paths:
            if Path(p).exists():
                command[0] = p
                break

    print(f"  [EXEC] {' '.join(command)}")
    result = subprocess.run(
        command, cwd=cwd, shell=True, capture_output=True, text=True, env=env
    )
    if result.returncode != 0:
        print(f"  [ERROR] Command failed with code {result.returncode}:\n{result.stdout}\n{result.stderr}")
        return False

    if "gradle" in command[0].lower():
        warnings = []
        for line in (result.stdout + "\n" + result.stderr).splitlines():
            line_stripped = line.strip()
            if not line_stripped:
                continue
            lower_line = line_stripped.lower()
            if "warning" in lower_line or line_stripped.startswith("w: "):
                warnings.append(line_stripped)
                
        if warnings:
            yellow = "\033[93m"
            reset = "\033[0m"
            print(f"{yellow}  [WARNINGS] Gradle reported warnings:{reset}")
            for w in warnings[:5]:
                print(f"{yellow}    {w}{reset}")
            if len(warnings) > 5:
                print(f"{yellow}    ... and {len(warnings) - 5} more warnings.{reset}")

    return True


def sign_jar(jar_path, private_key_b64):
    if not private_key_b64:
        print("  [WARN] PLUGIN_PRIVATE_SIGNING_KEY not set. JAR will not be signed.")
        return False

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        priv_key_file = tmp_path / "private.pem"
        cert_file = tmp_path / "cert.pem"
        p12_file = tmp_path / "keystore.p12"
        
        # 1. Prepare Private Key
        priv_key_pem = f"-----BEGIN PRIVATE KEY-----\n{private_key_b64}\n-----END PRIVATE KEY-----"
        priv_key_file.write_text(priv_key_pem)

        # 1.1 Create minimal openssl.cnf to avoid errors on some systems
        config_file = tmp_path / "openssl.cnf"
        config_content = "[req]\ndistinguished_name = req_distinguished_name\n[req_distinguished_name]\n"
        config_file.write_text(config_content)
        
        # Set environment variable for this process
        os.environ["OPENSSL_CONF"] = str(config_file)

        # 2. Create self-signed certificate for jarsigner
        subj = "/CN=Plugin Toolkit"
        cmd = ["openssl", "req", "-new", "-x509", "-key", str(priv_key_file), "-out", str(cert_file), "-days", "365", "-subj", subj, "-config", str(config_file)]
        if not run_command(cmd):
            return False

        # 3. Create PKCS12 keystore
        cmd = ["openssl", "pkcs12", "-export", "-in", str(cert_file), "-inkey", str(priv_key_file), "-out", str(p12_file), "-name", "plugin-key", "-passout", "pass:password"]
        if not run_command(cmd):
            return False

        # 4. Sign the JAR using jarsigner
        jarsigner_tool = find_jdk_tool("jarsigner")
        cmd = [jarsigner_tool, "-keystore", str(p12_file), "-storetype", "PKCS12", "-storepass", "password", "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", str(jar_path), "plugin-key"]
        if not run_command(cmd):
            return False
            
        print(f"  [SIGN] Successfully signed {jar_path.name}")
        return True


def get_detached_signature(jar_path, private_key_b64):
    if not private_key_b64:
        return None, None

    # 1. Calculate SHA-256 hash of the JAR
    sha256_hash = hashlib.sha256()
    with open(jar_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    
    hash_str = sha256_hash.hexdigest()

    # 2. Sign the hash string using RSA-SHA256
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        priv_key_file = tmp_path / "private.pem"
        hash_file = tmp_path / "hash.txt"
        sig_file = tmp_path / "sig.bin"

        priv_key_pem = f"-----BEGIN PRIVATE KEY-----\n{private_key_b64}\n-----END PRIVATE KEY-----"
        priv_key_file.write_text(priv_key_pem)
        hash_file.write_text(hash_str)

        # Use openssl to sign the hash file
        cmd = ["openssl", "dgst", "-sha256", "-sign", str(priv_key_file), "-out", str(sig_file), str(hash_file)]
        if not run_command(cmd):
            return hash_str, None
        
        with open(sig_file, "rb") as f:
            signature = base64.b64encode(f.read()).decode("utf-8")
            
        return hash_str, signature


def find_jdk_tool(tool_name):
    common_paths = [
        rf"C:\Program Files\Java\jdk-24\bin\{tool_name}.exe",
        rf"C:\Program Files\Java\jdk-26\bin\{tool_name}.exe",
        rf"C:\Program Files\Android\Android Studio\jbr\bin\{tool_name}.exe"
    ]
    for p in common_paths:
        if Path(p).exists():
            return p
    if shutil.which(tool_name):
        return tool_name
    return tool_name


def inject_manifest_into_jar(jar_path, manifest_data):
    """
    Updates or inserts META-INF/manifest.json inside an existing JAR file safely,
    sanitizing any mismatched central directory counts or Zip64 headers from Gradle.
    """
    manifest_bytes = json.dumps(manifest_data, indent=2).encode("utf-8")
    tmp_jar = jar_path.with_suffix(".tmp_sanitized.jar")

    try:
        with open(jar_path, "rb") as f:
            file_size = jar_path.stat().st_size
            f.seek(max(0, file_size - 1024), 0)
            tail = f.read()
            eocd_idx = tail.rfind(b"PK\x05\x06")
            if eocd_idx == -1:
                raise ValueError(f"Could not find End of Central Directory in {jar_path.name}")

            f.seek(max(0, file_size - 1024) + eocd_idx, 0)
            eocd = f.read(22)
            sig, disk, cd_disk, disk_entries, total_entries, cd_size, cd_offset, clen = struct.unpack("<IHHHHIIH", eocd)

            # Search for real central directory start if offset is slightly shifted
            if cd_offset >= file_size:
                cd_offset = file_size - 22 - cd_size
            f.seek(cd_offset)
            if f.read(4) != b"PK\x01\x02":
                for delta in range(-256, 257):
                    f.seek(max(0, cd_offset + delta))
                    if f.read(4) == b"PK\x01\x02":
                        cd_offset = max(0, cd_offset + delta)
                        break

            f.seek(cd_offset)
            entries = []
            while True:
                sig = f.read(4)
                if sig != b"PK\x01\x02":
                    break
                rest = f.read(42)
                if len(rest) < 42:
                    break
                ver_made, ver_need, flag, comp, mtime, mdate, crc, csize, usize, nlen, elen, clen, dnum, iattr, eattr, offset = struct.unpack("<HHHHHHIIIHHHHHII", rest)
                fname = f.read(nlen).decode("utf-8", errors="ignore")
                extra = f.read(elen)
                comment = f.read(clen)
                entries.append((fname, offset, csize, usize, comp, crc))

            with zipfile.ZipFile(tmp_jar, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
                for fname, offset, csize, usize, comp, crc in entries:
                    if fname == "META-INF/manifest.json":
                        continue
                    f.seek(offset)
                    loc_hdr = f.read(30)
                    if len(loc_hdr) < 30:
                        continue
                    lsig, lver, lflag, lcomp, lmtime, lmdate, lcrc, lcsize, lusize, lnlen, lelen = struct.unpack("<IHHHHHIIIHH", loc_hdr)
                    f.seek(offset + 30 + lnlen + lelen)
                    raw_bytes = f.read(csize if comp != 0 else usize)
                    if comp == 8:
                        try:
                            data = zlib.decompress(raw_bytes, -15)
                        except Exception:
                            data = raw_bytes
                    else:
                        data = raw_bytes
                    zout.writestr(fname, data)
                zout.writestr("META-INF/manifest.json", manifest_bytes)

        # Replace target jar with sanitized jar
        tmp_jar.replace(jar_path)
    except Exception as e:
        print(f"  [WARN] Python ZIP sanitization failed ({e}), falling back to JDK jar tool.")
        if tmp_jar.exists():
            tmp_jar.unlink()
        with tempfile.TemporaryDirectory() as tmp_dir:
            tmp_meta_inf = Path(tmp_dir) / "META-INF"
            tmp_meta_inf.mkdir(parents=True, exist_ok=True)
            manifest_file = tmp_meta_inf / "manifest.json"
            with open(manifest_file, "w", encoding="utf-8") as f:
                json.dump(manifest_data, f, indent=2)

            jar_tool = find_jdk_tool("jar")
            cmd = [jar_tool, "uf", str(jar_path), "-C", str(tmp_dir), "META-INF/manifest.json"]
            run_command(cmd)


def find_assets(plugin_path):
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


def generate_repo(name, url, output_dir, clean=False, target_plugin=None, variant_filter="all"):
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
        is_ai = p_dir.name.lower() in AI_INFERENCE_PLUGINS
        category = f"{magenta}[AI Inference: GPU+CPU & CPU-Only]{reset}" if is_ai else f"{green}[Standard / Pure Plugin]{reset}"
        print(f"  • {bold}{p_dir.name:<24}{reset} {category}")
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
            for candidate_pkg in [base_pkg, f"{base_pkg}.cpu"]:
                if candidate_pkg in previous_plugins:
                    repo_plugins.append(previous_plugins[candidate_pkg])
            continue

        is_ai_plugin = plugin_dir.name.lower() in AI_INFERENCE_PLUGINS

        # Determine which variants to build for this plugin
        variants_to_build = []
        if is_ai_plugin:
            if variant_filter in ("all", "gpu"):
                variants_to_build.append("gpu")
            if variant_filter in ("all", "cpu"):
                variants_to_build.append("cpu")
        else:
            variants_to_build.append("standard")

        for variant in variants_to_build:
            if variant == "gpu":
                variant_label = f"{bold}{magenta}[AI Inference: GPU + CPU]{reset}"
                target_pkg = base_pkg
                display_name = f"{base_name} (GPU + CPU)"
                display_desc = f"{base_desc} (GPU acceleration via CUDA / DirectML and CPU fallback)" if base_desc else "GPU + CPU accelerated build"
                gradle_args = ["gradlew.bat", f":{plugin_dir.name}:jar", "-PonnxVariant=gpu", "--no-configuration-cache", "--rerun-tasks"]
                target_jar_filename = f"{plugin_dir.name}-{base_version}-gpu.jar"
            elif variant == "cpu":
                variant_label = f"{bold}{cyan}[AI Inference: CPU-Only]{reset}"
                target_pkg = f"{base_pkg}.cpu"
                display_name = f"{base_name} (CPU)"
                display_desc = f"{base_desc} (CPU inference only, lightweight build)" if base_desc else "CPU-only lightweight build"
                gradle_args = ["gradlew.bat", f":{plugin_dir.name}:jar", "-PonnxVariant=cpu", "--no-configuration-cache", "--rerun-tasks"]
                target_jar_filename = f"{plugin_dir.name}-{base_version}-cpu.jar"
            else:
                variant_label = f"{bold}{green}[Standard / Pure Plugin]{reset}"
                target_pkg = base_pkg
                display_name = base_name
                display_desc = base_desc
                gradle_args = ["gradlew.bat", f":{plugin_dir.name}:jar", "--no-configuration-cache", "--rerun-tasks"] if clean else ["gradlew.bat", f":{plugin_dir.name}:jar"]
                target_jar_filename = f"{plugin_dir.name}-{base_version}.jar"

            print(f"\n{bold}Processing {display_name}{reset} {variant_label}...")

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
                            print(f"  -> Reusing existing build for {target_pkg} v{base_version}")
                            repo_plugins.append(prev_p)
                            should_build = False

            if not should_build:
                continue

            # Execute Gradle build
            if not run_command(gradle_args, cwd=str(root_path)):
                print(f"  [ERROR] Failed to compile {plugin_dir.name} ({variant}), skipping.")
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

            # Update manifest metadata for variant
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
            jars = list(jar_dir.glob("*.jar"))
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
            shutil.copy2(source_jar, target_jar_path)

            # Inject updated manifest inside the JAR
            inject_manifest_into_jar(target_jar_path, manifest)

            # Sign the JAR in the dist folder
            sign_jar(target_jar_path, PRIVATE_KEY_B64)

            # Compute detached signature and hash
            jar_hash, jar_sig = get_detached_signature(target_jar_path, PRIVATE_KEY_B64)

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
                flow_hash, flow_sig = get_detached_signature(target_flow_path, PRIVATE_KEY_B64)

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


def push_to_git(output_dir):
    dist_path = Path(output_dir).resolve()
    if not (dist_path / ".git").exists():
        print(f"Error: '{output_dir}' is not a git repository. Cannot push.")
        return
        
    print(f"\nPushing changes in '{output_dir}' to remote...")
    
    if not run_command(["git", "add", "."], cwd=str(dist_path)):
        print("Failed to add files.")
        return
        
    status = subprocess.run(["git", "status", "--porcelain"], cwd=str(dist_path), capture_output=True, text=True)
    if not status.stdout.strip():
        print("No changes to commit in dist folder.")
        return
        
    if not run_command(["git", "commit", "-m", "Auto-update plugins via generate_repo.py"], cwd=str(dist_path)):
        print("Failed to commit changes.")
        return
        
    if not run_command(["git", "push"], cwd=str(dist_path)):
        print("Failed to push changes.")
        return
        
    print("Successfully pushed changes to remote repository.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate a static plugin repository.")
    parser.add_argument("plugin", nargs="?", help="Name of the plugin directory or ID to build (optional)")
    parser.add_argument("--name", help="Repository name")
    parser.add_argument("--url", help="Base URL of the repository")
    parser.add_argument("--out", help="Output directory")
    parser.add_argument("--clean", action="store_true", help="Force clean build (recompile target or all plugins)")
    parser.add_argument("--force", action="store_true", help="Force regenerate (recompile target or all plugins)")
    parser.add_argument("--variant", choices=["all", "gpu", "cpu"], default="all", help="Plugin variant to build for AI plugins: all, gpu, or cpu (default: all)")
    parser.add_argument("--push", action="store_true", help="Automatically commit and push the output directory to git")

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
        target_plugin=args.plugin,
        variant_filter=args.variant
    )

    if args.push:
        push_to_git(config["out"])
