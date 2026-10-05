# 02: Android app (A1–A5, T1, T4, T3a)

Module `:app` in `android/`, package `ai.hellorumi.outbox`. It wraps the pure-Kotlin core from
phase 1c without changing it: Room implements `OutboxStore`, `HttpURLConnection` implements
`Transport`, WorkManager runs `OutboxProcessor.drainOnce()`, and one Compose screen shows it.

All app tests are instrumented and run on the real phone (vivo V2149, Android 11 / API 30). The
transport and acceptance tests talk to the real chaos proxy and Django over Wi-Fi, each with its own
`chat_id` (`transport-<uuid>`, `accept-<uuid>`), so server state from earlier runs never collides.

Versions: AGP 8.11.0, Kotlin 2.2.21, KSP 2.3.6, Compose BOM 2026.06.01 (material3 1.4.0),
activity-compose 1.13.0, Room 2.8.4, WorkManager 2.11.2, androidx.test runner 1.7.0 / ext-junit
1.3.0. compileSdk/targetSdk 36, minSdk 26.

## A1: Room store, one transaction per store call

- Tests (`RoomOutboxStoreTest`, in-memory Room): `A1_enqueueWritesMessageAndRowTogether`,
  `A1_enqueueIsAtomic`, `A1_outboxRowsKeepInsertionOrder`, `A1_markSentSetsSeqAndRemovesRow`,
  `A1_rescheduleUpdatesRowAndKeepsMessagePending`, `A1_markFailedSetsFailedAndRemovesRow`,
  `A1_requeueOnlyWhenFailed`, `A1_requeuedRowQueuesBehindItsChat`,
  `A1_concurrentRequeuesOfOneFailedMessageMakeOneRow`, `A1_observeEmitsTheChatsMessagesInSendOrder`
- Red (missing symbol): `Unresolved reference 'AppDb'.` / `'RoomOutboxStore'`
- Then a deliberately naive store (plain DAO calls, no `runInTransaction`, requeue reads the status
  and then writes):
  - `A1_enqueueIsAtomic: the message insert must roll back with the failed row insert expected null,
    but was:<Message(id=a, chatId=c, body=body a, createdAt=1000, status=PENDING, serverSeq=null)>`
  - `A1_concurrentRequeuesOfOneFailedMessageMakeOneRow: SQLiteConstraintException: UNIQUE constraint
    failed: outbox.messageId`
- Green: `db.runInTransaction { }` around enqueue, markSent, markFailed and requeue. Requeue's FAILED
  check is the conditional `UPDATE messages SET status='PENDING' WHERE id=? AND status='FAILED'`
  itself; it inserts the row only if that changed one row. `reschedule` is a single UPDATE, which
  SQLite already runs atomically.
- How the atomicity test fails on purpose: an orphan outbox row with the same `messageId` is planted
  first, so the second insert of `enqueue` hits the unique index. Only a transaction makes the first
  insert disappear too.
- Ordering: `outbox.key` is `@PrimaryKey(autoGenerate = true)`, and `outboxRows()` orders by it. The
  test inserts ids and timestamps that both sort the other way, so only insertion order passes. The
  screen orders messages by SQLite's `rowid` for the same reason (their key is a UUID string).
- Lesson: a check-then-act ("is it FAILED? then requeue") is only safe inside the transaction that
  acts. The race test is probabilistic, but here the unique index on `outbox.messageId` turned the
  race into a loud constraint error instead of a silent duplicate row: a DB constraint is the second
  line of defence when the code gets it wrong.

## Transport: HttpURLConnection

- Tests (`HttpTransportTest`, real proxy + server): `transport_201IsOkWithServerSeq`,
  `transport_duplicateIs200WithTheSameSeq`, `transport_400IsHttp400`,
  `transport_dropReplyIsNetworkErrorButTheServerHasTheRow`,
  `transport_dropRequestIsNetworkErrorAndTheServerHasNothing`, `transport_unreachableHostIsNetworkError`
- Red (missing symbol): `Unresolved reference 'HttpTransport'.`
- Red after writing the transport: every test, `java.io.IOException: Cleartext HTTP traffic to
  192.168.18.45 not permitted` (thrown by the test's own chaos call in `@Before`, before the
  transport even ran).
- Green: `res/xml/network_security_config.xml` with `cleartextTrafficPermitted="true"`, playground
  only, with a comment that production uses HTTPS.
- The transport: POST `{baseUrl}/messages`, `X-Device-Id` header, JSON
  `{client_msg_id, chat_id, body}`, 10 s connect and read timeouts. 2xx with `server_seq` → `Ok`,
  other status → `Http(code)`, any `IOException` (refused, reset, timeout, empty reply) →
  `NetworkError`. A 2xx without a `server_seq` → `Http(code)`, which is permanent, so a broken
  server cannot cause a retry loop.
- `drop_reply` comes back as `NetworkError` while `GET /messages` already shows the row: the lost
  ack, seen from the phone. It also shows Android's `HttpURLConnection` did not silently retry the
  POST (it can on a stale pooled connection; the proxy sends `Connection: close`, so there is none).
- Device id: a UUID made once and kept in SharedPreferences (`Settings.deviceId`, tested in
  `A5_deviceIdIsMadeOnceAndKept`). The server dedupes on (device id, client_msg_id), so a new device
  id per launch would turn every retry after a restart into a duplicate.
- Lesson: since Android 9 (API 28) apps cannot use plain HTTP unless they opt in, because anyone on
  the Wi-Fi can read and change it. The failure looks like a network error, so the transport would
  have retried it forever; only the exception message tells you what is wrong.

## A2/A3: WorkManager

- Tests (`OutboxWorkTest`, `work-testing` with `SynchronousExecutor` and `TestDriver`, a
  `WorkerFactory` that hands the worker an in-memory store and a scripted transport):
  `A2_workWaitsForANetworkConnection`, `A3_rowsLeftAfterAPassScheduleTheNextRunAtTheirDueTime`,
  `A3_emptyOutboxSchedulesNothing`, `A3_aSendWhileARunIsQueuedIsChainedNotDroppedOrRunAlongside`
- Red (missing symbol): `Unresolved reference 'OutboxWorker'.` / `'OutboxWork'`
- Red with `ExistingWorkPolicy.KEEP`:
  `A3_aSendWhileARunIsQueuedIsChainedNotDroppedOrRunAlongside: expected:<[ENQUEUED, BLOCKED]> but
  was:<[ENQUEUED]>` (the second enqueue was dropped).
- Green: `APPEND_OR_REPLACE`. `OutboxWork.enqueue(context, delayMs)` builds a one-time request with
  `NetworkType.CONNECTED` and an initial delay, as unique work `outbox-drain`. The worker runs one
  `drainOnce()`; if it returns a time, it enqueues the next run with `initialDelay = next - now`.
  The work is enqueued on every send and retry (the screen's `kick`) and in `MainActivity.onCreate`.
- Why `APPEND_OR_REPLACE`:
  - `drainOnce` is not reentrant, so two passes must never run at once. Appending makes the new
    request wait (`BLOCKED`) until the current one finishes.
  - A send during a pass must not be lost. `KEEP` drops the new request while one is running or
    waiting, and the worker's own reschedule is also an enqueue made while it is running, so `KEEP`
    would drop that too. (The A3 reschedule test passes even under `KEEP`: the test's synchronous
    executor applies the enqueue after the run has already finished. On a phone it would race. The
    chain test is the one that pins the policy.)
  - `REPLACE` cancels the running pass and starts a new one at once. A plain `Worker`'s thread is not
    interrupted by cancellation, so the old pass keeps sending while the new one starts: two drains
    at once, exactly what the core forbids.
  - `_OR_REPLACE` (rather than plain `APPEND`): if the previous run failed or was cancelled, a new
    chain starts instead of the new request being failed or cancelled along with it.
  - Cost, marked `ponytail:` in `OutboxWork.kt`: a send that lands while a run is waiting out a
    backoff delay waits behind it (up to 60 s), even for another chat, and each send adds one cheap
    pass to the chain.
- In a chain, an appended request's initial delay is counted from when it is unblocked (WorkManager
  resets its enqueue time), so "next run in 4 s" means 4 s after this pass ends.
- Lesson: the `CONNECTED` constraint replaces a hand-written network listener. You would otherwise
  register a `ConnectivityManager` callback, keep the process alive to hear it, and re-check after a
  reboot or a kill. WorkManager stores the request in its own database, hands it to JobScheduler,
  and the OS starts the app's worker when a network appears, even if the app is not running.

## A4: survives a kill (checked by hand, not automated)

- Room and WorkManager both persist to disk, so nothing in the code is A4-specific beyond enqueuing
  on app start.
- Checked on the phone, 2026-10-06: proxy set to `status:503` × 1000, sent `a4-1791234080` from the
  app (🕓), `adb shell am force-stop ai.hellorumi.outbox`, chaos cleared. For 90 s nothing reached the
  server, and the proxy log showed no request: Android cancels a force-stopped app's jobs and alarms
  until the user opens it again. Opened the app: delivered as `server_seq` 2 within 2 s.
- So "enqueue on app start" is not optional: after a force-stop it is the only thing that gets the
  outbox moving. (A swipe-away from recents is not a force-stop on stock Android; some vendor ROMs
  treat it like one.)
- Reboot: not run. It is an optional checklist in `docs/MANUAL-TESTS.md`.

## A5: one Compose screen

- Tests (`ChatScreenTest`, `createComposeRule` with an in-memory store):
  `A5_sendShowsAPendingRowAndKicksTheWorker`, `A5_sentRowShowsItsServerSeq`,
  `A5_tappingAFailedRowRetriesIt`, `A5_settingsChangeTheChatAndServerUrl`, `A5_deviceIdIsMadeOnceAndKept`
- Red (missing symbol): `Unresolved reference 'Settings'.` / `'ChatScreen'`
- First run after writing the screen, three harness problems, not app bugs:
  - `Failed to inject touch input.`: after a text field is focused the keyboard is up, and a touch
    that lands on the IME's window is refused. Clicks now go through
    `performSemanticsAction(OnClick)`, which is also what accessibility services use.
  - `... contains 'hello' ... is not displayed!`: the row was asserted before Room's Flow emitted
    (and possibly under the keyboard). Now `waitUntilExactlyOneExists(hasText(...))`.
  - `Cannot perform this operation because the connection pool has been closed.`: `@After` closed
    the db while the screen's Flow was still collecting; the compose rule disposes the content only
    after `@After`. The test no longer closes its in-memory db.
- Green: `ChatScreen(store, outbox, settings, kick)`: server URL and chat id fields (saved to
  SharedPreferences, default `http://192.168.18.45:8091` and `playground`), a `LazyColumn` over
  `store.observe(chatId)` (a Room `Flow`), and a text field + Send. Rows show 🕓 / ✓ `#seq` / ⚠,
  each icon with a content description (`pending`, `sent`, `failed, tap to retry`); a failed row is
  clickable and calls `Outbox.retry`. Room calls run on `Dispatchers.IO`, then `kick()` enqueues the
  work.
- Wiring: `OutboxApp` implements `Configuration.Provider` with a `WorkerFactory` that builds the
  processor from the current settings per run, so a URL change applies from the next pass. The
  manifest removes WorkManager's default startup initializer so that config is used.
- Lesson: the screen never sends anything. It writes to Room and the list redraws from Room, so the
  icons always show what is on disk, not what the UI hopes happened.

## Acceptance: T1, T4, T3a (automated)

- Tests (`AcceptanceTest`): real Room (in memory), real `HttpTransport`, real `OutboxProcessor`,
  the real proxy and Django. Only the clock is fake: the test jumps it to each wake-up time that
  `drainOnce` returns, and jitter is pinned at 1.0, so the backoff gaps are exact and nothing sleeps.
  - `T1_lostAckDeliversExactlyOnce`: `drop_reply` × 1 → the same id is sent twice, the server has
    exactly 1 row, the message is SENT with seq 1.
  - `T4_permanentErrorFailsWithoutRetryingUntilTapped`: `status:400` × 1 → FAILED, an hour later
    still no second request, server empty; `retry` → SENT, seq 1.
  - `T3a_serverErrorsThenRecoveryDeliverAllInOrderWithGrowingGaps`: `status:503` × 3, send three →
    gaps 1 s, 2 s, 4 s, only the head was retried, then all three land with seq 1, 2, 3 in order.
- These passed on the first run, because A1, the transport and the core already did the work. To
  prove they can fail, I broke the app code temporarily, ran them, and reverted:
  - fresh UUID per attempt in the transport → `T1: server has exactly 1 row expected:<[d146…]> but
    was:<[7b7f…, ef31…]>`
  - transport reporting 400 as 503 → `T4: expected:<FAILED> but was:<SENT>`
  - `ORDER BY key DESC` in the outbox query → `T3a: the head blocks its chat while it backs off
    expected:<[767b…, 767b…, …]> but was …` (the newest message went first)
- Lesson: an end-to-end test that passes the first time proves nothing until you have seen it fail.
  Each of these three breakages is a real mistake someone could make.

## End to end on the phone

Installed with `:app:installDebug`, opened, typed `adb-1791234041` with `adb shell input`, tapped
Send: the row went ✓ `#1` and `GET /messages?chat_id=playground` returned it with seq 1.
Screenshot: `docs/img/app.png`.

## How to run

From the repo root. The phone and laptop must be on the same Wi-Fi, with Django on :8080 and the
proxy on :8091 running (see `docs/MANUAL-TESTS.md`, Setup). `android/local.properties` must contain
`sdk.dir=/home/shoaib/Android/Sdk` (it is gitignored).

```
android/gradlew -p android :core:test                         # core, JVM (26 tests)
android/gradlew -p android :app:testDebugUnitTest             # app, JVM (none yet)
android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.baseUrl=http://192.168.18.45:8091   # on the phone (28 tests)
android/gradlew -p android :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.hellorumi.outbox.AcceptanceTest   # one class
android/gradlew -p android :app:installDebug
adb shell am start -n ai.hellorumi.outbox/.MainActivity
```

`baseUrl` defaults to `http://192.168.18.45:8091` if not passed. Device test reports:
`android/app/build/reports/androidTests/connected/debug/index.html`.
