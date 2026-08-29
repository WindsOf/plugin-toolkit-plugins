"""
Root wrapper for scripts/bump_version.py.
Allows running 'python bump_version.py' directly from repository root.
"""

import sys
from pathlib import Path

# Ensure project root is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.bump_version import (
    bump_version_string,
    extract_plugin_info,
    find_all_plugins,
    find_changelog_path,
    main,
    update_plugin_files,
)

if __name__ == "__main__":
    main()
