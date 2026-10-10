# Package Edition repository setup

Code, build recipes and public trust anchors now live in
`Mcpasi/AGENTCODI-Package-Edition` on `main`. The source repository's `main`
and `Mcpasi/package-edition` remain preserved. Existing signing keys were copied
as repository Actions Secrets; do not generate replacements for this move.

The next APK build uses:

```text
https://mcpasi.github.io/AGENTCODI-Package-Edition/apt/package-edition
```

Both the pinned bootstrap recipes and repository identity validator use this
address. On APK update, the app atomically replaces the exact original managed
source line in an existing `etc/apt/sources.list`. Installed package data,
the existing public keyring, additional source lines and customized sources
are retained. The published 0.1.2 APK continues to use the original endpoint
until it is updated.

## First publication and APK build

The repository owner activates Pages, starts publication/release workflows,
publishes the APK and performs device installation. The migration commit skips
automatic push workflows; only the Tests workflow is started separately to
validate the changes. Future relevant pushes retain their automatic builds.

1. In this repository's **Settings → Pages**, select **GitHub Actions** as the
   deployment source. If the `github-pages` environment restricts deployment
   branches, allow `main`.
2. Confirm that **Tests** is successful for the current `main` commit. The APT
   deployment requires those current checks.
3. Open **Actions → Package catalog → Run workflow** and select `main`:
   `source_run_id=0`, `repository_action=publish`,
   `repository_previous_run_id=0`. This builds the bootstrap and all four
   catalog groups from pinned sources, validates ARM64 runtime behavior,
   signs the complete repository and deploys the Pages site. The first run
   can take several hours.
4. Require a successful publication and HTTPS verification. The site root is
   replaced by the complete artifact containing `apt/package-edition`.
5. Open **Actions → APK Release → Run workflow** and select `main`. For
   `source_run_id`, use the successful Package catalog run from step 3 to reuse
   its verified bootstrap when inputs match, or use `0` for a fresh source
   build. Download the `agentcodi-package-release-apk` artifact and checksums.
   This workflow produces signed APK artifacts; publish the GitHub release
   separately, then install/update and test on the device.

For a signed snapshot without Pages deployment, select
`repository_action=build`. `none` builds/tests the catalog without a signed
repository snapshot. APT publication is an entry point of **Package catalog**;
**Signed APT repository** is its reusable workflow.

## Build reuse and later renewals

Run IDs belong to their GitHub repository. The historical bootstrap/catalog
run IDs from `Mcpasi/AGENTCODI` are deliberately removed from workflow defaults.
All new builds default to `0` and rebuild from the pinned recipes. Positive
`source_run_id` values must identify a successful `main` push or manual run
in this repository; unchanged inputs, successful source jobs, artifact
availability and payload hashes are checked before reuse.

The APK and APK Release workflows both expose `source_run_id`. For later
catalog/Release renewals, select the prior successful local catalog run and
`repository_action=publish`. Keep `repository_previous_run_id=0` to discover
the last actual Pages deployment in this repository automatically. The
seven-day Release validity still requires regular signed renewal.

**APT publication diagnostics** is manual and requires a positive `run_id`
from this repository. It no longer defaults to a historical failed source-repo
publication.

## Dependencies and secrets

The seven existing APT/APK signing Secrets and the separate build-mirror read
token are retained under their original names. The public APT fingerprint remains
`2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`; the application ID remains
`de.agentcodi.pkg`. Existing build version 0.1.3/versionCode 6 is already newer
than published 0.1.2/versionCode 5.

The private backup mirror remains `Mcpasi/agentcodi-build-inputs`, release
`0.7.6-preview.1`; it is not redistributed through this public repository.
All current `.github/ci/build-inputs.tsv` entries have SHA-256-pinned upstream
download URLs. The upstream fallback is used whenever mirror bytes are missing
or differ from the current pin (including the newer Community Codex archive).
At migration time, upstream had removed five pinned files: aapt2, fmt, libc++,
libexpat and libpng. Their exact bytes remain in the private mirror, so the
new repository needs `AGENTCODI_INPUTS_TOKEN` with **Contents: read** access
to that mirror. A separate one-off transfer workflow copies the existing
read token and verifies mirror access plus the required asset digests before
storing it. The initial signing-key transfer copied only signing Secrets.
APK workflows check this mirror access before starting expensive source builds.

Termux recipes, builder image, Android SDK/NDK and the Community Codex runtime
remain pinned to the original versions/hashes. The checked-in attr/acl source
archive URLs now point at this repository's preserved commit history and retain
their SHA-256 pins. The large `migration-backup` branch is archival; builds do
not require checking it out.
