#!/usr/bin/env python3
"""Restore unchanged APKs from verified Git-sized chunks."""
import argparse
import hashlib
import json
from pathlib import Path


def restore(root, output):
    output.mkdir(parents=True, exist_ok=True)
    for manifest_path in sorted((root / "releases").glob("*/release.json")):
        release = json.loads(manifest_path.read_text())
        for asset in release["assets"]:
            name = asset["name"]
            if Path(name).name != name:
                raise ValueError("Unsafe asset name")
            dest = output / name
            if dest.exists():
                raise FileExistsError(dest)
            part = dest.with_name(dest.name + ".restoring")
            checksum = hashlib.sha256()
            size = 0
            try:
                with part.open("xb") as stream:
                    for piece in asset["parts"]:
                        if Path(piece["file"]).name != piece["file"]:
                            raise ValueError("Unsafe chunk name")
                        data = (manifest_path.parent / piece["file"]).read_bytes()
                        if len(data) != piece["size"] or hashlib.sha256(data).hexdigest() != piece["sha256"]:
                            raise ValueError("APK chunk checksum mismatch")
                        stream.write(data)
                        checksum.update(data)
                        size += len(data)
                if size != asset["size"] or checksum.hexdigest() != asset["sha256"]:
                    raise ValueError("Complete APK checksum mismatch")
                part.replace(dest)
            except BaseException:
                part.unlink(missing_ok=True)
                raise
            print("Restored", name, checksum.hexdigest(), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("restored-apks"))
    args = parser.parse_args()
    restore(Path(__file__).resolve().parent, args.output.resolve())
