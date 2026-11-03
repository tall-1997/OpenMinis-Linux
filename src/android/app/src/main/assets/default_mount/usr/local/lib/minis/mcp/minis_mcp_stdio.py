#!/usr/bin/env python3
"""minis-mcp-stdio — speak MCP over stdio, run on Minis Ultra's HTTP MCP server.

Why this exists
---------------
The phone exposes its MCP tool surface (device tools, file tools, shell tools,
schedule tools, the approvals handshake, every ``phone_*`` RPC) over loopback
HTTP at ``/mcp``. Most local MCP clients — Claude Code, Codex CLI, custom
Node/Python agents — only know how to spawn a child and talk newline-delimited
JSON-RPC on its stdin/stdout. This bridge is that child: one JSON-RPC message
per line in, one JSON-RPC response per line out, forwarded verbatim to the
phone and back.

Contract
--------
* stdin  : one JSON object per line (MCP stdio framing — no Content-Length).
* stdout : one JSON object per line, protocol traffic only.
* stderr : human-readable diagnostics. Never mixed into stdout, or the client's
           parser breaks.

Discovery order for the endpoint
--------------------------------
1. ``--url`` / ``$MINIS_MCP_URL``
2. ``/var/minis/shared/mcp/endpoint.json`` -> ``{"url": ..., "token": ...}``
3. ``http://127.0.0.1:8765/mcp`` (the documented default port)

Token: ``--token`` / ``$MINIS_MCP_TOKEN`` / the endpoint file. Without one the
bridge sends no Authorization header, which only works when the phone's MCP
server has token auth disabled.

Examples
--------
    # Claude Code / Codex style entry
    {"command": "minis-mcp-stdio", "env": {"MINIS_MCP_TOKEN": "..."}}

    # manual smoke test
    echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | minis-mcp-stdio

    # find the phone, print what it advertises, exit
    minis-mcp-stdio --discover
"""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:8765/mcp"
ENDPOINT_FILE = "/var/minis/shared/mcp/endpoint.json"
DEFAULT_TIMEOUT = 600.0  # a tool call may legitimately run for minutes
DEFAULT_PORTS = (8765, 8766, 8767, 8768, 8769)


def log(msg: str) -> None:
    print(f"[minis-mcp-stdio] {msg}", file=sys.stderr, flush=True)


def load_endpoint_file(path: str) -> dict:
    """Parse an endpoint file. Missing or malformed reads as empty config."""
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def resolve_url(args: argparse.Namespace) -> str:
    if args.url:
        return args.url
    env = os.environ.get("MINIS_MCP_URL", "").strip()
    if env:
        return env
    file_cfg = load_endpoint_file(args.endpoint_file)
    url = str(file_cfg.get("url") or "").strip()
    if url:
        return url
    if args.discover:
        port = discover_port()
        if port:
            return f"http://127.0.0.1:{port}/mcp"
    return DEFAULT_URL


def resolve_token(args: argparse.Namespace) -> str:
    if args.token:
        return args.token
    env = os.environ.get("MINIS_MCP_TOKEN", "").strip()
    if env:
        return env
    return str(load_endpoint_file(args.endpoint_file).get("token") or "").strip()


def probe_port(port: int, timeout: float = 1.5) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def discover_port() -> int | None:
    for port in DEFAULT_PORTS:
        if probe_port(port):
            return port
    return None


def post(url: str, token: str, payload: str, timeout: float) -> tuple[int, str]:
    """POST one JSON-RPC message; return (status, body). Raises on transport error."""
    req = urllib.request.Request(url, data=payload.encode("utf-8"), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            return resp.status, body
    except urllib.error.HTTPError as exc:  # 401 / 413 / 400 still carry JSON
        body = exc.read().decode("utf-8", errors="replace")
        return exc.code, body


def error_for(request_id, code: int, message: str) -> str:
    return json.dumps(
        {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}},
        ensure_ascii=False,
    )


def handle_line(line: str, url: str, token: str, timeout: float, verbose: bool) -> str | None:
    """Forward one line; return the response line to emit, or None for silence."""
    line = line.strip()
    if not line:
        return None
    try:
        message = json.loads(line)
    except ValueError as exc:
        log(f"dropping non-JSON input line: {exc}")
        return error_for(None, -32700, f"Parse error: {exc}")
    if not isinstance(message, dict):
        return error_for(None, -32600, "Invalid Request: expected a JSON object")

    request_id = message.get("id")
    is_notification = "id" not in message
    if verbose:
        log(f"-> {message.get('method', '?')} id={request_id!r}")

    try:
        status, body = post(url, token, json.dumps(message, ensure_ascii=False), timeout)
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        log(f"transport failure on {message.get('method', '?')}: {exc}")
        if is_notification:
            return None
        return error_for(request_id, -32000, f"Bridge transport error: {exc}")

    body = body.strip()
    if verbose:
        log(f"<- {status} {len(body)}B")
    if is_notification or status == 202 or not body:
        return None
    reply = coerce_reply(status, body, request_id)
    # Pass the phone's JSON through untouched whenever it already is a reply.
    if len(reply.splitlines()) > 1:
        compact = json.dumps(json.loads(reply), ensure_ascii=False)
        return compact
    return reply


def coerce_reply(status: int, body: str, request_id) -> str:
    """Guarantee a JSON-RPC reply on stdout.

    The phone answers 4xx/5xx through frameworks that emit ``{"error": ...}``
    or plain text, and a strict client treats either as a protocol violation —
    it then reports "MCP server disconnected" with no cause. Wrapping the HTTP
    failure into a real error object keeps the failure legible in the client's
    logs and leaves the stream parseable.
    """
    try:
        parsed = json.loads(body)
    except ValueError:
        parsed = None
    if isinstance(parsed, dict) and parsed.get("jsonrpc"):
        return body
    snippet = body if not isinstance(parsed, dict) else str(parsed.get("error", body))
    snippet = " ".join(snippet.split())[:160]
    return error_for(request_id, -32001, f"HTTP {status}: {snippet}")


def iter_messages(stream):
    """Yield ``(payload_text, uses_content_length)`` for every inbound message.

    Two framings exist in the wild. The MCP spec
    (2024-11-05 and later) puts one JSON-RPC message per line; every LSP-derived
    host — and several MCP clients built on one — sends a
    ``Content-Length: N\r\n\r\n<body>`` header block instead. Reading the header
    as a JSON line produced a spurious -32700 per request and dropped the body
    of everything but the last message, so the frame type is detected here and
    answered in kind.

    Length is counted in **bytes** (Content-Length is a byte count); decoding
    happens after the body is cut, which keeps a Chinese tool argument from
    desyncing the stream.
    """
    while True:
        line = stream.readline()
        if not line:
            return
        stripped = line.strip()
        if not stripped:
            continue
        if stripped.lower().startswith(b"content-length:"):
            raw_length = stripped.split(b":", 1)[1].strip()
            try:
                length = int(raw_length)
            except ValueError:
                yield stripped.decode("utf-8", "replace"), True
                continue
            while True:  # other headers, then the blank separator
                header = stream.readline()
                if not header or not header.strip():
                    break
            body = stream.read(length)
            if body:
                yield body.decode("utf-8", "replace"), True
            continue
        yield stripped.decode("utf-8", "replace"), False


def write_message(text: str, framed: bool) -> bool:
    """Emit one reply; returns False when the client has gone away."""
    data = text.encode("utf-8")
    try:
        if framed:
            sys.stdout.buffer.write(b"Content-Length: %d\r\n\r\n" % len(data) + data)
        else:
            sys.stdout.buffer.write(data + b"\n")
        sys.stdout.buffer.flush()
        return True
    except BrokenPipeError:
        return False


def serve(args: argparse.Namespace) -> int:
    url = resolve_url(args)
    token = resolve_token(args)
    if args.verbose:
        log(f"endpoint {url} token={'set' if token else 'none'}")
    framed = False  # lock onto the client's framing at the first frame
    for text, uses_content_length in iter_messages(sys.stdin.buffer):
        framed = framed or uses_content_length
        out = handle_line(text, url, token, args.timeout, args.verbose)
        if out is None:
            continue
        if not write_message(out, framed):
            return 0
    return 0


def discover(args: argparse.Namespace) -> int:
    url = resolve_url(args)
    token = resolve_token(args)
    payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    try:
        status, body = post(url, token, payload, args.timeout)
    except Exception as exc:  # noqa: BLE001 — a discovery run reports, never raises
        log(f"{url} unreachable: {exc}")
        return 1
    if status != 200:
        log(f"{url} answered HTTP {status}: {body[:200]}")
        return 1
    try:
        tools = json.loads(body)["result"]["tools"]
    except (ValueError, KeyError, TypeError):
        log(f"{url} answered something that is not tools/list: {body[:200]}")
        return 1
    print(f"endpoint: {url}")
    print(f"token:    {'set' if token else 'none'}")
    print(f"tools:    {len(tools)}")
    for tool in tools:
        name = tool.get("name", "?") if isinstance(tool, dict) else "?"
        print(f"  - {name}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="minis-mcp-stdio",
        description="MCP stdio <-> Minis Ultra HTTP bridge (one JSON-RPC message per line).",
    )
    p.add_argument("--url", default=None, help=f"HTTP MCP endpoint (default {DEFAULT_URL})")
    p.add_argument("--token", default=None, help="Bearer token; else $MINIS_MCP_TOKEN or endpoint file")
    p.add_argument("--endpoint-file", default=ENDPOINT_FILE,
                   help=f"discovery file with url/token (default {ENDPOINT_FILE})")
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help="per-request timeout seconds")
    p.add_argument("--discover", action="store_true", help="probe 8765-8769 and print tools/list")
    p.add_argument("--verbose", action="store_true", help="log each exchange to stderr")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.discover:
        return discover(args)
    return serve(args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
