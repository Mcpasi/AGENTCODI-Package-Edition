# Arch Terminal integration

This guide covers **Package Edition 0.1.9 (in preparation)**. Version metadata
and the GitHub APK build are separate release steps.

AGENTCODI embeds the unchanged Arch Terminal 0.2.0 snapshot at
`5c51fdfcec0bf55349b61b6dc3fc49d1923438c3`. The snapshot is verified against
`third_party/arch-terminal.lock.json`; integration code lives outside it.

Open **Terminal** and install Arch before starting the Codex runtime. Arch's
download, extraction, pacman configuration, sessions, PTY and terminal renderer
come from the upstream project. No separate Arch Terminal APK is required.

Version 0.2.0 includes the terminal's persistent German/English language selector
and the updated pacman mirror migration. Both upstream translations are included
unchanged in the combined APK; running sessions survive a language change.

The adapters bind AGENTCODI's workspace at its original absolute path and at
`/workspace`. Both interactive terminals and Codex use the same installation,
pacman packages and guest `/root`. The Android Codex process and file APIs remain
native; commands and stdio MCP tools enter Arch through generated launchers.
The small app-server preload redirects Bionic passwd shell discovery to the Arch
Bash launcher. Loader environment variables are removed before entering Arch.
Generated launchers reject symbolic links and hardlinks before writing or
changing permissions, preserving the linked original file.

The former APT bootstrap/catalog is retired. Existing `files/usr` and HOME data
are preserved for export; they are absent from the active command/library paths.
They are not automatically converted to Arch packages.

The reusable `bootstrap.yml` workflow builds Arch's native runtime on x86_64 with
NDK r28c. The existing ARM64 APK container consumes `agentcodi-arch-terminal`
and otherwise keeps its pinned Codex runtime, toolchain and signing paths.
Debug and signed-release builds are temporarily enabled for
`Mcpasi/Fusion_Arch_Termux`; remove that exception after completion and keep
`main` as the build branch.

Portable tests cover source integrity, argv, quoting, shared paths, environment
cleanup, default-shell discovery and failure handling. Upstream native and Java
tests run in CI. The large rootfs test is available through manual workflow input.
Physical Android installation, keyboard and long-running session behavior still
need device validation.

The combined APK is distributed under GPL-3.0-or-later, while original AGENTCODI
files retain their Apache-2.0 license. The workflow supplies combined application
sources and upstream native input archives with the APK. Include these source
artifacts when distributing the binary. See the bundled Arch Terminal notices.
