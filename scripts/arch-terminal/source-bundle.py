#!/usr/bin/env python3
"""Bundle checked-in combined application sources alongside the native inputs."""
import hashlib
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[2]


def bundle():
    output = ROOT / "output/arch-terminal"
    output.mkdir(parents=True, exist_ok=True)
    # Read-only Git inventory: no add, checkout, commit, fetch or other mutation.
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode().split("\0")
    archive = output / "AGENTCODI-Arch-Terminal-source.tar.gz"
    with tarfile.open(archive, "w:gz") as target:
        for name in sorted(filter(None, names)):
            target.add(ROOT / name, arcname=name, recursive=False)
    checksums = []
    for source in sorted(output.glob("*.tar.gz")):
        checksums.append(hashlib.sha256(source.read_bytes()).hexdigest() + "  " + source.name)
    (output / "SOURCE-SHA256SUMS").write_text("\n".join(checksums) + "\n")
    print("Bundled combined AGENTCODI and Arch Terminal sources:", archive.name)


if __name__ == "__main__":
    bundle()
