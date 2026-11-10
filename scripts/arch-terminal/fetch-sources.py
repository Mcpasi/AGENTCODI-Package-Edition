#!/usr/bin/env python3
"""Cache pinned upstream archives and seed isolated builds with verified bytes."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LOCK = ROOT / "third_party/arch-terminal/scripts/dependencies.lock.json"
CACHE = ROOT / ".cache/arch-terminal/sources"
INPUTS = ("proot", "talloc", "libarchive")
ATTEMPTS = 4
TIMEOUT = 60


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def publish_verified(target, expected, write):
    """Publish only complete, verified files; concurrent builds get private partials."""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, prefix="." + target.name + ".",
                                         suffix=".part", delete=False) as output:
            partial = Path(output.name)
            write(output)
        if digest(partial) != expected:
            raise ValueError("SHA-256 mismatch: " + target.name)
        partial.replace(target)
    finally:
        if partial is not None:
            partial.unlink(missing_ok=True)


def fetch(item, cache):
    archive = cache / item["file"]
    if archive.is_file() and digest(archive) == item["sha256"]:
        print("Using verified cache:", item["file"], flush=True)
        return archive

    def download(output):
        request = urllib.request.Request(item["url"], headers={"User-Agent": "AGENTCODI-arch-sources"})
        with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
            shutil.copyfileobj(response, output)

    for attempt in range(1, ATTEMPTS + 1):
        print(f"Fetching {item['file']} (attempt {attempt}/{ATTEMPTS})", flush=True)
        try:
            publish_verified(archive, item["sha256"], download)
            return archive
        except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.IncompleteRead) as error:
            if isinstance(error, urllib.error.HTTPError):
                retryable = error.code in (408, 429) or 500 <= error.code < 600
                error.close()
                if not retryable:
                    raise
            if attempt == ATTEMPTS:
                raise RuntimeError(f"Download failed after {ATTEMPTS} attempts: {item['file']}") from error
            delay = 5 * 2 ** (attempt - 1)
            print(f"Download failed for {item['file']}: {error}; retrying in {delay}s", flush=True)
            time.sleep(delay)


def prepare(lock_path, cache, destination=None):
    lock = json.loads(lock_path.read_text())
    for name in INPUTS:
        item = lock[name]
        archive = fetch(item, cache)
        if destination is not None:
            target = destination / item["file"]
            if target.is_file() and digest(target) == item["sha256"]:
                continue

            def copy(output):
                with archive.open("rb") as source:
                    shutil.copyfileobj(source, output)

            publish_verified(target, item["sha256"], copy)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=CACHE)
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    prepare(LOCK, args.cache_dir, args.destination)


if __name__ == "__main__":
    main()
