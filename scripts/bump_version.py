"""
Version Bumping Utility for Plugins.

Automatically increments patch version in Kotlin @PluginInfo source annotations
and prepends a template entry to the corresponding changelog.md.
"""

import argparse
import os
import re
import sys
from datetime import date
from pathlib import Path

# Determine project root
_PROJECT_ROOT = Path(__file__).resolve().parent.parent if Path(__file__).resolve().parent.name == "scripts" else Path(".").resolve()
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))


def extract_plugin_info(plugin_dir: Path) -> dict | None:
    """
    Scans Kotlin source files in plugin_dir/src to find the @PluginInfo annotation,
    its current version, and the file containing it.
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
                    "description": desc_match.group(1) if desc_match else "",
                    "file_path": path,
                    "dir_path": plugin_dir,
                    "dir_name": plugin_dir.name
                }
    return None


def find_all_plugins(root_path: Path | None = None) -> list[dict]:
    search_root = root_path or _PROJECT_ROOT
    plugin_dirs = [
        d
        for d in search_root.iterdir()
        if d.is_dir()
        and d != search_root
        and (d / "build.gradle.kts").exists()
        and not d.name.startswith(".")
        and d.name not in ("build", "dist", "gradle")
    ]

    plugins = []
    for d in plugin_dirs:
        info = extract_plugin_info(d)
        if info:
            plugins.append(info)
    return plugins


def bump_version_string(version_str: str) -> str:
    """
    Increments the patch version by 1.
    E.g., 2.0.5 -> 2.0.6
    """
    parts = version_str.split('.')
    if len(parts) >= 1:
        try:
            last_part = int(parts[-1])
            parts[-1] = str(last_part + 1)
            return '.'.join(parts)
        except ValueError:
            pass
    return version_str + ".1"


def find_changelog_path(plugin_dir: Path) -> Path:
    """
    Finds existing changelog.md or returns path where it should be created.
    """
    paths = [
        plugin_dir / "src" / "main" / "resources" / "changelog.md",
        plugin_dir / "src" / "main" / "kotlin" / "resources" / "changelog.md"
    ]
    for p in paths:
        if p.exists():
            return p
            
    # If neither exists, prefer main/resources if it exists, else main/kotlin/resources
    res_dir = plugin_dir / "src" / "main" / "resources"
    if res_dir.exists():
        return res_dir / "changelog.md"
    return plugin_dir / "src" / "main" / "kotlin" / "resources" / "changelog.md"


def update_plugin_files(plugin: dict, new_version: str, dry_run: bool = False) -> bool:
    file_path = plugin["file_path"]
    current_version = plugin["version"]
    
    print(f"Updating {plugin['name']} ({plugin['id']}) from v{current_version} to v{new_version}:")
    
    # 1. Update Kotlin source file version
    if dry_run:
        print(f"  [Dry-Run] Would update `@PluginInfo` version to {new_version} in {file_path.relative_to(_PROJECT_ROOT)}")
    else:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        plugin_info_pattern = re.compile(r'(@PluginInfo\s*\([^)]*\))', re.DOTALL)
        
        def replacer(match):
            block = match.group(1)
            version_sub_pattern = re.compile(r'(\bversion\s*=\s*)(["\'])([^"\']*)(["\'])')
            if version_sub_pattern.search(block):
                return version_sub_pattern.sub(rf'\g<1>\g<2>{new_version}\g<4>', block)
            return block

        new_content, count = plugin_info_pattern.subn(replacer, content)
        if count > 0:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(new_content)
            print(f"  [OK] Updated version in source file: {file_path.name}")
        else:
            print(f"  [ERROR] Failed to locate and update version in source file: {file_path.name}")
            return False

    # 2. Update changelog.md
    changelog_path = find_changelog_path(plugin["dir_path"])
    today = date.today().isoformat()
    
    # Changelog template
    changelog_template = (
        f"Version: {new_version}\n"
        f"Date: {today}\n"
        f"Added:\n"
        f"  - \n"
        f"{'-' * 97}\n"
    )

    if dry_run:
        print(f"  [Dry-Run] Would prepend template to changelog at {changelog_path.relative_to(_PROJECT_ROOT)}")
        print("  [Dry-Run] Template Content:")
        for line in changelog_template.splitlines():
            print(f"    {line}")
    else:
        changelog_path.parent.mkdir(parents=True, exist_ok=True)
        old_content = ""
        if changelog_path.exists():
            with open(changelog_path, 'r', encoding='utf-8') as f:
                old_content = f.read()
        
        new_changelog_content = changelog_template + old_content.lstrip()
        
        with open(changelog_path, 'w', encoding='utf-8') as f:
            f.write(new_changelog_content)
        print(f"  [OK] Prepended entry to changelog: {changelog_path.name}")

    return True


def main():
    parser = argparse.ArgumentParser(description="Bump plugin versions and update source/changelog files.")
    parser.add_argument("plugin", nargs="?", help="Name of the plugin directory or ID to bump (e.g. betterimg, slicer, com.wip.ocr_ia)")
    parser.add_argument("--all", action="store_true", help="Bump version of all discovered plugins")
    parser.add_argument("--list", action="store_true", help="List all discovered plugins and their current versions")
    parser.add_argument("--set-version", help="Explicit version to set (default increments patch version)")
    parser.add_argument("--dry-run", action="store_true", help="Print changes without modifying files")

    args = parser.parse_args()
    plugins = find_all_plugins()

    if not plugins:
        print("No plugins found in the workspace.")
        return

    if args.list:
        print("\nDiscovered Plugins:")
        print(f"{'Plugin Name':<20} | {'Plugin ID':<30} | {'Current Version':<15}")
        print("-" * 71)
        for p in plugins:
            print(f"{p['name']:<20} | {p['id']:<30} | {p['version']:<15}")
        print()
        return

    target_plugins = []
    if args.all:
        target_plugins = plugins
    elif args.plugin:
        search_key = args.plugin.lower()
        matched = [
            p for p in plugins 
            if p["dir_name"].lower() == search_key or p["id"].lower() == search_key or p["name"].lower() == search_key
        ]
        if matched:
            target_plugins = matched
        else:
            print(f"Error: No plugin found matching name or ID '{args.plugin}'")
            print("Available plugins:")
            for p in plugins:
                print(f"  - {p['dir_name']} ({p['id']})")
            return
    else:
        parser.print_help()
        print("\nAvailable plugins:")
        for p in plugins:
            print(f"  - {p['dir_name']} (v{p['version']})")
        return

    # Bump versions
    success_count = 0
    for p in target_plugins:
        new_version = args.set_version if args.set_version else bump_version_string(p["version"])
        if update_plugin_files(p, new_version, dry_run=args.dry_run):
            success_count += 1

    if args.dry_run:
        print(f"\nDry-run complete. Would have updated {success_count} plugin(s).")
    else:
        print(f"\nSuccessfully updated {success_count} plugin(s).")


if __name__ == "__main__":
    main()
