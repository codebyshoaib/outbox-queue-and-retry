"""Chaos proxy: forwards HTTP to an upstream and fails on command (SPEC C1-C4).

    python3 chaos/proxy.py --listen 0.0.0.0:8081 --upstream http://127.0.0.1:8080
"""
import argparse
import http.client
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
              "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"}


class ProxyHandler(BaseHTTPRequestHandler):
    # HTTP/1.0 default: one request per connection.

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
        self._reply(*self._forward())

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _handle


def make_server(listen, upstream):
    """listen: (host, port), port 0 for ephemeral. upstream: 'http://host:port'."""
    server = ThreadingHTTPServer(listen, ProxyHandler)
    server.daemon_threads = True
    server.upstream = upstream
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
