#!/usr/bin/env python3
"""Optional local PTY probe against the source-matched Codex 0.156.1 host binary.

No authentication, model turn or runtime download is required by this driver.
See .github/performance/0.1.4/long-running-work/README.md for the binary pin.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import signal
import subprocess
import sys
import tempfile
import threading
import time


BINARY_SHA256 = "2458e31a21b3d515a4bad6c4f9fc18f5856a2b5af2d84495241c6dd467d453b8"
MARKER = b"LONG_BUILD_COMPLETE"
OUTPUT_BYTES = 18 * 1024 * 1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    binary = arguments.runtime.resolve()
    digest = hashlib.sha256()
    with binary.open("rb") as runtime_bytes:
        for block in iter(lambda: runtime_bytes.read(1024 * 1024), b""):
            digest.update(block)
    assert digest.hexdigest() == BINARY_SHA256, "Unexpected host runtime"
    report = {"release": "rust-v0.156.1", "binary_sha256": BINARY_SHA256, "cases": []}

    with tempfile.TemporaryDirectory(prefix="agentcodi-long-work-") as root:
        environment = dict(os.environ, CODEX_HOME=root + "/codex", HOME=root + "/home")
        Path(environment["CODEX_HOME"]).mkdir()
        Path(environment["HOME"]).mkdir()
        with open(root + "/stderr.log", "w+") as errors:
            process = subprocess.Popen([str(binary), "--listen", "stdio://"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True,
                env=environment, cwd=root, start_new_session=True)
            messages = queue.Queue()

            def read_messages():
                try:
                    for line in process.stdout:
                        messages.put(json.loads(line))
                finally:
                    messages.put(None)

            threading.Thread(target=read_messages, daemon=True).start()

            def send(message):
                process.stdin.write(json.dumps(message) + "\n")
                process.stdin.flush()

            def receive():
                try:
                    message = messages.get(timeout=15)
                    if message is not None:
                        return message
                except queue.Empty:
                    pass
                errors.seek(0)
                raise AssertionError("Runtime probe stalled or exited: " + errors.read()[-2000:])

            try:
                send({"id": 1, "method": "initialize", "params": {
                    "clientInfo": {"name": "agentcodi_long_work_probe", "version": "0.1.4"},
                    "capabilities": {"experimentalApi": True}}})
                while True:
                    message = receive()
                    if message.get("id") == 1:
                        assert "result" in message, message
                        report["user_agent"] = message["result"]["userAgent"]
                        break
                send({"method": "initialized"})
                output_command = [sys.executable, "-c",
                    "import sys; sys.stdout.buffer.write(b'x' * " + str(OUTPUT_BYTES) + " + " + repr(MARKER) + ")"]
                cases = [
                    ("finite-timeout", ["/bin/sh", "-c", "printf ready; sleep 2; printf completed"],
                        {"timeoutMs": 150, "outputBytesCap": 8 * 1024 * 1024}),
                    ("legacy-output-cap", output_command,
                        {"disableTimeout": True, "outputBytesCap": 8 * 1024 * 1024}),
                    ("unlimited-stream", output_command,
                        {"disableTimeout": True, "disableOutputCap": True}),
                ]
                for ident, (name, command, limits) in enumerate(cases, 2):
                    send({"id": ident, "method": "command/exec", "params": {
                        "command": command, "cwd": root, "processId": name,
                        "permissionProfile": ":danger-full-access", "tty": True,
                        "size": {"rows": 24, "cols": 80}, **limits}})
                    stats = {"case": name, "bytes": 0, "max_chunk": 0, "cap_reached": False, "marker": False}
                    marker_tail = b""
                    deadline = time.monotonic() + 30
                    while time.monotonic() < deadline:
                        message = receive()
                        if message.get("id") == ident:
                            assert "result" in message, message
                            stats["completion"] = message["result"]
                            break
                        if message.get("method") == "command/exec/outputDelta":
                            params = message["params"]
                            assert params["processId"] == name, params["processId"]
                            chunk = base64.b64decode(params["deltaBase64"], validate=True)
                            stats["bytes"] += len(chunk)
                            stats["max_chunk"] = max(stats["max_chunk"], len(chunk))
                            stats["cap_reached"] |= params["capReached"]
                            marker_window = marker_tail + chunk
                            stats["marker"] |= MARKER in marker_window
                            marker_tail = marker_window[-(len(MARKER) - 1):]
                    assert "completion" in stats, "Overall probe deadline exceeded"
                    assert stats["max_chunk"] <= (64 + 8) * 1024, stats
                    assert stats["completion"]["stdout"] == stats["completion"]["stderr"] == "", stats
                    if name == "finite-timeout":
                        assert stats["completion"]["exitCode"] == 124, stats
                    elif name == "legacy-output-cap":
                        assert stats["bytes"] == 8 * 1024 * 1024 and stats["cap_reached"] and not stats["marker"], stats
                    else:
                        assert stats["completion"]["exitCode"] == 0 and stats["marker"] and not stats["cap_reached"], stats
                        assert stats["bytes"] == OUTPUT_BYTES + len(MARKER), stats
                    report["cases"].append(stats)

                # Prove a quiet, unlimited PTY stays live until an explicit Stop.
                send({"id": 5, "method": "command/exec", "params": {
                    "command": ["/bin/sh", "-c", "printf ready; sleep 30"], "cwd": root,
                    "processId": "explicit-stop", "permissionProfile": ":danger-full-access",
                    "tty": True, "disableTimeout": True, "disableOutputCap": True}})
                ready_output = b""
                while ready_output != b"ready":
                    message = receive()
                    assert message.get("id") != 5, message
                    if message.get("method") == "command/exec/outputDelta":
                        assert message["params"]["processId"] == "explicit-stop", message
                        ready_output += base64.b64decode(message["params"]["deltaBase64"])
                        assert b"ready".startswith(ready_output), message
                try:
                    message = messages.get(timeout=0.25)
                    raise AssertionError("Quiet PTY completed unexpectedly: " + str(message))
                except queue.Empty:
                    pass
                send({"id": 6, "method": "command/exec/terminate", "params": {"processId": "explicit-stop"}})
                completions = {}
                while len(completions) < 2:
                    message = receive()
                    if message.get("id") in (5, 6):
                        assert "result" in message, message
                        completions[message["id"]] = message["result"]
                report["cases"].append({"case": "explicit-stop", "completion": completions[5], "terminate": completions[6]})
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)

    payload = json.dumps(report, indent=2) + "\n"
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(payload, encoding="utf-8")
    print(payload, end="")


if __name__ == "__main__":
    main()
