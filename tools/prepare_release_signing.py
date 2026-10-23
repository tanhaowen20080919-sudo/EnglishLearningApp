"""Restore the one permanent v1.0+ key from Actions Secrets; never generate a key."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SECRET_NAMES = ("ANDROID_RELEASE_KEYSTORE_BASE64", "ANDROID_RELEASE_STORE_PASSWORD",
                "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-env", action="store_true")
    args = parser.parse_args()
    created = False
    success = False
    try:
        missing = [name for name in SECRET_NAMES if not os.environ.get(name, "").strip()]
        if missing:
            raise ValueError("Missing permanent signing Secrets: " + ", ".join(missing) + ". Restore the existing v1.0 key; no new key will be generated.")
        pin = json.loads((ROOT / "config/release-signing.json").read_text())
        if os.environ["ANDROID_RELEASE_KEY_ALIAS"] != pin["key_alias"]:
            raise ValueError("Release alias differs from the permanent signing identity.")
        encoded = "".join(os.environ["ANDROID_RELEASE_KEYSTORE_BASE64"].split())
        if len(encoded) > 2_000_000:
            raise ValueError("Unexpected keystore size.")
        data = base64.b64decode(encoded, validate=True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        descriptor = os.open(args.output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        created = True
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
        result = subprocess.run([
            "keytool", "-exportcert", "-keystore", str(args.output),
            "-alias", os.environ["ANDROID_RELEASE_KEY_ALIAS"],
            "-storepass:env", "ANDROID_RELEASE_STORE_PASSWORD",
        ], capture_output=True, check=False)
        if result.returncode:
            raise ValueError("Permanent keystore could not be opened. No fallback signing is allowed.")
        actual = hashlib.sha256(result.stdout).hexdigest()
        if actual != pin["certificate_sha256"]:
            raise ValueError("Keystore certificate differs from the pinned v1.0 certificate. Signing stopped.")
        if args.github_env:
            value = str(args.output.resolve())
            if "\n" in value or "\r" in value:
                raise ValueError("Invalid temporary keystore path.")
            with open(os.environ["GITHUB_ENV"], "a") as stream:
                stream.write("ANDROID_RELEASE_KEYSTORE_FILE=" + value + "\n")
        success = True
        print("Verified permanent Release certificate SHA-256: " + actual)
        return 0
    except Exception as error:
        message = str(error) if isinstance(error, ValueError) else "Permanent signing restore failed; no signing files were replaced."
        print("::error::" + message, file=sys.stderr)
        return 1
    finally:
        if created and not success:
            args.output.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
