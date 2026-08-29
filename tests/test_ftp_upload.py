import os
import tempfile
import unittest
from unittest.mock import MagicMock, patch
from pathlib import Path

import generate_repo


class TestFtpUpload(unittest.TestCase):

    def setUp(self):
        # Clean environment variables before each test
        for key in ["FTP_HOST", "FTP_PORT", "FTP_USER", "FTP_USERNAME", "FTP_PASS", "FTP_PASSWORD", "FTP_DIR", "FTP_PATH", "FTP_REMOTE_DIR", "FTP_TLS", "FTP_SECURE", "FTP_TIMEOUT"]:
            if key in os.environ:
                del os.environ[key]

    def test_missing_credentials_returns_false(self):
        with tempfile.TemporaryDirectory() as tmp_dir:
            result = generate_repo.upload_to_ftp(output_dir=tmp_dir)
            self.assertFalse(result)

    def test_invalid_port_returns_false(self):
        os.environ["FTP_HOST"] = "example.com"
        os.environ["FTP_USER"] = "user"
        os.environ["FTP_PASS"] = "pass"
        os.environ["FTP_PORT"] = "not_a_number"
        with tempfile.TemporaryDirectory() as tmp_dir:
            result = generate_repo.upload_to_ftp(output_dir=tmp_dir)
            self.assertFalse(result)

    @patch("generate_repo.connect_ftp")
    def test_dry_run_mode_verifies_connection(self, mock_connect):
        mock_ftp = MagicMock()
        mock_ftp.pwd.return_value = "/www.windsofresub.cloud/plugins"
        mock_ftp.size.return_value = None
        mock_connect.return_value = (mock_ftp, "FTPS (Explicit TLS)")

        os.environ["FTP_HOST"] = "example.com"
        os.environ["FTP_USER"] = "user"
        os.environ["FTP_PASS"] = "pass"
        os.environ["FTP_DIR"] = "www.windsofresub.cloud/plugins"

        with tempfile.TemporaryDirectory() as tmp_dir:
            dist_path = Path(tmp_dir)
            (dist_path / "index.json").write_bytes(b"{}")
            result = generate_repo.upload_to_ftp(output_dir=tmp_dir, dry_run=True)
            self.assertTrue(result)
            mock_connect.assert_called_once()
            mock_ftp.storbinary.assert_not_called()
            mock_ftp.quit.assert_called_once()

    @patch("ftplib.FTP_TLS")
    def test_connect_ftp_tls_success(self, mock_tls_cls):
        mock_ftp = MagicMock()
        mock_tls_cls.return_value = mock_ftp

        ftp, mode = generate_repo.connect_ftp("example.com", 21, "user", "pass", secure=True)
        self.assertEqual(mode, "FTPS (Explicit TLS)")
        mock_ftp.connect.assert_called_once_with("example.com", 21)
        mock_ftp.login.assert_called_once_with("user", "pass")
        mock_ftp.prot_p.assert_called_once()
        mock_ftp.set_pasv.assert_called_once_with(True)

    @patch("ftplib.FTP")
    @patch("ftplib.FTP_TLS")
    def test_connect_ftp_tls_fallback_to_plain(self, mock_tls_cls, mock_plain_cls):
        mock_tls_cls.side_effect = Exception("TLS not supported")
        mock_plain = MagicMock()
        mock_plain_cls.return_value = mock_plain

        ftp, mode = generate_repo.connect_ftp("example.com", 21, "user", "pass", secure=True)
        self.assertEqual(mode, "FTP (Plain)")
        mock_plain.connect.assert_called_once_with("example.com", 21)
        mock_plain.login.assert_called_once_with("user", "pass")
        mock_plain.set_pasv.assert_called_once_with(True)

    def test_ensure_remote_dir_recursive(self):
        mock_ftp = MagicMock()
        generate_repo.ensure_remote_dir(mock_ftp, "plugins/com.wip.slicer")

        self.assertEqual(mock_ftp.cwd.call_count, 2)
        mock_ftp.cwd.assert_any_call("plugins")
        mock_ftp.cwd.assert_any_call("com.wip.slicer")

    def test_ensure_remote_dir_absolute(self):
        mock_ftp = MagicMock()
        generate_repo.ensure_remote_dir(mock_ftp, "/public_html/plugins")

        mock_ftp.cwd.assert_any_call("/")
        mock_ftp.cwd.assert_any_call("public_html")
        mock_ftp.cwd.assert_any_call("plugins")

    def test_resolve_remote_dir_auto_detects_domain(self):
        mock_ftp = MagicMock()
        # Direct cwd fails for 'plugins' from '/'
        def mock_cwd(path):
            if path == "plugins" and mock_ftp.current_dir == "/":
                import ftplib
                raise ftplib.error_perm("No such directory")
            if path == "/":
                mock_ftp.current_dir = "/"
            elif path == "www.windsofresub.cloud":
                mock_ftp.current_dir = "/www.windsofresub.cloud"
            elif path == "plugins" and mock_ftp.current_dir == "/www.windsofresub.cloud":
                mock_ftp.current_dir = "/www.windsofresub.cloud/plugins"
            return "250 OK"

        mock_ftp.current_dir = "/"
        mock_ftp.cwd.side_effect = mock_cwd
        mock_ftp.nlst.return_value = ["www.windsofresub.cloud"]
        mock_ftp.pwd.side_effect = lambda: mock_ftp.current_dir

        pwd = generate_repo.resolve_and_ensure_remote_base_dir(mock_ftp, "plugins")
        self.assertEqual(pwd, "/www.windsofresub.cloud/plugins")

    def test_get_remote_file_size(self):
        mock_ftp = MagicMock()
        mock_ftp.size.return_value = 1048576
        size = generate_repo.get_remote_file_size(mock_ftp, "test.jar")
        self.assertEqual(size, 1048576)

        mock_ftp.size.side_effect = Exception("SIZE error")
        size_err = generate_repo.get_remote_file_size(mock_ftp, "missing.jar")
        self.assertIsNone(size_err)

    def test_upload_file_skip_identical_size(self):
        mock_ftp = MagicMock()
        with tempfile.TemporaryDirectory() as tmp_dir:
            file_path = Path(tmp_dir) / "test.jar"
            file_path.write_bytes(b"x" * 1000)

            mock_ftp.size.return_value = 1000

            result = generate_repo.upload_file_with_progress(mock_ftp, file_path, "test.jar", force=False)
            self.assertTrue(result)
            mock_ftp.storbinary.assert_not_called()

            result_force = generate_repo.upload_file_with_progress(mock_ftp, file_path, "test.jar", force=True)
            self.assertTrue(result_force)
            mock_ftp.storbinary.assert_called_once()

    def test_upload_file_always_uploads_index_json(self):
        mock_ftp = MagicMock()
        with tempfile.TemporaryDirectory() as tmp_dir:
            file_path = Path(tmp_dir) / "index.json"
            file_path.write_bytes(b'{"name":"test"}')

            mock_ftp.size.return_value = len(b'{"name":"test"}')

            result = generate_repo.upload_file_with_progress(mock_ftp, file_path, "index.json", force=False)
            self.assertTrue(result)
            mock_ftp.storbinary.assert_called_once()

    @patch("generate_repo.connect_ftp")
    def test_upload_to_ftp_full_workflow(self, mock_connect):
        mock_ftp = MagicMock()
        mock_ftp.pwd.return_value = "/www.windsofresub.cloud/plugins"
        mock_ftp.size.return_value = None
        mock_connect.return_value = (mock_ftp, "FTPS (Explicit TLS)")

        os.environ["FTP_HOST"] = "ftp.windsofresub.cloud"
        os.environ["FTP_USER"] = "testuser"
        os.environ["FTP_PASS"] = "testpass"
        os.environ["FTP_DIR"] = "www.windsofresub.cloud/plugins"

        with tempfile.TemporaryDirectory() as tmp_dir:
            dist_path = Path(tmp_dir)
            (dist_path / "plugins" / "com.wip.demo").mkdir(parents=True)
            (dist_path / "plugins" / "com.wip.demo" / "demo-1.0.0.jar").write_bytes(b"dummy jar content")
            (dist_path / "plugins" / "com.wip.demo" / "manifest.json").write_bytes(b"{}")
            (dist_path / "index.json").write_bytes(b'{"schemaVersion":1}')

            success = generate_repo.upload_to_ftp(output_dir=str(dist_path))
            self.assertTrue(success)
            self.assertTrue(mock_ftp.storbinary.called)
            mock_ftp.quit.assert_called_once()

    @patch("generate_repo.upload_to_ftp")
    def test_deploy_repository_prefers_ftp_if_configured(self, mock_upload):
        os.environ["FTP_HOST"] = "ftp.windsofresub.cloud"
        with tempfile.TemporaryDirectory() as tmp_dir:
            generate_repo.deploy_repository(tmp_dir)
            mock_upload.assert_called_once_with(tmp_dir, force=False, dry_run=False)


if __name__ == "__main__":
    unittest.main()
