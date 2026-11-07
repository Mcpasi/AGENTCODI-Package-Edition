#!/usr/bin/env python3
"""Verify the pinned image against SHA-256 and the official Arch Linux ARM key."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
FINGERPRINT = '68B3537F39A313B3E574D06777193F152BDBE6A6'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true', help='download the pinned image if not cached')
    args = parser.parse_args()
    lock = json.loads((ROOT / 'scripts/rootfs.lock.json').read_text())
    archive = ROOT / '.cache/rootfs/ArchLinuxARM-aarch64-latest.tar.gz'
    signature = archive.with_suffix(archive.suffix + '.sig')
    archive.parent.mkdir(parents=True, exist_ok=True)
    for file, url in ((archive, lock['url']), (signature, lock['signature_url'])):
        if not file.exists():
            if not args.download:
                raise SystemExit('Image/signature not cached; run with --download.')
            temporary = file.with_suffix(file.suffix + '.download')
            with urllib.request.urlopen(url, timeout=60) as src, temporary.open('wb') as dst:
                shutil.copyfileobj(src, dst)
            temporary.replace(file)
    if archive.stat().st_size != lock['size']:
        raise SystemExit('Rootfs size mismatch; do not use this archive.')
    with archive.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != lock['sha256']:
        raise SystemExit('Rootfs hash mismatch; the mirror may have changed latest. Verify and update the lock explicitly.')
    with tempfile.TemporaryDirectory() as directory:
        subprocess.run(['gpg', '--homedir', directory, '--batch', '--import', str(ROOT / 'scripts/archlinuxarm-signing-key.asc')], check=True, capture_output=True)
        result = subprocess.run(['gpg', '--homedir', directory, '--batch', '--status-fd', '1', '--verify', str(signature), str(archive)], check=True, text=True, capture_output=True)
        if '[GNUPG:] VALIDSIG ' + FINGERPRINT + ' ' not in result.stdout:
            raise SystemExit('Unexpected signing key')
    print('Rootfs verified:', actual, 'official fingerprint', FINGERPRINT)

if __name__ == '__main__':
    main()
