"""Signing safety invariants, using dummy keystore bytes and the public certificate only."""
import base64
from contextlib import redirect_stderr
import importlib.util
import io
import json
import os
from pathlib import Path
import ssl
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("prepare_release_signing", ROOT / "tools/prepare_release_signing.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class ReleaseSigningTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.target = self.directory / "restored.jks"
        self.pin = json.loads((ROOT / "config/release-signing.json").read_text())
        self.certificate = ssl.PEM_cert_to_DER_cert((ROOT / "config/release-certificate.pem").read_text())
        self.values = {
            "ANDROID_RELEASE_KEYSTORE_BASE64": base64.b64encode(b"dummy-keystore-not-a-private-key").decode(),
            "ANDROID_RELEASE_STORE_PASSWORD": "fixture-store-password",
            "ANDROID_RELEASE_KEY_ALIAS": self.pin["key_alias"],
            "ANDROID_RELEASE_KEY_PASSWORD": "fixture-key-password",
            "GITHUB_ENV": str(self.directory / "github-env"),
        }

    def run_restore(self, values=None, certificate=None):
        result = subprocess.CompletedProcess(["keytool"], 0, stdout=certificate or self.certificate)
        with patch.dict(os.environ, self.values if values is None else values, clear=True), \
                patch.object(sys, "argv", ["restore", "--output", str(self.target), "--github-env"]), \
                patch.object(prepare.subprocess, "run", return_value=result) as keytool, \
                redirect_stderr(io.StringIO()), patch("sys.stdout", new_callable=io.StringIO):
            status = prepare.main()
        return status, keytool

    def test_missing_secrets_stop_without_creating_a_key(self):
        status, keytool = self.run_restore({})
        self.assertEqual(1, status)
        self.assertFalse(self.target.exists())
        keytool.assert_not_called()

    def test_wrong_alias_stops_before_writing_any_key(self):
        values = dict(self.values, ANDROID_RELEASE_KEY_ALIAS="different-alias")
        status, keytool = self.run_restore(values)
        self.assertEqual(1, status)
        self.assertFalse(self.target.exists())
        keytool.assert_not_called()

    def test_wrong_certificate_is_deleted_and_cannot_sign_a_release(self):
        status, _ = self.run_restore(certificate=b"a-different-public-certificate")
        self.assertEqual(1, status)
        self.assertFalse(self.target.exists())
        self.assertFalse((self.directory / "github-env").exists())

    def test_existing_key_is_never_overwritten_or_deleted(self):
        self.target.write_bytes(b"existing-key-must-be-preserved")
        status, keytool = self.run_restore()
        self.assertEqual(1, status)
        self.assertEqual(b"existing-key-must-be-preserved", self.target.read_bytes())
        keytool.assert_not_called()

    def test_verified_restore_has_private_permissions_and_exports_only_a_path(self):
        status, _ = self.run_restore()
        self.assertEqual(0, status)
        self.assertEqual(0o600, stat.S_IMODE(self.target.stat().st_mode))
        export = (self.directory / "github-env").read_text()
        self.assertEqual("ANDROID_RELEASE_KEYSTORE_FILE=" + str(self.target.resolve()) + "\n", export)
        self.assertNotIn("fixture-store-password", export)

    def test_invalid_base64_never_leaves_a_key_file(self):
        values = dict(self.values, ANDROID_RELEASE_KEYSTORE_BASE64="invalid!!")
        status, keytool = self.run_restore(values)
        self.assertEqual(1, status)
        self.assertFalse(self.target.exists())
        keytool.assert_not_called()


if __name__ == "__main__":
    unittest.main()
