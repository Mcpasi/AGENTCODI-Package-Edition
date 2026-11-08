# CI and portable checks

Release documentation targets **Package Edition 0.1.9 (in preparation)**.
The version bump is a separate release step; APKs are built on GitHub.

AGENTCODI keeps its existing Android identity, ARM64/Bionic APK container, Codex
runtime pins and signing paths. Arch Terminal replaces the former custom PTY,
APT bootstrap and package catalog. The unchanged upstream snapshot is checked
against `third_party/arch-terminal.lock.json` before use.

| Job or driver | Checks |
| --- | --- |
| `scripts/check-architecture.sh` | Module boundaries, app identity/security contracts, pinned upstream and compile-only inputs, retired APT/PTY code and final APK source contract. |
| `run-java-tests.sh` | Portable Java chat/protocol/approval/file suites, complete quoted-credential masking across streamed chunks and tool sections, plus actual Arch command-wrapper execution, quoting and hardlink rejection. Uses JDK 17. |
| `run-cpp-tests.sh` | Seven native supervisor, framing, lifecycle and file suites. Uses `host_shell_fixture.cpp` only as a protocol peer. |
| `test-arch-terminal.py` | Native adapter argv/binds/environment, shared workspace, missing installation, shell lookup, cancellation, immutable upstream sources and temporary branch gates. |
| `compile-android-sources.sh` | Combined resources and three Java namespaces, every manifest component, Java 8 lambda DEX desugaring and stable APK update signer. CI uses API 35; an installed local platform can be selected with `AGENTCODI_ANDROID_PLATFORM_VERSION`. |
| `run-chat-ui-tests.sh` | Existing chat/editor views and Arch navigation on Robolectric API 29 and 35. |
| `scripts/arch-terminal/test-upstream.sh` | Unchanged upstream native, Java, VT emulator and lint tests, in an isolated temporary copy with SDK 36 and NDK r28c. |
| Community runtime jobs | Existing release hashes, code-mode host and generated schema/RPC/approval checks against the real ARM64/Bionic Codex runtime. |
| `test-apk-contract.py` | Mutation tests for exact ABI/payload sets, staged bytes, original Arch/Codex/LLVM notices, pinned guest lock and Community/MPL source evidence. |

Portable commands that need no Android SDK:

```sh
bash .github/ci/run-java-tests.sh
python3 -B .github/ci/test-arch-terminal.py
python3 -B .github/ci/test-apk-contract.py
bash scripts/check-architecture.sh
```

The C++ supervisor tests expect canonical `/system/bin/sh` and `/system/lib64`.
Hosted CI creates those through `setup-system-shim.sh` on its disposable runner.
Do not create them on a development host just to run the suites; workspace,
protocol-parser and upstream native tests can be run independently. The complete
Android runtime and APK assembly remain GitHub jobs.

## Build flow

The APK workflows retain their `bootstrap` reusable dependency, now backed by
`bootstrap.yml`'s Arch Terminal native-runtime build on an x86_64 runner. It builds
upstream PRoot/talloc/libarchive from pinned source archives using NDK r28c.
`agentcodi-arch-terminal` supplies native files, license assets, rootfs/source
locks and corresponding source archives to the existing ARM64 APK container.

The APK job continues restoring `.github/ci/build-inputs.tsv`, checking private
mirror access and hashes, building the container and invoking
`scripts/build-debug-apk.sh` or the existing release-signing entry point. The
pinned LLVM toolchain, Community Codex binary/host relocation and schema hashes,
image/runtime dependencies and signing identities remain unchanged.

APK assembly compiles upstream Java plus AGENTCODI adapters into a separate JAR,
merges resources without modifying upstream files, packages the declared eleven
native files and compares final bytes against `apk-contract.json`. A real Bionic
probe verifies shell lookup interception. The Codex protocol smoke uses an
explicit Android system-shell fixture and tests the Arch adapter PATH/stdin MCP
environment without downloading a large guest image. Source tests separately
exercise actual production adapters.

All Android Java compiles use pinned compile-only AndroidX annotations and SDK
lambda signature stubs; these inputs are excluded from the runtime payload.

## Branches, secrets and source delivery

Tests run on every branch and PR. APK and reusable runtime builds allow `main`
and temporarily `Mcpasi/Fusion_Arch_Termux`; TODO comments mark every exception
for removal after integration. Manual debug runs default to preflight only.
The old `source_run_id` reuse and APT/Pages publication flows are removed.

Existing `AGENTCODI_INPUTS_TOKEN` and `AGENTCODI_RELEASE_*` secrets retain their
purpose. See [setup](PACKAGE_EDITION_SETUP.md) and
[release signing](RELEASE_SIGNING.md). No local Git mutation, push or publication
is needed to prepare an integration change.

The runtime artifact contains complete combined application sources, unchanged
upstream application sources and exact native dependency archives with portable
checksums. Keep them with distributed GPL-3.0-or-later combined APKs. Original
Apache-2.0 AGENTCODI and third-party notices remain intact.

A manual `rootfs_smoke` option on **Arch Terminal runtime** runs the upstream
791 MiB rootfs/GPG/PRoot/QEMU check. Device installation/update, keyboard,
foreground service/notifications, pacman, actual Codex-to-Arch commands and
long-running sessions still require physical Android validation. See the
[test contract](../../scripts/package-edition/TEST_CONTRACT.md).
