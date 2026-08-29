"""
JAR Signing, Header Normalization, and Signature Verification Utility.

Provides reusable functionality to:
- Normalize fat-JAR local file headers left in inconsistent states by Gradle/zipTree.
- Sign JAR archives internally using JDK jarsigner and RSA-SHA256 PKCS12 keystores.
- Generate and verify detached RSA-SHA256 signatures and SHA-256 digests.
- Support both programmatic module imports and standalone CLI execution.
"""

import argparse
import base64
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from dotenv import find_dotenv, load_dotenv

# Auto-load environment variables from current or parent directories
load_dotenv(find_dotenv(usecwd=True))

DEFAULT_PRIVATE_KEY_B64 = os.getenv("PLUGIN_PRIVATE_SIGNING_KEY")
DEFAULT_PUBLIC_KEY_B64 = os.getenv("PLUGIN_PUBLIC_SIGNING_KEY")


def find_jdk_tool(tool_name: str) -> str:
    """
    Locates a JDK executable (e.g. jarsigner, keytool) using JAVA_HOME,
    common Windows / Android Studio JDK paths, or system PATH.
    """
    # 1. Check JAVA_HOME if set
    java_home = os.getenv("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (f"{tool_name}.exe" if os.name == "nt" else tool_name)
        if candidate.exists():
            return str(candidate)

    # 2. Check standard Windows JDK installation locations
    common_paths = [
        rf"C:\Program Files\Java\jdk-26\bin\{tool_name}.exe",
        rf"C:\Program Files\Java\jdk-24\bin\{tool_name}.exe",
        rf"C:\Program Files\Java\jdk-21\bin\{tool_name}.exe",
        rf"C:\Program Files\Java\jdk-17\bin\{tool_name}.exe",
        os.path.expanduser(rf"~\AppData\Local\Programs\Android Studio\jbr\bin\{tool_name}.exe"),
        rf"C:\Program Files\Android\Android Studio\jbr\bin\{tool_name}.exe",
        rf"C:\Program Files\Android\Android Studio\jre\bin\{tool_name}.exe",
    ]
    for p in common_paths:
        if Path(p).exists():
            return p

    # 3. Fallback to system PATH
    found = shutil.which(tool_name)
    if found:
        return found

    return tool_name


def run_command(command: list[str], cwd: str | None = None, verbose: bool = True) -> tuple[bool, str, str]:
    """
    Executes a shell command with proper environment sanitization.
    """
    env = os.environ.copy()
    # Unset invalid OPENSSL_CONF if pointing to nonexistent path
    if "OPENSSL_CONF" in env and not os.path.exists(env["OPENSSL_CONF"]):
        del env["OPENSSL_CONF"]

    # Resolve jarsigner if it's the target executable
    if command and command[0] in ("jarsigner", "keytool"):
        command[0] = find_jdk_tool(command[0])

    cmd_str = subprocess.list2cmdline(command)
    if verbose:
        print(f"  [EXEC] {cmd_str}")

    result = subprocess.run(
        cmd_str, cwd=cwd, shell=True, capture_output=True, text=True, env=env
    )
    if result.returncode != 0 and verbose:
        print(f"  [ERROR] Command failed with code {result.returncode}:\n{result.stdout}\n{result.stderr}")

    return result.returncode == 0, result.stdout, result.stderr


def has_local_header_mismatch(jar_path: Path | str) -> bool:
    """
    Returns True if any entry in the JAR has a local file header whose
    compression type or sizes differ from its central directory record.

    Gradle's zipTree fat-JAR bundling can leave local headers with stale values
    from source archives. Java's ZipInputStream/jarsigner reads local headers
    and fails with ZipException if they mismatch.
    """
    jar_file = Path(jar_path)
    if not jar_file.exists():
        return False

    with open(jar_file, "rb") as f:
        raw = f.read()

    with zipfile.ZipFile(jar_file, "r") as z:
        for info in z.infolist():
            offset = info.header_offset
            if raw[offset:offset + 4] != b"PK\x03\x04":
                return True
            # Local file header layout (26 bytes starting at offset + 4):
            # <HHHHHIIIHH: version_needed(2), bit_flag(2), compress_type(2),
            #              mod_time(2), mod_date(2), crc32(4), compress_size(4),
            #              uncompress_size(4), fname_len(2), extra_len(2)
            lh = struct.unpack_from("<HHHHHIIIHH", raw, offset + 4)
            local_bit_flag = lh[1]
            local_compress = lh[2]
            local_compress_size = lh[6]
            local_file_size = lh[7]

            cd_compress_size = info.compress_size
            cd_file_size = info.file_size

            # Check compression method mismatch
            if local_compress != info.compress_type:
                return True

            # If bit 3 is not set, sizes in local header should match central directory
            has_data_descriptor = bool(local_bit_flag & 0x08)
            if not has_data_descriptor:
                if cd_compress_size < 0xFFFFFFFF and local_compress_size != cd_compress_size:
                    return True
                if cd_file_size < 0xFFFFFFFF and local_file_size != cd_file_size:
                    return True

    return False


def repack_jar_if_needed(jar_path: Path | str, output_path: Path | str | None = None, verbose: bool = True) -> bool:
    """
    Detects Gradle fat-JAR local file header discrepancies and rewrites the archive
    with fresh, normalized headers so Java tools (jarsigner/ZipInputStream) can
    process every entry without error.

    Returns True if repacked or already clean, False if repacking failed.
    """
    src_path = Path(jar_path)
    dst_path = Path(output_path) if output_path else src_path

    if not src_path.exists():
        if verbose:
            print(f"  [ERROR] File not found: {src_path}")
        return False

    try:
        if not has_local_header_mismatch(src_path):
            if output_path and src_path != dst_path:
                shutil.copy2(src_path, dst_path)
            return True
    except Exception as probe_err:
        if verbose:
            print(f"  [WARN] Could not probe local headers ({probe_err}); attempting repack.")

    if verbose:
        print(f"  [REPACK] Local-header mismatches detected in {src_path.name}. Normalizing...")

    tmp_path = dst_path.with_suffix(".repacking.tmp.jar")
    try:
        with zipfile.ZipFile(src_path, "r") as src_zip:
            with zipfile.ZipFile(tmp_path, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as dst_zip:
                for info in src_zip.infolist():
                    data = src_zip.read(info)
                    new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                    new_info.compress_type = info.compress_type
                    new_info.comment = info.comment
                    new_info.create_system = info.create_system
                    new_info.create_version = info.create_version
                    new_info.external_attr = info.external_attr
                    dst_zip.writestr(new_info, data)

        if dst_path.exists() and dst_path != src_path:
            dst_path.unlink()
        tmp_path.replace(dst_path)
        if verbose:
            print(f"  [REPACK] Successfully normalized {dst_path.name}.")
        return True
    except Exception as repack_err:
        if verbose:
            print(f"  [ERROR] Repack failed: {repack_err}")
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except Exception:
                pass
        return False


def sign_jar(
    jar_path: Path | str,
    private_key_b64: str | None = None,
    output_path: Path | str | None = None,
    alias: str = "plugin-key",
    password: str = "password",
    verbose: bool = True
) -> bool:
    """
    Signs a JAR archive using RSA-SHA256 and jarsigner.
    If private_key_b64 is not provided, defaults to PLUGIN_PRIVATE_SIGNING_KEY from .env.
    """
    key_b64 = private_key_b64 if private_key_b64 is not None else (os.getenv("PLUGIN_PRIVATE_SIGNING_KEY") or DEFAULT_PRIVATE_KEY_B64)
    if not key_b64:
        if verbose:
            print("  [ERROR] No private signing key provided. Set PLUGIN_PRIVATE_SIGNING_KEY in .env or pass --key.")
        return False

    src_path = Path(jar_path)
    dst_path = Path(output_path) if output_path else src_path

    if not src_path.exists():
        if verbose:
            print(f"  [ERROR] JAR file not found: {src_path}")
        return False

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        priv_key_file = tmp_path / "private.pem"
        cert_file = tmp_path / "cert.pem"
        p12_file = tmp_path / "keystore.p12"
        working_jar = tmp_path / "working.jar"
        signed_jar_temp = tmp_path / "signed_output.jar"

        # Copy source to working temp jar
        shutil.copy2(src_path, working_jar)

        # 1. Prepare Private Key PEM
        priv_key_pem = f"-----BEGIN PRIVATE KEY-----\n{key_b64.strip()}\n-----END PRIVATE KEY-----\n"
        priv_key_file.write_text(priv_key_pem, encoding="utf-8")

        # 2. Create minimal openssl.cnf to avoid environment issues
        config_file = tmp_path / "openssl.cnf"
        config_file.write_text("[req]\ndistinguished_name = req_distinguished_name\n[req_distinguished_name]\n", encoding="utf-8")
        os.environ["OPENSSL_CONF"] = str(config_file)

        # 3. Create self-signed certificate for jarsigner
        subj = "/CN=Plugin Toolkit"
        req_cmd = [
            "openssl", "req", "-new", "-x509",
            "-key", str(priv_key_file),
            "-out", str(cert_file),
            "-days", "365",
            "-subj", subj,
            "-config", str(config_file)
        ]
        ok, _, _ = run_command(req_cmd, verbose=verbose)
        if not ok:
            return False

        # 4. Create PKCS12 keystore
        p12_cmd = [
            "openssl", "pkcs12", "-export",
            "-in", str(cert_file),
            "-inkey", str(priv_key_file),
            "-out", str(p12_file),
            "-name", alias,
            "-passout", f"pass:{password}"
        ]
        ok, _, _ = run_command(p12_cmd, verbose=verbose)
        if not ok:
            return False

        # 5. Repack/normalize local headers if needed before jarsigner
        if not repack_jar_if_needed(working_jar, verbose=verbose):
            if verbose:
                print(f"  [ERROR] Header normalization failed for {src_path.name}.")
            return False

        # 6. Sign using jarsigner
        jarsigner_tool = find_jdk_tool("jarsigner")
        sign_cmd = [
            jarsigner_tool,
            "-keystore", str(p12_file),
            "-storetype", "PKCS12",
            "-storepass", password,
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-signedjar", str(signed_jar_temp),
            str(working_jar),
            alias
        ]
        ok, _, _ = run_command(sign_cmd, verbose=verbose)
        if not ok or not signed_jar_temp.exists():
            if verbose:
                print(f"  [ERROR] jarsigner failed to sign {src_path.name}")
            return False

        # Move output to destination
        dst_path.parent.mkdir(parents=True, exist_ok=True)
        if dst_path.exists():
            dst_path.unlink()
        shutil.move(str(signed_jar_temp), str(dst_path))

        if verbose:
            print(f"  [SIGN] Successfully signed {dst_path.name}")
        return True


def get_detached_signature(file_path: Path | str, private_key_b64: str | None = None, verbose: bool = True) -> tuple[str | None, str | None]:
    """
    Calculates SHA-256 digest of file_path and generates an RSA-SHA256 detached signature.
    Returns (sha256_hex_hash, base64_signature).
    """
    target = Path(file_path)
    if not target.exists():
        return None, None

    # Compute SHA-256 hash
    sha256_hash = hashlib.sha256()
    with open(target, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            sha256_hash.update(chunk)
    hash_str = sha256_hash.hexdigest()

    key_b64 = private_key_b64 if private_key_b64 is not None else (os.getenv("PLUGIN_PRIVATE_SIGNING_KEY") or DEFAULT_PRIVATE_KEY_B64)
    if not key_b64:
        return hash_str, None

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        priv_key_file = tmp_path / "private.pem"
        hash_file = tmp_path / "hash.txt"
        sig_file = tmp_path / "sig.bin"

        priv_key_pem = f"-----BEGIN PRIVATE KEY-----\n{key_b64.strip()}\n-----END PRIVATE KEY-----\n"
        priv_key_file.write_text(priv_key_pem, encoding="utf-8")
        hash_file.write_text(hash_str, encoding="utf-8")

        cmd = [
            "openssl", "dgst", "-sha256",
            "-sign", str(priv_key_file),
            "-out", str(sig_file),
            str(hash_file)
        ]
        ok, _, _ = run_command(cmd, verbose=verbose)
        if not ok or not sig_file.exists():
            return hash_str, None

        with open(sig_file, "rb") as f:
            signature_b64 = base64.b64encode(f.read()).decode("utf-8")

        return hash_str, signature_b64


def verify_jar_internal(jar_path: Path | str, verbose: bool = True) -> tuple[bool, str]:
    """
    Verifies internal JAR signature using jarsigner -verify.
    """
    target = Path(jar_path)
    if not target.exists():
        return False, f"File not found: {target}"

    jarsigner_tool = find_jdk_tool("jarsigner")
    ok, out, err = run_command([jarsigner_tool, "-verify", str(target)], verbose=verbose)
    if ok and "jar verified" in out.lower():
        return True, out.strip()
    return False, (err or out).strip()


def verify_detached_signature(
    file_path: Path | str,
    signature_b64: str,
    public_key_b64: str | None = None,
    verbose: bool = True
) -> tuple[bool, str]:
    """
    Verifies detached RSA-SHA256 signature against the file's SHA-256 hash.
    """
    pub_key_b64 = public_key_b64 if public_key_b64 is not None else (os.getenv("PLUGIN_PUBLIC_SIGNING_KEY") or DEFAULT_PUBLIC_KEY_B64)
    if not pub_key_b64:
        return False, "Public key not provided or found in environment."

    target = Path(file_path)
    if not target.exists():
        return False, f"File not found: {target}"

    # Compute SHA-256 hash
    sha256_hash = hashlib.sha256()
    with open(target, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            sha256_hash.update(chunk)
    hash_str = sha256_hash.hexdigest()

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)
        pub_key_file = tmp_path / "public.pem"
        hash_file = tmp_path / "hash.txt"
        sig_file = tmp_path / "sig.bin"

        pub_key_pem = f"-----BEGIN PUBLIC KEY-----\n{pub_key_b64.strip()}\n-----END PUBLIC KEY-----\n"
        pub_key_file.write_text(pub_key_pem, encoding="utf-8")
        hash_file.write_text(hash_str, encoding="utf-8")

        try:
            with open(sig_file, "wb") as f:
                f.write(base64.b64decode(signature_b64))

            cmd = [
                "openssl", "dgst", "-sha256",
                "-verify", str(pub_key_file),
                "-signature", str(sig_file),
                str(hash_file)
            ]
            ok, out, err = run_command(cmd, verbose=verbose)
            if ok:
                return True, "Detached signature verified successfully."
            return False, (err or out).strip()
        except Exception as e:
            return False, f"Detached signature verification error: {e}"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sign, repack, or verify JAR files for Plugin Toolkit.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  # Sign JAR in-place (reads private key from .env automatically):
  python scripts/sign_jar.py path/to/plugin.jar

  # Sign JAR and save to a separate output path:
  python scripts/sign_jar.py path/to/plugin.jar -o dist/plugin-signed.jar
  python scripts/sign_jar.py path/to/plugin.jar dist/plugin-signed.jar

  # Verify internal signature and detached hash:
  python scripts/sign_jar.py path/to/plugin.jar --verify

  # Only normalize fat-JAR local headers (no signing):
  python scripts/sign_jar.py path/to/plugin.jar --repack-only

  # Print detached SHA-256 hash and RSA signature:
  python scripts/sign_jar.py path/to/plugin.jar --detached-only
"""
    )
    parser.add_argument("jar", help="Path to the JAR file to sign, repack, or verify")
    parser.add_argument("output", nargs="?", default=None, help="Optional output path for signed JAR (default: in-place)")
    parser.add_argument("-o", "--out", dest="out_flag", default=None, help="Output path for signed JAR (alternative to positional output)")
    parser.add_argument("-k", "--key", help="Base64-encoded RSA private key (default: from .env PLUGIN_PRIVATE_SIGNING_KEY)")
    parser.add_argument("--public-key", help="Base64-encoded RSA public key (default: from .env PLUGIN_PUBLIC_SIGNING_KEY)")
    parser.add_argument("-v", "--verify", action="store_true", help="Verify the JAR signature instead of signing")
    parser.add_argument("--repack-only", action="store_true", help="Only normalize local file headers without signing")
    parser.add_argument("--detached-only", action="store_true", help="Calculate SHA-256 and detached signature without modifying file")
    parser.add_argument("-q", "--quiet", action="store_true", help="Suppress verbose execution logs")

    args = parser.parse_args()

    jar_path = Path(args.jar).resolve()
    if not jar_path.exists():
        print(f"Error: Target JAR file not found at: {jar_path}")
        return 1

    verbose = not args.quiet
    output_destination = args.out_flag or args.output

    # Mode 1: Detached signature only
    if args.detached_only:
        hash_str, sig_str = get_detached_signature(jar_path, private_key_b64=args.key, verbose=verbose)
        print(f"\nFile:      {jar_path}")
        print(f"SHA-256:   {hash_str}")
        if sig_str:
            print(f"Signature: {sig_str}")
        else:
            print("Signature: [No private key available to sign]")
        return 0

    # Mode 2: Verification
    if args.verify:
        print(f"\nVerifying JAR: {jar_path.name}...")
        ok_internal, msg_internal = verify_jar_internal(jar_path, verbose=verbose)
        if ok_internal:
            print("  [OK] Internal JAR signature verified.")
        else:
            print(f"  [FAIL] Internal JAR signature check failed:\n    {msg_internal}")

        hash_str, _ = get_detached_signature(jar_path, verbose=False)
        print(f"  [INFO] SHA-256: {hash_str}")

        return 0 if ok_internal else 1

    # Mode 3: Repack only
    if args.repack_only:
        target_out = Path(output_destination).resolve() if output_destination else jar_path
        print(f"\nNormalizing local headers for: {jar_path.name} -> {target_out.name}...")
        ok = repack_jar_if_needed(jar_path, output_path=target_out, verbose=verbose)
        return 0 if ok else 1

    # Mode 4: Sign JAR (Default)
    target_out = Path(output_destination).resolve() if output_destination else jar_path
    print(f"\nSigning JAR: {jar_path.name} -> {target_out.name}...")
    success = sign_jar(
        jar_path=jar_path,
        private_key_b64=args.key,
        output_path=target_out,
        verbose=verbose
    )

    if success:
        # Also print detached info
        h, s = get_detached_signature(target_out, private_key_b64=args.key, verbose=False)
        print(f"  [OK] JAR signed successfully.")
        print(f"  [INFO] SHA-256: {h}")
        return 0
    else:
        print(f"  [FAIL] Signing failed for {jar_path.name}.")
        return 1


if __name__ == "__main__":
    sys.exit(main())
