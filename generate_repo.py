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
from pathlib import Path
from dotenv import load_dotenv

# Load environment variables
load_dotenv()

PRIVATE_KEY_B64 = os.getenv("PLUGIN_PRIVATE_SIGNING_KEY")
PUBLIC_KEY_B64 = os.getenv("PLUGIN_PUBLIC_SIGNING_KEY")


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



def run_command(command, cwd=None):
    env = os.environ.copy()
    # Fix for broken OPENSSL_CONF on some systems (like the one we're running on)
    if "OPENSSL_CONF" in env and not os.path.exists(env["OPENSSL_CONF"]):
        # print(f"Clearing invalid OPENSSL_CONF: {env['OPENSSL_CONF']}")
        del env["OPENSSL_CONF"]

    # Try to use jarsigner if it's not in path but we know common JDK locations
    if command[0] == "jarsigner":
        # Check if jarsigner is in PATH
        if shutil.which("jarsigner") is None:
            # Try common JDK locations
            common_paths = [
                r"C:\Program Files\Java\jdk-26\bin\jarsigner.exe",
                r"C:\Program Files\Java\jdk-24\bin\jarsigner.exe",
                r"C:\Program Files\Android\Android Studio\jbr\bin\jarsigner.exe"
            ]
            for p in common_paths:
                if Path(p).exists():
                    command[0] = p
                    break

    print(f"Running: {' '.join(command)}")
    result = subprocess.run(
        command, cwd=cwd, shell=True, capture_output=True, text=True, env=env
    )
    if result.returncode != 0:
        print(f"Error: {result.stdout}\n{result.stderr}")
        return False
    return True


def sign_jar(jar_path, private_key_b64):
    if not private_key_b64:
        print("Warning: PLUGIN_PRIVATE_SIGNING_KEY not set. JAR will not be signed.")
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
        cmd = ["jarsigner", "-keystore", str(p12_file), "-storetype", "PKCS12", "-storepass", "password", str(jar_path), "plugin-key"]
        if not run_command(cmd):
            return False
            
        print(f"Successfully signed {jar_path.name}")
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


def generate_repo(name, url, output_dir, clean=False, target_plugin=None):
    root_path = Path(".").resolve()
    dist_path = root_path / output_dir
    plugins_dist_path = dist_path / "plugins"
    flows_dist_path = dist_path / "flows"

    # Load previous index.json if it exists (always load if target_plugin is provided to preserve others)
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

    # Ensure dist folder and plugins folder exist
    dist_path.mkdir(parents=True, exist_ok=True)
    plugins_dist_path.mkdir(exist_ok=True)

    repo_plugins = []

    # Discovery: only subdirectories that have build.gradle.kts (not the root itself)
    plugin_dirs = [
        d
        for d in root_path.iterdir()
        if d.is_dir()
        and d != root_path
        and (d / "build.gradle.kts").exists()
        and not d.name.startswith(".")
        and d.name not in ("build", "dist", "gradle")
    ]

    for plugin_dir in plugin_dirs:
        info = extract_plugin_info_from_source(plugin_dir)
        should_build = True
        pkg = None
        version = None
        
        if info:
            pkg = info["id"]
            version = info["version"]
            p_name = info.get("name", plugin_dir.name)
        else:
            p_name = plugin_dir.name
            
        is_target = False
        if target_plugin:
            search_key = target_plugin.lower()
            if plugin_dir.name.lower() == search_key or (pkg and pkg.lower() == search_key) or (p_name.lower() == search_key):
                is_target = True

        if target_plugin and not is_target:
            should_build = False
        elif not clean:
            if pkg in previous_plugins:
                prev_p = previous_plugins[pkg]
                if prev_p.get("version") == version:
                    jar_name = prev_p.get("fileName")
                    if jar_name:
                        target_jar_path = plugins_dist_path / pkg / jar_name
                        manifest_path = plugins_dist_path / pkg / "manifest.json"
                        # Verify that the target JAR and manifest actually exist
                        if target_jar_path.exists() and manifest_path.exists():
                            if not target_plugin or is_target:
                                print(f"No version bump for {plugin_dir.name} ({pkg} v{version}). Reusing existing build.")
                            should_build = False

        if not should_build:
            # Reuse the entry from previous plugins
            if pkg and pkg in previous_plugins:
                prev_p = previous_plugins[pkg]
                plugin_entry = {
                    "name": prev_p.get("name", p_name),
                    "pkg": pkg,
                    "version": version,
                    "fileName": prev_p.get("fileName"),
                    "description": prev_p.get("description", ""),
                    "targetAppVersion": prev_p.get("targetAppVersion", "1.0.0")
                }
                if "hash" in prev_p:
                    plugin_entry["hash"] = prev_p["hash"]
                if "signature" in prev_p:
                    plugin_entry["signature"] = prev_p["signature"]
                repo_plugins.append(plugin_entry)
                if not target_plugin or is_target:
                    print(f"  -> Reused {plugin_entry['name']} v{version} ({pkg})")
            continue

        print(f"Building {plugin_dir.name}...")
        if not run_command(
            ["gradlew.bat", f":{plugin_dir.name}:jar"], cwd=str(root_path)
        ):
            print(f"Failed to build {plugin_dir.name}, skipping.")
            continue

        assets = find_assets(plugin_dir)

        # Manifest is required for metadata — prefer the generated one in build/
        manifest_path = assets.get("manifest")
        build_manifest = (
            plugin_dir / "build" / "resources" / "main" / "META-INF" / "manifest.json"
        )
        if build_manifest.exists():
            manifest_path = build_manifest  # generated manifest takes precedence

        if not manifest_path:
            print(f"No manifest.json found for {plugin_dir.name}, skipping.")
            continue

        with open(manifest_path, "r") as f:
            manifest = json.load(f)

        plugin_meta = manifest.get("plugin", {})
        pkg = plugin_meta.get("id")
        p_name = plugin_meta.get("name")
        version = plugin_meta.get("version")
        description = plugin_meta.get("description", "")
        target_app_version = plugin_meta.get("targetAppVersion", "1.0.0")

        if not pkg or not version:
            print(
                f"Invalid manifest for {plugin_dir.name} (missing id or version), skipping."
            )
            continue

        # Find JAR
        jar_dir = plugin_dir / "build" / "libs"
        jars = list(jar_dir.glob("*.jar"))
        if not jars:
            print(f"No JAR found for {plugin_dir.name} in {jar_dir}, skipping.")
            continue

        source_jar = jars[0]
        jar_name = source_jar.name

        # Create plugin folder in dist
        pkg_dist_path = plugins_dist_path / pkg
        if pkg_dist_path.exists():
            shutil.rmtree(pkg_dist_path)
        pkg_dist_path.mkdir(parents=True)

        # Copy JAR
        target_jar_path = pkg_dist_path / jar_name
        shutil.copy2(source_jar, target_jar_path)

        # Sign the JAR in the dist folder
        sign_jar(target_jar_path, PRIVATE_KEY_B64)

        # Get detached signature and hash
        jar_hash, jar_sig = get_detached_signature(target_jar_path, PRIVATE_KEY_B64)

        # Copy assets
        if "changelog" in assets:
            shutil.copy2(assets["changelog"], pkg_dist_path / "changelog.md")
        if "icon" in assets:
            shutil.copy2(assets["icon"], pkg_dist_path / f"icon{assets['icon'].suffix}")

        # Copy manifest
        shutil.copy2(manifest_path, pkg_dist_path / "manifest.json")

        plugin_entry = {
            "name": p_name or plugin_dir.name,
            "pkg": pkg,
            "version": version,
            "fileName": jar_name,
            "description": description,
            "targetAppVersion": target_app_version,
        }

        if jar_hash:
            plugin_entry["hash"] = jar_hash
        if jar_sig:
            plugin_entry["signature"] = jar_sig

        repo_plugins.append(plugin_entry)

        print(f"  -> Added {p_name} v{version} ({pkg})")

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

                # Copy flow file to dist
                shutil.copy2(flow_file, target_flow_path)

                # Calculate hash and signature of flow file
                flow_hash, flow_sig = get_detached_signature(target_flow_path, PRIVATE_KEY_B64)

                # Extract metadata
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

    with open(dist_path / "index.json", "w") as f:
        json.dump(index, f, indent=2)

    print(f"\nRepository generated successfully in '{output_dir}/'")
    print(f"Total plugins: {len(repo_plugins)}")
    print(f"Total flows: {len(repo_flows)}")


def push_to_git(output_dir):
    dist_path = Path(output_dir).resolve()
    if not (dist_path / ".git").exists():
        print(f"Error: '{output_dir}' is not a git repository. Cannot push.")
        return
        
    print(f"\nPushing changes in '{output_dir}' to remote...")
    
    if not run_command(["git", "add", "."], cwd=str(dist_path)):
        print("Failed to add files.")
        return
        
    # Check if there are changes to commit
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
    parser.add_argument("--push", action="store_true", help="Automatically commit and push the output directory to git")

    args = parser.parse_args()

    config = {
        "name": "My Plugin Repository",
        "url": "http://localhost:8080",
        "out": "dist",
    }

    # Load from config file if exists
    config_path = Path("repo_config.json")
    if config_path.exists():
        with open(config_path, "r") as f:
            file_config = json.load(f)
            config.update(file_config)

    # Override with CLI args if provided
    if args.name:
        config["name"] = args.name
    if args.url:
        config["url"] = args.url
    if args.out:
        config["out"] = args.out

    clean_build = args.clean or args.force or False

    generate_repo(config["name"], config["url"], config["out"], clean=clean_build, target_plugin=args.plugin)

    if args.push:
        push_to_git(config["out"])
