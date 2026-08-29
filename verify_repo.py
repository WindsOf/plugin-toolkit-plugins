"""
Root wrapper for scripts/verify_repo.py.
Allows running 'python verify_repo.py' directly from repository root.
"""

import sys
from pathlib import Path

# Ensure project root is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.verify_repo import (
    main,
    run_command,
    verify_repo,
)

if __name__ == "__main__":
    sys.exit(main())
