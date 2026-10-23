"""Upload the backed-up permanent key to GitHub Secrets using gh's encrypted Secret API.

Values are passed on stdin, never command-line arguments or console output.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--passwords", type=Path, required=True)
    args = parser.parse_args()
    names = ("ANDROID_RELEASE_STORE_PASSWORD", "ANDROID_RELEASE_KEY_ALIAS", "ANDROID_RELEASE_KEY_PASSWORD")
    values = {}
    for line in args.passwords.read_text().splitlines():
        key, separator, value = line.partition("=")
        if separator and key in names:
            values[key] = value
    if any(not values.get(name) for name in names):
        sys.exit("Password backup is incomplete; nothing uploaded.")
    pin = json.loads((ROOT / "config/release-signing.json").read_text())
    if values["ANDROID_RELEASE_KEY_ALIAS"] != pin["key_alias"]:
        sys.exit("Alias differs from the permanent signing identity; nothing uploaded.")
    env = os.environ.copy()
    env.update(values)
    certificate = subprocess.run(["keytool", "-exportcert", "-keystore", str(args.keystore),
        "-alias", values["ANDROID_RELEASE_KEY_ALIAS"], "-storepass:env", "ANDROID_RELEASE_STORE_PASSWORD"],
        env=env, capture_output=True)
    if certificate.returncode or hashlib.sha256(certificate.stdout).hexdigest() != pin["certificate_sha256"]:
        sys.exit("Keystore does not match the permanent certificate; nothing uploaded.")
    values["ANDROID_RELEASE_KEYSTORE_BASE64"] = base64.b64encode(args.keystore.read_bytes()).decode("ascii")
    for name in ("ANDROID_RELEASE_KEYSTORE_BASE64",) + names:
        result = subprocess.run(["gh", "secret", "set", name, "--repo", args.repo],
            input=values[name], text=True, capture_output=True)
        if result.returncode:
            sys.exit("Could not save " + name + ". Check GitHub Secrets write permission. No secret values were printed.")
        print("Saved Secret: " + name)
    print("All four Secrets saved using the existing permanent key. No new key was generated.")


if __name__ == "__main__":
    main()
