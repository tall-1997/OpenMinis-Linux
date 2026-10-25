#!/usr/bin/env python3
"""Unit tests for [T-mcp-stdio-stderr-capture] and [T-mcp-cli-epipe-safe].

Run: python3 test_stdio_stderr_capture.py  (stdlib unittest, no deps)

Covers:
  * daemon.MCPServerProcess.start — a stdio server that dies at startup now
    raises MCPError whose message carries the child's stderr tail (the actual
    reason, e.g. a missing module), instead of the content-free
    "process exited before replying".
  * transport.stdio.STDIOTransport — same contract on the no-daemon path.
  * The stderr ring stays bounded when a server spams stderr, and keeps the
    LAST lines (the ones that usually state the fatal reason).
  * main._emit — a broken stdout (caller gone) exits non-zero quietly instead
    of cascading a second BrokenPipeError out of the top-level error handler.

Fake servers are real child processes (sys.executable) so the subprocess /
thread / pipe machinery is exercised end to end; the suite runs in well
under a second.
"""

import io
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from transport.http import MCPError  # noqa: E402
from transport import stdio as stdio_transport  # noqa: E402
# main MUST be imported before daemon: daemon.py anchors sys.path to the
# installed copy (/usr/local/lib/minis-mcp-cli) and would otherwise hijack
# `main` to resolve there instead of this repo checkout.
import main as cli_main  # noqa: E402
import daemon  # noqa: E402


class _FakeServer:
    """Write a fake stdio MCP server to disk and hand back its config."""

    def __init__(self, body):
        self.path = tempfile.NamedTemporaryFile(
            "w", suffix=".py", delete=False, encoding="utf-8")
        self.path.write(body)
        self.path.close()

    @property
    def cfg(self):
        return {"command": sys.executable, "args": [self.path.name]}

    def cleanup(self):
        try:
            os.unlink(self.path.name)
        except OSError:
            pass


CRASHING_SERVER = """
import sys
sys.stderr.write("FATAL: No module named mcp_server_git\\n")
sys.stderr.flush()
sys.exit(1)
"""

CHATTY_SERVER = """
import sys
for i in range(300):
    sys.stderr.write("noise line %d\\n" % i)
sys.stderr.write("FATAL: the real reason is last\\n")
sys.stderr.flush()
sys.exit(1)
"""


class DaemonStderrCaptureTests(unittest.TestCase):
    def tearDown(self):
        for attr in ("_fake",):
            fake = getattr(self, attr, None)
            if fake:
                fake.cleanup()

    def test_crash_message_carries_stderr_reason(self):
        self._fake = _FakeServer(CRASHING_SERVER)
        proc = daemon.MCPServerProcess("git", self._fake.cfg)
        with self.assertRaises(MCPError) as ctx:
            proc.start()
        self.assertIn("No module named mcp_server_git", ctx.exception.message)
        self.assertIn("STDIO_CRASH", ctx.exception.code)

    def test_stderr_ring_is_bounded_and_keeps_the_last_lines(self):
        self._fake = _FakeServer(CHATTY_SERVER)
        proc = daemon.MCPServerProcess("git", self._fake.cfg)
        with self.assertRaises(MCPError) as ctx:
            proc.start()
        # 300 noise lines: the ring keeps the tail, never the head.
        self.assertNotIn("noise line 0\\n", ctx.exception.message)
        self.assertNotIn("noise line 0 |", ctx.exception.message)
        self.assertIn("the real reason is last", ctx.exception.message)

    def test_tail_is_length_capped(self):
        self._fake = _FakeServer(CHATTY_SERVER)
        proc = daemon.MCPServerProcess("git", self._fake.cfg)
        with self.assertRaises(MCPError) as ctx:
            proc.start()
        self.assertLessEqual(len(ctx.exception.message), 1200)


class StdioTransportStderrCaptureTests(unittest.TestCase):
    def tearDown(self):
        if getattr(self, "_fake", None):
            self._fake.cleanup()

    def test_crash_message_carries_stderr_reason(self):
        self._fake = _FakeServer(CRASHING_SERVER)
        transport = stdio_transport.STDIOTransport(self._fake.cfg, "git")
        with self.assertRaises(MCPError) as ctx:
            transport.list_tools()
        self.assertIn("No module named mcp_server_git", ctx.exception.message)

    def test_retry_path_also_carries_the_reason(self):
        # _session retries once on STDIO_CRASH; the second crash must still
        # carry the stderr reason, not degrade back to the bare message.
        self._fake = _FakeServer(CRASHING_SERVER)
        transport = stdio_transport.STDIOTransport(self._fake.cfg, "git")
        with self.assertRaises(MCPError) as ctx:
            transport.list_tools()
        self.assertIn("server stderr:", ctx.exception.message)


class EmitEpipeSafeTests(unittest.TestCase):
    def test_emit_exits_quietly_on_broken_stdout(self):
        class Broken(io.StringIO):
            def write(self, _):
                raise BrokenPipeError("[Errno 32] Broken pipe")

        old_stdout = sys.stdout
        sys.stdout = Broken()
        try:
            with self.assertRaises(SystemExit) as ctx:
                cli_main._emit({"ok": True}, pretty=False)
            self.assertEqual(ctx.exception.code, 1)
        finally:
            sys.stdout = old_stdout


if __name__ == "__main__":
    unittest.main(verbosity=2)
