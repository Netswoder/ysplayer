"""Package reviewed public sources and audit APK/ignore rules without publishing."""
from pathlib import Path
import getpass
import io
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "artifacts"
ROOT_FILES = [".gitignore", "LICENSE", "NOTICE", "PRIVACY.md", "README.md", "THIRD_PARTY_NOTICES.md",
              "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
              "release-signing.properties.example", "app/build.gradle.kts", "app/proguard-rules.pro"]
SOURCE_DIRS = ["app/src", "app/schemas", "gradle", "docs", "tools", "third_party"]


def private_text(text):
    patterns = [r"[A-Za-z]:[\\/]+(?:Users|Pyth|音声|androidStudio)[\\/]",
                r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"]
    username = getpass.getuser()
    if len(username) >= 3 and username.lower() not in {"root", "user", "admin", "runner"}:
        patterns.append(r"\b" + re.escape(username) + r"\b")
    return any(re.search(pattern, text, re.I) for pattern in patterns)


def dex_strings(data):
    count, offset = struct.unpack_from("<II", data, 56)
    for i in range(count):
        start = struct.unpack_from("<I", data, offset + i * 4)[0]
        while data[start] & 0x80:
            start += 1
        start += 1
        end = data.index(b"\0", start)
        yield data[start:end].decode("utf-8", errors="replace")


def verify_ignores():
    ignored = ["private-signing/release.properties", "private-signing/ysplayer-release.p12", "local.properties",
               "example.apk", "example.aab", "artifacts/output.zip", "app/build/results.xml", ".gradle/cache", "build.log", ".env"]
    kept = ["gradle/wrapper/gradle-wrapper.jar", "app/schemas/com.ysplayer.data.LibraryDatabase/3.json",
            "third_party/juniversalchardet-1.0.3-sources.jar", "LICENSE", "app/src/main/AndroidManifest.xml"]
    with tempfile.TemporaryDirectory(prefix="ysplayer-ignore-") as temp:
        shutil.copyfile(ROOT / ".gitignore", Path(temp) / ".gitignore")
        subprocess.run(["git", "init", "-q", temp], check=True, capture_output=True)
        result = subprocess.run(["git", "-c", "core.excludesFile=", "-C", temp, "check-ignore", "--no-index", "--stdin"],
                                input=("\n".join(ignored + kept) + "\n").encode(), capture_output=True)
        if result.returncode not in (0, 1):
            raise RuntimeError("Git ignore validation failed")
        assert set(result.stdout.decode().splitlines()) == set(ignored), "Unexpected .gitignore behavior"


def main():
    verify_ignores()
    files = [ROOT / name for name in ROOT_FILES]
    for directory in SOURCE_DIRS:
        files.extend(path for path in (ROOT / directory).rglob("*") if path.is_file() and
                     "__pycache__" not in path.parts and path.suffix not in {".pyc", ".pyo"})
    files = sorted(set(files))
    for path in files:
        if path.suffix in {".jar", ".zip"}:
            continue  # Unmodified upstream source archives retain upstream copyright information.
        assert not private_text(path.read_text(encoding="utf-8")), "Private text in " + str(path.relative_to(ROOT))
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    with zipfile.ZipFile(apk) as archive:
        for filename in archive.namelist():
            if re.fullmatch(r"classes\d*\.dex", filename):
                for value in dex_strings(archive.read(filename)):
                    assert not private_text(value), "Private string in APK DEX"
        for path in (ROOT / "app/src/main/assets/legal").iterdir():
            assert archive.read("assets/legal/" + path.name) == path.read_bytes(), "Stale legal asset in APK"
        with zipfile.ZipFile(io.BytesIO(archive.read("assets/legal/juniversalchardet-1.0.3-source.zip"))) as bundle:
            assert bundle.read("juniversalchardet-1.0.3-sources.jar") == (ROOT / "third_party/juniversalchardet-1.0.3-sources.jar").read_bytes()
    OUT.mkdir(exist_ok=True)
    source_zip = OUT / "ysplayer-1.0.0-source.zip"
    with zipfile.ZipFile(source_zip, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            entry = zipfile.ZipInfo("ysplayer-1.0.0/" + path.relative_to(ROOT).as_posix(), (2026, 11, 2, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (0o100755 if path.name == "gradlew" else 0o100644) << 16
            archive.writestr(entry, path.read_bytes())
    shutil.copyfile(apk, OUT / "ysplayer-1.0.0.apk")
    shutil.copyfile(ROOT / "app/src/main/assets/legal/juniversalchardet-1.0.3-source.zip", OUT / "juniversalchardet-1.0.3-source.zip")
    preview = ROOT / "app/build/outputs/apk/preview/app-preview.apk"
    # Preview is deliberately separate: it is for existing test installations, not public release.
    if preview.exists():
        import json
        metadata = json.loads((preview.parent / "output-metadata.json").read_text())
        if metadata["elements"][0]["versionName"] == "1.0.0-preview":
            shutil.copyfile(preview, OUT / "ysplayer-1.0.0-preview.apk")
    print(f"Public source archive: {len(files)} files; private text / DEX / legal assets / ignore checks passed.")


if __name__ == "__main__":
    main()
