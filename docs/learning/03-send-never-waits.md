# 03: A send never waits behind a back-off (P9, A6, C5)

Found by hand in T3: with Django back up, a new message (`hi`) sat 🕓 until the stuck message's
scheduled retry fired, up to 60 s later. Fixed test-first, one step at a time, 2026-10-06.

## Why it happened

- All runs shared one unique work queue with `APPEND_OR_REPLACE`. The queue looked like
  `[retry run, waiting out its delay] → [hi's run, BLOCKED]`: `hi` waited behind a run that was not
  even doing anything.
- APPEND was there for a good reason: `drainOnce` must never run twice at once. The guarantee lived
  in the scheduling, not in the code that needed it.

## Choosing the fix: A vs B

- **A**: before a send, look at the queue; cancel the waiting run only if it has not started.
- **B (chosen)**: a person's action always REPLACEs (run now); a lock around `drainOnce` is the
  "one pass at a time" guarantee.
- The case for A: a JVM lock only holds in one process; REPLACE cancels a running worker (harmless
  for a blocking `Worker`, not for a `CoroutineWorker`); and losing A's race is mostly absorbed by
  the server's dedupe.
- Why B anyway: A is check-then-act on state that can change in between, and needs an async query
  on the tap path. B is one policy plus one lock. Its two limits are written next to the lock.
- Lesson: the server's dedupe is not only a retry fix, it is the safety net under every phone-side
  concurrency mistake. Still, do not lean on it on purpose.

## The test exposed a second gap before any code existed

Writing the A6 test forced a concrete scenario: `hi` in the **same chat** as the stuck message.
B alone runs a pass now, but the stuck head is not due, and P2 makes the rest of the chat wait.
So B alone would have "worked" in a test with two chats and still failed the user's actual case.
That produced a new core rule:

- **P9**: after a send saves, every waiting row (any chat) becomes due now, attempts kept.
- Lesson: writing the test first forces you to name the exact scenario; naming it is what exposed
  that the planned fix solved a different one.

## P9 in the core (JVM)

- Tests: `P9_sendMakesEveryWaitingRowDueNowAndKeepsItsAttempts`,
  `P9_afterASendTheStuckChatDrainsInTypedOrder`.
- Red: `expected … nextAttemptAt=43000 … but was … nextAttemptAt=92000` and
  `expected:<[one, hi]> but was:<[]>` (the T3 bug, in miniature).
- Green: `OutboxStore.makeAllDue(now)`, called from `send` right after `enqueue`, in its **own**
  transaction. Rule used: only group writes into one transaction when half of it would be *wrong*,
  not merely *less helpful*. If the hint fails, the message is still saved.
- "Attempts kept" is asserted on purpose: resetting them would also reset back-off, so every tap
  during a real outage would hammer the server from 1 s again.
- An old test broke: `P5_timeoutsServerErrorsAnd408And429AreTransient` (`Key … is missing in the
  map`). It shared one outbox across 7 cases; under P9 each new send pulled the previous case's row
  back in, which ate the scripted error. Question to ask: does the old test guard a *requirement* or
  an *accident*? Accident: each case now starts on an empty outbox, committed **before** the feature
  so the history shows the test was not bent to fit the new code.

## P9 in Room (device)

- Test: `P9_makeAllDuePullsOnlyLaterRowsToNowAndKeepsAttempts`.
- Red 1 (weak, compile): `Class 'RoomOutboxStore' … does not implement abstract member`.
- Red 2 (strong, empty method): `expected … nextAttemptAt=2000 but was … 90000`.
- Green: `UPDATE outbox SET nextAttemptAt = :now WHERE nextAttemptAt > :now`. One statement is
  atomic on its own.
- Proved the `WHERE` is pinned: removed it, the test failed (`early … expected 500 but was 2000`,
  an overdue row pushed *later*), put it back.
- Slip: undid that break with `git checkout`, which also threw away the uncommitted new query. Caught
  at once (it no longer compiled). Habit: **commit before you break something on purpose**, undo with
  the reverse edit.

## A6 on the device

- Tests: `A6_aSendDuringABackoffWaitRunsAPassWithoutWaitingOutTheDelay`,
  `A6_twoPassesNeverRunAtTheSameTime`.
- How the first one controls time: WorkManager's `TestDriver` lets a test meet the network
  constraint and the initial delay separately. The test only ever meets the network one, so a pass
  that relies on the delay elapsing can never run.
- Red with `kick()` = today's `enqueue()`: `expected:<3> but was:<1>` (the bug) and
  `expected:<1> but was:<2>`: two passes started together overlap, because nothing in the pass
  itself stops it. Today the queue hides that; B removes the queue's help, so the lock had to be
  *required* by a test, not added "just in case".
- Green: `kick()` = REPLACE, used by send, retry tap and app start; `enqueue()` keeps
  APPEND_OR_REPLACE for the worker re-queuing itself; `synchronized(LOCK) { drainOnce() }`.
- Proved the lock is pinned: removed it, the overlap test failed `expected:<1> but was:<2>`, put it
  back (reverse edit; `git status` clean).
- A flaky UI test showed up in the full run, `A5_tappingAFailedRowRetriesIt` (node not found), and
  passed 5/5 alone. Cause: it tapped a row the moment the DB said failed, before Room's Flow redrew
  the screen. Fixed in the test (wait for the row), committed separately before the feature. Lesson:
  a test that fails only under load is still telling you something; find out whether it is the
  test's timing or the app before fixing anything.

## End to end on the phone: T3 again

- Chat `t3-3`. Django stopped, message sent, it backed off to 7 attempts; the phone's DB said its
  next retry was about 24 s away. Django started at 02:45:57; `hi` sent.
- Server: `#1` (stuck message) at 02:46:05.946, `#2` (`hi`) at 02:46:05.992. Both about 13 s
  **before** the stuck message's own retry was due, in typed order, 46 ms apart (one in flight per
  chat). The tap did it, not the timer.
- A first attempt (`t3-2`) proved nothing: running the device suite uninstalls the app, which wiped
  the stuck message, so `hi` was just a normal send to a running server. Read the phone's DB
  (`adb exec-out run-as ai.hellorumi.outbox cat databases/outbox.db`) before believing a pass.

## C5: timestamps in the proxy log

- Test: `test_C5_each_log_line_starts_with_wall_clock_time_to_the_millisecond`.
- Red: `Regex didn't match … not found in 'GET /messages mode=pass'`.
- Green: one `datetime.now().strftime("%H:%M:%S.%f")[:-3]` prefix. Retry gaps can now be read off
  `/tmp/claude-1000/oq-chaos.log` instead of a terminal's `tail`.
- Still misleading: during an outage the line says `mode=pass` while the phone gets a 502. Logging
  the reply status is the next small improvement.
