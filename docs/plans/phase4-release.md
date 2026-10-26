# Phase 4: release readiness (Google Play)

## Goal
Turn the working intercom into an app a stranger can install from Google Play and use without help.
The scope stays the same: two phones, Wi-Fi Direct, no internet, accounts or paid services,
foreground only. Phase 3's open device checks (battery off USB power, a real headset, an
interrupting phone call) carry over and are done in R5.

## Where things stand (audit, 2026-10-17)
| Area | Today | Needed |
|---|---|---|
| Main flow | Diagnostic screen: Host / Join, raw link states, a peer list | A plain flow for a paired phone ("Connect", "Call", "Push-to-talk"); first-time pairing explained step by step |
| Roles | The user picks Host or Join | For paired phones, pick roles automatically, or at least explain them in one line |
| Icon and look | Template launcher icon, default Material theme | Own icon (adaptive and monochrome), colours, dark theme checked |
| Accessibility | Not checked; the talk button is a hold gesture | TalkBack labels, a tap-to-toggle alternative to holding, large fonts, contrast |
| Release build | Minify off, no release signing | R8 shrinking and resource shrinking, keep rules if needed, an upload key kept outside the repository, an Android App Bundle |
| Versioning | 1 / 0.1.0 | versionCode policy, versionName per release |
| Privacy | No data leaves the phones; backups off | Privacy policy (required for microphone and location), Data safety answers ("no data collected or shared"), in-app permission explanations checked |
| Store listing | None | Title, short and full description, screenshots, feature graphic, content rating |
| Compatibility | Tested on Android 13 and 16 | Android 8-11 (API 26-30) on real phones: emulators have no Wi-Fi Direct |
| Docs | README out of date | README for users and contributors; security notes kept current |

## Milestones (each ends with tests, an offline build and a check on both phones)
1. **R1 Consolidate.** Merge `phase3-audio` into `main` (local, fast-forward). Update the README.
   Set the version to 0.3.0 (versionCode 3).
2. **R2 Main flow and look.**
   - Home shows the paired phones with "Connect". The first-time path is "Pair a new phone", with
     the Host/Join steps written out.
   - In-call and push-to-talk screens that work one-handed.
   - Automatic roles for paired phones: the phone that taps Connect first hosts and the other joins
     directly. Fall back to the current choice if that fails.
   - Own icon and theme.
3. **R3 Accessibility.** TalkBack pass over every screen. A tap-to-talk / tap-to-stop option for
   push-to-talk. Font scale 200 % and contrast checks.
4. **R4 Release build.**
   - R8 and resource shrinking, with rules only where needed.
   - Release signing from a keystore outside the repository (the user creates it; never committed).
   - Build an `.aab`, and check the shrunk build on both phones (CPU and calls compared with the
     profile build).
5. **R5 Device coverage and Phase 3 leftovers.**
   - Android 8-11 phones if available.
   - Battery over 20-30 minutes off USB power, a wired or USB headset, and an incoming phone call
     during a Pairloom call.
6. **R6 Play preparation.** Privacy policy text, Data safety answers, store listing text and
   screenshots, content rating answers. Publishing (account, upload, review) is the user's: nothing
   is uploaded without explicit approval.

## Decisions taken
- No analytics, crash reporting or other network services, now or for release: they would break
  "no internet at runtime" and the privacy policy's "no data collected".
- The `profile` build type stays for measuring only; releases use the release type with the
  user's own key.

## Needs the user
- Phones with Android 8-11, if any are available (R5).
- Unplugged phones for the battery run, a headset, and a phone call to one of the phones (R5).
- A Google Play developer account, an upload key, and where to host the privacy policy (R4, R6).

## R2 results (2026-10-17)
- **Home** lists the paired phones, each with **Connect** and **Be ready**. Be ready hosts. Connect
  opens the join side and, once discovery has started, joins that phone directly, with no prompt
  on the host.
  - The first version joined as soon as the join screen opened. The joiner's stale-state cleanup
    (which cancels connections) then cancelled the join once. The join now waits until discovery
    has started.
  - For a phone paired before direct join existed, Connect falls back to the normal Join list.
    After one link it works directly.
- **Pair another phone** explains the Host / Join steps and the code check.
- **Linked** shows large Call and Push-to-talk buttons. Link statistics, the media test, paired-phone
  keys, location status and the event log are under "Show details".
- **Look**: themed (monochrome) launcher icon for Android 13+, teal brand colours where dynamic
  colour is unavailable (Android 8-11), and a dark window background in dark mode (no white flash
  at start).
- Device check, A03s "Be ready" and Redmi "Connect", 3 runs: linked every time in about 9 s, the
  script's polling included, with no host prompt. No crashes.
- Not done: automatic roles. "Be ready" on one phone is still needed. Picking roles automatically
  would need each phone to see the other's group before linking. Revisit after R3.

## R3 results (2026-10-17)
- **Tap to talk**: push-to-talk can work by tapping (tap to start, tap to stop) instead of
  holding. It is on automatically while TalkBack's touch exploration is running, since a hold
  gesture is hard to perform there, and it can be chosen with a switch otherwise. The microphone
  stops sending if the panel goes away or the mode changes mid-press.
- **TalkBack**: the talk area is a button with a state ("talking" / "listening") and an action
  label. "<name> is talking", "Nothing heard from…" and the call-ended message are polite live
  regions, and an incoming call or push-to-talk request is assertive. The pairing code is read
  digit by digit.
- **Large fonts**: the big buttons use a minimum height instead of a fixed one, so text can grow.
- Device check (A03s): switching on tap to talk, one tap made the Redmi show "SM-A037F is
  talking", and a second tap brought back "Nobody is talking", with a clean spurt in the log.
- Not checked on a phone: TalkBack itself and 200 % font scale. Both are system settings the user
  turns on; to be done by hand.

## R4 results (2026-10-26)
- Release builds run R8 and resource shrinking. No keep rules were needed: no reflection-based
  libraries, and only platform crypto. Profile APK 1.1 MB instead of 8.5 MB; release bundle 2.5 MB.
- Release signing reads `keystore.properties` (git-ignored), with the passwords optionally from
  environment variables. Without it the bundle is unsigned. Steps are in `docs/release.md`. The
  upload key is the user's to create.
- Offline builds skip lint vital (`-x ...`, documented). The Compose mapping file is switched off,
  because it needs an artifact the offline cache does not have.
- Shrunk build on both phones: direct-join link, a call ("Call ended by …" shown on the other
  phone) and tap-to-talk all worked, with no crashes.
- **Found and fixed during this check**: in push-to-talk, 22 % of frames were concealed. A spurt
  starts with the minimum 30 ms delay, before the spread is known. When the target then rose, the
  buffer could only add delay by rebuffering after 10 missing frames, so frames kept arriving after
  their turn. Now each late frame, while the buffer is below its target, stretches playout by one
  concealed frame (`Playout.Stretch`). Rerun: late frames 174 -> 29, concealed 22 % -> 4.8 %, calls
  unchanged (2.3 %).
