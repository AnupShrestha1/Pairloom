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
- The floor goes to whoever the hub hears first while nobody is talking, and the hub tells everyone
  who has it (FLOOR: talker, or nobody). The talker's TALK_END gives it back. No request message is
  needed, so a lost message can never leave someone holding a floor they did not ask for.
- Someone who presses Talk while another person has the floor is not relayed for that whole press,
  even if the floor frees up meanwhile (press again, like a walkie-talkie). Their phone shows a
  short "busy" buzz, and "Asha is talking" stays on screen.
- The hub takes the floor back after 0.5 s without the talker's audio (a lost TALK_END, or a phone
  that went away), and after 60 s of holding.
- The hosting phone's own user joins the hub like any other member, through an ordinary session on
  a loopback socket, so the relay has no special case for the host.

### Control protocol (version 7) additions
- GROUP_JOIN (member to host: codecs, UDP port, key) and GROUP_WELCOME (host to member: the
  group's codec, the member's id, the hub's port and key). Like MEDIA_OFFER and MEDIA_ANSWER, but
  each member joins or leaves on its own; nobody has to accept anyone else.
- GROUP_LEAVE (either way: left, not admitted, or ended).
- GROUP_ROSTER (every phone on the link, who is in the talk, and which entry is the receiver).
- FLOOR (holder id or none).
- Frames may now be up to 1024 bytes, so a full roster of 12 long names fits.

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

## G1 results (2026-10-27)
- `media/GroupHub.kt`: `Floor`, `Restamper` and the `GroupHub` relay (one thread, one UDP socket,
  a key pair per member, never decodes). It answers members' probes and probes each member, so
  every phone keeps its round-trip measure.
- `GroupHubTest` (5 tests) runs real loopback UDP with three simulated phones, each an unchanged
  push-to-talk `MediaSession`, with frames tagged with the talker and position:
  - phone 1 then phone 2 talk; each listener plays the frames whole and in order, and no phone
    hears itself;
  - phones 1 and 3 press together: 1 keeps the floor, 3 is heard by nobody for that press, then is
    heard on its next one;
  - a phone that vanishes mid-spurt frees the floor within 0.5 s, and the others carry on.
- Full suite: 121 tests, passing on three runs in a row.
- Not covered on the computer: Opus across talkers (one decoder fed by two encoders in turn), which
  the device checks will listen for.

## G2 results (2026-10-27)
- **Protocol 7** (`ControlProtocol`): the five group messages above. Phones on protocol 6 cannot
  link with 7, as with every earlier version change.
- **`media/GroupTalk.kt`**:
  - `GroupHost` (hosting phone): the roster, joins and leaves, the group codec, and the hub's
    lifetime;
  - `GroupMemberLink` (every phone): joins, runs the push-to-talk session, and keeps the roster
    and floor;
  - `InProcessLink`: the hosting phone's own user joins its hub this way. The virtual phone (G3)
    will use it too.
- **Hosting several phones** (`LinkViewModel`): after the first link the hosting phone keeps
  listening. Each later phone gets its own TLS control link and becomes a group member, but only
  if it is already paired: `SessionNegotiator` can now refuse to pair, so a stranger is turned away
  without a code prompt.
- **App**: Linked shows **Group talk**, or **Join group talk (n in it)** when others are in it.
  The push-to-talk screen then reads "Group talk":
  - "Galaxy is talking";
  - pressing while someone else has the floor shows "Redmi has the floor. Let go and try again.";
  - if they let go first, the screen keeps saying "Not heard. Let go and press again to talk."
  A 1:1 call offered while a phone is in the group talk is declined automatically (one
  microphone).
- **Tests**: 127 (protocol round trips and malformed frames, three in-process members over
  loopback, the codec rule, a member that vanishes, an unpaired phone turned away).
- **On both phones** (the Redmi hosted, the A03s joined):
  - both joined the group talk and took turns over Wi-Fi Direct, Opus, both directions;
  - packets lost: A03s 0-2 of about 1,500, Redmi 0;
  - concealed: 3 % (A03s) and 0.5-1 % (Redmi);
  - estimated one-way delay: about 190-210 ms;
  - the busy messages and the hand-over showed as intended, with no crash.
- **Fixed: Disconnect after a group talk looked like a dropped link.** The extra closing work
  let the Wi-Fi Direct group go before the goodbye, so the other phone started reconnecting. Now
  the goodbyes go out first and the group is removed after everything has closed. Checked: the
  other phone saw "disconnected" in both linked runs (it had seen a dropped link before the fix).

Still open:
- If the first phone leaves, the hosting phone ends the whole link, extra members included. The
  group should stay up while anyone is left.
- Home's Connect picks roles by key. A third phone whose key is lower than the host's would try
  to host instead of joining, so joining an existing group needs its own button (G4).
- Several real phones in one Wi-Fi Direct group are untested (only two phones are available). The
  virtual phone covers the relay path on the host.

## G3 results (2026-10-27)
- **Virtual phone** (`src/debug/.../debugtools/VirtualPhone.kt`; `src/release` and `src/profile`
  hold a stub with `AVAILABLE = false`):
  - it joins the hub in-process with its own keys and UDP socket, so its audio goes through the
    same relay as a real phone's;
  - every 8 s it plays a 2 s, 330 Hz test tone, but only while nobody else has the floor;
  - it logs what it heard every 10 s.

  It is switched on in Settings > troubleshooting, on the hosting phone while linked. Checked:
  the release and profile APKs contain none of its code.
- **Three-way check** (Redmi hosting with the virtual phone, A03s joined):
  - the A03s talked for 12 s; the Redmi showed "Galaxy is talking", and the virtual phone heard
    1,229 frames with none lost, holding back twice while the A03s had the floor;
  - the A03s heard the virtual phone's spurts (0.17 % lost, about 210 ms one-way);
  - a press that started during a virtual spurt was shut out for the whole press, as designed.
- **Found and fixed: the Opus decoders on both phones do not keep input timestamps across a
  jump.** After a pause between spurts or a change of talker, their output times keep counting
  on, for example 3622 for a packet at 6559. Frames landed seconds from where they belonged:
  - on the A03s, 16 % lost, a 6 s delay spread and an 11 s "decode delay";
  - ordinary 1:1 Opus push-to-talk was affected too.

  The decoder now matches outputs to packets in order (`DecodeOrder`, as the encoder already
  did). A timestamp is trusted only when it matches. After the fix the A03s lost 0.2-0.3 %, with a
  70-90 ms spread.
- **Fixed: Disconnect could still look like a drop.** Closing the virtual phone's codecs took
  about 4 s. The goodbyes now go out before any audio is closed, and the group is removed no
  sooner than 300 ms after them. Checked: the other phone saw "disconnected".
- Tests: 129, including the decoder labelling.
- Not confirmed: the A03s showing "Virtual phone is talking". Its screen reads never caught it,
  probably because uiautomator waits for the glowing rings to stop moving; the Redmi showed it.
