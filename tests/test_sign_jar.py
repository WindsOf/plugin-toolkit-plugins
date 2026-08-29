"""
Unit tests for scripts/sign_jar.py.
"""

import hashlib
import os
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

from scripts import sign_jar


class TestSignJar(unittest.TestCase):

    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.tmp_path = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def _create_dummy_jar(self, filename: str = "sample.jar") -> Path:
        jar_path = self.tmp_path / filename
        with zipfile.ZipFile(jar_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nCreated-By: Test\n")
            zf.writestr("com/wip/test/TestClass.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x34")
            zf.writestr("resources/test.txt", "Hello Plugin Toolkit")
        return jar_path

    def test_find_jdk_tool_fallback(self):
        tool = sign_jar.find_jdk_tool("nonexistent_tool_12345")
        self.assertEqual(tool, "nonexistent_tool_12345")

    def test_get_detached_signature_hash_computation(self):
        jar_path = self._create_dummy_jar("hash_test.jar")
        # Test hash computation when no private key is provided
        hash_str, sig_str = sign_jar.get_detached_signature(jar_path, private_key_b64="", verbose=False)
        self.assertIsNotNone(hash_str)
        self.assertIsNone(sig_str)

        # Verify hash matches actual sha256
        sha = hashlib.sha256(jar_path.read_bytes()).hexdigest()
        self.assertEqual(hash_str, sha)

    def test_verify_jar_internal_missing_file(self):
        ok, msg = sign_jar.verify_jar_internal(self.tmp_path / "missing.jar", verbose=False)
        self.assertFalse(ok)
        self.assertIn("File not found", msg)

    def test_verify_detached_signature_missing_public_key(self):
        jar_path = self._create_dummy_jar("sig_test.jar")
        ok, msg = sign_jar.verify_detached_signature(jar_path, "dummy_sig", public_key_b64="", verbose=False)
        self.assertFalse(ok)
        self.assertIn("Public key not provided", msg)

    @patch("scripts.sign_jar.run_command")
    def test_verify_jar_internal_mocked_success(self, mock_run):
        mock_run.return_value = (True, "jar verified.\nWarning: ...", "")
        jar_path = self._create_dummy_jar("verify_mock.jar")
        ok, out = sign_jar.verify_jar_internal(jar_path, verbose=False)
        self.assertTrue(ok)

    @patch("scripts.sign_jar.run_command")
    def test_verify_jar_internal_mocked_failure(self, mock_run):
        mock_run.return_value = (False, "", "jarsigner: java.lang.SecurityException: invalid signature")
        jar_path = self._create_dummy_jar("verify_fail.jar")
        ok, err = sign_jar.verify_jar_internal(jar_path, verbose=False)
        self.assertFalse(ok)


if __name__ == "__main__":
    unittest.main()
