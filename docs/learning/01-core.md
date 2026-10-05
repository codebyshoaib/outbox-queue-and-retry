# 01: Phone outbox core (P1–P8)

Pure Kotlin in `android/core`, package `ai.hellorumi.outbox.core`. No Android imports; Room and
WorkManager wrap it in a later phase. Order built: P8, P1, P3, P4, P5, P6, P2, P7.

Where a test needed a brand-new API, the first red is a compile error ("missing symbol"). Where
that was the only red, I then added just the empty signature (a port method or a stub) and ran
again to see the assertion fail too, so each behaviour has a real "wrong answer" red as well.

The pieces: `Outbox` (tap path: `send`, `retry`), `OutboxProcessor.drainOnce()` (network path),
`Backoff`, and three ports with fakes in tests: `OutboxStore`, `Transport`, `Clock`. The fake store
works on a copy and swaps it in at the end, and `failNextOp` throws after the first write, so it
behaves like a DB transaction and the atomicity tests mean something.

## P8: time and randomness are injected

- Test: `P8_sendStampsTimesFromInjectedClock`
- Red (missing symbol): `Fakes.kt:3:47 Unresolved reference 'Clock'.`
- Green: `Clock` fun interface, the model, an `OutboxStore` port with only `enqueue`, and
  `Outbox.send` stamping `createdAt`/`nextAttemptAt` from `clock.now()`. The id was a hard-coded
  `""` on purpose (fake it until a test demands more), which P1 then broke.
- The random seam (`random: () -> Double` on the processor) arrived with P5, its only user, and is
  pinned in every P5 test.
- Lesson: if code reads the wall clock or `Random` directly, you cannot test backoff without
  sleeping. Pass time and randomness in, and a test can jump an hour in one line.

## P1: send saves message and row in one transaction, no network

- Tests: `P1_sendWritesPendingMessageAndRowInOneTransaction`, `P1_eachSendGetsAFreshUuidClientMsgId`,
  `P1_failedSaveLeavesNothingBehind`
- Red: `java.lang.IllegalArgumentException: Invalid UUID string: ` (the fresh-UUID test). The other
  two passed at once, because P8 had already built `send` on a single `enqueue` call. They stay as
  guards: splitting the write into two store calls would break them.
- Green: `UUID.randomUUID()` as the client_msg_id.
- "No network on the tap path" is structural: `Outbox` has no `Transport`, so it cannot send.
- Lesson: make the id on the phone, at tap time, and save message and outbox row together. If the
  app dies between two separate writes you get a message that never sends, or a row with no message.

## P3: every send carries the row's client_msg_id; retries reuse it

- Tests: `P3_sendCarriesDeviceIdAndClientMsgId`, `P3_retryReusesTheSameClientMsgId`
- Red (missing symbol): `Unresolved reference 'OutboxProcessor'.` / `'SendResult'`, `'Transport'`
- Green: `Transport` port, `SendResult` (`Ok`, `Http`, `NetworkError`), and a processor that sends
  each outbox row's stored message. The id comes from the stored row, never made again.
- Lesson: the retry must be the same request, byte for byte where it matters. A new id on retry
  turns "server got it but the ack was lost" into a duplicate message.

## P4: on ack, sent with server_seq and row deleted, atomically

- Tests: `P4_ackMarksSentWithServerSeqAndDeletesRow`,
  `P4_crashWhileMarkingSentLeavesMessagePendingAndRowQueued`,
  `P4_lostAckThenResendOfSameIdEndsSentWithServersSeq`
- Red (missing symbol): `'markSent' overrides nothing.` After adding the port method:
  `expected:<(SENT, 5)> but was:<(PENDING, null)>`
- Green: `if (result is SendResult.Ok) store.markSent(id, seq)`.
- Lost ack at this layer: the first call returns `NetworkError` (the server stored it), the second
  returns `Ok(200, 1)` for the same id. The message ends `SENT` with seq 1 and the transport saw the
  id twice. The server's dedupe (S2) is what makes that second call safe.
- Lesson: "sent" and "remove from outbox" are one fact and get one transaction. If the app crashes
  between them, the row stays and gets resent, and the server's dedupe absorbs that.

## P5: transient failure backs off with jitter

- Tests: `P5_backoffDoublesFromOneSecondAndCapsAtSixty`, `P5_jitterScalesDelayBetweenHalfAndFull`,
  `P5_hugeAttemptCountsDoNotOverflow`, `P5_transientFailureBacksOffWithJitter`,
  `P5_rowIsNotResentBeforeItsBackoffEnds`, `P5_timeoutsServerErrorsAnd408And429AreTransient`
- Red (missing symbol): `Unresolved reference 'Backoff'.` After stubs:
  `1 s x 0.75 jitter expected:<1000750> but was:<null>`, `Http(code=500) expected:<1> but was:<0>`,
  `kotlin.NotImplementedError` (Backoff stub)
- Green: `Backoff.delayMs = min(60 s, 1 s shl clamp(attempts-1, 0, 6)) * (0.5 + 0.5 * random)`;
  a failed send does `attempts + 1` and `reschedule(now + delay)`; rows not due are skipped;
  `drainOnce` returns the next wake-up time.
- Lesson: double the wait so a dead server is not hammered, cap it so the user is not stuck for
  hours, and add jitter so a thousand phones coming back online do not retry at the same moment.
  Clamp the exponent: `1000 shl 40` silently wraps instead of failing.

## P6: permanent failure, failed and never retried by itself

- Tests: `P6_permanentFailureMarksFailedAndDropsRow`, `P6_failedMessageIsNeverRetriedByItself`,
  `P6_anyOtherNon2xxStatusIsPermanent`, `P6_permanentFailureDoesNotBlockTheNextMessageInTheChat`
- Red (missing symbol): `'markFailed' overrides nothing.` After adding the port method:
  `HTTP 100 expected:<FAILED> but was:<PENDING>`, `expected:<1> but was:<2>` (it was retried)
- Green: `SendResult.isTransient()` (network error, 5xx, 408, 429) and `markFailed` for everything else.
- Lesson: decide which errors are worth retrying and treat every other one as permanent. If you
  retry "unknown" codes, a 400 for a bad message loops forever and drains the battery.

## P2: due rows oldest first, at most one in flight per chat

- Tests: `P2_laterMessageWaitsWhileTheEarlierOneBacksOff`, `P2_aBlockedChatDoesNotBlockOtherChats`,
  `P2_sendsDueRowsOldestFirst`, `P2_wakeUpTimeIsTheEarliestChatHeadNotARowQueuedBehindIt`
- Red: `second must not overtake first expected:<[8bb1…]> but was:<[8bb1…, b846…]>`
- First fix: a `blockedChats` set. A chat stops for the pass at its first not-due or transient row.
  That made a test that had been passing go red:
  `waking at the blocked row's time would busy-loop expected:<1000750> but was:<1000000>`.
  The wake-up was the minimum over all rows, and the row stuck behind the head was "due now", so
  the worker would wake, do nothing, and wake again.
- Green: compute the wake-up only from each chat's oldest row (`distinctBy { chatId }`).
- Lesson: ordering is per chat. A failing head blocks its own chat and nothing else, and "when to
  wake up" must look at what can actually go next, not at everything that is queued.

## P7: retry a failed message

- Tests: `P7_retryPutsAFailedMessageBackPendingAndDueNowUnderTheSameId`,
  `P7_retryOnlyAppliesToFailedMessages`, `P7_failedRetryLeavesTheMessageFailed`
- Red (missing symbol): `Unresolved reference 'retry'.` After a `false` stub:
  `Expected value to be true.`
- Green: `Outbox.retry` calls `store.requeue(id, now)`, which flips FAILED to PENDING and inserts a
  fresh row (attempts 0, due now) in one transaction, or does nothing if the message is not FAILED.
- Lesson: a manual retry is the same message under the same id, not a new send. Only FAILED ones
  qualify, otherwise a tap could reset a pending row's backoff.

## How to run

```
/home/shoaib/Taleemabad/outbox-wt-core/android/gradlew -p /home/shoaib/Taleemabad/outbox-wt-core/android :core:test
```

Gradle 8.14.3 (wrapper), Kotlin 2.2.21, JDK 17, `kotlin("test")` on JUnit 4. Reports are in
`android/core/build/reports/tests/test/index.html`.
