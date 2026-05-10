import os
import json
import shutil
import subprocess
import argparse
from pathlib import Path

def run_command(command, cwd=None):
    print(f"Running: {' '.join(command)}")
    result = subprocess.run(command, cwd=cwd, shell=True, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"Error: {result.stderr}")
        return False
    return True

def find_assets(plugin_path):
    assets = {}
    # Common resource paths
    search_paths = [
        plugin_path / "src" / "main" / "resources",
        plugin_path / "src" / "main" / "kotlin" / "resources",
        plugin_path / "build" / "resources" / "main" / "META-INF"
    ]
    
    for path in search_paths:
        if not path.exists():
            continue
            
        # Find changelog
        if "changelog" not in assets:
            cl = path / "changelog.md"
            if cl.exists():
                assets["changelog"] = cl
                
        # Find icon
        if "icon" not in assets:
            for ext in [".png", ".webp", ".svg", ".jpg"]:
                icon = path / f"icon{ext}"
                if icon.exists():
                    assets["icon"] = icon
                    break
                    
        # Find manifest (source of truth)
        if "manifest" not in assets:
            manifest = path / "manifest.json"
            if manifest.exists():
                assets["manifest"] = manifest
                
    return assets

def generate_repo(name, url, output_dir):
    root_path = Path(".")
    dist_path = root_path / output_dir
    plugins_dist_path = dist_path / "plugins"
    
    # Ensure dist folder exists
    if dist_path.exists():
        shutil.rmtree(dist_path)
    dist_path.mkdir(parents=True)
    plugins_dist_path.mkdir()
    
    repo_plugins = []
    
    # Discovery
    plugin_dirs = [d for d in root_path.iterdir() if d.is_dir() and (d / "build.gradle.kts").exists()]
    
    # Build plugins
    for plugin_dir in plugin_dirs:
        print(f"Building {plugin_dir.name}...")
        if not run_command(["gradlew.bat", f":{plugin_dir.name}:jar"]):
            print(f"Failed to build {plugin_dir.name}, skipping.")
            continue
            
        assets = find_assets(plugin_dir)
        
        # Manifest is required for metadata
        manifest_path = assets.get("manifest")
        if not manifest_path:
            # Try to find it in build if not in resources
            build_manifest = plugin_dir / "build" / "resources" / "main" / "META-INF" / "manifest.json"
            if build_manifest.exists():
                manifest_path = build_manifest
            else:
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
            print(f"Invalid manifest for {plugin_dir.name} (missing id or version), skipping.")
            continue
            
        # Find JAR
        jar_dir = plugin_dir / "build" / "libs"
        jars = list(jar_dir.glob("*.jar"))
        if not jars:
            print(f"No JAR found for {plugin_dir.name} in {jar_dir}, skipping.")
            continue
        
        # Take the most recent or best named JAR
        # Usually it's {plugin_dir.name}-{version}.jar or just {plugin_dir.name}.jar
        source_jar = jars[0] # Simple heuristic
        jar_name = f"plugin-{version}.jar" # Standardize name in repo #TODO: coule be avoided
        
        # Create plugin folder in dist
        pkg_dist_path = plugins_dist_path / pkg
        pkg_dist_path.mkdir(exist_ok=True)
        
        # Copy JAR
        shutil.copy2(source_jar, pkg_dist_path / jar_name)
        
        # Copy Assets
        if "changelog" in assets:
            shutil.copy2(assets["changelog"], pkg_dist_path / "changelog.md")
        if "icon" in assets:
            shutil.copy2(assets["icon"], pkg_dist_path / f"icon{assets['icon'].suffix}")
        
        # Copy manifest to pkg folder as well (optional but recommended in docs)
        shutil.copy2(manifest_path, pkg_dist_path / "manifest.json")
        
        # Add to index
        repo_plugins.append({
            "name": p_name or plugin_dir.name,
            "pkg": pkg,
            "version": version,
            "fileName": jar_name,
            "description": description,
            "minAppVersion": "1.0.0" # Default or extract from manifest
        })
        
    # Generate index.json
    index = {
        "name": name,
        "url": f"{url.rstrip('/')}/index.json",
        "schemaVersion": 1,
        "pluginsFolder": "plugins",
        "plugins": repo_plugins
    }
    
    with open(dist_path / "index.json", "w") as f:
        json.dump(index, f, indent=2)
        
    print(f"\nRepository generated successfully in '{output_dir}/'")
    print(f"Total plugins: {len(repo_plugins)}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate a static plugin repository.")
    parser.add_argument("--name", default="My Plugin Repository", help="Repository name")
    parser.add_argument("--url", default="http://localhost:8080", help="Base URL of the repository")
    parser.add_argument("--out", default="dist", help="Output directory")
    
    args = parser.parse_args()
    generate_repo(args.name, args.url, args.out)
