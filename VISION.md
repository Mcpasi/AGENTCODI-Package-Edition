## AGENTCODI VISION

AGENTCODI is an AI that runs directly on your Android device's hardware. It operates within its own dedicated workspace, where it can write code, execute tests, and perform builds. It functions inside a proot userspace based on Arch Linux (ARM64) and utilizes `pacman` as its package manager.

This keeps all the packages you need for building projects in one single place.

AGENTCODI also features a built-in editor with syntax highlighting for HTML, CSS, JS, Python, and Java, allowing you to continue working on your code directly.

---

The idea for AGENTCODI emerged from various other experiments, ranging from solutions involving Termux or custom gateways. While those do work natively, the setup process, from installing Node.js to setting up Codex via NPM, can be quite overwhelming for a beginner. AGENTCODI eliminates all of that: simply launch the runtime, log in, and you’re ready to go.

In essence, AGENTCODI turns your smartphone into a full-fledged development machine.

Perhaps you should ask yourselves: Why shouldn't I just use my smartphone for software development? You can write code, have agents work directly on your data, and execute—or trigger the execution of—tests and builds; your smartphone is powerful enough for all of this. However, this is only made possible by specifically setting `targetSdkVersion = 28`. Doing so bypasses the W^X (Write XOR Execute) policy, allowing AGENTCODI to run in compatibility mode, which in turn permits the direct execution of downloaded binary files and scripts.

But this door, too, will eventually be closed and with it, the dream of using your device as a development machine will come to an end.