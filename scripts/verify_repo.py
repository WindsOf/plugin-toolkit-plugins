"""
Plugin Toolkit Repository Verification Utility.

Verifies internal JAR signatures, SHA-256 digests, and detached RSA signatures
for all plugins and flows defined in dist/index.json.
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from dotenv import find_dotenv, load_dotenv

# Ensure project root is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.sign_jar import (
    find_jdk_tool,
    get_detached_signature,
    run_command,
    verify_detached_signature,
    verify_jar_internal,
)

# Load environment variables
load_dotenv(find_dotenv(usecwd=True))


def verify_repo(dist_dir: str = "dist") -> bool:
    dist_path = Path(dist_dir).resolve()
    index_path = dist_path / "index.json"

    if not index_path.exists():
        print(f"Error: {index_path} not found. Run generate_repo.py first.")
        return False

    with open(index_path, "r", encoding="utf-8") as f:
        index = json.load(f)

    public_key_b64 = index.get("signPublicKey") or os.getenv("PLUGIN_PUBLIC_SIGNING_KEY")
    if not public_key_b64:
        print("Warning: Repository has no signPublicKey in index.json or environment. Only internal JAR signatures can be checked.")

    plugins = index.get("plugins", [])
    print(f"Verifying {len(plugins)} plugins in repository: '{index.get('name')}'\n")

    all_ok = True

    for plugin in plugins:
        pkg = plugin.get("pkg")
        filename = plugin.get("fileName")
        expected_hash = plugin.get("hash")
        signature_b64 = plugin.get("signature")

        jar_path = dist_path / "plugins" / pkg / filename
        print(f"Checking {pkg} ({filename})...")

        if not jar_path.exists():
            print(f"  [FAIL] File not found: {jar_path}")
            all_ok = False
            continue

        # 1. Internal JAR Verification
        ok_internal, out_internal = verify_jar_internal(jar_path, verbose=False)
        if ok_internal:
            print("  [OK] Internal JAR signature verified.")
        else:
            print(f"  [FAIL] Internal JAR signature verification failed.")
            if out_internal:
                print(f"    Error: {out_internal.strip()}")
            all_ok = False

        # 2. Metadata Verification (Hash & Signature)
        if expected_hash and signature_b64 and public_key_b64:
            # Calculate actual hash
            sha256_hash = hashlib.sha256()
            with open(jar_path, "rb") as f:
                for byte_block in iter(lambda: f.read(65536), b""):
                    sha256_hash.update(byte_block)
            actual_hash = sha256_hash.hexdigest()

            if actual_hash == expected_hash:
                print("  [OK] SHA-256 hash matches index.json.")
            else:
                print(f"  [FAIL] Hash mismatch!")
                print(f"    Expected: {expected_hash}")
                print(f"    Got:      {actual_hash}")
                all_ok = False

            # Verify Detached Signature
            ok_sig, msg_sig = verify_detached_signature(
                jar_path,
                signature_b64,
                public_key_b64=public_key_b64,
                verbose=False
            )
            if ok_sig:
                print("  [OK] Detached metadata signature verified.")
            else:
                print(f"  [FAIL] Detached metadata signature verification failed: {msg_sig}")
                all_ok = False
        else:
            print("  [SKIP] Metadata verification skipped (missing hash, signature, or public key in index.json).")

        print("-" * 50)

    # Verify Flows
    flows = index.get("flows", [])
    if flows:
        print(f"\nVerifying {len(flows)} flows in repository:\n")
        for flow in flows:
            flow_name = flow.get("name")
            filename = flow.get("fileName")
            expected_hash = flow.get("hash")
            signature_b64 = flow.get("signature")

            flow_path = dist_path / "flows" / filename
            print(f"Checking flow {flow_name} ({filename})...")

            if not flow_path.exists():
                print(f"  [FAIL] File not found: {flow_path}")
                all_ok = False
                continue

            if expected_hash and signature_b64 and public_key_b64:
                sha256_hash = hashlib.sha256()
                with open(flow_path, "rb") as f:
                    for byte_block in iter(lambda: f.read(65536), b""):
                        sha256_hash.update(byte_block)
                actual_hash = sha256_hash.hexdigest()

                if actual_hash == expected_hash:
                    print("  [OK] SHA-256 hash matches index.json.")
                else:
                    print(f"  [FAIL] Hash mismatch!")
                    print(f"    Expected: {expected_hash}")
                    print(f"    Got:      {actual_hash}")
                    all_ok = False

                ok_sig, msg_sig = verify_detached_signature(
                    flow_path,
                    signature_b64,
                    public_key_b64=public_key_b64,
                    verbose=False
                )
                if ok_sig:
                    print("  [OK] Detached metadata signature verified.")
                else:
                    print(f"  [FAIL] Detached metadata signature verification failed: {msg_sig}")
                    all_ok = False
            else:
                print("  [SKIP] Metadata verification skipped (missing hash, signature, or public key in index.json).")

            print("-" * 50)

    if all_ok:
        print("\nSUCCESS: All plugins and flows are correctly signed and verified.")
    else:
        print("\nFAILURE: Some verification checks failed.")

    return all_ok


def main():
    parser = argparse.ArgumentParser(description="Verify repository JAR signatures, hashes, and detached metadata.")
    parser.add_argument("--dist", default="dist", help="Path to the dist directory (default: dist)")
    args = parser.parse_args()

    success = verify_repo(dist_dir=args.dist)
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
