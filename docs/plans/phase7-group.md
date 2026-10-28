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

## G4 results (2026-10-27)
- **Who is on the link**: one line under the status, for example
  "Redmi (you) · Galaxy · Virtual phone". Whoever has the floor is shown in the other phone's
  colour; phones not in the talk are dimmed and marked "not in the talk". It shows on the group
  talk screen, and on Linked once more than two phones are on the link.
- **Join a group whatever the keys say**: Home shows "Join Redmi's group instead" under Connect
  (Android 10+, for a phone whose group is known). It always joins and never hosts. Checked: the
  A03s joined the Redmi this way in 1.3 s.
- **The group stays up when the first phone leaves** (hosting phone): if other phones are still
  linked, the next one is promoted to the main link (its name at the top, 1:1 calls, link
  statistics) and the group talk goes on. The link ends only when the last phone leaves or this
  phone's user disconnects. Not checked on the phones: it needs a second real phone linked to the
  host (the virtual phone joins in-process, not over a control link).
- Checked on both phones with the virtual phone: the member lists on both screens, "Galaxy is
  talking" on the Redmi while the A03s talked, the virtual phone holding back while the A03s had
  the floor, and Disconnect seen as "disconnected". No crash.

## Plan: G5 and G6 (agreed 2026-10-27)
Out of scope, after discussion: host migration between paired phones, relay chains
(E -> D -> C -> B) and open channels that strangers join. Each costs more than it gives for this
app, and can't be tested properly with two phones. Open channels would also break the app's privacy
promise.

### G5: rides that tolerate distance
- **Host in the middle.** The hosting phone matters most: everyone's range is measured from it.
  - The member line marks it: "Redmi (host)".
  - Each member's link quality comes from what the hub actually receives: good, fair, weak, or
    out of range. The host sends it in the roster, so every phone can see whose link is weak and
    who would make a better host.
- **Dropping out gracefully.**
  - A phone that loses the group keeps trying to rejoin for the whole ride (30 min instead of
    90 s).
  - If it was in the group talk, it rejoins the talk by itself once the link is back.
  - Everyone sees "Galaxy (out of range)" instead of silence.
- **A clear ending.**
  - On the hosting phone, Disconnect asks first when other phones depend on it: "End the group
    for everyone?"
  - Members tell "the host ended the group" apart from "lost the host".

### G6: ride codes
- **Starting:** a rider taps **Start a ride**, and the app shows a short code (three words) and a
  QR code. The others enter it once.
- **Joining:** any phone with the code joins automatically when in range, with no pairing,
  Connect or system prompt. That includes riders who come back after dropping out.
- **Finding the group:**
  - the host advertises a tag derived from the code over Wi-Fi Direct service discovery;
  - the group's name and Wi-Fi password are derived from the code, so joining needs no prompt;
  - a phone that finds nobody hosts. If two start hosting at once, the lower key gives way.
- **Security:**
  - joining uses a password-based handshake (PAKE, from a well-reviewed library), so a short
    code can't be tested offline by someone recording the radio;
  - the code expires after the ride (24 h at most);
  - the host can block a phone by key, or start a new code.
- **Scope:**
  - code members get group talk only, with no 1:1 calls;
  - they appear as "joined with the ride code";
  - they can pair properly afterwards.
- **Host hand-over:** if the host leaves, the next phone with the code hosts and the others join
  it.

## G5 results (2026-10-27)
- **Roster**:
  - each entry now carries "host" and the link quality the hub measures (good, fair, weak, out of
    range), counted from what it received over the last two seconds;
  - a phone that drops stays listed as "out of range" for up to 10 minutes, until it comes back;
  - the member line reads, for example, "Redmi (you, host) · Galaxy · Virtual phone".
- **Rides**:
  - a dropped group keeps reconnecting for 30 minutes instead of 90 s, in the same role (a member
    never starts hosting because of the keys);
  - a phone that was in the talk rejoins it by itself.
- **Endings**:
  - Disconnect on a host with two or more phones depending on it asks "End the group for
    everyone?";
  - members say "Redmi ended the group." instead of "disconnected".
- Tests: 132, including the quality rule, the host flag, and dropped phones kept and forgotten.
- On both phones (Redmi hosting with the virtual phone, the A03s joined with "Join Redmi's group
  instead"):
  1. Both lines showed host and you as intended.
  2. Killing the host's app put the A03s on "Lost the group … up to 30 minutes".
  3. When the host came back, the A03s rejoined as a member ("as before") and was back in the
     group talk by itself, 17 s after the host's Connect.
  4. The host's Disconnect asked first. Keep kept the group; End for everyone ended it.
  5. The A03s showed "Redmi ended the group."
- Not checked on phones: "out of range" and the link-quality labels for a real third phone (the
  virtual phone is in-process, so its link is always good).

## G6a results: ride code core (2026-10-27)
- **One design change from the plan: nothing public comes from the code.** About 16.7 million
  three-word codes are far too few to hide behind Wi-Fi's password. If the group name or the
  Wi-Fi password were derived from the code, someone recording one member joining could try every
  code offline in seconds. So:
  - a ride's Wi-Fi Direct group has a random name, which the host advertises (G6b);
  - its Wi-Fi password is a public constant, so it protects nothing, and it was never meant to:
    the audio is protected by TLS and per-member keys as before;
  - the code is tested only inside the J-PAKE handshake, which allows one guess per connection
    attempt.

  Anyone can join a ride's Wi-Fi group, but they can't join the ride or hear it.
- **`security/RideCode`**:
  - three words from a 256-word list of short, common words;
  - lenient parsing (any case, any separators);
  - the code never appears in `toString`, so it can't leak into a log.
- **`session/RideJoin`**:
  - J-PAKE (RFC 8236) from Bouncy Castle 1.80.2, over the 2048-bit NIST group with SHA-256,
    inside the mutual TLS link, with key confirmation in round 3;
  - the secret also includes both TLS key fingerprints, so someone in the middle with keys of
    their own fails even with the right code;
  - the result is 32 shared bytes.
- **`session/RideGuard`**: after 5 wrong codes in a minute, the host refuses joins until the
  minute is over.
- **Protocol**:
  - RIDE_PAKE (round, up to six signed numbers);
  - frames up to 4 KB;
  - a new failure: "The ride code does not match".
- **Bouncy Castle**:
  - the app's first third-party library, taken from the offline Gradle cache;
  - its unused data files (post-quantum tables, certificate messages) are left out of the APK,
    so the release APK stays at 1.5 MB.
- **Tests**: 138. They cover:
  - parsing;
  - the same code giving both phones the same secret, and a new secret on each join;
  - a wrong code rejected;
  - someone in the middle rejected;
  - the guard;
  - the wire format and malformed frames.
- **Next (G6b)**: Start a ride and Join a ride in the app; the host advertising the ride over
  Wi-Fi Direct service discovery; members finding it, joining and running RideJoin; ride members
  in the group talk; rejoining with the code during the ride.

## Naming (2026-10-28)
"Ride" was only the user's example of a group out and about, not what the app is for. The
feature is now a **join code**: Start a group / Join with a code. Code: `JoinCode`, `CodeJoin`,
`CodeGuard`, the CODE_PAKE message, and "-PairloomCode" Wi-Fi groups. G5's texts say "group" too.
Older sections above keep the words they were written with.

## G6b results: join codes in the app (2026-10-28)
- **Screens**: Start a group and Join with a code, on Home and on the first screen (no pairing
  needed). The host shows its three words while waiting, and "Join code: …" during the talk. A
  joining phone shows "Looking for the group…". Leave the group replaces Disconnect, and private
  calls are hidden in a code group.
- **Finding the group**: service discovery (DNS-SD) was never answered between the Redmi host and
  the A03s, even with the service added before the group was created. The joining phone therefore
  also watches the peer list and tries any phone that hosts a group. All code groups share one
  public Wi-Fi name ("DIRECT-PL-PairloomCode") and password, and the join picks the host by its
  device address. Nothing about the Wi-Fi group comes from the code; J-PAKE inside TLS decides.
  A host that failed the code is skipped for the rest of that search.
- **Device check (Redmi hosting, A03s joining)**, all passed:
  1. the Redmi starts a group and shows its code;
  2. a wrong code: the A03s finds the group, J-PAKE fails, and it shows "The join code does not
     match"; the Redmi keeps waiting;
  3. the right code: found in 4 s, Wi-Fi joined after 17 s, J-PAKE and TLS 0.5 s later; both
     phones are in the group talk by themselves, and the Redmi hears the A03s talk;
  4. the A03s leaves; the Redmi goes back to waiting with the same code.
- **Not checked**: more than one code group in range, and rejoining a code group after a drop.
- **Version**: 0.7.0 (code 7). The store listing and privacy policy drafts now describe groups and
  join codes.
