# Local APK build

From the repository root:

```sh
./scripts/build-local.sh --check
./scripts/build-local.sh
```

The standalone driver builds the existing Android ARM64 APK on **Linux x86_64**,
without Docker, QEMU, an Android emulator or changes under `/system`. Existing
CI workflows and build scripts are unchanged. The driver reads their application
identity, Community Codex pins, runtime package hashes, platform and R8 pins as
data; it never executes `build-debug-apk.sh`.

## Prerequisites

- JDK 17, Python 3.12+, Bash, Git, curl, `dpkg-deb`, CMake 3.22+, make,
  `readelf` (binutils) and zip.
- Android SDK **Build Tools 35.0.0** (`aapt2`, `apksigner`, `zipalign`).
- Android NDK **r28c / 28.2.13676358**, also used by the pinned Arch Terminal build.
- `rg` and `dpkg-query` for the default architecture/Java host checks.

Select installed tools explicitly if necessary:

```sh
AGENTCODI_JAVA_HOME=/path/to/jdk-17 ./scripts/build-local.sh \
  --sdk /path/to/android-sdk --ndk /path/to/android-ndk-r28c
```

SDK discovery uses `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `~/Android/Sdk`, the
existing Buildozer installation or `.cache/android-sdk`. NDK discovery uses
`ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, the SDK's pinned side-by-side NDK,
Buildozer or `.cache/android-ndk-r28c`. Explicit paths/environment variables take
precedence and fail clearly if invalid. No tools are installed automatically and
no SDK licences are accepted on your behalf.

## Inputs, outputs and checks

The default build runs the existing architecture checks and Java host tests.
`--skip-tests` skips those checks when they have already been run separately.
The Java driver is reused from `.github/ci/run-java-tests.sh`; no Actions workflow,
GitHub runner or CI-produced artifact is required. C++ supervisor tests requiring
the `/system` shim are separate from this build.

Only the pinned Community archive, ARM64 `libc++` and zlib packages, Android API 35
platform JAR, R8 JAR and Arch native source archives are downloaded and verified.
The seven build-only Termux `aapt2` packages are unnecessary. APK inputs are cached
under `.cache/android` (`--cache-dir` or `AGENTCODI_CACHE_DIR`); the existing Arch
source fetcher keeps its verified archives in `.cache/arch-terminal/sources`. If an old upstream
input is unavailable, an authenticated `gh` can use the existing private backup;
`AGENTCODI_INPUTS_REPO` / `AGENTCODI_INPUTS_TAG` override its repository/tag.
Existing `AGENTCODI_CODEX_ARCHIVE` / `AGENTCODI_CODEX_SOURCE_DIR` selection applies.

The driver builds Arch Terminal in a temporary copy of its verified snapshot.
The native engine/adapters use NDK r28c instead of the CI Termux LLVM 21.1.8
toolchain, and link the same pinned ARM64 C++ and zlib runtimes. Locally generated
native bytes can therefore differ from the CI build. The Codex/host relocation,
zlib bytes, app identity and signing identity keep their existing pins.

Output defaults to `output/local-apk/` (`--output-dir`):

- Versioned and convenience APK names, each with a portable SHA-256 checksum.
- `package-apk-contract.json`, produced by the existing final-payload verifier.
- `local-build.json`, recording tools, pins and actual validation coverage.
- `sources/`, containing combined application sources, unchanged Arch sources,
  exact native input archives and `SOURCE-SHA256SUMS`, to accompany distribution.

Every shipped ELF is checked for ARM64, 16 KiB load alignment, resolved dynamic
dependencies and required JNI symbols. The final APK is checked for its exact
native/assets contract, staged bytes, notices, signer and Android identity.

**No Android ARM64 program is executed.** Schema generation, Bionic shell probes,
Codex app-server/code-mode smoke tests, optional ARM rootfs smoke and device tests
remain separate ARM64 CI/device checks. `local-build.json` records these omissions;
a local build does not claim the runtime coverage of the CI APK build.

## Release signing

Debug is the default and uses the existing stable public development signer.
`--variant release` supports the existing `AGENTCODI_RELEASE_KEYSTORE`,
`AGENTCODI_RELEASE_KEY_ALIAS`, `AGENTCODI_RELEASE_CERT_SHA256`,
`AGENTCODI_RELEASE_STORE_PASSWORD_FILE` and `AGENTCODI_RELEASE_KEY_PASSWORD_FILE`
configuration. Private files must be outside the checkout, non-empty, regular,
non-symlink, not hard-linked and accessible only to their owner.
`AGENTCODI_RELEASE_PASSWORD_MODE=prompt` allows interactive password entry.
The existing final APK release gate is applied; nothing is published or uploaded.

The local driver's offline regressions can be run with:

```sh
python3 -B scripts/local-build/test_build.py
```
