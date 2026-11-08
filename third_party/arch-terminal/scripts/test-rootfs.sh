#!/usr/bin/env bash
set -Eeuo pipefail
project="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$project"
test_root="${ARCH_TEST_ROOTFS_DIR:-$project/.build/rootfs-test}"
python3 scripts/verify-rootfs.py --download
python3 scripts/fetch-sources.py
cmake -S . -B .build/host -DCMAKE_BUILD_TYPE=Debug \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DLIBARCHIVE_SOURCE="$project/.build/sources/libarchive"
cmake --build .build/host -j"${ARCH_BUILD_JOBS:-1}"
if [ ! -f "$test_root/usr/bin/pacman" ]; then
  mkdir -p "$test_root"
  (umask 0077; .build/host/extract_test --deny-hardlinks .cache/rootfs/ArchLinuxARM-aarch64-latest.tar.gz "$test_root")
fi
scripts/build-host-proot.sh
mkdir -p .build/rootfs-config
javac -encoding UTF-8 -d .build/rootfs-config app/src/main/java/de/archterminal/TerminalLanguage.java app/src/main/java/de/archterminal/RootfsConfig.java tests/java/de/archterminal/ConfigureRootfs.java
if [ ! -f "$test_root/.configured-for-test-0.1.3" ]; then
  if [ -f "$test_root/.configured-for-test" ]; then
    .build/host/extract_test --repair-modes "$test_root"
  fi
  java -cp .build/rootfs-config de.archterminal.ConfigureRootfs "$test_root"
  touch "$test_root/.configured-for-test-0.1.3"
fi
java -cp .build/rootfs-config de.archterminal.ConfigureRootfs "$test_root" --upgrade
if [ "$(uname -m)" != aarch64 ]; then
  archive=.cache/tools/qemu-user-static_7.2.deb
  mkdir -p .cache/tools .build/qemu
  if [ ! -f "$archive" ]; then
    curl -fL --retry 3 https://deb.debian.org/debian/pool/main/q/qemu/qemu-user-static_7.2+dfsg-7+deb12u18+b3_amd64.deb -o "$archive"
  fi
  printf '%s  %s\n' c3e3ba2bd87f8c5b9a5da5ef21b5a3b82d7c63b89dd448d9ddaa4eabc5b6e402 "$archive" | sha256sum -c -
  if [ ! -x .build/qemu/usr/bin/qemu-aarch64-static ]; then
    bsdtar -xf "$archive" -C .build/qemu data.tar.xz
    tar -xf .build/qemu/data.tar.xz -C .build/qemu ./usr/bin/qemu-aarch64-static
  fi
  qemu=(-q "$project/.build/qemu/usr/bin/qemu-aarch64-static")
else qemu=(); fi
mkdir -p "$test_root/dev" "$test_root/proc" "$test_root/sys" "$test_root/tmp"
export PROOT_TMP_DIR="$test_root/tmp"
export PROOT_LOADER="$project/.build/proot-host/src/loader/loader"
export PROOT_NO_SECCOMP=1
mkdir -p output/tests
.build/proot-host/src/proot --kill-on-exit --link2symlink -0 "${qemu[@]}" \
  -r "$test_root" -w /root -b /dev -b /proc \
  /usr/bin/env -i PATH=/usr/bin HOME=/root LANG=C.UTF-8 TERM=xterm-256color /bin/bash -c \
  'set -e; uname -m; bash --version | head -1; pacman --version; pacman -Q bash pacman archlinuxarm-keyring; test -e /lib/ld-linux-aarch64.so.1; printf "ARCH_ROOTFS_SMOKE_OK\n"' \
  | tee output/tests/rootfs-host.txt
grep -q ARCH_ROOTFS_SMOKE_OK output/tests/rootfs-host.txt
echo 'Host PRoot/ARM64 rootfs smoke passed. This does not substitute Android kernel/SELinux tests.'
if [ "${1:-}" = --network ]; then
  # The fixture is disposable. Keep partial package transactions out of the user's live rootfs.
  rm -f "$test_root/etc/resolv.conf"
  cp /etc/resolv.conf "$test_root/etc/resolv.conf"
  .build/proot-host/src/proot --kill-on-exit --link2symlink -0 "${qemu[@]}" \
    -r "$test_root" -w /root -b /dev -b /proc \
    /usr/bin/env -i PATH=/usr/bin HOME=/root LANG=C.UTF-8 TERM=xterm-256color /bin/bash -c \
    'set -e; pacman-key --init; pacman-key --populate archlinuxarm; pacman -Syy --noconfirm --needed git mc nodejs npm tree; pacman -Q git mc nodejs npm tree; test -x /usr/bin/git; node --version; npm --version; mc --version; tree --version; printf "ARCH_PACKAGE_INSTALL_OK\n"' \
    2>&1 | tee output/tests/rootfs-network.txt
  grep -q ARCH_PACKAGE_INSTALL_OK output/tests/rootfs-network.txt
  if grep -q 'directory permissions differ' output/tests/rootfs-network.txt; then
    echo 'Package installation reported differing directory permissions.' >&2
    exit 1
  fi
fi
