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
