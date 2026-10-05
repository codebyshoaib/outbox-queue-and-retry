"""Chaos proxy: forwards HTTP to an upstream and fails on command (SPEC C1-C4).

    python3 chaos/proxy.py --listen 0.0.0.0:8081 --upstream http://127.0.0.1:8080
"""
import argparse
import http.client
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"}


def parse_mode(mode):
    """Returns the mode if valid, else raises ValueError."""
    if mode in ("drop_request", "drop_reply"):
        return mode
    kind, _, arg = mode.partition(":") if isinstance(mode, str) else ("", "", "")
    if kind == "status" and arg.isdigit() and 100 <= int(arg) <= 599:
        return mode
    if kind == "delay" and arg.isdigit():
        return mode
    raise ValueError("mode must be drop_request, drop_reply, status:<100-599> or delay:<ms>")


class Chaos:
    """Pending failure mode and how many forwarded requests it still applies to."""

    def __init__(self):
        self.lock = threading.Lock()
        self.mode, self.count = None, 0

    def state(self):
        with self.lock:
            return {"mode": self.mode, "count": self.count}

    def set(self, payload):
        if not isinstance(payload, dict):
            raise ValueError("body must be a JSON object")
        mode = parse_mode(payload.get("mode"))
        count = payload.get("count", 1)
        if type(count) is not int or count < 0:
            raise ValueError("count must be an integer >= 0")
        with self.lock:
            self.mode, self.count = (mode, count) if count else (None, 0)
            return {"mode": self.mode, "count": self.count}

    def take(self):
        """Consumes one unit and returns the mode to apply, or None for pass-through."""
        with self.lock:
            if not self.count:
                return None
            mode = self.mode
            self.count -= 1
            if not self.count:
                self.mode = None
            return mode


class ProxyHandler(BaseHTTPRequestHandler):
    # HTTP/1.0 default: one request per connection.

    def log_request(self, *args):
        pass  # replaced by one line per request in _handle

    def _log(self, mode):
        sys.stderr.write("%s %s mode=%s\n" % (self.command, self.path, mode or "pass"))

    def _json(self, status, obj):
        self._reply(status, [("Content-Type", "application/json")], json.dumps(obj).encode())

    def _control(self):
        if self.command == "POST":
            length = int(self.headers.get("Content-Length") or 0)
            try:
                return self._json(200, self.server.chaos.set(json.loads(self.rfile.read(length))))
            except ValueError as e:  # JSONDecodeError is a ValueError
                return self._json(400, {"error": str(e)})
        if self.command == "GET":
            return self._json(200, self.server.chaos.state())
        self._json(405, {"error": "use GET or POST"})

    def _forward(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP}
        up = urlsplit(self.server.upstream)
        conn = http.client.HTTPConnection(up.hostname, up.port or 80, timeout=30)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            resp = conn.getresponse()
            return resp.status, resp.getheaders(), resp.read()
        finally:
            conn.close()

    def _reply(self, status, headers, body):
        self.send_response(status)
        for k, v in headers:
            if k.lower() not in HOP_BY_HOP:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _handle(self):
        if urlsplit(self.path).path == "/_chaos":
            self._log("control")
            return self._control()
        mode = self.server.chaos.take()
        self._log(mode)
        self._reply(*self._forward())

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _handle


def make_server(listen, upstream):
    """listen: (host, port), port 0 for ephemeral. upstream: 'http://host:port'."""
    server = ThreadingHTTPServer(listen, ProxyHandler)
    server.daemon_threads = True
    server.upstream = upstream
    server.chaos = Chaos()
    return server


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--listen", default="0.0.0.0:8081")
    p.add_argument("--upstream", default="http://127.0.0.1:8080")
    args = p.parse_args(argv)
    host, port = args.listen.rsplit(":", 1)
    server = make_server((host, int(port)), args.upstream)
    print("chaos proxy %s -> %s" % (args.listen, args.upstream), file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
