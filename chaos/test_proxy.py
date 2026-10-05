import http.client
import json
import threading
import time
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
                         "headers": dict(self.headers), "body": body,
                         "at": time.monotonic()})
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
        threading.Thread(target=self.server.serve_forever, args=(0.01,), daemon=True).start()

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
        threading.Thread(target=self.proxy.serve_forever, args=(0.01,), daemon=True).start()
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


class ChaosControlMixin:
    def set_chaos(self, payload):
        status, _, body = self.request(
            "POST", "/_chaos", body=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json"})
        return status, json.loads(body)

    def get_chaos(self):
        status, _, body = self.request("GET", "/_chaos")
        self.assertEqual(status, 200)
        return json.loads(body)


class TestC2ChaosControl(ChaosControlMixin, ProxyTestCase):
    def test_C2_default_state_is_pass_through(self):
        self.assertEqual(self.get_chaos(), {"mode": None, "count": 0})

    def test_C2_post_sets_mode_and_get_shows_it(self):
        status, state = self.set_chaos({"mode": "delay:0", "count": 2})
        self.assertEqual(status, 200)
        self.assertEqual(state, {"mode": "delay:0", "count": 2})
        self.assertEqual(self.get_chaos(), {"mode": "delay:0", "count": 2})

    def test_C2_each_forwarded_request_consumes_one_then_back_to_pass_through(self):
        self.set_chaos({"mode": "delay:0", "count": 2})
        self.request("GET", "/messages")
        self.assertEqual(self.get_chaos(), {"mode": "delay:0", "count": 1})
        self.request("GET", "/messages")
        self.assertEqual(self.get_chaos(), {"mode": None, "count": 0})
        status, _, _ = self.request("GET", "/messages")
        self.assertEqual(status, 201)
        self.assertEqual(self.upstream.count, 3)

    def test_C2_count_defaults_to_one_and_zero_clears(self):
        self.assertEqual(self.set_chaos({"mode": "drop_reply"})[1],
                         {"mode": "drop_reply", "count": 1})
        self.assertEqual(self.set_chaos({"mode": "drop_reply", "count": 0})[1],
                         {"mode": None, "count": 0})

    def test_C2_invalid_input_replies_400_and_keeps_state(self):
        self.set_chaos({"mode": "delay:0", "count": 1})
        for bad in [{"mode": "explode"}, {"mode": "status:abc"}, {"mode": "status:99"},
                    {"mode": "status:600"}, {"mode": "delay:-1"}, {"mode": "delay:x"},
                    {"mode": "drop_reply", "count": -1}, {"mode": "drop_reply", "count": "2"},
                    {"mode": "drop_reply", "count": True}, {"count": 1}, ["drop_reply"]]:
            with self.subTest(bad=bad):
                status, body = self.set_chaos(bad)
                self.assertEqual(status, 400)
                self.assertIn("error", body)
        status, _, _ = self.request("POST", "/_chaos", body=b"not json")
        self.assertEqual(status, 400)
        self.assertEqual(self.get_chaos(), {"mode": "delay:0", "count": 1})
        self.assertEqual(self.upstream.count, 0)


MSG = json.dumps({"client_msg_id": "abc", "chat_id": "c1", "body": "hi"}).encode()


class TestC3Modes(ChaosControlMixin, ProxyTestCase):
    def test_C3_drop_request_closes_connection_without_forwarding(self):
        self.set_chaos({"mode": "drop_request", "count": 1})
        with self.assertRaises(ConnectionError):  # RemoteDisconnected is a ConnectionResetError
            self.request("POST", "/messages", body=MSG)
        self.assertEqual(self.upstream.count, 0)
        self.assertEqual(self.request("POST", "/messages", body=MSG)[0], 201)
        self.assertEqual(self.upstream.count, 1)

    def test_C3_drop_reply_reaches_upstream_but_client_gets_no_response(self):
        self.set_chaos({"mode": "drop_reply", "count": 1})
        # Not a status line, not an empty body: the socket closes with zero bytes written.
        with self.assertRaises(http.client.RemoteDisconnected):
            self.request("POST", "/messages", body=MSG, headers={"X-Device-Id": "dev-1"})
        # The server did the work (and would have committed) before the client saw the drop.
        self.assertEqual(self.upstream.count, 1)
        self.assertEqual(self.upstream.requests[0]["body"], MSG)

    def test_C3_drop_reply_retry_reaches_upstream_a_second_time(self):
        self.set_chaos({"mode": "drop_reply", "count": 1})
        with self.assertRaises(http.client.RemoteDisconnected):
            self.request("POST", "/messages", body=MSG)
        status, _, _ = self.request("POST", "/messages", body=MSG)
        self.assertEqual(status, 201)
        # Two copies upstream: deduping by client_msg_id is the server's job (S2).
        self.assertEqual([r["body"] for r in self.upstream.requests], [MSG, MSG])

    def test_C3_status_replies_code_with_json_body_without_forwarding(self):
        for code in (503, 400):
            with self.subTest(code=code):
                self.set_chaos({"mode": "status:%d" % code, "count": 1})
                status, headers, body = self.request("POST", "/messages", body=MSG)
                self.assertEqual(status, code)
                self.assertEqual(headers["Content-Type"], "application/json")
                self.assertEqual(json.loads(body)["chaos"], "status:%d" % code)
        self.assertEqual(self.upstream.count, 0)

    def test_C3_delay_waits_before_forwarding(self):
        self.set_chaos({"mode": "delay:300", "count": 1})
        sent = time.monotonic()
        status, _, _ = self.request("POST", "/messages", body=MSG)
        self.assertEqual(status, 201)
        self.assertEqual(self.upstream.count, 1)
        # Lower bound only, so a slow machine cannot make this flaky.
        self.assertGreaterEqual(self.upstream.requests[0]["at"] - sent, 0.3)


class TestC4ControlImmune(ChaosControlMixin, ProxyTestCase):
    def test_C4_chaos_endpoint_is_never_dropped_delayed_or_counted(self):
        for mode in ("drop_request", "drop_reply", "status:503", "delay:2000"):
            with self.subTest(mode=mode):
                self.assertEqual(self.set_chaos({"mode": mode, "count": 1})[0], 200)
                started = time.monotonic()
                for _ in range(3):
                    self.assertEqual(self.get_chaos(), {"mode": mode, "count": 1})
                    status, _, _ = self.request("GET", "/_chaos?verbose=1")
                    self.assertEqual(status, 200)
                self.assertLess(time.monotonic() - started, 1.0)  # delay:2000 not applied
                # Overwriting the pending mode is itself a /_chaos call; it must get through.
                self.assertEqual(self.set_chaos({"mode": mode, "count": 1})[0], 200)
        self.assertEqual(self.upstream.count, 0)


if __name__ == "__main__":
    unittest.main()
