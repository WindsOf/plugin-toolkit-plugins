"""
Root wrapper for scripts/generate_repo.py.
Allows running 'python generate_repo.py' directly from repository root
and maintains full backward compatibility for existing tests and tools.
"""

import sys
from pathlib import Path

# Ensure project root is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

import scripts.generate_repo as _impl

# Register as module alias so mock.patch('generate_repo.*') patches scripts.generate_repo
sys.modules["generate_repo"] = _impl

from scripts.generate_repo import (
    EXCLUDED_DIRS,
    PRIVATE_KEY_B64,
    PUBLIC_KEY_B64,
    connect_ftp,
    deploy_repository,
    ensure_remote_dir,
    extract_plugin_info_from_source,
    find_assets,
    find_jdk_tool,
    find_optional_settings,
    generate_repo,
    get_detached_signature,
    get_remote_file_size,
    main,
    push_to_git,
    repack_jar_if_needed,
    resolve_and_ensure_remote_base_dir,
    run_command,
    sign_jar,
    upload_file_with_progress,
    upload_to_ftp,
)

if __name__ == "__main__":
    main()
