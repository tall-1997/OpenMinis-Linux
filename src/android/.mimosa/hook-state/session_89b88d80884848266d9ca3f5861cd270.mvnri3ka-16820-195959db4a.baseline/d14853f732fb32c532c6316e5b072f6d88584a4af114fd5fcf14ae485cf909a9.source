#!/usr/bin/env python3
"""Tests for [T-mcp-http-get-sse] — the GET SSE event listener.

Run: python3 test_http_events.py  (stdlib unittest, offline)

Covers:
  * server notifications dispatch (tools/list_changed)
  * server→client REQUESTS are answered on the POST channel: ping → {} result
  * a 405 (server without GET support) ends the listener silently
  * a connect failure inside the listener never propagates
"""

import json
import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import httpx  # noqa: E402

from transport.http import HTTPTransport  # noqa: E402
import transport.http as http_mod  # noqa: E402


class _FakeStream:
    """httpx.stream context manager over pre-baked lines."""

    def __init__(self, lines, status=200):
        self.lines = lines
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    @property
    def status_code(self):
        return self.status

    def iter_lines(self):
        for ln in self.lines:
            yield ln


def _sse_lines(*payloads):
    out = []
    for p in payloads:
        for chunk in p.split("\n"):
            out.append("data: %s" % chunk)
        out.append("")
    return out


NOTIFY = json.dumps({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})
PING_REQ = json.dumps({"jsonrpc": "2.0", "id": 11, "method": "ping", "params": {}})


class EventListenerTests(unittest.TestCase):
    def setUp(self):
        self.posted = []
        self.notes = []
        self.requests = []
        self._orig_post = http_mod.httpx.post
        self._orig_stream = http_mod.httpx.stream

    def tearDown(self):
        http_mod.httpx.post = self._orig_post
        http_mod.httpx.stream = self._orig_stream

    def _patch(self, stream_lines=None, stream_status=200, stream_raises=None):
        def fake_post(url, **kwargs):
            self.posted.append(kwargs)
            return httpx.Response(200, headers={"content-type": "application/json"},
                                  text=json.dumps({"jsonrpc": "2.0", "id": 0, "result": {}}))

        def fake_stream(method, url, **kwargs):
            if stream_raises is not None:
                raise stream_raises
            return _FakeStream(stream_lines or [], status=stream_status)

        http_mod.httpx.post = fake_post
        http_mod.httpx.stream = fake_stream

    def _make_transport(self):
        t = HTTPTransport({"url": "https://example.test/mcp"}, "events")
        t._initialized = True
        t._session_id = "sid-1"
        return t

    def _wait(self, cond, timeout=2.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if cond():
                return True
            time.sleep(0.02)
        return False

    def test_notification_dispatched(self):
        self._patch(stream_lines=_sse_lines(NOTIFY))
        t = self._make_transport()
        t.start_event_listener(
            on_notification=lambda m, p: self.notes.append(m),
            on_server_request=lambda rid, m, p: None,
        )
        self.assertTrue(self._wait(lambda: self.notes == ["notifications/tools/list_changed"]))
        t.stop_event_listener()

    def test_ping_request_answered_with_empty_result(self):
        self._patch(stream_lines=_sse_lines(PING_REQ))
        t = self._make_transport()
        t.start_event_listener(
            on_notification=lambda m, p: None,
            on_server_request=lambda rid, m, p: self.requests.append(
                (rid, m)) or t._respond_to_server_request(rid, m),
        )
        self.assertTrue(self._wait(lambda: self.posted))
        body = self.posted[0]["json"]
        self.assertEqual(body["id"], 11)
        self.assertEqual(body["result"], {})
        self.assertIsNone(body.get("error"))
        t.stop_event_listener()

    def test_unsupported_server_request_gets_32601(self):
        self._patch(stream_lines=_sse_lines(
            json.dumps({"jsonrpc": "2.0", "id": 12, "method": "sampling/createMessage"})))
        t = self._make_transport()
        t.start_event_listener(
            on_notification=lambda m, p: None,
            on_server_request=lambda rid, m, p: t._respond_to_server_request(rid, m),
        )
        self.assertTrue(self._wait(lambda: self.posted))
        body = self.posted[0]["json"]
        self.assertEqual(body["error"]["code"], -32601)
        t.stop_event_listener()

    def test_405_ends_listener_silently(self):
        self._patch(stream_status=405, stream_lines=[])
        t = self._make_transport()
        t.start_event_listener(
            on_notification=lambda m, p: self.notes.append(m),
            on_server_request=lambda rid, m, p: None,
        )
        # Give the thread a moment to hit the 405 path; nothing may crash,
        # nothing may be answered.
        time.sleep(0.15)
        self.assertEqual(self.notes, [])
        self.assertEqual(self.posted, [])

    def test_connect_failure_never_propagates(self):
        self._patch(stream_raises=httpx.ConnectError("nope"))
        t = self._make_transport()
        t.start_event_listener(  # must not raise synchronously or in-thread
            on_notification=lambda m, p: None,
            on_server_request=lambda rid, m, p: None,
        )
        time.sleep(0.15)
        t.stop_event_listener()


    def test_concurrent_start_spawns_single_listener(self):
        # [T-mcp-sse-listener-race] start_event_listener is a check-then-start;
        # without its lock two tool-call threads both see "no live listener" and
        # spawn duplicate GET SSE streams, the second overwriting the first's
        # stop event (an orphan that can never be stopped).
        release = threading.Event()
        stream_calls = []

        class _BlockingStream:
            status_code = 200

            def __enter__(self_inner):
                return self_inner

            def __exit__(self_inner, *exc):
                return False

            def iter_lines(self_inner):
                release.wait(5.0)
                return iter([])

        def fake_stream(method, url, **kwargs):
            stream_calls.append(url)
            return _BlockingStream()

        http_mod.httpx.stream = fake_stream
        t = self._make_transport()

        barrier = threading.Barrier(8, timeout=5)

        def go():
            barrier.wait()
            t.start_event_listener()

        threads = [threading.Thread(target=go) for _ in range(8)]
        for th in threads:
            th.start()
        for th in threads:
            th.join(5)
        self.assertTrue(t._listener_thread.is_alive())
        release.set()
        t.stop_event_listener()
        t._listener_thread.join(5)
        self.assertEqual(1, len(stream_calls),
                         "concurrent start_event_listener spawned %d SSE streams"
                         % len(stream_calls))


if __name__ == "__main__":
    unittest.main(verbosity=2)
