## AGENTCODI Package Editon 0.1.1

- Add a complete Simplified Chinese interface, including dialogs, accessibility
  labels, runtime status and notifications. Offer 简体中文 in Settings and Android
  app-language settings, and detect Simplified Chinese device locales (including
  `zh-Hans`, `zh-CN` and `zh-SG`) using the same language resolution throughout.
  Explicit script tags take precedence over regions; unsupported Traditional
  Chinese device locales retain the English fallback. 

- Fix single-file inspection/export following a replaced package root symlink.
  Reject noncanonical roots and pass the original selected root to the no-follow
  opener, preventing exports from a symlink target outside the selected area.

- Fix ZIP export accepting an in-place file change when size, inode and original
  modification time are preserved. Retain the opened file's Unix change time
  (`ctime`) in Java/native metadata and compare it across archive snapshots.

Source code and license information:

[GPL/LGPL source code access with download instructions](https://github.com/Mcpasi/AGENTCODI/blob/0e97641f60c9100e12167ebc620bfe27b52a3916/scripts%2Fpackage-edition%2FREADME.md)

[Public source directory with checksums](https://mcpasi.github.io/AGENTCODI/apt/package-edition/dists/stable/repository-manifest.json)

[Fixed Bootstrap source manifest for the current APK](https://mcpasi.github.io/AGENTCODI/apt/package-edition/sources/manifests/aeb150ee111286e49c794f6838bd7e36b4f90decb194cb9a51fc35bd8a89a80a.json)

[GPL-2.0 License](https://spdx.org/licenses/GPL-2.0-only.html)
[GPL-3.0 License](https://spdx.org/licenses/GPL-3.0-only.html)
[LGPL-2.1 License](https://spdx.org/licenses/LGPL-2.1-only.html)
[LGPL-3.0 License](https://spdx.org/licenses/LGPL-3.0-only.html)
