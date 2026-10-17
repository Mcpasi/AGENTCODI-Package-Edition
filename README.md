# AGENTCODI Package Edition

<div align="center">

### Work with Codex and development tools on your Android device

[Download](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases) · [Website](https://devsblog.com/) · [Report a bug](https://github.com/Mcpasi/AGENTCODI-Package-Edition/issues) · [Changelog](CHANGELOG.md) · [Standard Edition](https://github.com/Mcpasi/AGENTCODI)

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Architecture](https://img.shields.io/badge/Architecture-ARM64-555555)
![Release](https://img.shields.io/badge/Published-0.1.5-blue)
![Status](https://img.shields.io/badge/Status-experimental%20prerelease-orange)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

AGENTCODI Package Edition lets you work on code from your phone or tablet. Ask Codex to explain a project, change files or run commands, then follow its progress in the chat. You can also edit code yourself in the built-in editor, use the terminal and install tools such as Python, Node.js and Git.

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

1. Download the APK from the [latest published release, 0.1.5](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases/tag/V.0.1.5-package). Under **Assets**, choose `AGENTCODI-Package-0.1.5-arm64-v8a-release.apk`.
2. Open the APK on your device and install it. If Android asks, allow installation from the app you used to download or open it.
3. Open AGENTCODI, go to **Settings** and tap **Start Codex runtime**. This starts the local service used by the chat and terminal.
4. In **Settings**, use **Sign in with ChatGPT** and finish in your browser, or enter an OpenAI API key using the dedicated account controls.
5. Tap **New chat**. You can ask Codex to create something, or use **Import files** to bring existing files into the workspace.

To run commands yourself, open **Terminal**. Install any development tools you need using the commands below.

Android may show an installation warning because the app targets an older Android API. This allows installed development tools to run from the app's writable storage; the app still requires Android 10 or newer.

## Versions

- **Available to download: 0.1.5.** Published on October 14, 2026 as an experimental prerelease, tagged `V.0.1.5-package`. Device tests passed on physical Android hardware.
- **Included in 0.1.5:** the workspace editor described below, a separate Package Edition icon and an Android freedom page in Settings.

The [changelog](CHANGELOG.md) has the full release notes. The [roadmap](ROADMAP-package-edition.md) tracks ongoing work and benchmark results.

## Install development tools

The app sets up its package manager on first start. Open **Terminal** and run:

```sh
apt update
apt install python nodejs-lts npm git ripgrep
```

This installs Python, Node.js, npm, Git and ripgrep, a tool for searching text in files. You can install just the packages you need.

To update installed APT packages:

```sh
apt update
apt upgrade
```

To remove one:

```sh
apt remove <package>
```

Replace `<package>` with its name. Install APT packages from the app's signed repository; packages built for Termux may contain paths that do not work in AGENTCODI.

<details>
<summary>npm, Python and package paths</summary>

Install an npm tool:

```sh
npm install -g <npm-package>
```

Install a Python package for your user:

```sh
python -m ensurepip --user
python -m pip install --user <python-package>
```

Or create a Python environment for a project:

```sh
python -m venv .venv
.venv/bin/python -m pip install <python-package>
```

Replace the names in angle brackets with the package you want.

APT packages live under `$PREFIX`, normally `/data/data/de.agentcodi.pkg/files/usr`. Global npm tools and Python user packages use `$HOME/.local`. Use `dpkg-query -W` to list installed APT packages.

If a command is missing or resolves to an unexpected location, open **Terminal > Package diagnostics**. It shows installed APT packages, command locations and environment paths without changing anything.

</details>

## Work with files

The file browser has three areas: **Workspace** for your project files, **APT packages** for installed system tools, and **User packages** for tools installed under `$HOME/.local`. Both package areas are read-only.

Imported files go into `workspace/imports`. Importing an archive or DEB file only copies it into the workspace; use the terminal to unpack or install it.

You can export individual files or folders through Android's file picker. Folders are exported as ZIP archives. Known credential paths, such as `auth.json` and `.ssh`, are excluded from package previews and exports. Review files before sharing them, especially if you have saved credentials under other names.

ZIP exports are useful for moving files. They do not preserve executable permissions, links or empty directories, so they are not a complete backup of installed packages.

### Workspace editor

The workspace editor is included in the published 0.1.5 APK.

Open a text or code file in **Workspace** to edit it, or tap **New file** in a folder to start a file. Tap **Save** when you are done; a hardware keyboard also supports **Ctrl+S**. New files are created on their first save.

- JavaScript, HTML, CSS and Python have syntax highlighting.
- Editable files show line numbers and wrap long lines to fit the screen.
- Unsaved changes are marked. Leaving or refreshing offers **Save**, **Discard** or **Cancel**.
- If another program changes a file while you are editing, saving reports the conflict and keeps your draft.

The editor supports UTF-8 text files up to 1 MiB. Images, binary files, larger text files and installed package files open in read-only previews. Saving keeps the file's existing line endings, UTF-8 byte order mark and executable permissions. Save before exporting an edited file.

To run code you have saved, open **Terminal** in the file's folder and use the appropriate tool. For example, after `apt install python`, run a Python file with `python example.py`.

## Approvals and extra tools

In **Settings**, enable **Confirm file changes and commands** if you want Codex to ask before file changes and commands beyond recognized safe reads. Full access remains active.

Managed MCP servers ask before each tool call. The dialog shows the server, request and redacted parameters. **Allow** runs that call once; **Decline** or **Cancel** closes it without running it. MCP forms that need extra fields or URL-based input are currently unsupported.

## Updating the app

Install a newer release APK over the existing app to keep your files, settings and installed packages. Use `apt update` and `apt upgrade` separately to update APT packages.

If Android refuses an update because the signing certificate differs, you may have an older development build. Export the files you need before uninstalling it: uninstalling deletes the app's private data.

For a bug report, include the app version, Android version, device model and steps to reproduce it. Use [Issues](https://github.com/Mcpasi/AGENTCODI-Package-Edition/issues) for bugs and the reporting instructions in [SECURITY.md](SECURITY.md) for security concerns.

## Technical details

<details>
<summary>App identity and Android compatibility</summary>

Package Edition uses the application ID `de.agentcodi.pkg` and separate private storage from regular AGENTCODI.

It uses `targetSdk 28` to allow execution from its writable package directory. The minimum supported version is Android 10 / API 29.

The published 0.1.5 APK and current source use Android `versionCode 8`.

</details>

<details>
<summary>Package repository and compatibility</summary>

The signed APT repository is:

```text
https://mcpasi.github.io/AGENTCODI-Package-Edition/apt/package-edition
```

Pinned signing-key fingerprint:

```text
2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89
```

Packages are built for Android ARM64/Bionic and AGENTCODI's own installation prefix. Existing Termux DEBs can contain hard-coded paths such as `/data/data/com.termux/files/usr`, so compatibility is not automatic.

Version 0.1.2 uses the original APT endpoint. Published APKs from 0.1.3 onward migrate the original managed source to the dedicated repository on update, retaining installed packages and custom sources.

See the [package build contract](scripts/package-edition/README.md) for recipes, provenance and reconstruction instructions.

</details>

<details>
<summary>Codex runtime and access model</summary>

The app pins the Community Android ARM64 runtime from [DioNanos/codex-termux](https://github.com/DioNanos/codex-termux), release [v0.156.1-termux.1](https://github.com/DioNanos/codex-termux/releases/tag/v0.156.1-termux.1), based on OpenAI Codex `rust-v0.156.1`. Runtime archives, source revisions, native binaries, schema files and integration hashes are pinned and verified in CI.

Full access is the only runtime mode. Optional command/file confirmation uses Codex's `untrusted` policy. Programs installed through APT, npm, pip or other tools run with AGENTCODI's app permissions. The workspace, `HOME`, `CODEX_HOME` and package prefix are separate directories, but that separation does not isolate programs from account files.

</details>

<details>
<summary>Build and verification</summary>

The repository includes application sources, package recipes, build scripts and CI checks.

- [Package Edition roadmap](ROADMAP-package-edition.md)
- [Package build contract](scripts/package-edition/README.md)
- [Test contract](scripts/package-edition/TEST_CONTRACT.md)
- [CI guide and portable host drivers](.github/ci/README.md)
- [Repository setup and workflow inputs](.github/ci/PACKAGE_EDITION_SETUP.md)
- [Release signing](.github/ci/RELEASE_SIGNING.md)

Local runners require the Termux Android toolchain:

```sh
./scripts/test.sh
./scripts/build-debug-apk.sh
```

APK and package builds run on `main`. The Tests workflow also checks feature branches and pull requests. CI checks app identity, APK contents, ARM64 compatibility, alignment, signing, package bootstrap, source availability and license material.

</details>

## Source code and licenses

AGENTCODI is an independent open-source project, with no affiliation with or endorsement by OpenAI.

Original application code, tests, resources, build automation and documentation are copyright 2026 Pascal (Mc Pasi), licensed under [Apache 2.0](LICENSE). Third-party components retain their own licenses and copyright notices. See [NOTICE.md](NOTICE.md) and the notices in the app.

Complete original sources for the Codex runtime's MPL-2.0 components are included in the APK. Export them without an account or internet connection:

**Settings > Licenses and notices > Codex dependency notices > Save MPL sources**

Source and license documentation:

- [Community Codex dependency licenses and provenance](third_party/community-codex/README.md)
- [MPL source offer, archive hashes and upstream locations](third_party/community-codex/MPL-SOURCE-OFFER.txt)
- [APT package sources and reconstruction instructions](third_party/package-source-archives/README.md)
- [Package build contract](scripts/package-edition/README.md)
