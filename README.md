# Pairloom

A local voice intercom for two Android phones. No internet, cloud servers, or accounts at runtime.

The phones connect to each other over Wi-Fi Direct, with no router involved. Talk in a full-duplex
call or with push-to-talk, while the app is open.

Status: 0.5.0, a working prototype, not yet on Google Play. The release work is planned in
[docs/plans/phase4-release.md](docs/plans/phase4-release.md).

## What it does
- **Pairing.** The first time two phones connect, both show a 6-digit code; the users confirm it
  matches. After that the phones recognise each other, and a phone whose identity changed is
  refused.
- **Calls.** Full-duplex voice on the earpiece, loudspeaker or a wired/USB headset. The platform's
  echo cancellation, noise suppression and gain control are used.
- **Push-to-talk.** Hold to talk on the loudspeaker. The other phone shows who is talking.
- **One-tap reconnect.** Paired phones connect with Connect on both, with no system prompt: the
  phones agree on who hosts from their keys.
- Shows why a call ended, and warns when nothing has been heard from the other phone for a second.
- **Background calls and reconnect.** A foreground service keeps the link while connected. With
  the screen off, calls work but sound rougher on some phones (Wi-Fi power saving).

## How to use it
1. Install the app on both phones and open it on both.
2. The first time: tap **Host** on one phone and **Join** on the other, pick the first phone,
   accept the connection prompt on the host if one appears, then compare the 6-digit codes and
   confirm on both phones.
3. After that: tap **Connect** on both phones, in any order. They pick their roles themselves and
   connect without any prompt.
4. Tap **Call** or **Push-to-talk**. The other phone accepts.

Calls keep going in the background, with the screen off or another app open. A notification
shows the call with Hang up, and incoming calls ring as a call notification with Answer and
Decline. If the link drops, both phones reconnect by themselves for up to 90 seconds.

## Security and privacy
- Every link is mutual TLS (1.3, or 1.2 with ECDHE-ECDSA AEAD) with a per-install Android Keystore
  key, pinned at pairing. See [docs/security/phase2-secure-session.md](docs/security/phase2-secure-session.md).
- Audio travels as UDP packets, each encrypted and authenticated with AES-256-GCM. The keys are
  fresh for every call and exchanged inside TLS. Old or replayed packets are dropped.
- Nothing is sent anywhere else: no analytics, no crash reporting, no accounts. App data is
  excluded from cloud backup and device transfer.

## Measured on the test phones (Galaxy A03s, Redmi Note 13 Pro)
- Estimated one-way delay about 180-200 ms, not counting the microphone's own hardware delay.
- 0 % network loss at close range; 2-3 % of frames concealed.
- About 20-35 % of one CPU core during a call (release code).

Details are in [docs/plans/phase3-audio.md](docs/plans/phase3-audio.md).

## Building
Android Studio (or its bundled JDK) and the Android SDK 36:

```bash
./gradlew assembleDebug testDebugUnitTest
```

- Kotlin and Jetpack Compose; minSdk 26, target and compile SDK 36.
- The `profile` build type is release code signed with the debug key, for measuring CPU and
  battery only. It is not for publishing.
- The phones must support Wi-Fi Direct. Emulators do not, so device tests need two real phones.

## Project layout
- `app/src/main/java/.../p2p`: Wi-Fi Direct (groups, discovery, direct join).
- `.../security`: identity, pairing, trusted phones.
- `.../net`: the control protocol and channel.
- `.../session`: the link state machine and view model.
- `.../media`: UDP media, encryption, jitter buffer, audio and codecs.
- `.../ui`: the Compose screen.
- `docs/`: the security design and the phase plans with measurements.
