"""HTTP MCP transport — JSON-RPC 2.0 over HTTP POST.

Flow: initialize -> tools/list / tools/call. Supports the streamable-HTTP MCP
endpoints (single POST returning either application/json or an SSE
`text/event-stream` body, both of which we parse for the JSON-RPC reply).

`$ENV_VAR` references in headers (and URL) are expanded from the process
environment so secrets live in env, not the config file. 5-minute timeout.

Errors are raised as `MCPError(code, message)`; main.py renders the unified
{"error","code","server"} envelope.
"""

import json
import os
import re
import threading
import time

try:
    import httpx
except ImportError:  # pragma: no cover - the sh wrapper installs httpx first
    httpx = None


def _ensure_httpx():
    """[T-mcp-httpx-lazy-reimport] Re-resolve httpx at call time.

    The daemon forks on the FIRST MCP call, which can precede the sh
    wrapper's pip install (slow mirror, offline first call) — and a module
    captured at import time stays None for the daemon's whole life, so every
    remote server answered "httpx unavailable" forever, even after httpx
    landed on disk (a --refresh only reconnects; it does not restart the
    process). A failed import is not cached by Python, so re-importing here
    succeeds the moment the package is installed — no daemon restart, no
    lost session state.
    """
    global httpx
    if httpx is not None:
        return True
    try:
        import httpx as _httpx
    except ImportError:
        return False
    httpx = _httpx
    return True

TIMEOUT_SECONDS = 300  # 5 min

# $VAR and $$VAR both expand from the process env; the UI picker emits $$VAR to
# make a reference visually explicit. The optional second `$` is consumed in the
# same match (no double-expansion), so $$VAR / $${VAR} resolve identically to
# $VAR / ${VAR}.
_ENV_RE = re.compile(r"\$\$?\{?([A-Za-z_][A-Za-z0-9_]*)\}?")


class MCPError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message


def expand_env(value):
    """Replace $VAR / ${VAR} / $$VAR / $${VAR} with the environment value (empty
    if unset)."""
    if not isinstance(value, str):
        return value
    return _ENV_RE.sub(lambda m: os.environ.get(m.group(1), ""), value)


def _expand_headers(headers):
    out = {}
    for k, v in (headers or {}).items():
        out[k] = expand_env(v)
    return out


def _parse_sse(text, want_id):
    """[T-mcp-http-sse-id-match] Extract the JSON-RPC reply for want_id from an
    SSE body. Per the SSE spec an event's data may span several `data:` lines
    (joined with \n) and blank lines separate events. The previous parser had
    two defects: a payload split across data: lines was dropped, and "last
    data line wins" let a trailing NOTIFICATION from a streaming server
    shadow the actual reply (an id-less message yields result=None, i.e. an
    empty tools list that looks like the server has no tools). Match the
    request id; fall back to the first response-shaped message so a
    non-conforming id still surfaces its error instead of a parse failure."""
    candidates = []
    data_buf = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            if data_buf:
                candidates.append("\n".join(data_buf))
                data_buf = []
            continue
        if line.startswith("data:"):
            data_buf.append(line[5:].strip())
    if data_buf:
        candidates.append("\n".join(data_buf))
    fallback = None
    for payload in candidates:
        try:
            msg = json.loads(payload)
        except ValueError:
            continue
        if not isinstance(msg, dict):
            continue
        if "result" in msg or "error" in msg:
            if want_id is None or msg.get("id") == want_id:
                return msg
            if fallback is None:
                fallback = msg
    if fallback is not None:
        return fallback
    raise MCPError("PARSE_ERROR", "no JSON-RPC payload in SSE stream")


def _parse_response(resp, want_id=None):
    """Extract the JSON-RPC object from either a JSON body or an SSE stream."""
    ctype = resp.headers.get("content-type", "")
    text = resp.text
    if "text/event-stream" in ctype:
        return _parse_sse(text, want_id)
    try:
        return json.loads(text)
    except ValueError as exc:
        raise MCPError("PARSE_ERROR", "invalid JSON response: %s" % exc)


# --- OAuth token bridge ------------------------------------------------------
#
# [T-mcp-static-oauth] Servers whose config carries an `oauth` object are
# authorized natively (the app runs the PKCE Authorization Code flow and owns
# the Keychain-backed credentials). The native side materializes a token
# bridge file the guest can read:
#
#     /var/minis/mcp-servers/oauth/<server>.json
#     { "access_token": "...", "expires_at": 1789999999,
#       "refresh_token": "...", "token_endpoint": "https://...",
#       "client_id": "...", "client_secret": "..." }        # secret optional
#
# The transport attaches `Authorization: Bearer <access_token>` and, on 401 or
# a token that is already past `expires_at`, performs a standard
# refresh_token grant against `token_endpoint` and rewrites the bridge file
# (so the native side and later calls see the fresh token). If refresh is
# impossible/fails, AUTH_REQUIRED tells the agent/user to re-authorize in
# Settings → MCP Integrations. The bridge file lives OUTSIDE servers.json on
# purpose: servers.json syncs across devices via iCloud, tokens must not.

OAUTH_DIR = "/var/minis/mcp-servers/oauth"


def _oauth_token_path(server_name):
    return os.path.join(OAUTH_DIR, "%s.json" % server_name)


def _authorize_deeplink(server_name):
    """[T-mcp-oauth-deeplink] Markdown link that jumps straight to the
    server's edit form in the app (where the Authorize button is). The agent
    relays error text verbatim, so chat renders this as a tappable link.
    Server names may contain URL-unsafe chars — percent-encode the path
    segment; the iOS/Android deep-link routers decode it back."""
    from urllib.parse import quote
    return "[Authorize](minis://settings/mcp-servers/%s)" % quote(server_name, safe="")


def _load_oauth_tokens(server_name):
    try:
        with open(_oauth_token_path(server_name), "r", encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def _save_oauth_tokens(server_name, tokens):
    try:
        os.makedirs(OAUTH_DIR, exist_ok=True)
        tmp = _oauth_token_path(server_name) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(tokens, f)
        os.replace(tmp, _oauth_token_path(server_name))
        try:
            os.chmod(_oauth_token_path(server_name), 0o600)
        except OSError:
            pass
    except OSError:
        pass  # best-effort; next call refreshes again


class HTTPTransport:
    def __init__(self, server, server_name):
        self.url = expand_env(server.get("url", ""))
        self.headers = _expand_headers(server.get("headers"))
        self.server_name = server_name
        self.oauth_cfg = server.get("oauth") if isinstance(server.get("oauth"), dict) else None
        self._id = 0
        self._session_id = None
        # [T-mcp-http-reinit] True once a handshake has completed for this
        # transport instance. The daemon reuses one HTTPTransport across calls,
        # so re-running initialize() every call made a STATEFUL streamable-HTTP
        # server (one that returns Mcp-Session-Id) reject the 2nd+ call with
        # HTTP 400 "Server already initialized". Tracked separately from
        # _session_id because a stateless server never sets _session_id yet
        # still must not be re-initialized.
        self._initialized = False
        # [T-mcp-sse-listener-race] Guards the check-then-start in
        # start_event_listener and the teardown in stop_event_listener.
        self._listener_lock = threading.Lock()

    def _next_id(self):
        self._id += 1
        return self._id

    # -- OAuth helpers -------------------------------------------------------

    def _oauth_access_token(self, force_refresh=False):
        """Current access token for an oauth server, refreshing if expired or
        forced. Raises AUTH_REQUIRED when no usable token can be produced."""
        tokens = _load_oauth_tokens(self.server_name)
        if not tokens or not tokens.get("access_token"):
            raise MCPError("AUTH_REQUIRED", (
                "server '%s' uses OAuth but has no stored token — tap %s to "
                "open its settings page and sign in"
                % (self.server_name, _authorize_deeplink(self.server_name))))
        import time as _time
        expired = False
        exp = tokens.get("expires_at")
        if isinstance(exp, (int, float)) and exp > 0:
            expired = _time.time() > (exp - 60)  # refresh 60s early
        if force_refresh or expired:
            refreshed = self._oauth_refresh(tokens)
            if refreshed:
                return refreshed["access_token"]
            if force_refresh or expired:
                raise MCPError("AUTH_REQUIRED", (
                    "server '%s' OAuth token expired and refresh failed — "
                    "tap %s to re-authorize"
                    % (self.server_name, _authorize_deeplink(self.server_name))))
        return tokens["access_token"]

    def _oauth_refresh(self, tokens):
        """refresh_token grant → rewrite the bridge file. Returns the new token
        dict or None."""
        refresh_token = tokens.get("refresh_token")
        token_endpoint = tokens.get("token_endpoint")
        client_id = tokens.get("client_id")
        if not (refresh_token and token_endpoint and client_id) or not _ensure_httpx():
            return None
        form = {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": client_id,
        }
        if tokens.get("client_secret"):
            form["client_secret"] = tokens["client_secret"]
        # [T-mcp-oauth-resource] RFC 8707 Resource Indicator, required by the
        # MCP auth spec on every token request. The native side writes the
        # canonical server URI into the bridge file; reuse it verbatim.
        if tokens.get("resource"):
            form["resource"] = tokens["resource"]
        try:
            resp = httpx.post(token_endpoint, data=form, timeout=30)
        except httpx.HTTPError:
            return None
        if resp.status_code >= 400:
            return None
        try:
            payload = resp.json()
        except ValueError:
            return None
        access = payload.get("access_token")
        if not access:
            return None
        import time as _time
        new_tokens = dict(tokens)
        new_tokens["access_token"] = access
        # Some providers rotate the refresh token on every grant.
        if payload.get("refresh_token"):
            new_tokens["refresh_token"] = payload["refresh_token"]
        if payload.get("expires_in"):
            try:
                new_tokens["expires_at"] = int(_time.time()) + int(payload["expires_in"])
            except (TypeError, ValueError):
                pass
        _save_oauth_tokens(self.server_name, new_tokens)
        return new_tokens

    def _post(self, method, params=None, notify=False, _oauth_retried=False):
        if not _ensure_httpx():
            raise MCPError("CONNECTION_ERROR", "httpx unavailable")
        body = {"jsonrpc": "2.0", "method": method}
        if not notify:
            body["id"] = self._next_id()
        if params is not None:
            body["params"] = params
        # Force the MCP Streamable HTTP (2025-03-26) required Accept and
        # Content-Type regardless of how the user configured server.headers.
        # setdefault is case-sensitive, so a user-supplied "accept" /
        # "content-type" (any casing) or an incomplete Accept that omits
        # text/event-stream would slip through and some gateways reject it
        # (e.g. 401 "oauth token is not found"). Drop any case-variant of these
        # two keys, then set the canonical values; all other headers
        # (Authorization, etc.) keep their original casing and value.
        headers = {
            k: v for k, v in self.headers.items()
            if k.lower() not in ("accept", "content-type")
        }
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json, text/event-stream"
        # [T-mcp-static-oauth] OAuth servers get their Authorization from the
        # native-materialized token bridge, overriding any static header.
        if self.oauth_cfg is not None:
            headers["Authorization"] = "Bearer %s" % self._oauth_access_token(
                force_refresh=_oauth_retried)
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        # [T-mcp-protocol-version-header] The 2025-06-18 spec requires clients
        # to declare the negotiated version on every post-initialize request.
        if self._initialized:
            headers["MCP-Protocol-Version"] = "2025-06-18"
        # [T-mcp-http-timeout-phases] A scalar timeout applied to EVERY phase,
        # so an UNREACHABLE host burned the full 300s budget inside connect()
        # before any error surfaced. Bound connect/pool at 15s; keep the long
        # read budget for genuinely slow tool executions.
        timeout = httpx.Timeout(connect=15.0, read=TIMEOUT_SECONDS,
                                write=30.0, pool=15.0)
        resp = None
        # [T-mcp-http-connect-retry] ONE retry on connect-phase failures only:
        # nothing was sent, so the retry cannot double-execute a tool call. A
        # failure after send (read timeout, reset mid-response) is NOT retried
        # — tools/call may be non-idempotent.
        for attempt in range(2):
            try:
                resp = httpx.post(self.url, json=body, headers=headers,
                                  timeout=timeout)
                break
            except httpx.ConnectTimeout as exc:
                if attempt == 0:
                    time.sleep(0.5)
                    continue
                raise MCPError("CONNECTION_ERROR",
                               "could not connect (timeout): %s" % exc)
            except httpx.ConnectError as exc:
                if attempt == 0:
                    time.sleep(0.5)
                    continue
                raise MCPError("CONNECTION_ERROR", "could not connect: %s" % exc)
            except httpx.TimeoutException:
                raise MCPError("TIMEOUT",
                               "request timed out after %ds" % TIMEOUT_SECONDS)
            except httpx.HTTPError as exc:
                raise MCPError("CONNECTION_ERROR", str(exc))
        # Capture a session id handed back by the server (streamable-HTTP).
        sid = resp.headers.get("mcp-session-id")
        if sid:
            self._session_id = sid
        # [T-mcp-static-oauth] 401 on an oauth server: refresh once and retry
        # the same request; a second 401 falls through to AUTH_REQUIRED via
        # _oauth_access_token(force_refresh=True) on the retry, or to the
        # generic error below if refresh succeeded but the server still 401s.
        if resp.status_code == 401 and self.oauth_cfg is not None and not _oauth_retried:
            return self._post(method, params=params, notify=notify, _oauth_retried=True)
        if resp.status_code >= 400:
            raise MCPError(
                "CONNECTION_ERROR", "HTTP %d: %s" % (resp.status_code, resp.text[:200])
            )
        if notify:
            return None
        rpc = _parse_response(resp, want_id=body.get("id"))
        if isinstance(rpc, dict) and rpc.get("error"):
            err = rpc["error"]
            raise MCPError("MCP_ERROR", err.get("message", json.dumps(err)))
        return rpc.get("result") if isinstance(rpc, dict) else rpc

    def initialize(self):
        result = self._post(
            "initialize",
            {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "minis-mcp-cli", "version": "1.0.0"},
            },
        )
        # MCP requires a notifications/initialized after a successful init.
        try:
            self._post("notifications/initialized", notify=True)
        except MCPError:
            pass
        self._initialized = True
        return result

    def _ensure_initialized(self):
        """[T-mcp-http-reinit] Handshake once per transport lifetime, not once
        per call. A stateful streamable-HTTP server tracks the session and
        rejects a duplicate `initialize` (HTTP 400 "Server already
        initialized"); a stateless server tolerates re-init but gains nothing
        from it. Either way, one handshake is correct."""
        if not self._initialized:
            self.initialize()

    def _reset_session(self):
        """Forget the current session so the next call re-handshakes from
        scratch. Used for self-healing when the server reports the session is
        stale/unknown."""
        self._session_id = None
        self._initialized = False
        # [T-mcp-sse-listener-race] The GET SSE stream is bound to the dead
        # session's id; tear it down so the next call re-arms a fresh one
        # instead of waiting for the server to EOF the orphan.
        self.stop_event_listener()

    @staticmethod
    def _is_session_error(err):
        """True when an MCPError indicates the session state disagrees with the
        server — i.e. re-handshaking is the right recovery. Covers a duplicate
        initialize being rejected AND a server that has forgotten our session
        (restart / eviction), both of which self-heal by a fresh handshake."""
        msg = str(err).lower()
        return (
            "already initialized" in msg
            or "session" in msg          # "no valid session", "session expired/not found", …
        )

    def _call_with_reconnect(self, do_call):
        """Run `do_call` after ensuring a handshake, and if it fails with a
        session-state error, drop the session and retry ONCE from a fresh
        handshake. One retry only, so a persistently broken server surfaces the
        real error instead of looping."""
        self._ensure_initialized()
        try:
            return do_call()
        except MCPError as exc:
            if not self._is_session_error(exc):
                raise
            self._reset_session()
            self.initialize()
            return do_call()

    # -- GET event stream (server-initiated messages) --------------------------
    #
    # [T-mcp-http-get-sse] Streamable HTTP lets a client hold a GET SSE stream
    # for server→client notifications (e.g. tools/list_changed) and requests
    # (ping / sampling / roots). BEST-EFFORT by contract: tool calls never
    # depend on this stream, a server without GET support answers 405 and the
    # listener simply ends, and every failure inside the listener is swallowed.
    # We answer server requests on the POST channel: ping → {}, anything else
    # → JSON-RPC -32601 (this client implements no sampling/roots features).

    def start_event_listener(self, on_notification=None, on_server_request=None):
        # [T-mcp-sse-listener-race] Check-then-start must be atomic: the daemon
        # arms this from tool-call threads, and two concurrent starts used to
        # spawn duplicate GET SSE streams whose first stop event was then
        # overwritten — the orphan thread could never be stopped.
        with self._listener_lock:
            if getattr(self, "_listener_thread", None) and self._listener_thread.is_alive():
                return
            self._listener_stop = threading.Event()
            self._listener_thread = threading.Thread(
                target=self._event_listener,
                args=(on_notification, on_server_request),
                daemon=True,
            )
            self._listener_thread.start()

    def stop_event_listener(self):
        with self._listener_lock:
            stop = getattr(self, "_listener_stop", None)
            if stop is not None:
                stop.set()
            resp = getattr(self, "_listener_resp", None)
            if resp is not None:
                try:
                    resp.close()
                except Exception:
                    pass

    def _listener_headers(self):
        headers = dict(self.headers)
        headers["Accept"] = "text/event-stream"
        if self.oauth_cfg is not None:
            try:
                headers["Authorization"] = "Bearer %s" % self._oauth_access_token()
            except MCPError:
                return None  # no usable token → no listener; calls will surface it
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        if self._initialized:
            headers["MCP-Protocol-Version"] = "2025-06-18"
        return headers

    def _event_listener(self, on_notification, on_server_request):
        try:
            headers = self._listener_headers()
            if headers is None:
                return
            with httpx.stream(
                "GET", self.url, headers=headers,
                timeout=httpx.Timeout(connect=15.0, read=None,
                                      write=30.0, pool=15.0),
            ) as resp:
                if resp.status_code >= 400:
                    return  # 405 = no GET stream; nothing to listen to
                self._listener_resp = resp
                data_buf = []
                for raw in resp.iter_lines():
                    if self._listener_stop.is_set():
                        return
                    line = raw.strip() if isinstance(raw, str) else raw.decode().strip()
                    if not line:
                        if data_buf:
                            self._dispatch_event(
                                "\n".join(data_buf), on_notification, on_server_request)
                            data_buf = []
                        continue
                    if line.startswith("data:"):
                        data_buf.append(line[5:].strip())
                if data_buf:
                    self._dispatch_event(
                        "\n".join(data_buf), on_notification, on_server_request)
        except Exception:
            return  # best-effort by contract — never propagate
        finally:
            self._listener_resp = None

    def _dispatch_event(self, payload, on_notification, on_server_request):
        try:
            msg = json.loads(payload)
        except ValueError:
            return
        if not isinstance(msg, dict) or "method" not in msg:
            return  # responses to OUR requests do not arrive on this stream
        if "id" in msg:
            if on_server_request:
                on_server_request(msg["id"], msg["method"], msg.get("params"))
        else:
            if on_notification:
                on_notification(msg["method"], msg.get("params"))

    def _respond_to_server_request(self, req_id, method):
        body = {"jsonrpc": "2.0", "id": req_id}
        if method == "ping":
            body["result"] = {}
        else:
            body["error"] = {"code": -32601,
                             "message": "method not supported by this client: %s" % method}
        headers = dict(self.headers)
        headers["Content-Type"] = "application/json"
        if self.oauth_cfg is not None:
            try:
                headers["Authorization"] = "Bearer %s" % self._oauth_access_token()
            except MCPError:
                return
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        if self._initialized:
            headers["MCP-Protocol-Version"] = "2025-06-18"
        try:
            httpx.post(self.url, json=body, headers=headers,
                       timeout=httpx.Timeout(connect=15.0, read=30.0,
                                             write=30.0, pool=15.0))
        except httpx.HTTPError:
            pass  # best-effort

    def list_tools(self):
        result = self._call_with_reconnect(lambda: self._post("tools/list"))
        return (result or {}).get("tools", [])

    def call_tool(self, tool, arguments):
        return self._call_with_reconnect(
            lambda: self._post("tools/call", {"name": tool, "arguments": arguments or {}})
        )

    def ping(self):
        """Reachability check via a fresh anonymous handshake.

        [T-mcp-http-reinit] Ping deliberately does NOT reuse the cached
        session: it drops any existing session first so a stateful server sees
        a clean initialize rather than a duplicate one (which it would reject),
        and it never leaves a half-open session behind for the real calls to
        trip over. Because it re-inits from scratch, the very next call_tool /
        list_tools reuses the session ping established — no wasted extra
        handshake."""
        self._reset_session()
        self.initialize()
        return True
