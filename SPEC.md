# Outbox queue and retry: spec

A playground that proves one thing on a real phone: **a message typed with bad or no network is
delivered exactly once, in order, without the user doing anything.** It mirrors what WhatsApp does
for sending (save first, retry with the same id, server drops duplicates, ✓ on server ack) and is
the first piece a Rumi-built chat would need.

Not in scope: receiving / sync down, push, media, encryption, multi-device, auth, groups. Each of
those builds on this and gets its own study.

## Pieces

| Piece | Stack | Why this stack |
|---|---|---|
| `server/` | Django + DRF, SQLite | Taleemabad's backend stack; SQLite is enough for one laptop |
| `chaos/` | Python stdlib HTTP proxy | Sits between phone and server and fails on command, so failures are repeatable |
| `android/` | Kotlin, Room, WorkManager, Compose | What a real Rumi Android client would use |

```
phone (android/)  --HTTP-->  chaos proxy :8091  --HTTP-->  Django :8080
                                 ^ POST /_chaos sets the next failure
```

## Glossary

- **client_msg_id**: UUID made on the phone when the user taps send. Never regenerated.
- **Outbox**: local table of messages not yet acked by the server.
- **Ack**: the server's 200/201 reply, sent only after its DB commit. Shown as ✓.
- **Transient failure**: no connection, timeout, HTTP 5xx, 408, 429. Retried.
- **Permanent failure**: any other non-2xx status. Not retried (an unknown status must not loop forever).
- **Due**: an outbox row whose `next_attempt_at <= now`.

## Behaviour

### Server (S)

- **S1** `POST /messages` with header `X-Device-Id` and body `{client_msg_id, chat_id, body}`
  stores the message and replies **201** `{client_msg_id, server_seq}`.
- **S2** The same `(device_id, client_msg_id)` sent again replies **200** with the *same*
  `server_seq` and stores nothing new. Holds under concurrent duplicate requests.
- **S3** `server_seq` is per chat, starts at 1, has no gaps, and follows arrival order.
- **S4** Invalid input replies **400** and stores nothing: missing/blank `X-Device-Id`, non-UUID
  `client_msg_id`, blank `chat_id`, blank `body`, `body` over 4096 chars.
- **S5** The reply is written only after the transaction commits.
- **S6** `GET /messages?chat_id=X` lists stored messages in `server_seq` order (for checking).

### Chaos proxy (C)

- **C1** By default forwards every request to the server unchanged.
- **C2** `POST /_chaos {"mode": ..., "count": n}` makes the next `n` forwarded requests fail
  with that mode, then returns to pass-through. `GET /_chaos` shows the current mode and count.
- **C3** Modes:
  - `drop_request`: closes the connection without forwarding.
  - `drop_reply`: forwards, lets the server commit, then closes without replying (**the lost ack**).
  - `status:<code>`: replies `<code>` without forwarding.
  - `delay:<ms>`: forwards after waiting.
- **C4** `/_chaos` itself is never affected by chaos.

### Phone: outbox core (P), pure Kotlin, no Android imports

- **P1** `send(chatId, body)` writes the message (status `pending`) and its outbox row in **one
  transaction**, then returns. No network on the tap path.
- **P2** The processor sends **due** rows oldest first, with **at most one in flight per chat**.
  A later message in a chat waits for the earlier one.
- **P3** Each send carries the row's `client_msg_id`; a retry uses the same id.
- **P4** On 200/201: message → `sent` (✓) and its outbox row deleted, in one transaction.
- **P5** On a transient failure: `attempts += 1`,
  `next_attempt_at = now + min(60 s, 1 s × 2^(attempts-1)) × jitter`, jitter uniform in
  [0.5, 1.0]. The message stays `pending`.
- **P6** On a permanent failure: message → `failed`, outbox row deleted, never retried by itself.
- **P7** `retry(messageId)` on a `failed` message puts it back as `pending` with `attempts = 0`,
  due now, same `client_msg_id`.
- **P8** Time and randomness come from an injected clock and random source (testable).
- **P9** After `send` saves the new message, every other outbox row (any chat) becomes due now;
  its `attempts` is kept, so a further failure keeps backing off from where it was. A tap is the
  best hint that the network may be back. Cost: one retry burst per tap while the server is down.

### Phone: Android (A)

- **A1** Room holds `messages` and `outbox`; P1, P4 and P6 use Room transactions.
- **A2** WorkManager runs the processor as unique work with a `CONNECTED` network constraint, so
  it wakes when the network comes back.
- **A3** After a pass, if outbox rows remain, the next run is scheduled for the earliest
  `next_attempt_at`. The work is also enqueued on every `send` and on app start.
- **A4** Survives app kill and phone reboot: pending rows are still sent afterwards.
- **A6** A send while a run is waiting out a back-off delay starts a pass now (it does not queue
  behind the delay). Two passes never run at once: a lock around the pass is the guarantee, not
  the work queue (the app runs in one process; the worker stays blocking).
- **A5** One screen: chat list of messages with status (🕓 pending, ✓ sent, ⚠ failed, tap to
  retry), a text box, a send button, and a settings field for the server URL.

## Acceptance: the five chaos tests

Run on the phone against the proxy over Wi-Fi (not `adb reverse`: USB tunnels ignore airplane mode).

| # | Setup | Pass |
|---|---|---|
| T1 Lost ack | `drop_reply` × 1, send 1 | Server has exactly 1 row; phone shows ✓ |
| T2 Killed between save and send | Send with network off, force-stop app, network on, open app | Delivered, ✓ |
| T3 Server down | Stop Django, send 3, wait 2 min, start Django | All 3 delivered in order; retry gaps grew |
| T4 Permanent error | `status:400` × 1, send 1 | ⚠ failed, no further requests for it; tap retry → ✓ |
| T5 Airplane mode | Airplane on, send 5, airplane off | 5 ✓, server has 5 rows, `server_seq` 1–5 in typed order |

T1, T4 and T3a (proxy answers `status:503` × 3, then passes: all 3 delivered in order, gaps grew)
are automated as on-device instrumented tests driving the proxy. T3 itself (really stopping
Django), T2 and T5 need a
human hand on the phone and are a checklist in `docs/MANUAL-TESTS.md`.

## How it is built

TDD: each behaviour id above gets a failing test first, then the code. Test names carry the id
(`test_S2_duplicate_returns_same_seq`, `P5_transientFailureBacksOffWithJitter`).
Order: server, chaos and phone core in parallel (no shared code) → Android shell → acceptance.
`docs/LEARNING.md` indexes `docs/learning/<phase>.md`: per behaviour, what failed first and what it taught.
