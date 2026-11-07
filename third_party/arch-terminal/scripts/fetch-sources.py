#!/usr/bin/env python3
"""Fetch immutable build inputs; a bad cache entry is never used."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()

def fix_tar_acl_modes(source):
    # PAX ACLs are read before the member header. They must not mark the final mode
    # as supplied: default-only ACLs start at 0000, and access ACLs lack set-id bits.
    # The tar header contains the complete mode, including the ACL's group mask.
    path = source / 'libarchive/archive_read_support_format_tar.c'
    before = 'if (!archive_entry_perm_is_set(entry)) {'
    after = ('if (!archive_entry_perm_is_set(entry)\n'
             '\t    || (archive_entry_acl_types(entry) & ARCHIVE_ENTRY_ACL_TYPE_POSIX1E)) {')
    text = path.read_text()
    if text.count(before) == 1:
        path.write_text(text.replace(before, after))
    elif text.count(after) != 1:
        raise RuntimeError('Unexpected libarchive tar mode parser; review the ACL compatibility fix.')

def main():
    lock = json.loads((ROOT / 'scripts/dependencies.lock.json').read_text())
    cache = ROOT / '.cache/sources'
    cache.mkdir(parents=True, exist_ok=True)
    for key in ('proot', 'talloc', 'libarchive'):
        item = lock[key]
        archive = cache / item['file']
        if not archive.exists() or digest(archive) != item['sha256']:
            part = archive.with_suffix(archive.suffix + '.part')
            print('Fetching', item['file'], flush=True)
            with urllib.request.urlopen(item['url'], timeout=60) as src, part.open('wb') as dst:
                shutil.copyfileobj(src, dst)
            if digest(part) != item['sha256']:
                part.unlink()
                raise RuntimeError('SHA-256 mismatch: ' + item['file'])
            part.replace(archive)
        destination = ROOT / '.build/sources' / key
        stamp = destination / '.verified-source'
        if stamp.exists() and stamp.read_text() == item['sha256']:
            continue
        if destination.exists():
            shutil.rmtree(destination)
        destination.mkdir(parents=True)
        # These archives are authenticated by the exact hash above.
        unpack = destination / '_unpack'
        unpack.mkdir()
        if archive.suffix == '.zip':
            with zipfile.ZipFile(archive) as source:
                source.extractall(unpack)
        else:
            with tarfile.open(archive) as source:
                source.extractall(unpack, filter='data')
        top, = unpack.iterdir()
        for child in top.iterdir():
            shutil.move(str(child), destination)
        shutil.rmtree(unpack)
        stamp.write_text(item['sha256'])
    fix_tar_acl_modes(ROOT / '.build/sources/libarchive')

if __name__ == '__main__':
    main()
