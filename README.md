# Package Edition migration backup

This branch preserves the complete original signed APT snapshot, APK releases, release notes, Git bundle and integrity reports for migration step 1. The application code is on [main](https://github.com/Mcpasi/AGENTCODI-Package-Edition/tree/main).

Read [MIGRATION.md](MIGRATION.md) for the dependency inventory and step 2 handoff. GitHub release asset uploads returned HTTP 401; these original bytes are preserved here through the available Git transport. This branch contains no workflow files.

Clone just this branch to restore the backups:

```sh
git clone --single-branch --branch migration-backup https://github.com/Mcpasi/AGENTCODI-Package-Edition.git AGENTCODI-backup
cd AGENTCODI-backup
sha256sum -c SHA256SUMS
python3 restore-apks.py --output restored-apks
```

APKs are split into 64 MiB pieces to stay below GitHub's per-file limit. The restoration script checks each piece and the complete original APK SHA-256 before publishing the file. No build or signing step is involved.

APT lives under `apt/package-edition/`, including all DEBs and corresponding sources. Both original signatures and every signed payload hash were verified. The unchanged snapshot expires on 2026-10-15 at 11:45:56 UTC and remains an archival backup thereafter. Existing APKs continue to use the original APT endpoint.

For application development, use `git clone --single-branch --branch main` to avoid downloading this large backup branch.
