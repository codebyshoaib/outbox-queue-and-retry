# Manual tests: T2, T3, T5

T1, T4 and T3a are automated (`AcceptanceTest` on the phone). These three need a hand on the phone,
because they turn the network or the server off for real.

## Setup (once per session)

- Laptop, from the repo root, two terminals:
  - Django: `server/.venv/bin/python server/manage.py runserver 0.0.0.0:8080 --noreload`
  - Proxy (port 8091; 8081 belongs to something else on this laptop):
    `python3 chaos/proxy.py --listen 0.0.0.0:8091 --upstream http://127.0.0.1:8080 2>>/tmp/claude-1000/oq-chaos.log`
- Watch the proxy log with timestamps, so retry gaps are visible:
  `tail -n0 -f /tmp/claude-1000/oq-chaos.log | while read l; do echo "$(date +%T) $l"; done`
- Phone on the **same Wi-Fi** as the laptop (`192.168.18.45`). Never `adb reverse`: a USB tunnel
  ignores airplane mode and the test would prove nothing.
- App installed: `android/gradlew -p android :app:installDebug`
- Open it: `adb shell am start -n ai.hellorumi.outbox/.MainActivity`
- Server URL field: `http://192.168.18.45:8091` (the proxy). Clear any chaos left over:
  `curl -X POST http://192.168.18.45:8091/_chaos -d '{"mode":"drop_request","count":0}'`
- **Use a fresh Chat id per test** (type it in the Chat id field), e.g. `t2-1`, `t3-1`, `t5-1`, so
  `server_seq` starts at 1 and old rows do not get in the way.
- Check the server: `curl -s 'http://192.168.18.45:8080/messages?chat_id=t5-1' | python3 -m json.tool`

Airplane mode from adb **works on this vivo V2149 (Android 11)**, checked 2026-10-06:

```
adb shell cmd connectivity airplane-mode enable    # Wi-Fi goes down ("Network is unreachable")
adb shell cmd connectivity airplane-mode disable   # Wi-Fi back in about 8 s
adb shell cmd connectivity airplane-mode           # prints enabled / disabled
```

adb itself keeps working in airplane mode because it runs over the USB cable.

Icons in the app: 🕓 pending (saved, not acked), ✓ sent with `#server_seq`, ⚠ failed (tap to retry).

Typing from adb instead of the keyboard (taps are for this phone's 720x1600 screen; re-check with
`adb shell uiautomator dump` if they miss): `adb shell input tap 274 1470` (message box),
`adb shell input text hello1`, `adb shell input keyevent 4` (hide keyboard),
`adb shell input tap 611 1480` (Send).

---

## T2: killed between save and send

Spec: send with network off, force-stop the app, network on, open the app → delivered, ✓.

1. [ ] Chat id `t2-1`. Leave the app open.
2. [ ] `adb shell cmd connectivity airplane-mode enable`
3. [ ] Type `killed` and tap Send. Row shows 🕓.
4. [ ] `adb shell am force-stop ai.hellorumi.outbox` (the app vanishes).
5. [ ] `adb shell cmd connectivity airplane-mode disable`. Wait until Wi-Fi is back
       (`adb shell ping -c1 192.168.18.45`).
6. [ ] Optional, worth seeing: wait a minute **without** opening the app. Expect nothing in the proxy
       log and `GET /messages?chat_id=t2-1` empty. Android cancels a force-stopped app's jobs, so
       WorkManager cannot run until the app is opened again. (A4 check on 2026-10-06 showed exactly
       this: nothing for 90 s, then delivered 2 s after opening.)
7. [ ] `adb shell am start -n ai.hellorumi.outbox/.MainActivity`

Pass:
- [ ] The row turns ✓ `#1` within a few seconds of opening.
- [ ] `GET /messages?chat_id=t2-1` has exactly 1 row, body `killed`, `server_seq` 1.
- [ ] Proxy log shows one `POST /messages mode=pass` for it.

What it proves: the message was in Room before the app died (P1/A1), and opening the app enqueues
the work again (A3 "on app start").

## T3: server down

Spec: stop Django, send 3, wait 2 min, start Django → all 3 delivered in order; retry gaps grew.

1. [ ] Chat id `t3-1`. Start the timestamped proxy log tail (Setup).
2. [ ] Stop Django: Ctrl-C in its terminal (or `pkill -f 'manage.py runserver 0.0.0.0:8080'`).
       Check: `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://192.168.18.45:8091/messages`
       prints `502` (the proxy says the upstream is down, which the phone treats as transient).
3. [ ] Send `one`, `two`, `three`. All 🕓.
4. [ ] Wait 2 minutes. Watch the log: only the first message is retried (one in flight per chat,
       the others queue behind it), and the gaps between `POST /messages mode=pass` lines grow:
       about 1, 2, 4, 8, 16, 32, 60 s, each times a random 0.5 to 1.0. JobScheduler may add a few
       seconds to short delays, so the early gaps are rough; the doubling is what to look for.
5. [ ] Start Django again (same command as Setup). Do not touch the phone.
6. [ ] Wait for the next retry (at most about 60 s plus scheduling slack).

Pass:
- [ ] All three rows turn ✓ with `#1`, `#2`, `#3` in typed order.
- [ ] `GET /messages?chat_id=t3-1`: 3 rows, bodies `one`, `two`, `three`, `server_seq` 1, 2, 3.
- [ ] The logged gaps grew (write them down here): ____________________

## T5: airplane mode

Spec: airplane on, send 5, airplane off → 5 ✓, server has 5 rows, `server_seq` 1–5 in typed order.

1. [ ] Chat id `t5-1`.
2. [ ] `adb shell cmd connectivity airplane-mode enable`
3. [ ] Send `m1`, `m2`, `m3`, `m4`, `m5`. All 🕓. The proxy log shows nothing (the work waits on
       its `CONNECTED` constraint, so no attempt is even made).
4. [ ] `adb shell cmd connectivity airplane-mode disable`. Keep the app open.

Pass:
- [ ] All five turn ✓ `#1` to `#5`, in typed order, without touching the phone.
- [ ] `GET /messages?chat_id=t5-1`: exactly 5 rows, `m1`..`m5`, `server_seq` 1..5.
- [ ] Proxy log: 5 `POST /messages mode=pass` lines, no 4xx/5xx.

## Optional: A4 reboot (not in the five, not yet run)

1. [ ] Chat id `a4-reboot`. Airplane on, send `after-reboot`, 🕓.
2. [ ] `adb reboot`, wait for the phone, unlock it, airplane off. Do **not** open the app.
3. [ ] Pass: the row reaches the server on its own (`GET /messages?chat_id=a4-reboot`), because
       WorkManager re-registers its persisted work after boot. If it only arrives after opening the
       app, write that down: some vendor ROMs block background starts after boot.
