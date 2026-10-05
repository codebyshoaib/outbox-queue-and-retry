# Phase 1b: chaos proxy (C1-C4)

`chaos/proxy.py` sits between the phone and Django and fails on command. Python stdlib only.
Tests in `chaos/test_proxy.py` start a fake upstream (counts requests, records bodies and
arrival times) and the proxy in-process on ephemeral ports.

## C1 Pass-through

- **Tests:** `test_C1_forwards_request_and_relays_reply_unchanged`,
  `test_C1_forwards_get_without_body`
- **Red:** `ModuleNotFoundError: No module named 'chaos.proxy'`
- **Green:** `ThreadingHTTPServer` handler that copies method, path+query, headers (minus
  hop-by-hop and `Host`) and body to the upstream with `http.client`, reads the full reply, and
  relays status, headers and body with `Connection: close`.
- **Lesson:** a proxy needs one decision per header. Hop-by-hop headers (`Connection`,
  `Transfer-Encoding`, ...) describe one link, not the message, so they are not copied across.

- **Test:** `test_C1_upstream_down_replies_502` (added after C4, for T3 "server down")
- **Red:** `http.client.RemoteDisconnected: Remote end closed connection without response`
  (plus a traceback per request on the proxy)
- **Green:** catch `OSError` around the forward and reply `502 {"error": ...}`.
- **Lesson:** when Django is down, the proxy should say so with a 5xx, not pretend to be a
  network fault. Both are transient for the phone, but a 502 is easier to read in the logs.

## C2 Control endpoint

- **Tests:** `test_C2_default_state_is_pass_through`, `test_C2_post_sets_mode_and_get_shows_it`,
  `test_C2_each_forwarded_request_consumes_one_then_back_to_pass_through`,
  `test_C2_count_defaults_to_one_and_zero_clears`, `test_C2_invalid_input_replies_400_and_keeps_state`
- **Red:** `AssertionError: 201 != 200`: `/_chaos` was forwarded to the upstream like any path.
- **Green:** a `Chaos` object (`mode`, `count`, one lock). `take()` consumes one unit per forwarded
  request and returns to pass-through at zero. `/_chaos` is routed before `take()`. Bad JSON, an
  unknown mode, `status` outside 100-599, a negative/non-integer `delay` or `count` all reply 400
  and leave the state unchanged.
- **Lesson:** read-and-decrement must happen under one lock. Two concurrent requests that both
  read `count == 1` would both fail, and "fail the next 1 request" would become "fail 2".
- **Later fix:** an empty `POST /_chaos` body crashed the handler (`json.loads(None)` raises
  `TypeError`, not `ValueError`). Found while refactoring; the test case was added in the same
  commit as the fix, not before it.

## C3 Modes

### drop_request

- **Test:** `test_C3_drop_request_closes_connection_without_forwarding`
- **Red:** `AssertionError: ConnectionError not raised`
- **Green:** read the request body, write nothing, close.
- **Lesson:** reading the body first matters. Closing a socket with unread data makes the kernel
  send a RST instead of a FIN, so the client error would change from "closed" to "reset" by timing.

### drop_reply (the lost ack)

- **Tests:** `test_C3_drop_reply_reaches_upstream_but_client_gets_no_response`,
  `test_C3_drop_reply_retry_reaches_upstream_a_second_time`
- **Red:** `AssertionError: RemoteDisconnected not raised`
- **Green:** forward, read the **full** upstream response (so the server has committed and
  replied), then close without writing a byte. The client gets
  `http.client.RemoteDisconnected` (curl: exit 52, "Empty reply from server").
- **Lesson:** this is the failure that makes idempotency necessary. With a lost request the
  phone's retry is the first copy. With a lost reply the phone sees the same error, but the server
  already has the message, so the retry is a duplicate. The phone cannot tell the two apart; only
  the server's `(device_id, client_msg_id)` dedupe (S2) makes the retry safe. The second test
  shows the proxy really does deliver both copies.

### status:<code>

- **Test:** `test_C3_status_replies_code_with_json_body_without_forwarding`
- **Red:** `AssertionError: 201 != 503`
- **Green:** reply `<code>` with `{"chaos": "status:<code>"}`, never forward.
- **Lesson:** one switch drives both phone paths: `status:503` is transient (P5, retry with
  backoff), `status:400` is permanent (P6, fail and stop). That is T4.

### delay:<ms>

- **Test:** `test_C3_delay_waits_before_forwarding`
- **Red:** `AssertionError: 0.0009687419951660559 not greater than or equal to 0.3`
- **Green:** `time.sleep(ms / 1000)` before forwarding.
- **Lesson:** time it at the upstream (when the request arrived), not at the client, so the test
  proves the wait happened *before* forwarding. It checks only a lower bound, so a slow machine
  cannot make it flaky.

## C4 Control is immune

- **Test:** `test_C4_chaos_endpoint_is_never_dropped_delayed_or_counted`
- **Red:** none. It passed on arrival, because C2 already routes `/_chaos` before `take()`.
  To prove the test can fail, I added `self.server.chaos.take()` as the first line of `_handle`
  and reran it: `AssertionError: {'mode': None, 'count': 0} != {'mode': 'drop_request', 'count': 1}`.
  Then I removed that line.
- **Green:** no new code.
- **Lesson:** a test that is green on its first run has not been shown to test anything. A
  one-line mutation is the cheapest way to check it can catch the bug. The rule also matters in
  practice: if `/_chaos` could be dropped, a test harness could never reset a proxy that had
  `drop_request` pending.

## How to run

```sh
# tests (from the repo root)
python3 -m unittest -v chaos/test_proxy.py

# proxy in front of Django
python3 chaos/proxy.py --listen 0.0.0.0:8081 --upstream http://127.0.0.1:8080
```

Each request prints one line to stderr: `POST /messages mode=drop_reply` (`pass` when no chaos,
`control` for `/_chaos`).

```sh
P=http://localhost:8091  # 8081 is taken on this laptop

curl -s $P/_chaos                                                    # {"mode": null, "count": 0}
curl -s -XPOST $P/_chaos -d '{"mode": "drop_reply", "count": 1}'     # T1: lose the next ack
curl -s -XPOST $P/_chaos -d '{"mode": "drop_request", "count": 2}'   # next 2 never reach Django
curl -s -XPOST $P/_chaos -d '{"mode": "status:400", "count": 1}'     # T4: permanent error
curl -s -XPOST $P/_chaos -d '{"mode": "status:503", "count": 3}'     # transient 5xx x3
curl -s -XPOST $P/_chaos -d '{"mode": "delay:5000", "count": 1}'     # slow network
curl -s -XPOST $P/_chaos -d '{"mode": "drop_reply", "count": 0}'     # clear: back to pass-through
```

`count` defaults to 1 when omitted. `count: 0` clears any pending mode.
