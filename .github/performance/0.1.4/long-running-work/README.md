# Long-running work: reproduction and validation

The Package Edition interactive terminal now has no server or client lifetime
deadline. Output streams into the existing rolling display rather than exhausting
a session-wide capture quota. Human questions and command/file/MCP approvals
have a seven-day local deadline; explicit shorter user-input deadlines still win.
Administrative/control RPCs retain their finite deadlines.

These fixes were reproduced before changing the affected implementation. The
same eight fixtures were then rerun against all three original production classes
from commit `1f714a12260f673ee234803a1082430169f3ba0d`, without checking out or
changing a ref. [before.txt](before.txt) records eight failures;
[after.txt](after.txt) records eight passes. [sources.json](sources.json) binds
both source sets and the fixtures to SHA-256 hashes.

| Reproduced failure | Reproduction | Result after repair |
| --- | --- | --- |
| Terminal killed by its 30-minute server deadline | Replay the emitted `command/exec` parameters at 31 minutes of virtual elapsed time | `disableTimeout: true`, no conflicting `timeoutMs`, PTY remains live |
| Client waiter has an additional lifetime deadline | Observe the actual command thread waiting on its response latch | Untimed wait; Stop, interruption, connection failure and exit release it |
| Terminal ends after its capture quota | Replay 18 MiB across stdout/stderr with the server's per-stream capture rules | No lifetime quota, current output remains visible, older display output rolls out |
| Valid server output above 64 KiB is rejected | Replay a 73,025-byte block observed from the unchanged real server | Accept the server's 64-KiB target plus one 8-KiB native read; reject larger blocks |
| Human decisions receive a ten-minute local expiry | Project MCP, command, file and user-input requests and inspect their deadlines | Seven days locally; explicit decisions and expiry remain effective |
| One-hour server deadline is cut to ten minutes | Project `autoResolutionMs: 3600000` | Preserve the explicit one-hour deadline |
| Resolved requests retain scheduled tasks and request data | Inspect the queue of a stock Java scheduled executor after server resolution | Cancel and remove the deadline immediately |
| Already-running old timer cancels a new request reusing the same id | Hold the controller lock until the expired callback is blocked, resolve the request, reuse its id, then release the callback | Old timer cannot act on the new request |

Run with a JDK, preferably the same JDK 17 as CI:

```sh
python3 -B .github/ci/replay-long-running-work.py --baseline 1f714a12260f673ee234803a1082430169f3ba0d
# Expected: eight failures, exit status 1.
python3 -B .github/ci/replay-long-running-work.py
# Expected: eight passes, exit status 0.
.github/ci/run-java-tests.sh
scripts/check-architecture.sh
ANDROID_HOME=/path/to/android-sdk .github/ci/compile-android-sources.sh
```

The replay uses the same source list as the host suite. For the baseline, it
materializes only `CodexTerminalSession`, `CodexAppServerClient` and
`CodexSessionController` from Git into a temporary directory. The fixtures and
other sources remain identical. It does not lower production deadlines or add
non-protocol timeout fields. The full unmodified baseline passed 389 host tests;
the repaired implementation passes all 402. Additional lifecycle checks cover
natural exit and pending RPC release, connection failure, interruption, finite
administrative/streaming requests and oversized output. Existing checks continue
to cover input wiping, redaction, malformed Base64, PTY geometry, resize, rapid
restart, approval decisions, short expiry, turn Stop and runtime teardown.

The additional [native.json](native.json) probe uses the published Linux x86-64
app-server from OpenAI `rust-v0.156.1`. The package's Community runtime is pinned
to `DioNanos/codex-termux` commit `ea762071ec4acbf1531fcc7daf47524836f70a09`;
its upstream pin is `b412ff32c417f855c2b2d1581b77058eed87c84b`.
The two execution implementation files were fetched at both commits and found
byte-identical:

| Source file | SHA-256 at both commits |
| --- | --- |
| `codex-rs/app-server/src/command_exec.rs` | `73015751277a57d978d44dc047ff365858b5db1992d7405e49531372ea781057` |
| `codex-rs/app-server/src/request_processors/command_exec_processor.rs` | `41762b70772c7b2a08078e424bac4f011000bce6f6b73edb2d63293adfe7b867` |

The native probe requests a 150-ms deadline to reproduce the real server's
timeout mechanism quickly, separately from the unmodified 30-minute virtual
replay. It observes exit code 124, the old 8-MiB output cap and loss of the final
marker, then receives all 18 MiB plus that marker using `disableTimeout` and
`disableOutputCap`. A quiet unlimited PTY remains live and responds to explicit
termination. Streamed output is absent from the final buffered response. This
is a host execution/protocol check; it is not a physical Android run.

Download the pinned native probe into a temporary directory:

```sh
probe_dir="$(mktemp -d /tmp/agentcodi-native-probe.XXXXXX)"
curl --fail --location --output "$probe_dir/runtime.tar.gz" \
  https://github.com/openai/codex/releases/download/rust-v0.156.1/codex-app-server-x86_64-unknown-linux-musl.tar.gz
printf '1c1616d00db0387492abbeaa125b900012cb9375e2a7f51470bc934c7a3f14cf  %s\n' \
  "$probe_dir/runtime.tar.gz" | sha256sum -c -
tar -xzf "$probe_dir/runtime.tar.gz" -C "$probe_dir"
python3 -B .github/ci/test-long-running-terminal-runtime.py \
  --runtime "$probe_dir/codex-app-server-x86_64-unknown-linux-musl" \
  --output "$probe_dir/native.json"
```

The driver also checks the extracted binary's SHA-256 before executing it. It
uses an isolated home/workspace, makes only local `command/exec` calls, and does
not authenticate or start a model turn. Individual observations may vary in
chunk size because the server coalesces pending reads.

Reviewed paths outside these fixes: chat turns continue through notifications
after the short `turn/start` acknowledgement, and the UI does not stop the
shared foreground runtime when the chat/terminal screen is closed. The native
supervisor's two-second stop budget applies to explicit teardown. Existing MCP
tool budgets remain configurable. No further changes were made without a
reproduced failure. Screen lock, Doze, OEM process termination and prolonged
physical Android builds still require device tests; host checks cannot prove
their behavior. Version remains `0.1.4` / Android versionCode 7.
