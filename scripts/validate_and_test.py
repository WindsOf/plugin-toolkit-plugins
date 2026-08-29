"""
Comprehensive validation and verification script.
Tests JAR signing across multiple installed JDKs, runs full repository generation for slicer,
validates repository signatures, and runs the unit test suite.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# Ensure root directory is in sys.path
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from scripts.generate_repo import generate_repo
from scripts.sign_jar import (
    find_jdk_tool,
    get_detached_signature,
    run_command,
    sign_jar,
    verify_detached_signature,
    verify_jar_internal,
)
from scripts.verify_repo import verify_repo


def test_jdks_on_jar(jar_path: Path):
    """
    Tests signing and verifying the given JAR across all available JDK installations.
    """
    candidate_jdks = [
        ("JDK 26", r"C:\Program Files\Java\jdk-26\bin\jarsigner.exe"),
        ("JDK 24", r"C:\Program Files\Java\jdk-24\bin\jarsigner.exe"),
        ("Android Studio JBR", os.path.expanduser(r"~\AppData\Local\Programs\Android Studio\jbr\bin\jarsigner.exe")),
    ]

    available_jdks = [(name, path) for name, path in candidate_jdks if Path(path).exists()]
    print(f"\nFound {len(available_jdks)} JDK installations for testing:")
    for name, path in available_jdks:
        print(f"  • {name}: {path}")

    results = []

    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_path = Path(tmp_dir)

        for name, jarsigner_path in available_jdks:
            print(f"\n--- Testing signing with {name} ---")
            output_jar = tmp_path / f"signed_{name.replace(' ', '_')}.jar"

            # Set environment or monkeypatch jarsigner path temporarily
            original_find = sys.modules["scripts.sign_jar"].find_jdk_tool
            sys.modules["scripts.sign_jar"].find_jdk_tool = lambda tool: jarsigner_path if tool == "jarsigner" else original_find(tool)

            try:
                sign_ok = sign_jar(
                    jar_path=jar_path,
                    output_path=output_jar,
                    verbose=True
                )

                if sign_ok and output_jar.exists():
                    # Verify internal signature with jarsigner
                    verify_ok, verify_msg = verify_jar_internal(output_jar, verbose=True)
                    # Verify detached signature
                    h, sig = get_detached_signature(output_jar, verbose=False)
                    det_ok, det_msg = verify_detached_signature(output_jar, sig, verbose=False)

                    passed = sign_ok and verify_ok and det_ok
                    results.append((name, passed, f"Sign: {sign_ok}, Internal Verify: {verify_ok}, Detached: {det_ok}"))
                    print(f"  [{'PASS' if passed else 'FAIL'}] {name} verification result: {results[-1][2]}")
                else:
                    results.append((name, False, "Signing failed"))
                    print(f"  [FAIL] {name} signing failed.")
            finally:
                sys.modules["scripts.sign_jar"].find_jdk_tool = original_find

    return results


def main():
    print("=" * 60)
    print("   Comprehensive Plugin Toolkit Signing & Repo Test   ")
    print("=" * 60)

    # 1. Check slicer built JAR
    slicer_jar = _PROJECT_ROOT / "slicer" / "build" / "libs" / "slicer.jar"
    if not slicer_jar.exists():
        print(f"Building slicer jar first...")
        gradle_wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        run_command([gradle_wrapper, ":slicer:jar", "--no-configuration-cache"], cwd=str(_PROJECT_ROOT))

    if not slicer_jar.exists():
        print(f"Error: {slicer_jar} not found after build.")
        return 1

    # 2. Test signing with each JDK
    jdk_results = test_jdks_on_jar(slicer_jar)

    # 3. Generate repo for slicer (clean build)
    print("\n" + "=" * 60)
    print("   Running generate_repo for Slicer (Clean Build)   ")
    print("=" * 60)
    repo_success = generate_repo(
        name="WindsOf Plugin Toolkit Repository",
        url="https://windsofresub.cloud/plugins",
        output_dir="dist",
        clean=True,
        target_plugin="slicer"
    )

    # 4. Verify entire dist repository
    print("\n" + "=" * 60)
    print("   Verifying Entire dist/ Repository Catalog   ")
    print("=" * 60)
    repo_verify_ok = verify_repo(dist_dir="dist")

    # 5. Run unit tests
    print("\n" + "=" * 60)
    print("   Running Unit Test Suite   ")
    print("=" * 60)
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=str(_PROJECT_ROOT / "tests"), pattern="test_*.py")
    runner = unittest.TextTestRunner(verbosity=2)
    test_result = runner.run(suite)
    tests_ok = test_result.wasSuccessful()

    # Final Summary
    print("\n" + "=" * 60)
    print("   FINAL TEST RESULTS SUMMARY   ")
    print("=" * 60)
    for name, passed, detail in jdk_results:
        status = "PASSED" if passed else "FAILED"
        print(f"  • JDK Testing ({name}): [{status}] - {detail}")
    print(f"  • Generate Repo: [{'PASSED' if repo_success else 'FAILED'}]")
    print(f"  • Verify Repo:   [{'PASSED' if repo_verify_ok else 'FAILED'}]")
    print(f"  • Unit Tests:    [{'PASSED' if tests_ok else 'FAILED'}] ({test_result.testsRun} tests run)")
    print("=" * 60)

    overall_ok = all(p for _, p, _ in jdk_results) and repo_success and repo_verify_ok and tests_ok
    if overall_ok:
        print("\nALL CHECKS PASSED SUCCESSFULLY!\n")
        return 0
    else:
        print("\nSOME CHECKS FAILED. Please review output above.\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
