# Phase 5: background calls and reconnect

## Goal
A call or a push-to-talk session keeps going when Pairloom is not on screen: the screen is off,
the phone is in a pocket, or another app is open. A link that drops for a moment comes back by
itself. Same scope otherwise: two phones, Wi-Fi Direct, no internet.

## Today
- All link and call state lives in `LinkViewModel`, an activity-scoped ViewModel. The activity
  ends the media session in `onStop` (version 1 is foreground only), and the link ends if the
  activity is destroyed.
- Nothing keeps the process important while it is in the background, and no notification shows a
  running call.
- A link that is silent for 5 s ends, and the user has to tap Connect again on both phones.

## Design
### B1. Keep running in the background
- **App-scoped owner.** The `LinkViewModel` instance is kept in a `ViewModelStore` owned by the
  `Application` (`PairloomApp`), not by the activity. It survives the activity being stopped,
  destroyed and recreated, and is cleared only when the process ends. The UI code is unchanged:
  the activity gets the same instance.
- **Foreground service** (`LinkService`) while a link is up:
  - type `connectedDevice` while linked, plus `microphone` during a call or push-to-talk. The
    microphone type is only added from the foreground: calls are started or accepted in the app.
  - An ongoing notification shows "Connected to X", "In call with X" or "Push-to-talk with X",
    with Mute / End call / Disconnect actions. Tapping it opens the app.
  - It stops as soon as the link ends.
- Leaving the app no longer ends the call. If the notification permission (Android 13+) is
  refused, the call still continues; only the notification is hidden.
- New permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (asked for with the Wi-Fi Direct
  permissions, never required).
- The screen no longer has to stay on: `FLAG_KEEP_SCREEN_ON` only while the app is visible.

### B2. Incoming calls in the background
- A call or push-to-talk request from the other phone while Pairloom is not visible shows a
  high-priority call-category notification ("X is calling"). Tapping it opens the app, where the
  user accepts. Accepting needs the microphone from the foreground, as Android 14+ requires.

### B3. Reconnect after a dropped link
- When a link to a paired phone ends because the other phone stopped responding (not because a
  user disconnected), both phones try Connect again by themselves for up to a minute, with the
  same roles. They show "Reconnecting to X". A call in progress ends cleanly ("link lost"); the
  user calls again once the link is back.

### B4. Policy and documents
- Play foreground-service declaration (types and why), privacy policy and Data safety updates,
  and README.

## Risks
- Doze and battery savers (MIUI, One UI) may still throttle Wi-Fi Direct with the screen off. To
  be measured with the screen off.
- Android 14+ refuses to start a microphone foreground service from the background, which is why
  calls start only from the app.
- The heads-up notification depends on the notification permission and the user's settings.

## Status (2026-10-26)
### B1: done, partly verified
- `PairloomApp` owns the `LinkViewModel`. `LinkService` (a foreground service) follows the link with
  an ongoing notification: "Connected to X", "In call with X", "Push-to-talk with X",
  "X is calling" (only while Pairloom is not on screen) and "Reconnecting to X". It has
  Mute / End call / Disconnect / Cancel actions. Leaving the app no longer ends a call.
- On the phones: the notification showed "Connected to SM-A037F", then "In call with SM-A037F".
  Android logged the service type changing from connected device (16) to connected device +
  microphone (144). With the Redmi (host) in the background and its screen off, the call stayed up
  for 30 s and the process stayed alive.
- **Problem: quality with the screen off.** The A03s saw 15.7 % concealed and about 540 ms
  estimated delay. A likely cause: the Wi-Fi low-latency lock only acts while the app is in front
  with the screen on. Up to Android 13 the high-performance lock still works in the background, so
  both are now held. Android 14+ (the Redmi) has no app-level alternative; to be measured.
- **Problem: the link later ended** ("the other phone stopped responding") while the Redmi stayed
  in the background with its screen off. Not diagnosed yet. Candidates: MIUI's background
  restrictions, Doze, or Wi-Fi Direct power saving on the group owner.
- **Not verified**: the notification's End call button. Turning the Redmi's screen off locked it,
  and the test cannot unlock it.

### B2: in place, not verified
- The incoming-call notification comes from the same mapping (`noticeFor`): high-importance
  channel, call category, opens the app to answer.

### B3: done, not verified on the phones
- A link to a paired phone that drops (stopped responding, group ended, network error; not a
  user's Disconnect) restarts Connect by itself, with the same roles, for up to 90 s, on both
  phones. "Reconnecting to X" shows on screen and in the notification, which also keeps the
  service running, so no new background start is needed. A running call ends as "the link was
  lost". Cancel stops it.
- On Android 12 and older, Wi-Fi Direct discovery from the background may need more location
  access than the app has. Untested.

### Needs the user
- Unlock the Redmi. From now on, a test that turns a screen off needs someone there to unlock
  the phone afterwards. The tests never unlock a phone themselves.
