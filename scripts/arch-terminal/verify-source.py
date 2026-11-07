#!/usr/bin/env python3
"""Verify that every vendored source byte matches the pinned upstream snapshot."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verify(root=ROOT):
    lock = json.loads((root / "third_party/arch-terminal.lock.json").read_text())
    source = root / "third_party/arch-terminal"
    ignored = {".build", ".cache", ".gradle", "build", "output", "__pycache__"}
    actual = {path.relative_to(source).as_posix() for path in source.rglob("*")
              if path.is_file() and not ignored.intersection(path.relative_to(source).parts)}
    if actual != set(lock["files"]):
        raise ValueError("Arch Terminal source file set changed")
    for name, expected in lock["files"].items():
        path = source / name
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Arch Terminal source changed: " + name)
    annotation = root / "third_party/androidx-annotation"
    provenance = json.loads((annotation / "PROVENANCE.json").read_text())
    if hashlib.sha256((annotation / "annotation-1.3.0.jar").read_bytes()).hexdigest() != provenance["sha256"]:
        raise ValueError("Compile-only AndroidX annotation checksum differs")
    stubs = root / "third_party/android-lambda-stubs"
    provenance = json.loads((stubs / "PROVENANCE.json").read_text())
    if hashlib.sha256((stubs / "core-lambda-stubs.jar").read_bytes()).hexdigest() != provenance["sha256"]:
        raise ValueError("Compile-only Android lambda stub checksum differs")
    print("Verified unchanged Arch Terminal", lock["version"], lock["commit"])


if __name__ == "__main__":
    verify()
