#!/usr/bin/env python3
"""Tests for the HTTP transport round-2 fixes.

Run: python3 test_http_transport.py  (stdlib unittest; httpx is required and
the sh wrapper normally installs it, same as the other suites.)

Covers:
  * [T-mcp-http-sse-id-match] SSE replies are matched by request id — a
    trailing notification no longer shadows the reply; a payload split across
    several data: lines parses; a non-matching id falls back to the first
    response-shaped message instead of PARSE_ERROR.
  * [T-mcp-protocol-version-header] post-initialize requests carry
    MCP-Protocol-Version: 2025-06-18; the initialize itself does not.
  * [T-mcp-http-timeout-phases] the request timeout is a phased httpx.Timeout
    with a bounded connect phase.
  * [T-mcp-http-connect-retry] one retry on connect-phase failures only;
    nothing is retried after a successful send.
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import httpx  # noqa: E402

from transport.http import HTTPTransport, MCPError, _parse_sse  # noqa: E402
import transport.http as http_mod  # noqa: E402


def _sse(*payloads):
    return "text/event-stream", "".join(
        "data: %s\n\n" % p for p in payloads)


class SseParsingTests(unittest.TestCase):
    REPLY = json.dumps({"jsonrpc": "2.0", "id": 7, "result": {"tools": [1]}})
    NOTIFY = json.dumps({"jsonrpc": "2.0", "method": "notifications/message",
                         "params": {}})

    def test_trailing_notification_no_longer_shadows_the_reply(self):
        ctype, body = _sse(self.REPLY, self.NOTIFY)
        resp = httpx.Response(200, headers={"content-type": ctype}, text=body)
        msg = _parse_sse(body, want_id=7)
        self.assertEqual(msg.get("id"), 7)
        self.assertIn("tools", msg["result"])

    def test_notification_first_reply_last_also_matches(self):
        ctype, body = _sse(self.NOTIFY, self.REPLY)
        msg = _parse_sse(body, want_id=7)
        self.assertEqual(msg.get("id"), 7)

    def test_multiline_data_event_joins_into_one_payload(self):
        body = ('data: {"jsonrpc": "2.0",\n'
                'data:  "id": 7,\n'
                'data:  "result": {"tools": []}}\n\n')
        msg = _parse_sse(body, want_id=7)
        self.assertEqual(msg["result"], {"tools": []})

    def test_non_matching_id_falls_back_to_response_message(self):
        other = json.dumps({"jsonrpc": "2.0", "id": 99, "result": {}})
        msg = _parse_sse("data: %s\n\n" % other, want_id=7)
        self.assertEqual(msg.get("id"), 99)

    def test_no_json_payload_raises_parse_error(self):
        with self.assertRaises(MCPError) as ctx:
            _parse_sse("data: not json\n\ndata: still not\n\n", want_id=7)
        self.assertEqual(ctx.exception.code, "PARSE_ERROR")

    def test_json_body_still_parses_directly(self):
        resp = httpx.Response(
            200, headers={"content-type": "application/json"}, text=self.REPLY)
        msg = _parse_response_wrapper(resp, want_id=7)
        self.assertEqual(msg.get("id"), 7)


def _parse_response_wrapper(resp, want_id=None):
    # direct re-export test of the JSON path
    return http_mod._parse_response(resp, want_id=want_id)


class _CountingServer:
    """Fake httpx.post-level server that records calls and can fail on
    connect for the first N attempts."""

    def __init__(self, connect_failures=0):
        self.attempts = 0
        self.connect_failures = connect_failures
        self.headers_seen = []
        self.timeout_seen = None

    def __call__(self, url, **kwargs):
        self.attempts += 1
        self.headers_seen.append(kwargs.get("headers", {}))
        self.timeout_seen = kwargs.get("timeout")
        if self.attempts <= self.connect_failures:
            raise httpx.ConnectError("connection refused")
        body = kwargs.get("json") or {}
        method = body.get("method")
        result = {}
        if method == "initialize":
            result = {"protocolVersion": "2025-06-18"}
        elif method == "tools/list":
            result = {"tools": [{"name": "echo"}]}
        return httpx.Response(
            200, headers={"content-type": "application/json"},
            text=json.dumps({"jsonrpc": "2.0", "id": body.get("id"),
                             "result": result}),
        )


class _PatchedPost:
    """Swap http_mod.httpx.post for a bare callable (no MockTransport needed:
    the counting server builds raw httpx.Response objects itself)."""

    def __init__(self, server):
        self.server = server
        self._orig = http_mod.httpx.post

    def __enter__(self):
        http_mod.httpx.post = self.server
        return self

    def __exit__(self, *exc):
        http_mod.httpx.post = self._orig
        return False


class ProtocolVersionTests(unittest.TestCase):
    def test_post_init_requests_carry_the_header_and_init_does_not(self):
        server = _CountingServer()
        with _PatchedPost(server):
            t = HTTPTransport({"url": "https://example.test/mcp"}, "pv")
            t.list_tools()
            t.list_tools()
        self.assertEqual(len(server.headers_seen), 4)  # init + notify + list + list
        self.assertNotIn("MCP-Protocol-Version", server.headers_seen[0])
        self.assertEqual(server.headers_seen[2].get("MCP-Protocol-Version"),
                         "2025-06-18")


class TimeoutPhaseTests(unittest.TestCase):
    def test_connect_phase_is_bounded(self):
        server = _CountingServer()
        with _PatchedPost(server):
            t = HTTPTransport({"url": "https://example.test/mcp"}, "to")
            t.list_tools()
        self.assertIsInstance(server.timeout_seen, httpx.Timeout)
        self.assertEqual(server.timeout_seen.connect, 15.0)
        self.assertEqual(server.timeout_seen.read, 300.0)


class ConnectRetryTests(unittest.TestCase):
    def test_one_connect_failure_is_retried_and_recovers(self):
        server = _CountingServer(connect_failures=1)
        with _PatchedPost(server):
            t = HTTPTransport({"url": "https://example.test/mcp"}, "retry")
            tools = t.list_tools()
        self.assertEqual(tools, [{"name": "echo"}])
        # init failed once + retried (2), then notify + tools/list (1 each):
        # the retry budget is PER REQUEST, not per call session.
        self.assertEqual(server.attempts, 4)

    def test_persistent_connect_failure_surfaces_connection_error(self):
        server = _CountingServer(connect_failures=99)
        with _PatchedPost(server):
            t = HTTPTransport({"url": "https://example.test/mcp"}, "retry")
            with self.assertRaises(MCPError) as ctx:
                t.list_tools()
        self.assertEqual(ctx.exception.code, "CONNECTION_ERROR")
        self.assertIn("could not connect", ctx.exception.message)
        self.assertEqual(server.attempts, 2)  # bounded: exactly one retry


if __name__ == "__main__":
    unittest.main(verbosity=2)
