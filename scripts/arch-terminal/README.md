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
Debug and signed-release builds run on `main`.

Portable tests cover source integrity, argv, quoting, shared paths, environment
cleanup, default-shell discovery and failure handling. Upstream native and Java
tests run in CI. The large rootfs test is available through manual workflow input.
Physical Android installation, keyboard and long-running session behavior still
need device validation.

Arch Terminal's main application code is authored by Mcpasi and licensed under
GPL-3.0-or-later. Its PRoot, talloc, libarchive and Termux terminal emulator/view
components retain their original authorship, licenses and copyright notices.
The original component attributions are preserved in
[`third_party/arch-terminal/NOTICE.md`](../../third_party/arch-terminal/NOTICE.md).
The license texts accompany the unchanged source snapshot, including Arch
Terminal's [`LICENSE`](../../third_party/arch-terminal/LICENSE), and are bundled
in the APK's `assets/licenses/`.

AGENTCODI remains licensed under Apache 2.0 for its original code, tests,
resources, build automation and documentation. Integrating Arch Terminal does
not change that license. The combined APK is distributed under
GPL-3.0-or-later. The workflow supplies combined application sources and upstream
native input archives with the APK. Include these source artifacts when
distributing the binary.
