#!/usr/bin/env python3
"""End-to-end tests for scripts/mcp/minis-mcp-stdio.

Runs the real bridge as a subprocess against a fake MCP HTTP server, so the
stdio framing, the HTTP hop, notification silence, error fallbacks and
endpoint discovery are all exercised over actual pipes.

    python3 scripts/mcp/test_minis_mcp_stdio.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BRIDGE = Path(__file__).resolve().parent / "minis-mcp-stdio"

TOOLS = [
    {"name": "shell_execute", "description": "run a command"},
    {"name": "phone_task_list", "description": "list approval tasks"},
]


class FakeMcp(BaseHTTPRequestHandler):
    """Minimal /mcp endpoint: initialize, ping, tools/list, tools/call."""

    token: str | None = None
    seen_auth: list[str] = []
    # When set, a rejected request answers this raw body instead of a JSON-RPC
    # error — the shape a plain HTTP framework (nginx/Flask) produces.
    raw_error_body: str | None = None

    def log_message(self, *args):  # keep test output clean
        pass

    def _send(self, status: int, body: str) -> None:
        raw = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):  # noqa: N802 — stdlib naming
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8")
        FakeMcp.seen_auth.append(self.headers.get("Authorization", ""))
        if FakeMcp.token and self.headers.get("Authorization", "") != f"Bearer {FakeMcp.token}":
            if FakeMcp.raw_error_body is not None:
                self._send(401, FakeMcp.raw_error_body)
                return
            self._send(401, json.dumps({"jsonrpc": "2.0", "id": None,
                                        "error": {"code": -32001, "message": "auth"}}))
            return
        if self.path not in ("/mcp", "/message"):
            self._send(404, "")
            return
        msg = json.loads(raw)
        method, rid = msg.get("method"), msg.get("id")
        if method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if method == "tools/list":
            body = {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}}
        elif method == "tools/call":
            name = msg["params"]["name"]
            body = {"jsonrpc": "2.0", "id": rid,
                    "result": {"content": [{"type": "text", "text": f"called {name}"}]}}
        elif method == "initialize":
            body = {"jsonrpc": "2.0", "id": rid,
                    "result": {"protocolVersion": "2024-11-05",
                               "serverInfo": {"name": "minis", "version": "test"}}}
        else:
            body = {"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32601, "message": f"no {method}"}}
        # Pretty-printed on purpose: the bridge must compact it to one line.
        self._send(200, json.dumps(body, indent=2))


class BridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeMcp)
        cls.port = cls.server.server_address[1]
        cls.url = f"http://127.0.0.1:{cls.port}/mcp"
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def run_bridge(self, lines: list[str], *extra: str, env: dict | None = None):
        full_env = {**os.environ, **(env or {})}
        proc = subprocess.run(
            [sys.executable, str(BRIDGE), "--url", self.url, "--timeout", "10", *extra],
            input="\n".join(lines) + "\n",
            capture_output=True, text=True, timeout=60, env=full_env,
        )
        return proc

    def run_bridge_raw(self, raw: bytes, *extra: str) -> subprocess.CompletedProcess:
        proc = subprocess.run(
            [sys.executable, str(BRIDGE), "--url", self.url, "--timeout", "10", *extra],
            input=raw, capture_output=True, timeout=60,
        )
        return proc

    @staticmethod
    def content_length_frame(payload: str) -> bytes:
        body = payload.encode("utf-8")
        return b"Content-Length: %d\r\n\r\n%s" % (len(body), body)

    # ── core framing ──────────────────────────────────────────────────────

    def test_tools_list_round_trip(self):
        proc = self.run_bridge(['{"jsonrpc":"2.0","id":1,"method":"tools/list"}'])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        out = proc.stdout.strip().splitlines()
        self.assertEqual(len(out), 1, proc.stdout)
        payload = json.loads(out[0])
        self.assertEqual(payload["id"], 1)
        self.assertEqual([t["name"] for t in payload["result"]["tools"]],
                         ["shell_execute", "phone_task_list"])

    def test_multi_line_response_is_compacted(self):
        proc = self.run_bridge(['{"jsonrpc":"2.0","id":7,"method":"tools/list"}'])
        out = proc.stdout.strip().splitlines()
        self.assertEqual(len(out), 1, "body must be compacted to a single line")

    def test_notification_produces_no_stdout(self):
        proc = self.run_bridge(
            ['{"jsonrpc":"2.0","method":"notifications/initialized"}',
             '{"jsonrpc":"2.0","id":2,"method":"tools/list"}']
        )
        out = proc.stdout.strip().splitlines()
        self.assertEqual(len(out), 1, f"only the request answers, got {out}")
        self.assertEqual(json.loads(out[0])["id"], 2)

    def test_tools_call_passes_result_through(self):
        line = json.dumps({"jsonrpc": "2.0", "id": "abc", "method": "tools/call",
                           "params": {"name": "shell_execute", "arguments": {"command": "id"}}})
        proc = self.run_bridge([line])
        payload = json.loads(proc.stdout.strip())
        self.assertEqual(payload["id"], "abc")
        self.assertIn("called shell_execute", payload["result"]["content"][0]["text"])

    def test_parse_error_keeps_stream_alive(self):
        proc = self.run_bridge(['not json', '{"jsonrpc":"2.0","id":3,"method":"tools/list"}'])
        out = proc.stdout.strip().splitlines()
        self.assertEqual(len(out), 2, out)
        first = json.loads(out[0])
        self.assertEqual(first["error"]["code"], -32700)
        self.assertEqual(json.loads(out[1])["id"], 3)

    def test_transport_failure_reports_with_request_id(self):
        proc = subprocess.run(
            [sys.executable, str(BRIDGE), "--url", "http://127.0.0.1:1/mcp", "--timeout", "2"],
            input='{"jsonrpc":"2.0","id":9,"method":"tools/list"}\n',
            capture_output=True, text=True, timeout=30,
        )
        payload = json.loads(proc.stdout.strip())
        self.assertEqual(payload["id"], 9)
        self.assertEqual(payload["error"]["code"], -32000)

    def test_auth_header_forwarded(self):
        FakeMcp.token = "s3cret"
        try:
            proc = subprocess.run(
                [sys.executable, str(BRIDGE), "--url", self.url, "--token", "s3cret"],
                input='{"jsonrpc":"2.0","id":1,"method":"tools/list"}\n',
                capture_output=True, text=True, timeout=30,
            )
            self.assertIn("result", json.loads(proc.stdout.strip()))
            self.assertIn("Bearer s3cret", FakeMcp.seen_auth[-1])
        finally:
            FakeMcp.token = None

    def test_missing_token_surfaces_server_error_body(self):
        FakeMcp.token = "s3cret"
        try:
            proc = self.run_bridge(['{"jsonrpc":"2.0","id":4,"method":"tools/list"}'])
            payload = json.loads(proc.stdout.strip())
            self.assertEqual(payload["error"]["message"], "auth")
        finally:
            FakeMcp.token = None

    def test_plain_http_error_body_is_wrapped_into_json_rpc(self):
        """An HTTP framework's {"error": "..."} body is not a JSON-RPC reply;
        forwarding it verbatim makes strict clients choke on protocol errors."""
        FakeMcp.token = "s3cret"
        FakeMcp.raw_error_body = '{"error": "unauthorized"}'
        try:
            proc = self.run_bridge(['{"jsonrpc":"2.0","id":5,"method":"tools/list"}'])
            payload = json.loads(proc.stdout.strip())
            self.assertEqual(payload["jsonrpc"], "2.0")
            self.assertEqual(payload["id"], 5)
            self.assertEqual(payload["error"]["code"], -32001)
            self.assertIn("401", payload["error"]["message"])
            self.assertIn("unauthorized", payload["error"]["message"])
        finally:
            FakeMcp.token = None
            FakeMcp.raw_error_body = None

    # ── LSP-style framing (Content-Length headers) ────────────────────────

    def test_content_length_frame_answers_in_kind(self):
        """A Content-Length client gets Content-Length output — and no stray
        -32700 for the header line it never meant as a message."""
        raw = self.content_length_frame('{"jsonrpc":"2.0","id":11,"method":"tools/list"}')
        proc = self.run_bridge_raw(raw)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        out = proc.stdout
        self.assertTrue(out.startswith(b"Content-Length:"), out[:120])
        header, _, body = out.partition(b"\r\n\r\n")
        declared = int(header.split(b":", 1)[1])
        self.assertEqual(declared, len(body), "Content-Length must match the body bytes")
        payload = json.loads(body.decode("utf-8"))
        self.assertEqual(payload["id"], 11)
        self.assertIn("result", payload, "the header line must not become a parse error")

    def test_content_length_stream_keeps_one_frame_per_answer(self):
        first = self.content_length_frame('{"jsonrpc":"2.0","id":21,"method":"tools/list"}')
        second = self.content_length_frame(
            '{"jsonrpc":"2.0","id":22,"method":"tools/call",'
            '"params":{"name":"shell_execute","arguments":{"command":"id"}}}')
        proc = self.run_bridge_raw(first + second)
        self.assertEqual(proc.returncode, 0, proc.stderr.decode())
        text = proc.stdout.decode("utf-8")
        frames = [f for f in text.split("Content-Length:") if f.strip()]
        self.assertEqual(len(frames), 2, text)
        ids = []
        for frame in frames:
            _, _, body = frame.partition("\r\n\r\n")
            ids.append(json.loads(body)["id"])
        self.assertEqual(ids, [21, 22])

    def test_content_length_body_with_multibyte_text_stays_byte_accurate(self):
        """Content-Length counts bytes; a Chinese argument must not desync the
        stream (the classic Python str-vs-bytes trap)."""
        call = json.dumps({"jsonrpc": "2.0", "id": 31, "method": "tools/call",
                           "params": {"name": "shell_execute",
                                      "arguments": {"command": "echo 你好世界"}}},
                          ensure_ascii=False)
        proc = self.run_bridge_raw(self.content_length_frame(call))
        text = proc.stdout.decode("utf-8")
        header, _, body = text.partition("\r\n\r\n")
        self.assertEqual(int(header.split(":", 1)[1]), len(body.encode("utf-8")))
        self.assertEqual(json.loads(body)["id"], 31)

    def test_line_client_never_receives_content_length(self):
        """Regression: NDJSON clients keep plain one-line answers."""
        proc = self.run_bridge(['{"jsonrpc":"2.0","id":41,"method":"tools/list"}'])
        self.assertNotIn("Content-Length", proc.stdout)
        self.assertEqual(json.loads(proc.stdout.strip())["id"], 41)

    # ── discovery ─────────────────────────────────────────────────────────

    def test_endpoint_file_discovery(self):
        with tempfile.TemporaryDirectory() as tmp:
            endpoint = Path(tmp) / "endpoint.json"
            endpoint.write_text(json.dumps({"url": self.url, "token": "tok"}), encoding="utf-8")
            script = (
                "import runpy;"
                f"g=runpy.run_path(r'{BRIDGE}', run_name='bridge');"
                f"a=g['build_parser']().parse_args(['--endpoint-file', r'{endpoint}']);"
                "print(g['resolve_url'](a));print(g['resolve_token'](a))"
            )
            proc = subprocess.run([sys.executable, "-c", script], capture_output=True,
                                  text=True, timeout=30)
            self.assertEqual(proc.stdout.splitlines(), [self.url, "tok"], proc.stderr)

    def test_env_wins_over_endpoint_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            endpoint = Path(tmp) / "endpoint.json"
            endpoint.write_text(json.dumps({"url": "http://127.0.0.1:9999/mcp"}), encoding="utf-8")
            script = (
                "import runpy;"
                f"g=runpy.run_path(r'{BRIDGE}', run_name='bridge');"
                f"a=g['build_parser']().parse_args(['--endpoint-file', r'{endpoint}']);"
                "print(g['resolve_url'](a))"
            )
            proc = subprocess.run(
                [sys.executable, "-c", script], capture_output=True, text=True, timeout=30,
                env={**os.environ, "MINIS_MCP_URL": "http://127.0.0.1:8765/mcp"},
            )
            self.assertEqual(proc.stdout.strip(), "http://127.0.0.1:8765/mcp")

    def test_discover_lists_tools(self):
        proc = subprocess.run(
            [sys.executable, str(BRIDGE), "--url", self.url, "--discover"],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("tools:    2", proc.stdout)
        self.assertIn("shell_execute", proc.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
