#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
project="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$project"
python3 scripts/fetch-sources.py
mkdir -p .build/host-deps/talloc
cp scripts/talloc-replace.h .build/host-deps/talloc/replace.h
cc -O2 -fPIC -I.build/host-deps/talloc -I.build/sources/talloc -c .build/sources/talloc/talloc.c -o .build/host-deps/talloc/talloc.o
ar rcs .build/host-deps/talloc/libtalloc.a .build/host-deps/talloc/talloc.o
if [ ! -d .build/proot-host ]; then
  cp -a .build/sources/proot .build/proot-host
  make -C .build/proot-host/src clean HAS_LOADER_32BIT= >.cache/proot-host-clean.log 2>&1
fi
make -C .build/proot-host/src -j"${ARCH_BUILD_JOBS:-1}" HAS_LOADER_32BIT= PROOT_UNBUNDLE_LOADER=/unused \
  LD="cc -B$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin -fuse-ld=lld" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -include $project/scripts/proot-compat.h -I. -I$project/.build/sources/talloc" \
  LDFLAGS="-L$project/.build/host-deps/talloc -ltalloc -Wl,-z,noexecstack"
