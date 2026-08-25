import json
import subprocess
import os
import hashlib
import base64
import tempfile
import shutil
from pathlib import Path

def run_command(command, cwd=None):
    # Helper to find jarsigner if it's not in path but we know common JDK locations
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
                    
    env = os.environ.copy()
    # Fix for broken OPENSSL_CONF on some systems
    if "OPENSSL_CONF" in env and not os.path.exists(env["OPENSSL_CONF"]):
        del env["OPENSSL_CONF"]

    result = subprocess.run(
        command, cwd=cwd, shell=False, capture_output=True, text=True, env=env
    )
    return result.returncode == 0, result.stdout, result.stderr

def verify_repo(dist_dir="dist"):
    dist_path = Path(dist_dir).resolve()
    index_path = dist_path / "index.json"
    
    if not index_path.exists():
        print(f"Error: {index_path} not found. Run generate_repo.py first.")
        return

    with open(index_path, "r") as f:
        index = json.load(f)

    public_key_b64 = index.get("signPublicKey")
    if not public_key_b64:
        print("Warning: Repository has no signPublicKey in index.json. Only internal JAR signatures can be checked.")

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
        success, out, err = run_command(["jarsigner", "-verify", str(jar_path)])
        if success and "jar verified" in out:
            print("  [OK] Internal JAR signature verified.")
        else:
            print(f"  [FAIL] Internal JAR signature verification failed.")
            if err: print(f"    Error: {err.strip()}")
            all_ok = False

        # 2. Metadata Verification (Hash & Signature)
        if expected_hash and signature_b64 and public_key_b64:
            # Calculate actual hash
            sha256_hash = hashlib.sha256()
            with open(jar_path, "rb") as f:
                for byte_block in iter(lambda: f.read(4096), b""):
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
            with tempfile.TemporaryDirectory() as tmp_dir:
                tmp_path = Path(tmp_dir)
                pub_key_file = tmp_path / "public.pem"
                hash_file = tmp_path / "hash.txt"
                sig_file = tmp_path / "sig.bin"

                pub_key_pem = f"-----BEGIN PUBLIC KEY-----\n{public_key_b64}\n-----END PUBLIC KEY-----"
                pub_key_file.write_text(pub_key_pem)
                hash_file.write_text(actual_hash)
                
                try:
                    with open(sig_file, "wb") as f:
                        f.write(base64.b64decode(signature_b64))

                    # Use openssl to verify
                    # We sign the text content of the hash string
                    cmd = ["openssl", "dgst", "-sha256", "-verify", str(pub_key_file), "-signature", str(sig_file), str(hash_file)]
                    v_success, v_out, v_err = run_command(cmd)
                    if v_success:
                        print("  [OK] Detached metadata signature verified.")
                    else:
                        print(f"  [FAIL] Detached metadata signature verification failed.")
                        if v_err: print(f"    Error: {v_err.strip()}")
                        all_ok = False
                except Exception as e:
                    print(f"  [ERROR] Exception during signature verification: {e}")
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
                # Calculate actual hash
                sha256_hash = hashlib.sha256()
                with open(flow_path, "rb") as f:
                    for byte_block in iter(lambda: f.read(4096), b""):
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
                with tempfile.TemporaryDirectory() as tmp_dir:
                    tmp_path = Path(tmp_dir)
                    pub_key_file = tmp_path / "public.pem"
                    hash_file = tmp_path / "hash.txt"
                    sig_file = tmp_path / "sig.bin"

                    pub_key_pem = f"-----BEGIN PUBLIC KEY-----\n{public_key_b64}\n-----END PUBLIC KEY-----"
                    pub_key_file.write_text(pub_key_pem)
                    hash_file.write_text(actual_hash)

                    try:
                        with open(sig_file, "wb") as f:
                            f.write(base64.b64decode(signature_b64))

                        # Use openssl to verify
                        cmd = ["openssl", "dgst", "-sha256", "-verify", str(pub_key_file), "-signature", str(sig_file), str(hash_file)]
                        v_success, v_out, v_err = run_command(cmd)
                        if v_success:
                            print("  [OK] Detached metadata signature verified.")
                        else:
                            print(f"  [FAIL] Detached metadata signature verification failed.")
                            if v_err: print(f"    Error: {v_err.strip()}")
                            all_ok = False
                    except Exception as e:
                        print(f"  [ERROR] Exception during signature verification: {e}")
                        all_ok = False
            else:
                print("  [SKIP] Metadata verification skipped (missing hash, signature, or public key in index.json).")

            print("-" * 50)

    if all_ok:
        print("\nSUCCESS: All plugins and flows are correctly signed and verified.")
    else:
        print("\nFAILURE: Some verification checks failed.")

if __name__ == "__main__":
    verify_repo()
