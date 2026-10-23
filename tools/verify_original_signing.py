"""Use the historical Actions Secret only when its public certificate matches the old release.

Never prints or uploads the keystore, creates a new key, or modifies Gradle signing settings.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

EXPECTED_CERTIFICATE = "99278f5e7fbe0bc2392a2fe3c2afa304f61a5fa438423b1ff3d670d26dcbb31a"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep-keystore", action="store_true")
    args = parser.parse_args()
    report = {"secret_name": "ANDROID_DEBUG_KEYSTORE_BASE64", "expected_certificate_sha256": EXPECTED_CERTIFICATE,
              "present": False, "verified": False}
    target = Path(os.environ["RUNNER_TEMP"]) / "original-signing.keystore"
    created = False
    success = False
    try:
        value = os.environ.get("ANDROID_DEBUG_KEYSTORE_BASE64", "")
        if not value.strip():
            raise ValueError("Original signing Secret ANDROID_DEBUG_KEYSTORE_BASE64 is unavailable to this run. No APK will be signed and no replacement key will be generated.")
        report["present"] = True
        encoded = "".join(value.split())
        if len(encoded) > 2_000_000:
            raise ValueError("Original signing Secret has an unexpected size.")
        data = base64.b64decode(encoded, validate=True)
        # Exclusive creation prevents overwriting any existing signing file, even in the runner.
        fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        created = True
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
        result = subprocess.run([
            "keytool", "-exportcert", "-keystore", str(target), "-alias", "androiddebugkey",
            "-storepass:env", "ORIGINAL_STORE_PASSWORD",
        ], capture_output=True, check=False)
        if result.returncode:
            raise ValueError("The historical keystore could not be opened with the historical Android debug signing settings. Existing configuration was not changed.")
        actual = hashlib.sha256(result.stdout).hexdigest()
        report["actual_certificate_sha256"] = actual
        if actual != EXPECTED_CERTIFICATE:
            raise ValueError("Historical Secret certificate does not match the documented old release certificate. Signing stopped.")
        report["verified"] = True
        success = True
        print("Verified original release certificate SHA-256: " + actual)
    except Exception as error:
        message = str(error) if isinstance(error, ValueError) else "Original signing verification failed. No signing configuration was replaced."
        report["error"] = message
        print("::error::" + message, file=sys.stderr)
    finally:
        if created and not (success and args.keep_keystore):
            target.unlink(missing_ok=True)
        Path("original-signing-audit.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
