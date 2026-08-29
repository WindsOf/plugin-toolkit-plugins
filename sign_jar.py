"""
Root wrapper for scripts/sign_jar.py.
Allows running 'python sign_jar.py' directly from repository root.
"""

import sys
from pathlib import Path

# Ensure project root is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.sign_jar import (
    DEFAULT_PRIVATE_KEY_B64,
    DEFAULT_PUBLIC_KEY_B64,
    find_jdk_tool,
    get_detached_signature,
    has_local_header_mismatch,
    main,
    repack_jar_if_needed,
    run_command,
    sign_jar,
    verify_detached_signature,
    verify_jar_internal,
)

if __name__ == "__main__":
    sys.exit(main())
