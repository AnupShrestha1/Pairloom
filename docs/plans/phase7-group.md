# Phase 7: group push-to-talk

## Goal
Three or more phones on one push-to-talk channel, like a walkie-talkie group: one person talks at a
time and everyone else hears them. Full group calls (everyone talking at once) are left for later.
Two-phone calls and push-to-talk keep working exactly as now.

## Starting point
The code assumes one other phone throughout:
- the hosting phone accepts the first connection only (`acceptPeer`);
- `LinkViewModel` holds one control channel and one `MediaNegotiation`;
- a `MediaSession` sends to and receives from one address.

## Design
### Topology: a star around the host
- The hosting phone (Wi-Fi Direct group owner) is the **hub**. Every member links to the hub with
  the existing mutual TLS and pairing. Members do not link to each other, so a member only has to
  be paired with the host.
- The hub relays: a talker's audio goes to the hub, and the hub sends it on to every other member
  and plays it itself. The hub does not decode audio to relay it: it opens each packet (checking it
  is authentic) and seals the same payload again for each listener with that listener's key.
- One codec for the whole group: Opus if the hub and every member have it, otherwise PCM. The hub
  then never transcodes.
- **Re-stamping**: each phone numbers its frames from its own microphone clock. The hub gives
  every listener one rising frame count, so one talker following another never looks like frames
  going back in time to the listener's jitter buffer.

### Floor control
- Pressing Talk asks the hub for the floor (FLOOR_REQUEST). The hub grants it if nobody is talking,
  and tells everyone who has it (FLOOR: talker, or nobody). Releasing gives it back.
- If someone else has the floor: a short "busy" buzz, and "Asha is talking" stays on screen.
- The hub takes the floor back if the talker's phone disappears, or after 60 s of holding.

### Control protocol (version 7) additions
GROUP_ROSTER (members' names and ids), FLOOR_REQUEST, FLOOR_RELEASE, FLOOR (holder id or none),
and a group kind for MEDIA_OFFER.

### Simulating a third phone (only two real phones exist)
1. **On the computer, in tests**: the hub and several members as real UDP and TLS endpoints on
   localhost in one JVM test. They run the same classes as the app, with software keys instead of
   the Android Keystore. This checks the relay, the re-stamping, floor control and the codec choice
   with three, four, or more members.
2. **On the phones: a virtual phone.** In debug builds only (a `debug` source set, so it can never
   ship in a release), the hosting phone can add a simulated member. It joins the hub inside the
   same app over localhost, with its own software identity, and it:
   - talks in short spurts of test tone or a recorded phrase;
   - counts what it receives.

   With the Redmi hosting, the A03s joining and the virtual phone on the Redmi, the group has three
   members. The A03s hears the virtual phone through the hub over Wi-Fi Direct, the virtual phone
   receives the A03s, and floor conflicts can be produced on purpose.

## Milestones
- **G1 Hub core (JVM)**:
  - floor control, re-stamping and relay as plain classes;
  - a loopback test with a hub and three simulated members: everyone hears the talker intact, one
    talker at a time, and the hand-over between talkers is clean.
- **G2 Hosting several members**: the hub keeps accepting paired members, each with its own TLS
  control channel. Protocol version 7, and the group offer, roster and floor messages.
- **G3 Virtual phone** (debug builds): a simulated member on the hosting phone.
- **G4 Group screen**: members and who is talking, the talk pad, busy feedback, and joining and
  leaving.
- Device checks after each step: Redmi hosting, A03s plus the virtual phone as members.

## Risks
- Wi-Fi Direct groups have a client limit that varies by phone (often 4-8).
- The hub's radio carries every stream twice (in and out), and more on 2.4 GHz with more members.
- MIUI and One UI background limits apply to the hub as in Phase 5.
