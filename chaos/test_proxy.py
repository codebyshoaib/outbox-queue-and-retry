import http.client
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from chaos.proxy import make_server


class FakeUpstream:
    """Counts requests and records what arrived; replies 201 with a fixed JSON body."""

    def __init__(self):
        self.requests = []
        self.lock = threading.Lock()
        upstream = self

        class Handler(BaseHTTPRequestHandler):
            def _handle(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length)
                with upstream.lock:
                    upstream.requests.append(
                        {"method": self.command, "path": self.path,
                         "headers": dict(self.headers), "body": body})
                reply = b'{"server_seq": 1}'
                self.send_response(201)
                self.send_header("Content-Type", "application/json")
                self.send_header("X-Upstream", "yes")
                self.send_header("Content-Length", str(len(reply)))
                self.end_headers()
                self.wfile.write(reply)

            do_GET = do_POST = do_PUT = do_DELETE = _handle

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def url(self):
        return "http://127.0.0.1:%d" % self.server.server_address[1]

    @property
    def count(self):
        with self.lock:
            return len(self.requests)

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class ProxyTestCase(unittest.TestCase):
    def setUp(self):
        self.upstream = FakeUpstream()
        self.proxy = make_server(("127.0.0.1", 0), self.upstream.url)
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        self.port = self.proxy.server_address[1]

    def tearDown(self):
        self.proxy.shutdown()
        self.proxy.server_close()
        self.upstream.close()

    def request(self, method, path, body=None, headers=None):
        """Returns (status, headers, body bytes). Raises on connection errors."""
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            resp = conn.getresponse()
            return resp.status, resp.headers, resp.read()
        finally:
            conn.close()


class TestC1PassThrough(ProxyTestCase):
    def test_C1_forwards_request_and_relays_reply_unchanged(self):
        body = json.dumps({"client_msg_id": "abc", "chat_id": "c1", "body": "hi"}).encode()
        status, headers, reply = self.request(
            "POST", "/messages?chat_id=c1", body=body,
            headers={"X-Device-Id": "dev-1", "Content-Type": "application/json"})

        self.assertEqual(self.upstream.count, 1)
        got = self.upstream.requests[0]
        self.assertEqual(got["method"], "POST")
        self.assertEqual(got["path"], "/messages?chat_id=c1")
        self.assertEqual(got["headers"]["X-Device-Id"], "dev-1")
        self.assertEqual(got["headers"]["Content-Type"], "application/json")
        self.assertEqual(got["body"], body)

        self.assertEqual(status, 201)
        self.assertEqual(headers["X-Upstream"], "yes")
        self.assertEqual(reply, b'{"server_seq": 1}')

    def test_C1_forwards_get_without_body(self):
        status, _, reply = self.request("GET", "/messages?chat_id=c1")
        self.assertEqual(status, 201)
        self.assertEqual(self.upstream.requests[0]["method"], "GET")
        self.assertEqual(self.upstream.requests[0]["body"], b"")


if __name__ == "__main__":
    unittest.main()
