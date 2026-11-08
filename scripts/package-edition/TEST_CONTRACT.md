# Arch integration test contract

Installation ID remains `de.agentcodi.pkg`, minimum API 29, target SDK 28 and
Full access. Arch Terminal 0.2.0 is imported unchanged at
`5c51fdfcec0bf55349b61b6dc3fc49d1923438c3`. The retired APT/catalog and custom
PTY renderer/session implementation are excluded from this build.

| Layer | Coverage |
| --- | --- |
| Source/architecture | All upstream file hashes, compile-only dependency hashes, native/runtime boundaries, removed APT/PTY implementations and one shared APK payload contract. |
| Java host | Codex/chat/approval/file APIs, preserved legacy data and executable argument-preserving Arch wrappers, quoting, compatibility preferences and rejected linked launchers. |
| C++ host | Supervisor environment/lifecycle/framing, PNG validation, workspace readers/imports and the host-only system-shell protocol fixture. The supervisor suites require the disposable CI `/system` shim. |
| Arch adapters | Codex Bash and stdio MCP argv, shared workspace binds, guest environment cleanup, absent-install failure, upstream LaunchSpec compatibility, passwd shell discovery and SIGTERM-to-PRoot-cleanup cancellation. |
| Upstream Arch | Unchanged native PTY/extractor/hardlink, Java, VT emulator and lint tests in isolated source copies. |
| Android compile/DEX | Combined sources/resources and both resource namespaces; Java 8 lambda desugaring, target/minimum SDK, manifest classes, identity and update signer stability. |
| Android UI | Terminal navigation to Arch without starting Codex or downloading packages; existing chat/editor suites on API 29 and 35. |
| APK/Bionic | Existing pinned Codex and code-mode host/schema/protocol smoke; actual Bionic shell lookup interception, commands, Full access, PTY API and stdio MCP environment. No large guest image is included in this protocol-only smoke. |
| Final APK | Exact eleven native files, every staged byte, ARM64, 16 KiB segments, signature, original Arch/Codex/libc++/zlib notices, pinned rootfs lock and MPL source material. |
| Optional rootfs smoke | Manual Arch Terminal runtime workflow input downloads the approximately 791 MiB pinned rootfs and runs the upstream PRoot/QEMU smoke. |
| Physical Android | Installation/update, keyboard, notifications/services, actual Arch installation, pacman, simultaneous terminal/Codex workspace edits and long-running/cancelled commands remain device checks. |

The APK workflow retains its container and signing path. Its reusable bootstrap
dependency now builds Arch native runtime files instead of APT packages. Debug,
release and reusable runtime branch gates temporarily allow
`Mcpasi/Fusion_Arch_Termux`; TODO comments require returning to `main` afterward.

The runtime artifact includes original Arch application/native source archives
and the complete combined AGENTCODI source bundle. Distribute those archives
with the combined GPL-3.0-or-later APK; original AGENTCODI Apache notices remain.
Historical APT/device results do not constitute Arch integration validation.
