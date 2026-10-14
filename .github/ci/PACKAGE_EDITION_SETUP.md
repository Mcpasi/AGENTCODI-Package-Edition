# Package Edition repository setup

Code, build recipes and public trust anchors live in
`Mcpasi/AGENTCODI-Package-Edition` on `main`. Existing signing keys are retained
as repository Actions secrets; keep the same signing identities for updates.

GitHub Pages serves the signed APT repository at:

```text
https://mcpasi.github.io/AGENTCODI-Package-Edition/apt/package-edition
```

Both the pinned bootstrap recipes and repository identity validator use this
address. On APK update, the app atomically replaces the exact original managed
source line in an existing `etc/apt/sources.list`. Installed package data,
the existing public keyring, additional source lines and customized sources
are retained. Version 0.1.2 uses the original endpoint; published APKs from 0.1.3 onward
use this repository's endpoint and apply the migration on update.

## Workflow scope

APK, APK Release, Package catalog, source archive checks and Community license
research use this repository's `main`. The Tests workflow runs on all branches
and pull requests, including the Community runtime and package toolchain jobs.
Package bootstrap and Signed APT repository are reusable workflows called
locally. Artifact reuse and publication diagnostics query the current
`GITHUB_REPOSITORY` and validate the producer's repository and branch.
The temporary debug APK/bootstrap exception for the editor feature branch has
been removed; manual APK builds also select `main`.

## Catalog publication and APK build

GitHub Pages is configured with **GitHub Actions** as its deployment source.
Tests and the complete signed catalog publication succeeded in this repository
on 2026-10-10. Relevant pushes to `main` start their configured builds
automatically; manual runs use the inputs below. APK release publication
and physical device validation remain separate from CI.

[AGENTCODI Package Edition V0.1.5](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases/tag/V.0.1.5-package)
was published on 2026-10-14 as the latest experimental prerelease, tagged
`V.0.1.5-package` (Android `versionCode 8`), with the modular workspace editor.
The current source on `main` has the same version identity.
Physical Android device tests for version 0.1.5 passed.

1. Keep **Settings → Pages → GitHub Actions** as the deployment source. If the
   `github-pages` environment restricts deployment branches, allow `main`.
2. Confirm that **Tests** is successful for the current `main` commit. The APT
   deployment requires those current checks.
3. Open **Actions → Package catalog → Run workflow** and select `main`:
   `repository_action=publish`, `repository_previous_run_id=0`. Select a
   successful local catalog run for `source_run_id` to reuse matching source
   builds, or `0` to rebuild from pinned sources. The workflow validates ARM64
   runtime behavior, signs the complete repository and deploys the Pages site.
   Fresh compiler builds can take several hours.
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

Run IDs belong to their GitHub repository; runs from another repository cannot
be reused. All builds default to `0` and rebuild from the pinned recipes. Positive
`source_run_id` values must identify a successful `main` push or manual run
in this repository; unchanged inputs, successful source jobs, artifact
availability and payload hashes are checked before reuse.

The APK and APK Release workflows both expose `source_run_id`. For later
catalog/Release renewals, select the prior successful local catalog run and
`repository_action=publish`. Keep `repository_previous_run_id=0` to discover
the last actual Pages deployment in this repository automatically. The
seven-day Release validity still requires regular signed renewal.

**APT publication diagnostics** is manual and requires a positive `run_id`
from this repository.

## Dependencies and secrets

The seven APT/APK signing secrets and the separate build-mirror read token
use the existing names. The public APT fingerprint remains
`2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89`; the application ID remains
`de.agentcodi.pkg`. Published and current source version 0.1.5/versionCode 8 is newer than
version 0.1.4/versionCode 7, version 0.1.3/versionCode 6 and version
0.1.2/versionCode 5.

The private backup mirror remains `Mcpasi/agentcodi-build-inputs`, release
`0.7.6-preview.1`; it is not redistributed through this public repository.
All current `.github/ci/build-inputs.tsv` entries have SHA-256-pinned upstream
download URLs. The upstream fallback is used whenever mirror bytes are missing
or differ from the current pin (including the newer Community Codex archive).
Upstream no longer supplies five pinned files: aapt2, fmt, libc++, libexpat
and libpng. Their exact bytes remain in the private mirror, so the repository
needs `AGENTCODI_INPUTS_TOKEN` with **Contents: read** access to that mirror.
APK workflows check this mirror access before starting expensive source builds.

Termux recipes, builder image, Android SDK/NDK and the Community Codex runtime
remain pinned to the original versions/hashes. The checked-in attr/acl source
archive URLs point at this repository's preserved commit history and retain
their SHA-256 pins. Archival backups are not build inputs.

## Retained APK releases

The imported 0.1.0–0.1.2 release pages link to their unchanged signed APKs
in the original AGENTCODI repository. These APKs have not been re-uploaded
as release assets here. Use the download link in each release page;
the original signing identity and historical build evidence remain valid.
