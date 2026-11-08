#!/usr/bin/env python3
"""Sequential live check of the app's ARM64 databases and their package URLs."""
import io
import re
import tarfile
import urllib.request
from pathlib import Path


def main():
    project = Path(__file__).resolve().parents[1]
    source = (project / "app/src/main/java/de/archterminal/RootfsConfig.java").read_text()
    declaration = source.split("DEFAULT_MIRRORLIST =", 1)[1].split(";", 1)[0]
    mirrors = re.findall(r'Server = (https://[^"\\]+)', declaration)
    if len(mirrors) < 2:
        raise RuntimeError("Expected a primary HTTPS mirror and a fallback")
    targets = {"bash", "pacman", "git", "mc", "nodejs", "npm", "tree"}
    for mirror in mirrors:
        selected = {}
        for repo in ("core", "extra", "alarm", "aur"):
            base = mirror.replace("$arch", "aarch64").replace("$repo", repo)
            url = f"{base}/{repo}.db"
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
                print(f"{url}: HTTP {response.status}, {len(data)} bytes, Last-Modified={response.headers.get('Last-Modified')}", flush=True)
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as archive:
                for member in archive:
                    if not member.isfile() or not member.name.endswith("/desc"):
                        continue
                    with archive.extractfile(member) as stream:
                        fields = dict(re.findall(r"%([A-Z]+)%\n([^\n]+)", stream.read().decode()))
                    if fields.get("NAME") in targets:
                        selected[fields["NAME"]] = f"{base}/{fields['FILENAME']}"
        if selected.keys() != targets:
            raise RuntimeError(f"Packages missing from {mirror}: {targets - selected.keys()}")
        for name, url in sorted(selected.items()):
            # Resolve exactly the package filenames advertised by this mirror, including signatures.
            for resource in (url, url + ".sig"):
                request = urllib.request.Request(resource, method="HEAD")
                with urllib.request.urlopen(request, timeout=30) as response:
                    if response.status != 200:
                        raise RuntimeError(f"{resource}: HTTP {response.status}")
                    print(f"{name}: {resource}: HTTP 200", flush=True)
    print("ARCH_MIRRORS_OK", flush=True)


if __name__ == "__main__":
    main()
