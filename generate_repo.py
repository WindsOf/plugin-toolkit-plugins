import json
import shutil
import subprocess
import argparse
from pathlib import Path


def run_command(command, cwd=None):
    print(f"Running: {' '.join(command)}")
    result = subprocess.run(
        command, cwd=cwd, shell=True, capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"Error: \n{result.stderr}")
        return False
    return True


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


def generate_repo(name, url, output_dir):
    root_path = Path(".").resolve()
    dist_path = root_path / output_dir
    plugins_dist_path = dist_path / "plugins"

    # Ensure dist folder exists (clean rebuild)
    if dist_path.exists():
        shutil.rmtree(dist_path)
    dist_path.mkdir(parents=True)
    plugins_dist_path.mkdir()

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

    # Build plugins (always run Gradle from the root so settings.gradle.kts is found)
    for plugin_dir in plugin_dirs:
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
        pkg_dist_path.mkdir(exist_ok=True)

        # Copy JAR
        shutil.copy2(source_jar, pkg_dist_path / jar_name)

        # Copy assets
        if "changelog" in assets:
            shutil.copy2(assets["changelog"], pkg_dist_path / "changelog.md")
        if "icon" in assets:
            shutil.copy2(assets["icon"], pkg_dist_path / f"icon{assets['icon'].suffix}")

        # Copy manifest
        shutil.copy2(manifest_path, pkg_dist_path / "manifest.json")

        repo_plugins.append(
            {
                "name": p_name or plugin_dir.name,
                "pkg": pkg,
                "version": version,
                "fileName": jar_name,
                "description": description,
                "minAppVersion": "1.0.0",
            }
        )

        print(f"  -> Added {p_name} v{version} ({pkg})")

    # Generate index.json
    index = {
        "name": name,
        "url": f"{url.rstrip('/')}/index.json",
        "schemaVersion": 1,
        "pluginsFolder": "plugins",
        "plugins": repo_plugins,
    }

    with open(dist_path / "index.json", "w") as f:
        json.dump(index, f, indent=2)

    print(f"\nRepository generated successfully in '{output_dir}/'")
    print(f"Total plugins: {len(repo_plugins)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate a static plugin repository.")
    parser.add_argument("--name", help="Repository name")
    parser.add_argument("--url", help="Base URL of the repository")
    parser.add_argument("--out", help="Output directory")

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

    generate_repo(config["name"], config["url"], config["out"])
