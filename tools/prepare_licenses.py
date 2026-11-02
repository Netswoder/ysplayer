"""Refresh shipped notices from :app:exportReleaseDependencies (Python stdlib only).

Run after changing dependencies. Review new dependency families before adding them
to this script. Downloads license texts and the exact upstream MPL source archive.
"""
from pathlib import Path
from urllib.request import urlopen, Request
import hashlib
import io
import json
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LEGAL = ROOT / "app/src/main/assets/legal"
THIRD = ROOT / "third_party"
MAVEN = "https://repo.maven.apache.org/maven2/com/googlecode/juniversalchardet/juniversalchardet/1.0.3/"


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": "YsPlayer-License-Packager/1.0"}), timeout=45) as response:
        return response.read()


def license_for(group, coordinate):
    if group == "com.googlecode.juniversalchardet":
        return "MPL-1.1", "https://code.google.com/archive/p/juniversalchardet/"
    if group == "org.jsoup":
        return "MIT", "https://github.com/jhy/jsoup"
    families = {
        "androidx.": "https://android.googlesource.com/platform/frameworks/support/",
        "org.jetbrains.kotlin": "https://github.com/JetBrains/kotlin",
        "org.jetbrains:": "https://github.com/JetBrains/java-annotations",
        "io.coil-kt": "https://github.com/coil-kt/coil",
        "com.google.accompanist": "https://github.com/google/accompanist",
        "com.google.guava": "https://github.com/google/guava",
        "com.squareup.okhttp3": "https://github.com/square/okhttp",
        "com.squareup.okio": "https://github.com/square/okio",
        "org.jspecify": "https://github.com/jspecify/jspecify",
    }
    for prefix, source in families.items():
        if (group + ":").startswith(prefix):
            if group.startswith("androidx.media3"):
                source = "https://github.com/androidx/media"
            elif group.startswith("org.jetbrains.kotlinx"):
                source = "https://github.com/Kotlin/" + ("kotlinx.serialization" if "serialization" in coordinate else "kotlinx.coroutines")
            return "Apache-2.0", source
    raise ValueError("Unreviewed license family: " + group)


def embedded_notices(data, prefix=""):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in sorted(archive.namelist()):
            if entry.lower().endswith(".jar"):
                yield from embedded_notices(archive.read(entry), prefix + entry + ":")
            elif not entry.endswith("/") and re.search(r"(^|/)([^/]*(license|notice|copying)[^/]*)$", entry, re.I):
                if entry.lower().endswith((".class", ".kotlin_metadata")):
                    continue
                yield prefix + entry, archive.read(entry).decode("utf-8", errors="replace")


def main():
    LEGAL.mkdir(parents=True, exist_ok=True)
    THIRD.mkdir(exist_ok=True)
    shutil.copyfile(ROOT / "PRIVACY.md", LEGAL / "PRIVACY.txt")
    texts = {
        "Apache-2.0.txt": "https://www.apache.org/licenses/LICENSE-2.0.txt",
        "MPL-1.1.txt": "https://raw.githubusercontent.com/spdx/license-list-data/main/text/MPL-1.1.txt",
        "jsoup-MIT.txt": "https://raw.githubusercontent.com/jhy/jsoup/jsoup-1.18.3/LICENSE",
    }
    for filename, url in texts.items():
        (LEGAL / filename).write_bytes(fetch(url))
    (ROOT / "LICENSE").write_bytes((LEGAL / "Apache-2.0.txt").read_bytes())
    rows = dict(line.split("\t", 1) for line in (ROOT / "app/build/legal/runtime-dependencies.tsv").read_text(encoding="utf-8").splitlines())
    inventory, notices = [], {}
    for coordinate, filename in sorted(rows.items()):
        license_id, source = license_for(coordinate.split(":")[0], coordinate)
        inventory.append({"coordinate": coordinate, "license": license_id, "source": source})
        for entry, text in embedded_notices(Path(filename).read_bytes()):
            if not text.strip():
                continue
            digest = hashlib.sha256(text.encode()).hexdigest()
            block = notices.setdefault(digest, {"text": text, "origins": []})
            block["origins"].append(coordinate + " / " + entry)
    (LEGAL / "dependencies.json").write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    introduction = """YsPlayer / 音声书架 1.0.0
Copyright 2026 YsPlayer contributors
Project code: Apache License 2.0. Third-party components retain their own licenses.

juniversalchardet 1.0.3 is distributed under MPL 1.1, not relicensed under Apache 2.0.
Its exact, unmodified upstream Java sources and original Maven build descriptor,
with license and build instructions, are included in this APK. Use the Export
source button to save juniversalchardet-1.0.3-source.zip; no network is required.
The source archive is also included in the public source distribution at:
app/src/main/assets/legal/juniversalchardet-1.0.3-source.zip
We have not modified the library source code. Android's build tools transform
compiled Java bytecode into DEX and may optimize it as part of the APK build.

The library detects subtitle character encoding; it does not decode audio.
No permission is granted over user audio, artwork, subtitles or DLsite content.
YsPlayer is not affiliated with DLsite.

Runtime components (resolved release artifacts, including transitive dependencies):
"""
    sections = [introduction + "\n".join(f"{r['coordinate']} | {r['license']} | {r['source']}" for r in inventory)]
    sections += ["License: " + filename + "\n\n" + (LEGAL / filename).read_text(encoding="utf-8") for filename in texts]
    sections += ["Upstream notice\n" + "\n".join(block["origins"]) + "\n\n" + block["text"] for block in notices.values()]
    (LEGAL / "THIRD_PARTY_NOTICES.txt").write_text("\n\n====================\n\n".join(sections), encoding="utf-8")
    source_url = MAVEN + "juniversalchardet-1.0.3-sources.jar"
    source_path = THIRD / "juniversalchardet-1.0.3-sources.jar"
    source_path.write_bytes(fetch(source_url))
    # Verify against the repository's published digest before redistributing.
    expected = fetch(source_url + ".sha1").decode().strip().split()[0]
    assert hashlib.sha1(source_path.read_bytes()).hexdigest() == expected
    source_pom = fetch(MAVEN + "juniversalchardet-1.0.3.pom")
    build_readme = """Unmodified juniversalchardet 1.0.3 upstream source package.
License selected for this distribution: Mozilla Public License 1.1.
All upstream copyright and multi-license source headers remain intact.
The exact upstream sources.jar and original Maven POM are provided without edits.

Build (JDK 8 or newer, no external runtime libraries):
Extract sources.jar into an empty directory. From that directory, compile all
org/**/*.java using javac -encoding US-ASCII -d classes @sources.txt, where
sources.txt contains the relative path of each .java file, one per line.
Create a JAR using jar cf juniversalchardet.jar -C classes .
The original POM targets Java 5; a modern javac may require a supported -source
and -target value, such as 8. This does not change any upstream source files.

Original project: https://code.google.com/archive/p/juniversalchardet/
Source artifact: """ + source_url + "\n"
    bundle = LEGAL / "juniversalchardet-1.0.3-source.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for filename, data in [(source_path.name, source_path.read_bytes()), ("pom.xml", source_pom),
                               ("LICENSE-MPL-1.1.txt", (LEGAL / "MPL-1.1.txt").read_bytes()), ("BUILD.txt", build_readme.encode())]:
            entry = zipfile.ZipInfo(filename, (2026, 11, 2, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    (THIRD / "README.md").write_text("# juniversalchardet 1.0.3\n\nOriginal upstream source JAR, redistributed under MPL 1.1 with original copyright headers.\n\n"
        + "Upstream: " + source_url + "\n\nSHA-256: `" + hashlib.sha256(source_path.read_bytes()).hexdigest() + "`\n\n"
        + "The complete exportable source bundle, POM and license are in `app/src/main/assets/legal/`.\n", encoding="utf-8")
    print(f"Prepared {len(inventory)} runtime components, {len(notices)} distinct embedded notices, and MPL source bundle.")


if __name__ == "__main__":
    main()
