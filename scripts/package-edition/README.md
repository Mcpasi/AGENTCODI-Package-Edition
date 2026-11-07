# APK payload contract

The package manager, APT bootstrap, recipes, catalog, repository signing key and
publication workflows formerly in this directory have been removed. The directory
name is retained for the existing APK verifier entry point.

`apk-contract.json` defines the exact eleven ARM64 native payloads, resource
notices and asset paths. `verify-apk-contract.py` compares every final APK byte
against staged inputs and validates Community Codex/MPL and Arch Terminal source,
rootfs and original license evidence. Extra ABIs/assets, duplicate ZIP members,
retired APT payloads and changed native/legal bytes fail verification.

See [Arch Terminal integration](../arch-terminal/README.md) for runtime builds,
workspace mapping, pacman and corresponding sources, and
[the test contract](TEST_CONTRACT.md) for validation coverage.
