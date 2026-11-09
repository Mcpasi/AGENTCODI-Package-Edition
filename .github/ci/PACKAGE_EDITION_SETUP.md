# Repository build setup

AGENTCODI retains the existing application ID, signing identities, private
build-input mirror and ARM64 APK build container. The former APT repository,
package catalog and publication workflows are removed. No Pages deployment or
APT signing secrets are used by builds from `main`.

Debug and signed-release builds run on `main`. Push filters and
job/reusable-workflow gates restrict builds to `main`. Tests run on all
branches and pull requests.

## Runtime and APK artifacts

`bootstrap.yml` keeps the reusable dependency used by the APK workflows. It now
builds the unchanged pinned Arch Terminal 0.2.0 native runtime on an x86_64
runner with NDK r28c (`28.2.13676358`). The ARM64 APK job downloads
`agentcodi-arch-terminal` into `output/arch-terminal` and continues through the
existing build-input verification, container, Codex checks and signing steps.
There is no former APT `source_run_id` reuse path.

The runtime artifact contains PRoot, its loader, the native PTY/extractor,
original license assets and rootfs lock, upstream source/native-input archives,
combined application source archive and portable checksums. Retain the source
archives when distributing the APK. The combined application is GPL-3.0-or-later;
original AGENTCODI sources keep their Apache-2.0 notices.

Manual **APK** runs default to `preflight_only=true`; disable it to build the APK.
Relevant pushes build it automatically. **APK Release** uses the existing
private signing secrets. No release or artifact publication is performed by
local integration work. The **Arch Terminal runtime** workflow also offers an
optional large `rootfs_smoke` input.

## Existing secrets

`AGENTCODI_INPUTS_TOKEN` still reads the private
`Mcpasi/agentcodi-build-inputs` mirror at release `0.7.6-preview.1` when the
pinned Android build/Codex inputs are unavailable upstream. Both APK workflows
check access before expensive builds.

Private release signing uses the existing `AGENTCODI_RELEASE_*` secrets listed
in [RELEASE_SIGNING.md](RELEASE_SIGNING.md). Debug builds retain the public
AOSP development test identity. The integration changes neither identity.

See [the integration guide](../../scripts/arch-terminal/README.md),
[CI drivers](README.md) and [test contract](../../scripts/package-edition/TEST_CONTRACT.md).
