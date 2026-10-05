# Phase 1: server (S1–S6)

What each behaviour's first failing test said, what turned it green, and what it teaches about
idempotent APIs. Code: `server/chat/` (views, serializers, models), tests in `server/chat/tests/`.

## S1: store and reply 201

- **Test:** `test_S1_new_message_returns_201_with_seq_and_is_stored`
- **Red:** `AssertionError: 404 != 201` (no route yet). The test looks the model up lazily
  (`apps.get_model`) so a missing model shows up as a failed assertion, not an import error.
- **Green:** `Chat(chat_id, last_seq)` + `Message`, an `APIView` that bumps `last_seq` and inserts.
- **Lesson:** the reply carries the client's own id back (`client_msg_id`) so the phone can match
  the ack to the outbox row without trusting request order.

## S2: same (device, client_msg_id) again → 200, same seq, nothing new

- **Tests:** `test_S2_duplicate_returns_same_seq`,
  `test_S2_duplicate_that_loses_the_insert_race_returns_same_seq`,
  `test_S2_same_client_msg_id_from_another_device_is_a_new_message`
- **Red:** `AssertionError: 201 != 200` for both duplicate tests. The other-device test was already
  green: it is a guard that the key is the *pair*, not `client_msg_id` alone.
- **Green:** `UniqueConstraint(sender_device_id, client_msg_id)`; look up first and reply 200 if found;
  otherwise insert inside a savepoint (`transaction.atomic()`); on `IntegrityError` re-read the
  winner's row and reply 200 with its seq. If the re-read finds nothing, a different constraint
  fired, so the error is re-raised.
- **How the race is tested:** real thread races on SQLite are flaky, so the test patches
  `find_existing` to miss once while the row already exists. That forces the insert, the
  `IntegrityError`, and the re-read, every time. It also asserts the lookup ran twice, so a rename
  can't quietly turn it into the plain duplicate test.
- **Lesson:** "check then insert" alone is not idempotent: two requests can both pass the check.
  The database's unique constraint is what holds the line; the check is only a fast path.

## S3: per-chat seq from 1, no gaps, arrival order

- **Tests:** `test_S3_seq_is_per_chat_and_starts_at_1`, `test_S3_seq_follows_arrival_order`,
  `test_S3_no_gap_after_duplicates_and_a_lost_race`,
  `test_S3_db_refuses_two_messages_with_the_same_seq_in_a_chat`
- **Red:** the first three were **already green**: S1's per-chat counter and S2's savepoint (the
  seq bump rolls back together with the losing insert) delivered them. They stay as regression
  guards. The real red was the backstop: `AssertionError: IntegrityError not raised`.
- **Green:** `UniqueConstraint(chat, server_seq)` and `select_for_update()` on the chat row while
  allocating the seq.
- **Not proven by tests:** `select_for_update` does nothing on SQLite (SQLite lets only one writer
  in at a time anyway). It matters on Postgres. The unique constraint means that if the lock were
  ever wrong, the database would refuse the duplicate seq instead of storing it.
- **Lesson:** do the counter bump and the insert in the same transaction, so a failed insert never
  uses up a number.

## S4: invalid input → 400, nothing stored

- **Tests:** `test_S4_missing_or_blank_device_id_is_rejected`, `test_S4_non_uuid_client_msg_id_is_rejected`,
  `test_S4_blank_chat_id_is_rejected`, `test_S4_blank_body_is_rejected`,
  `test_S4_body_over_4096_chars_is_rejected`, `test_S4_missing_fields_and_non_object_json_are_rejected`,
  `test_S4_ids_longer_than_the_column_are_rejected`,
  `test_S4_body_of_exactly_4096_chars_is_accepted_and_stored_verbatim`
- **Red:** `AssertionError: 201 != 400` (bad input stored) plus crashes such as
  `KeyError: 'x-device-id'` and `ValueError: badly formed hexadecimal UUID string`. The 4096
  boundary test was already green.
- **Gotcha found on the way:** the test helper did `client_msg_id or uuid4()`, so an empty string
  was silently swapped for a valid UUID. Fixed in its own `test(S4)` commit before the feature.
- **Green:** `MessageIn` serializer: `UUIDField`, `chat_id` max 255, `body` max 4096 with
  `trim_whitespace=False` (stored verbatim) plus a check that rejects whitespace-only bodies. The
  `X-Device-Id` header is checked in `validate()` (required, stripped, max 255).
- **Lesson:** validate before touching the DB, and make the client treat 400 as permanent. A bad
  request retried forever is a self-inflicted outage.

## S5: reply only after commit

- **Test:** `test_S5_row_is_committed_before_the_201_reply_is_built` (a `TransactionTestCase`, so
  commits are real). It spies on `Response`. At the moment the view builds its reply, a second,
  independent `sqlite3` connection reads the row. That connection only sees committed data. This
  needs a file-backed test DB (`DATABASES.default.TEST.NAME`).
- **Red:** there was **no red phase from missing code**. The S2 structure (insert in an outermost
  `atomic()`, `Response` built after the block exits) already met S5. To show the test is not
  trivially true, I mutated the view with `@transaction.atomic` on `post` (so the reply is built
  inside the transaction). The test went red:
  `AssertionError: Lists differ: [None] != [(1,)]`. Then I reverted. No `feat(S5)` commit exists,
  only a `refactor(S5)` comment saying the view must never run inside a request transaction.
- **Limit:** the test checks the moment the `Response` object is built, not the moment the bytes
  leave the socket. That is the strictest point the view controls.
- **Lesson:** an ack is a promise that the data is stored. Send it before the commit and a crash in
  between becomes a lost message that the phone has already deleted from its outbox.

## S6: list a chat in seq order

- **Tests:** `test_S6_lists_only_that_chat_in_server_seq_order` (rows inserted out of seq order so
  pk order can't fake it), `test_S6_unknown_chat_lists_nothing`, `test_S6_missing_chat_id_is_rejected`
- **Red:** `AssertionError: 405 != 200` (no GET handler).
- **Green:** `get()` on the same view, `order_by("server_seq")`, `MessageOut` serializer; a blank or
  missing `chat_id` is 400.
- **Lesson:** order by the server's sequence, never by timestamps or primary keys. The sequence is
  the agreed order.

## How to run

```bash
cd server
python3.12 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python manage.py test chat                 # 20 tests
.venv/bin/python manage.py migrate
.venv/bin/python manage.py runserver 0.0.0.0:8080    # ALLOWED_HOSTS=* so a phone on the LAN can reach it
```

Quick check:

```bash
curl -X POST http://<laptop-ip>:8080/messages -H 'X-Device-Id: d1' -H 'Content-Type: application/json' \
  -d '{"client_msg_id":"6f1c0f8e-2b7a-4c55-9a43-0d6c2a1e9b10","chat_id":"c1","body":"hi"}'   # 201, then 200 on repeat
curl 'http://<laptop-ip>:8080/messages?chat_id=c1'
```
