#!/usr/bin/env python3
"""Inspect the actual APK, not just build configuration."""
from pathlib import Path
import os
import re
import struct
import subprocess
import sys
import zipfile

def verify(apk):
    sdk = Path(os.environ['ANDROID_HOME'])
    tools = sdk / 'build-tools/35.0.0'
    subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    config = (Path(__file__).resolve().parent.parent / 'app/build.gradle').read_text()
    version_code = re.search(r'\bversionCode\s+(\d+)', config).group(1)
    version_name = re.search(r"\bversionName\s+'([^']+)'", config).group(1)
    if f"versionCode='{version_code}' versionName='{version_name}'" not in badging:
        raise AssertionError('APK version does not match app/build.gradle')
    for value in ("package: name='de.archterminal'", "sdkVersion:'28'", "targetSdkVersion:'28'", "native-code: 'arm64-v8a'"):
        if value not in badging:
            raise AssertionError('APK manifest mismatch: ' + value)
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        for name in ('libarchterminal.so', 'libproot.so', 'libproot-loader.so'):
            data = archive.read('lib/arm64-v8a/' + name)
            if data[:5] != b'\x7fELF\x02' or struct.unpack_from('<H', data, 18)[0] != 183:
                raise AssertionError('Expected aarch64 ELF: ' + name)
            phoff = struct.unpack_from('<Q', data, 32)[0]
            phsize, phcount = struct.unpack_from('<HH', data, 54)
            for index in range(phcount):
                header = struct.unpack_from('<IIQQQQQQ', data, phoff + index * phsize)
                if header[0] == 1 and (header[7] < 16384 or (header[2] - header[3]) % 16384):
                    raise AssertionError('ELF is not 16 KiB page compatible: ' + name)
        if 'assets/rootfs.lock.json' not in names:
            raise AssertionError('Missing pinned rootfs manifest')
        if not any(name.startswith('assets/licenses/') for name in names):
            raise AssertionError('Missing license texts')
    print('APK verified: signature, alignment, target 28, ARM64 ELFs, 16 KiB segments, runtime assets.')

if __name__ == '__main__':
    verify(Path(sys.argv[1]))
