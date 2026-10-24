"""Verify a distributable Release APK against the public permanent signing pin."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(apk: Path):
    pin = json.loads((ROOT / "config/release-signing.json").read_text())
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    certificates = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", certificates)
    if fingerprints != [pin["certificate_sha256"]]:
        raise ValueError("APK certificate does not match the permanent v1.0 signing identity.")
    metadata = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    app = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    if not app or app[1] != pin["application_id"]:
        raise ValueError("APK applicationId differs from the existing application.")
    source = (ROOT / "app/build.gradle.kts").read_text()
    expected_code = re.search(r"versionCode\s*=\s*(\d+)", source)[1]
    expected_name = re.search(r'versionName\s*=\s*"([^"]+)"', source)[1]
    if (app[2], app[3]) != (expected_code, expected_name):
        raise ValueError("APK version does not match the checked-out source.")
    if "application-debuggable" in metadata:
        raise ValueError("Distributable Release APK must not be debuggable.")
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ValueError("APK ZIP integrity check failed.")
        notes_data=archive.read("assets/word-study-notes.json")
        notes=json.loads(notes_data)
        source_notes=(ROOT/"app/src/main/assets/word-study-notes.json").read_bytes()
        if hashlib.sha256(notes_data).digest()!=hashlib.sha256(source_notes).digest():
            raise ValueError("APK study notes differ from the reviewed source asset.")
        if len(notes["morphology"])!=1640:
            raise ValueError("APK is missing word morphology/usage records.")
        audio_files = sum(name.startswith("assets/pronunciation/") and name.endswith(".mp3") for name in archive.namelist())
    with apk.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"application_id": app[1], "version_code": int(app[2]), "version": app[3],
            "certificate_sha256": fingerprints[0], "apk_sha256": digest,
            "debuggable": False, "offline_audio_files": audio_files,
            "reviewed_study_cards":len(notes["studyCards"]),"morphology_records":len(notes["morphology"]),
            "study_notes_sha256":hashlib.sha256(notes_data).hexdigest(),
            "signing_identity": "permanent-v1.0-and-later"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(args.apk)
    text = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(text)
    print(text, end="")
