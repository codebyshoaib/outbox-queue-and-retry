# Outbox queue and retry

A playground that proves one thing on a real Android phone: **a message typed with bad or no network
is delivered exactly once, in order, without the user doing anything.** It rebuilds the sending half
of what WhatsApp does offline: save first, retry with the same id, the server drops duplicates, ✓ on
the server's ack.

Built test-first. Every behaviour in [SPEC.md](SPEC.md) has an id (S1–S6, C1–C5, P1–P9, A1–A6), and
`git log` shows each one as a failing `test(X)` commit followed by a passing `feat(X)` commit.

| Piece | Stack | What it does |
|---|---|---|
| [`server/`](server) | Django + DRF, SQLite | `UNIQUE(device, client_msg_id)` dedupe, per-chat `server_seq`, reply only after commit |
| [`chaos/`](chaos) | Python stdlib | HTTP proxy that fails on command: drop the request, drop the reply (lost ack), return a status, delay |
| [`android/core`](android/core) | Pure Kotlin | The outbox: save first, one in flight per chat, back-off 1 s → 60 s × jitter, 4xx = failed |
| [`android/app`](android/app) | Room, WorkManager, Compose | Transactions per write, network-constrained work, a send never waits behind a back-off |

```
phone (android/app)  --HTTP-->  chaos proxy :8091  --HTTP-->  Django :8080
```

## What was proven

- Automated on a device against the real proxy and server: lost ack → exactly one row (T1),
  permanent error → failed then retried (T4), three 503s → delivered in order (T3a).
- By hand: airplane mode, five messages sent offline, all delivered in typed order on reconnect (T5);
  server down with growing retry gaps, then delivered (T3); after the P9/A6 fix, a send while a
  message is stuck delivers both about 13 s before the stuck message's own retry was due.
- Tests: 20 server, 15 proxy, 28 core (JVM), 31 on the device.

What it does **not** cover: receiving and sync down, push, media, encryption, multi-device, scale.

## Learning log

[docs/LEARNING.md](docs/LEARNING.md): per behaviour, the test written first, how it failed, what made
it pass, and the lesson. Manual test checklists: [docs/MANUAL-TESTS.md](docs/MANUAL-TESTS.md).

## Run it

```bash
# server
python3 -m venv server/.venv && server/.venv/bin/pip install -r server/requirements.txt
server/.venv/bin/python server/manage.py migrate
server/.venv/bin/python server/manage.py runserver 0.0.0.0:8080 --noreload

# chaos proxy
python3 chaos/proxy.py --listen 0.0.0.0:8091 --upstream http://127.0.0.1:8080

# tests
server/.venv/bin/python server/manage.py test chat
python3 -m unittest chaos.test_proxy
android/gradlew -p android :core:test
android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.baseUrl=http://<laptop-lan-ip>:8091
```

The phone must be on the same Wi-Fi as the laptop. Do not use `adb reverse`: a USB tunnel ignores
airplane mode, so the offline tests would prove nothing. The app's default server URL and the docs
use this author's LAN address; change it in the app's settings field.

## Licence

[Apache-2.0](LICENSE).
