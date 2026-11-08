# AGENTCODI Package Edition

<div align="center">

### Work with Codex and development tools on your Android device

[Download](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases) · [Website](https://devsblog.com/) · [Report a bug](https://github.com/Mcpasi/AGENTCODI-Package-Edition/issues) · [Changelog](CHANGELOG.md) · [Standard Edition](https://github.com/Mcpasi/AGENTCODI)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Next release](https://img.shields.io/badge/Next%20release-0.1.9-blue)
![Status](https://img.shields.io/badge/Status-experimental%20prerelease-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

AGENTCODI Package Edition lets you work on code from your phone or tablet. Ask Codex to explain a project, change files or run commands, then follow its progress in the chat. You can also edit code yourself in the built-in editor, use the terminal and install tools such as Python, Node.js and Git.

Package Edition **0.1.9 is in preparation**. It embeds
[Arch Terminal](https://github.com/Mcpasi/Arch_Terminal) 0.2.0 unchanged and
replaces the former APT bootstrap, package catalog and terminal.
Published 0.1.6 APKs predate this migration; the Arch instructions below apply to
new builds from this source branch.

The terminal, project files and development tools run on your device. Codex model requests use an online service and require an internet connection and sign-in. You can use the app without installing Termux or setting up a separate gateway.

Package Edition installs alongside [regular AGENTCODI](https://github.com/Mcpasi/AGENTCODI), with its own files, settings and sign-in.

## Screenshots

<p align="center">
  <img src="screenshots/Screenshot_20261015_024541_AGENTCODI%20Package.jpg" alt="Codex chat planning a package installation" width="30%">
  <img src="screenshots/Screenshot_20261015_024547_AGENTCODI%20Package.jpg" alt="Codex chat with completed commands and package verification" width="30%">
  <img src="screenshots/Screenshot_20261017_221255_AGENTCODI%20Package.jpg" alt="Generated image displayed inline in the Codex chat" width="30%">
</p>
<p align="center">
  <img src="screenshots/Screenshot_20261015_024629_AGENTCODI%20Package.jpg" alt="Built-in terminal running ripgrep" width="30%">
  <img src="screenshots/Screenshot_20261015_024708_AGENTCODI%20Package.jpg" alt="Workspace editor with JavaScript syntax highlighting" width="30%">
</p>

## What you can do

- **Work with Codex:** start chats and follow responses, plans, commands and file changes as they arrive.
- **Read formatted responses:** view selectable native Markdown in answers, reasoning and plan cards, with code blocks, tables, lists and clickable links.
- **View images in chat:** see generated Codex images and image-view results directly in the conversation, even when technical tool details are collapsed.
- **Use a terminal:** run commands yourself, install packages and work with the same tools Codex uses.
- **Edit code yourself:** open workspace files or create new ones, with syntax highlighting for JavaScript, HTML, CSS and Python.
- **Bring in your files:** import files through Android's file picker, browse your workspace and export files or folders. A workspace is the app's folder for your project files.
- **Browse installed tools:** inspect and export package files in separate, read-only views.
- **Connect extra tools:** manage MCP servers, which give Codex access to additional tools. Each managed MCP tool request asks for your approval.
- **Choose your language:** the interface supports English, German and Simplified Chinese, with automatic device-language detection.

## Before you install

You need **Android 10 or newer**, an **ARM64 (arm64-v8a) device**, and an internet connection for Codex and package downloads.

> [!IMPORTANT]
> **Package Edition is for experienced users and always runs with Full access.**
>
> Codex and installed programs can read, change or delete files accessible to AGENTCODI, including its account data. Command approvals can ask you before an action, but they do not restrict programs to the project folder. Android still separates AGENTCODI from other apps. See [SECURITY.md](SECURITY.md) for details.

## Get started

1. For an Arch integration build, download the APK from a successful **APK** or **APK Release** Actions run on `Mcpasi/Fusion_Arch_Termux`. The [earlier 0.1.6 release](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases/tag/v.0.1.6.package.3) uses the former APT environment.
2. Open the APK on your device and install it. If Android asks, allow installation from the app you used to download or open it.
3. For an Arch integration build, open **Terminal** and choose **Arch installieren / fortsetzen**. The pinned first download is about 791 MiB; allow at least 5 GiB of free internal storage. Then go to **Settings** and tap **Start Codex runtime**. Terminal sessions also work independently of Codex.
4. In **Settings**, use **Sign in with ChatGPT** and finish in your browser, or enter an OpenAI API key using the dedicated account controls.
5. Tap **New chat**. You can ask Codex to create something, or use **Import files** to bring existing files into the workspace.

To run commands yourself, open **Terminal**. Install any development tools you need using the commands below.

Android may show an installation warning because the app targets an older Android API. This allows installed development tools to run from the app's writable storage; the app still requires Android 10 or newer.

## Versions

- **0.1.9 (in preparation):** Arch Terminal 0.2.0 with a shared Arch environment for terminal sessions, Codex and stdio MCP tools. Development tools are installed with pacman.
- **Fixed in 0.1.9:** generated Arch launchers reject hardlinks before changing another file's contents or permissions. Quoted passwords are fully masked in crash reports and chat, including streamed output and escaped quotes.
- **Verification:** 392 Java host tests, 19 Arch integration tests and architecture checks passed locally before the version bump. The 0.1.9 APK build and physical Android validation are pending.
- **Included since 0.1.6:** selectable native Markdown for answers, reasoning and plan cards, plus inline previews of generated images and image-view results.
- **Included since 0.1.5:** the workspace editor described below, a separate Package Edition icon and an Android freedom page in Settings.

The [changelog](CHANGELOG.md) has the full release notes. The [roadmap](ROADMAP-package-edition.md) tracks ongoing work and benchmark results.

## Install development tools

In **Terminal**, complete the Arch installation and initialize its keyring at the
first login. Update Arch and install tools with pacman:

```sh
pacman -Syu
pacman -S git python python-pip nodejs npm ripgrep
```

Remove a package with `pacman -R <package>` and inspect installed packages with
`pacman -Q`. Arch Terminal manages the mirrors and package signatures. The former
AGENTCODI APT repository and catalog are no longer part of this source build.

Terminal sessions and Codex commands share the same Arch installation and
workspace. The workspace is available at `/workspace` and at the original
absolute path used by AGENTCODI's editor and file APIs. Arch packages live inside
`files/arch`; the guest home is `/root`. Use a project virtual environment for
Python packages and the regular npm commands for Node packages. Restart the
Codex runtime after installing a new program needed as a direct stdio MCP command
so its host launcher is refreshed.

Old APT and `$HOME/.local` data remain available in the legacy read-only browser
areas for export. Their binaries are absent from the active command and library
paths and are not converted to pacman packages.

## Work with files

The file browser shows **Workspace** and read-only areas for **Earlier APT packages** and **Earlier user packages**. These retain files from previous APKs for export. Inspect the active Arch installation and its pacman packages in Terminal.

Imported files go into `workspace/imports`. Importing an archive or DEB file only copies it into the workspace; use the terminal to unpack or install it.

You can export individual files or folders through Android's file picker. Folders are exported as ZIP archives. Known credential paths, such as `auth.json` and `.ssh`, are excluded from package previews and exports. Review files before sharing them, especially if you have saved credentials under other names.

ZIP exports are useful for moving files. They do not preserve executable permissions, links or empty directories, so they are not a complete backup of installed packages.

### Workspace editor

The workspace editor is included in published APKs from 0.1.5 onward.

Open a text or code file in **Workspace** to edit it, or tap **New file** in a folder to start a file. Tap **Save** when you are done; a hardware keyboard also supports **Ctrl+S**. New files are created on their first save.

- JavaScript, HTML, CSS and Python have syntax highlighting.
- Editable files show line numbers and wrap long lines to fit the screen.
- Unsaved changes are marked. Leaving or refreshing offers **Save**, **Discard** or **Cancel**.
- If another program changes a file while you are editing, saving reports the conflict and keeps your draft.

The editor supports UTF-8 text files up to 1 MiB. Images, binary files, larger text files and installed package files open in read-only previews. Saving keeps the file's existing line endings, UTF-8 byte order mark and executable permissions. Save before exporting an edited file.

To run code you have saved, open **Terminal** in the file's folder and use the appropriate tool. For example, after `pacman -S python`, run a Python file with `python example.py`.

## Approvals and extra tools

In **Settings**, enable **Confirm file changes and commands** if you want Codex to ask before file changes and commands beyond recognized safe reads. Full access remains active.

Managed MCP servers ask before each tool call. The dialog shows the server, request and redacted parameters. **Allow** runs that call once; **Decline** or **Cancel** closes it without running it. MCP forms that need extra fields or URL-based input are currently unsupported.

## Updating the app

Install a newer release APK over the existing app to keep your files, settings and installed packages. For Arch integration builds, use `pacman -Syu` separately to update Arch packages.

If Android refuses an update because the signing certificate differs, you may have an older development build. Export the files you need before uninstalling it: uninstalling deletes the app's private data.

For a bug report, include the app version, Android version, device model and steps to reproduce it. Use [Issues](https://github.com/Mcpasi/AGENTCODI-Package-Edition/issues) for bugs and the reporting instructions in [SECURITY.md](SECURITY.md) for security concerns.

## Technical details

<details>
<summary>App identity and Android compatibility</summary>

Package Edition uses the application ID `de.agentcodi.pkg` and separate private storage from regular AGENTCODI.

It uses `targetSdk 28` to allow execution from its writable package directory. The minimum supported version is Android 10 / API 29.

The 0.1.9 release is being prepared. Android version metadata is updated
separately before the GitHub APK build.

</details>

<details>
<summary>Arch Terminal integration</summary>

The unchanged upstream snapshot is pinned at
`5c51fdfcec0bf55349b61b6dc3fc49d1923438c3` and checked with SHA-256 per file.
PRoot runs Arch Linux ARM aarch64 under Android without requiring a separate app
or root access. Rootfs downloads retain upstream's pinned size, SHA-256 and
installation transaction. PRoot is not a separate security boundary.

The [integration guide](scripts/arch-terminal/README.md) describes the shared
workspace, command adapters, native build and corresponding source artifacts.

</details>

<details>
<summary>Codex runtime and access model</summary>

The app pins the Community Android ARM64 runtime from [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux), release [v0.156.1-termux.1](https://github.com/DioNanos/codex-termux/releases/tag/v0.156.1-termux.1), based on OpenAI Codex `rust-v0.156.1`. Runtime archives, source revisions, native binaries, schema files and integration hashes are pinned and verified in CI.

Full access is the only runtime mode. Optional command/file confirmation uses Codex's `untrusted` policy. Programs installed through pacman, npm, pip or other tools run with AGENTCODI's app permissions. The workspace, `HOME`, `CODEX_HOME` and Arch rootfs are separate directories, but that separation does not isolate programs from account files.

</details>

<details>
<summary>Build and verification</summary>

The repository includes application sources, the unchanged Arch Terminal snapshot, build scripts and CI checks.

- [Package Edition roadmap](ROADMAP-package-edition.md)
- [Package build contract](scripts/package-edition/README.md)
- [Test contract](scripts/package-edition/TEST_CONTRACT.md)
- [CI guide and portable host drivers](.github/ci/README.md)
- [Repository setup and workflow inputs](.github/ci/PACKAGE_EDITION_SETUP.md)
- [Release signing](.github/ci/RELEASE_SIGNING.md)

The complete APK runner retains its pinned ARM64 build container. Portable checks can also run on a Linux host:

```sh
bash .github/ci/run-java-tests.sh
python3 -B .github/ci/test-arch-terminal.py
bash scripts/check-architecture.sh
```

APK builds run on `main` and temporarily on `Mcpasi/Fusion_Arch_Termux`, with TODO comments to restore main-only builds after integration. The Tests workflow checks all branches and pull requests. The reusable native-runtime job replaces the former APT bootstrap dependency; the existing APK container and signing path remain. CI checks app identity, DEX, APK contents, ARM64 compatibility, alignment, signing, Arch integration and source/license material.

</details>

## Source code and licenses

AGENTCODI is an independent open-source project, with no affiliation with or endorsement by OpenAI.

Original AGENTCODI code, tests, resources, build automation and documentation are copyright 2026 Pascal (Mc Pasi), licensed under [Apache 2.0](LICENSE). The combined APK including Arch Terminal is distributed under GPL-3.0-or-later; Arch Terminal and native input source bundles accompany the build artifacts. Third-party components retain their own licenses and copyright notices. See [NOTICE.md](NOTICE.md) and the notices in the app.

Complete original sources for the Codex runtime's MPL-2.0 components are included in the APK. Export them without an account or internet connection:

**Settings > Licenses and notices > Codex dependency notices > Save MPL sources**

Source and license documentation:

- [Community Codex dependency licenses and provenance](third_party/community-codex/README.md)
- [MPL source offer, archive hashes and upstream locations](third_party/community-codex/MPL-SOURCE-OFFER.txt)
- [Arch Terminal sources and notices](third_party/arch-terminal/NOTICE.md)
- [Package build contract](scripts/package-edition/README.md)
