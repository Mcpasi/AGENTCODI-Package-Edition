#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "$0")/android-env.sh"
cd "$ARCH_PROJECT_ROOT"
python3 scripts/fetch-sources.py
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -x "$toolchain/aarch64-linux-android28-clang" ]; then
  echo 'Android NDK r28c required. Run scripts/setup-android.sh or set ANDROID_NDK_HOME.' >&2
  exit 1
fi
if ! grep -q 'Pkg.Revision = 28.2.13676358' "$ANDROID_NDK_HOME/source.properties"; then
  echo 'The native build pins NDK 28.2.13676358 (r28c).' >&2
  exit 1
fi
native_build="$ARCH_PROJECT_ROOT/.build/android"
output="$ARCH_PROJECT_ROOT/app/build/generated/jniLibs/arm64-v8a"
mkdir -p "$native_build/talloc" "$native_build/proot" "$output"
cp scripts/talloc-replace.h "$native_build/talloc/replace.h"
cc="$toolchain/aarch64-linux-android28-clang"
"$cc" -O2 -fPIC -I"$native_build/talloc" -I.build/sources/talloc \
  -c .build/sources/talloc/talloc.c -o "$native_build/talloc/talloc.o"
"$toolchain/llvm-ar" rcs "$native_build/talloc/libtalloc.a" "$native_build/talloc/talloc.o"
make -C "$ARCH_PROJECT_ROOT/.build/sources/proot/src" \
  -j"${ARCH_BUILD_JOBS:-1}" CC="$cc" LD="$cc" \
  OBJCOPY="$toolchain/llvm-objcopy" OBJDUMP="$toolchain/llvm-objdump" STRIP="$toolchain/llvm-strip" \
  PROOT_UNBUNDLE_LOADER=/unused \
  HAS_LOADER_32BIT= \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -include $ARCH_PROJECT_ROOT/scripts/proot-compat.h -I. -I$ARCH_PROJECT_ROOT/.build/sources/proot/src -I$ARCH_PROJECT_ROOT/.build/sources/talloc" \
  LDFLAGS="-L$native_build/talloc -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384"
cp .build/sources/proot/src/proot "$output/libproot.so"
cp .build/sources/proot/src/loader/loader "$output/libproot-loader.so"
cmake -S . -B "$native_build/cmake" -G 'Unix Makefiles' \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_BUILD_TYPE=Release -DLIBARCHIVE_SOURCE="$ARCH_PROJECT_ROOT/.build/sources/libarchive"
cmake --build "$native_build/cmake" --target archterminal -j"${ARCH_BUILD_JOBS:-1}"
cp "$native_build/cmake/libarchterminal.so" "$output/libarchterminal.so"
"$toolchain/llvm-strip" --strip-unneeded "$output/libarchterminal.so" "$output/libproot.so"
mkdir -p app/build/generated/assets/licenses
cp scripts/rootfs.lock.json app/build/generated/assets/rootfs.lock.json
cp third_party/*COPYING third_party/*txt app/build/generated/assets/licenses/
cp third_party/termux/APACHE-2.0.txt third_party/termux/UPSTREAM-LICENSE.md app/build/generated/assets/licenses/
cp LICENSE NOTICE.md app/build/generated/assets/licenses/
