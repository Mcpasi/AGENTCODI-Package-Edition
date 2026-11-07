#!/usr/bin/env python3
"""Merge resource inputs without modifying the pinned Arch Terminal snapshot."""
import argparse
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]


def prepare(destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError("Resource staging directory must be new")
    shutil.copytree(ROOT / "app/src/main/res", destination)
    upstream = ROOT / "third_party/arch-terminal"
    for relative in ("app/src/main/res/drawable", "third_party/termux/view-src/main/res/drawable"):
        for source in (upstream / relative).iterdir():
            target = destination / "drawable" / source.name
            if target.exists():
                raise ValueError("Resource collision: " + source.name)
            shutil.copy2(source, target)
    shutil.copy2(upstream / "third_party/termux/view-src/main/res/values/strings.xml",
                 destination / "values/arch_terminal_selection.xml")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    prepare(parser.parse_args().destination)
