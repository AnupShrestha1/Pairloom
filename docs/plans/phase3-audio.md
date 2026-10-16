# Phase 3 plan: real-time voice

Status: plan. Builds on Phase 2 (authenticated, encrypted control channel over Wi-Fi Direct).

## Goal
Two paired phones talk to each other in real time over the Wi-Fi Direct link, in **full duplex**
or **push-to-talk**, while the app is open. No internet, accounts or paid services.

Targets (to be measured on the A03s and Redmi):
- Mouth-to-ear latency under 150 ms in full duplex at a few metres.
- Under 1 % packet loss at 5 m; clean degradation (no crashes, no stuck audio) when the link is lost.
- No audible echo in full duplex with the earpiece; usable with the speaker where the phone's echo
  canceller works.

## Facts this plan relies on (checked on both phones)
| Fact | A03s (Android 13) | Redmi (Android 16) |
|---|---|---|
| Native output rate / HAL buffer | 48 kHz / 1024 frames (21 ms) | 48 kHz / 1024 frames |
| `android.hardware.audio.low_latency` | no | yes |
| Opus via MediaCodec | encoder and decoder (`c2.android.opus.*`) | encoder and decoder |
| Platform AEC / AGC effects | listed | not listed in the dump (check at runtime) |
| NDK / CMake installed | no | — |

Opus encoding through MediaCodec exists from API 29; minSdk is 26, so PCM stays as the fallback
for API 26-28 and needs no NDK.

## Design

### Media transport
- UDP on the Wi-Fi Direct interface, bound to this phone's group address only. The peer address is
  the address of the authenticated TLS peer; each side sends its UDP port over the control channel.
- Packets from any other address or port are dropped and counted.
- Separate from the TCP control channel, so a lost voice packet never delays the next one.

### Media encryption and integrity
- When a call starts, each phone generates a random 256-bit key and a random 4-byte salt for **its
  own sending direction** and sends them to the peer inside the TLS control channel. This works on
  every API level (the TLS exporter would need API 31).
- AES-256-GCM per packet (javax.crypto). Nonce = salt ‖ 64-bit sequence number; the packet header
  is authenticated data. Packets that fail authentication are dropped and counted, never played.
- A 128-packet sliding replay window rejects duplicates and replays. New keys for every call; a
  sequence number never repeats within a key.

### Packet format (version 1)
`version (1) | flags (1: PTT/talk-spurt start, codec) | sequence (8) | media timestamp in samples (4) | ciphertext | GCM tag (16)`

### Audio capture and playback
- `AudioRecord` with `VOICE_COMMUNICATION` (enables the platform voice processing), 48 kHz mono
  16-bit, so no resampling on these phones; `AcousticEchoCanceler`, `NoiseSuppressor` and
  `AutomaticGainControl` attached where `isAvailable()`.
- `AudioTrack` with `USAGE_VOICE_COMMUNICATION`, `CONTENT_TYPE_SPEECH`, `PERFORMANCE_MODE_LOW_LATENCY`;
  `AudioManager.MODE_IN_COMMUNICATION` during a call; earpiece/speaker toggle.
- Capture, send, receive and playback on dedicated threads at `THREAD_PRIORITY_URGENT_AUDIO`.

### Codecs
- **PCM** (always available): 10 ms frames (960 bytes), so a packet stays below one Wi-Fi frame
  (no IP fragmentation). About 0.8 Mbit/s per direction.
- **Opus** via MediaCodec where both phones can encode and decode (API 29+): 20 ms frames, 24-32
  kbit/s mono. Chosen by capability exchange at call start; otherwise PCM.

### Jitter buffer and concealment
- Adaptive playout delay between 30 and 120 ms, driven by measured inter-arrival jitter, with the
  playout clock taken from the AudioTrack.
- Missing frame: repeat the last frame with fading for up to 3 frames, then silence. Late frames
  are dropped. Statistics: loss, late, concealed, buffer depth.

### Modes
- **Full duplex**: both phones send continuously while the call is up. Default route: earpiece.
- **Push-to-talk**: send only while the button is held; the other phone shows "talking". Both may
  talk at once (no floor control in v1). Default route: speaker.
- Mute in both modes.

### Control channel additions (protocol version 3)
`CALL_OFFER` (mode, codecs, UDP port, key, salt), `CALL_ANSWER` (accept or decline, chosen codec,
port, key, salt), `CALL_END`, `TALK` (PTT pressed or released), `MUTE`, and `MEDIA_STATS` once a
second (loss, jitter, buffer depth) so each phone can show the other side's reception.

### Permissions, lifecycle and interruptions
- `RECORD_AUDIO`, requested when the user starts the first call, never at launch.
- Foreground only (v1): the call ends when the activity stops. No foreground service is needed.
- Audio focus: an incoming phone call or another app taking focus ends or pauses the Pairloom call.
- A route change (headset plugged or unplugged) re-routes without ending the call.

## Milestones (each ends with unit tests, an offline build and a device test in both directions)
1. **A1 Media transport.** UDP socket on p2p0, packet format, AES-GCM, replay window, jitter buffer.
   A synthetic tone instead of the microphone, so loss, jitter and latency are measured without
   acoustic effects. Also check whether Wi-Fi power saving inflates latency (the control-channel
   pings showed up to 700 ms round trips at one ping per second).
2. **A2 Full-duplex PCM.** Microphone and speaker, voice processing effects, earpiece/speaker,
   mute, call start/end over the control channel, `RECORD_AUDIO` flow. Latency measured with a
   click-loopback test.
3. **A3 Push-to-talk.** Hold-to-talk button, "talking" indicator, speaker default.
4. **A4 Opus.** MediaCodec encoder and decoder with capability negotiation and PCM fallback;
   compare latency and quality with PCM.
5. **A5 Hardening.** Audio focus and interruptions, route changes, link loss mid-call, error and
   statistics UI, battery and CPU check over a 30-minute call.

## A1 results (2026-10-16, A03s hosting, both phones on home Wi-Fi, phones next to each other)
Synthetic 48 kHz tone, 10 ms PCM frames (960 bytes), AES-256-GCM, 30-40 s per run; the tone was
started from each phone in turn and media flows in both directions in every run.

| Run | Group channel | Round trip avg / min / max | Delay spread | Jitter-buffer target | Concealed | Loss | Played frames intact | Rejected (auth / source / replay) |
|---|---|---|---|---|---|---|---|---|
| 1, first jitter buffer | 2412 MHz (A03s Wi-Fi on 2462) | ~130 / 6 / 460 ms | not measured | 40-50 ms | ~36 % (buffer trimmed ~1,900 frames) | 0-0.3 % | 100 % | 0 / 0 / 0 |
| 2, burst-aware buffer + Wi-Fi lock | 2412 MHz | 142-144 / 9 / 516-574 ms | ~250 ms | 260-270 ms | ~2 % | 0.05-0.2 % | 100 % | 0 / 0 / 0 |
| 3, group on the host's Wi-Fi channel | **2462 MHz** | **56-66 / 9 / 183-191 ms** | **114-120 ms** | **130-140 ms** | ~2.4 % | 0-0.05 % | 100 % | 0 / 0 / 0 |

Findings:
- **Multi-channel concurrency is the main latency source.** When a phone is also connected to normal
  Wi-Fi on another channel, its radio time-shares between the two and delivers packets in bursts.
  Putting the group on the host's Wi-Fi channel halved the round trip and the delay spread.
- The remaining spread most likely comes from the Redmi, whose normal Wi-Fi is on 5 GHz while the
  group must stay on 2.4 GHz (the A03s is 2.4 GHz only). Not yet confirmed: the next check is a run
  with normal Wi-Fi disconnected on both phones (Wi-Fi on, no network), which the user does.
- An RFC 3550 jitter estimate alone badly underestimates bursty delivery; the buffer now sizes
  itself from the delay spread over the last 2 s and trims gradually.
- Encryption, authentication and source checks held throughout: no forged, replayed or misrouted
  packet was ever played, and every played frame matched the sender's tone bit for bit.
- Not covered yet: the Redmi as host (the A03s, with Location off, does not discover peers), and
  distance or obstacles.

Consequences for A2: with both phones on different normal-Wi-Fi channels the playout delay is about
130 ms plus audio I/O, above the 150 ms mouth-to-ear target. Options, in order: tell the user (UI
hint) when normal Wi-Fi is on another band; prefer the host's channel (done); consider a shorter
buffer cap in PTT, where a little more delay is acceptable anyway.

## A2 results (2026-10-16, first full-duplex call)
A 61-second voice call between the A03s (group owner) and the Redmi, earpiece, phones side by side,
both on home Wi-Fi (group on the A03s's channel). Mute and unmute were used during the call.

| | A03s | Redmi |
|---|---|---|
| Platform voice processing | echo canceller, noise suppressor and gain control all on | not logged (log rotated) |
| Microphone buffer | 3840 bytes = 40 ms | — |
| Speaker buffer | 8208 bytes = 4104 frames = **85 ms** (no low-latency audio feature) | — |
| Network loss / concealed | 0.15 % / 1.7 % | 0 % / 2.7 % |
| Round trip avg / max | 63 / 322 ms | 65 / 608 ms |
| Jitter-buffer target | 160 ms | 140 ms |
| Estimated one-way (network + buffer, no audio hardware) | 191 ms | 173 ms |
| Rejected packets (auth / source / replay) | 0 / 0 / 0 | 0 / 0 / 0 |

Findings:
- Calls work end to end, in both directions, with the platform's voice processing on the A03s.
- Mouth-to-ear delay is about 250-300 ms once audio hardware is added, about twice the 150 ms
  target. The largest parts are the jitter buffer (driven by the burstiness measured in A1) and the
  A03s's 85 ms output buffer. A5 should trim the AudioTrack buffer with `setBufferSizeInFrames`,
  measure real mouth-to-ear delay acoustically, and repeat with normal Wi-Fi disconnected.
- The microphone permission was granted with `adb shell pm grant` for this test; the in-app request
  flow (ask on Call or Accept) is implemented but was not exercised on the phones.
- Not yet assessed: subjective audio quality, echo on the speaker, and push-to-talk (A3).

## Latency pass after A2 (2026-10-16)
Changes: the speaker uses only two device bursts of its buffer and grows by one burst per underrun
(A03s: burst 256 frames, settles at 1024 frames = 21 ms instead of 4104 = 85 ms); the jitter
buffer sizes itself from the 97th-percentile delay spread, so a rare spike no longer holds it high;
the speaker delay is now measured from AudioTrack timestamps. Also fixed: rotating a phone ended the
call (a configuration change stops the activity; the call now survives it).

Two 55-60 s calls, same set-up as A2 (both phones on home Wi-Fi):

| | Before | After |
|---|---|---|
| Speaker buffer in use (A03s) | 85 ms | 21 ms |
| Measured speaker delay | — | 64-65 ms on both phones (mixer and HAL included) |
| Jitter-buffer target | 140-160 ms | 100-170 ms (follows the measured spread, 90-152 ms) |
| Estimated mouth-to-ear (network/2 + buffer + speaker + 10 ms microphone frame) | about 250-300 ms | 207-214 ms, plus microphone hardware |
| Concealed / network loss | ~2 % / 0-0.15 % | 2-3 % / 0 % |

What is left: the jitter buffer is now the largest part, and it is sized by real burstiness on the
link (each phone's radio also serving its home network on another channel). The next measurement is
with home Wi-Fi disconnected on both phones; if the spread drops, a UI hint ("disconnect from Wi-Fi
for the lowest delay") is the cheap win. The speaker's ~65 ms is the platform floor on these phones.

## Latency floor without home Wi-Fi (2026-10-16)
Same call test with the home router switched off (both phones: Wi-Fi on, no network; group on
2412 MHz, A03s hosting, Redmi calling), 60 s:

| | Home Wi-Fi connected | No home Wi-Fi |
|---|---|---|
| Round trip avg / min / max | 59-69 / 5-7 / 203-317 ms | 62-74 / 7-9 / 494-698 ms |
| Delay spread (97th percentile) | 90-152 ms | 76-81 ms |
| Jitter-buffer target | 100-170 ms | 90-100 ms |
| Measured speaker delay | 64-65 ms | 58-59 ms |
| Estimated one-way | 207-214 ms | 189-206 ms |
| Concealed / loss | 2-3 % / 0 % | 2-2.6 % / 0.14-0.26 % |

Conclusion: once the group is on the host's channel, home Wi-Fi costs only about 10-20 ms. The
remaining burstiness belongs to the Wi-Fi Direct link itself: a 7 ms minimum round trip shows the
radio path is fast, but delivery still comes in bursts with spikes up to 700 ms, most likely client
power saving on the Wi-Fi Direct link, which Android does not let apps control. The floor on these
phones is therefore roughly 190-200 ms plus microphone hardware with the current design.

Ideas, not yet tried: swap roles so the other phone is the client (to see which phone's power
saving causes the bursts; needs the A03s to discover, i.e. Location on); concealment that hides more
late frames so the buffer percentile can drop (Opus in A4 has better concealment); a lower-latency
push-to-talk buffer is not useful, as PTT tolerates delay.

## Risks
- **Echo on speaker** on the low-end A03s: the platform AEC may be weak. Mitigation: earpiece
  default for full duplex, PTT for speaker use.
- **Wi-Fi Direct power saving** could add latency spikes; continuous media traffic usually keeps the
  radio awake. Measured in A1 before the audio path exists.
- **MediaCodec Opus** may add buffering or behave differently between devices; PCM remains the
  fallback.
- **A03s buffer sizes** (no low-latency feature): 21 ms HAL buffers set a floor on latency.
- API 26-30 behaviour is untested on the available phones.

## Out of scope for Phase 3
Background calls and a foreground service, Bluetooth headsets, more than two phones, recording,
video.
