# Phase 6: UI and UX

## Brief
- **Subject**: a private intercom between two phones near each other, with no signal needed: at
  home, on a building site, on a trail.
- **Audience**: pairs of ordinary people (family, a work crew), not technicians.
- **Primary job**: connect and talk in as few taps as possible, and see at a glance whether the
  other phone is there and who is talking.

## Audit of the current screens (0.5.0)
1. Every screen opens with a large "Pairloom / Local voice intercom for two phones" header. The
   top third of the screen, where status belongs, is spent on branding.
2. Phones appear as model codes ("23117RA68G", "SM-A037F"), so nobody can tell whose phone is whose.
3. Everything is a stack of identical rounded cards holding paragraphs. There is no hierarchy, and
   the controls sit mid-screen, not where a thumb rests.
4. The home screen permanently carries three numbered paragraphs of pairing instructions.
5. The call screen shows engineering readouts ("PCM · audio replaced or lost: 0.9 % · estimated
   delay 202 ms") instead of a timer. End call is not red, and Mute / Speaker are text pills.
6. Connecting, linked and talking are only lines of text; there is no visual sense of "we are
   connected".
7. Material You's dynamic colours make the app look different on every phone (light blue on both
   test phones), so it has no identity.

## Direction: two threads
The brand mark, two rings, becomes the live status and the one bold element of the app:
- **apart** when not connected,
- **drawing together** while connecting,
- **interlocked** when linked,
- and the other phone's ring **pulses** while that person is talking.

Everything else stays quiet: one column, plain sentences, controls at the bottom like a handset.

### Colour (own palette; dynamic colour off)
| Name | Hex | Role |
|---|---|---|
| Pitch black | #000000 | dark background (true black: AMOLED pixels off, and the neon rings stand out; asked for by the user) |
| Cool paper | #EEF1F5 | light background |
| Thread teal | #1E9C96 | this phone's ring; primary actions (Connect, Call, Answer) |
| Thread saffron | #F2A93B | the other phone's ring; "is talking" |
| Signal red | #D64545 | hang up, decline, errors |
| Mist | #9AA8BC | secondary text on dark |

Teal and saffron are dyed-thread colours, a nod to the loom in the name. This avoids both the
cream-and-terracotta and the black-and-acid-accent defaults.

### Type
- The phone's own system font (Roboto, One UI Sans, MiSans), on purpose. The app feels native on
  each phone, nothing has to be downloaded or bundled, and names in any script (Devanagari
  included) render properly.
- Personality comes from scale and weight:
  - the other phone's name is the largest text (34 sp, medium);
  - one status line (18 sp);
  - small secondary text;
  - tabular figures for the call timer and the pairing code.
- Sentence case, no all-caps labels.

### Layout (portrait, single column)
```
Linked                      In call                     Incoming
+------------------------+  +------------------------+  +------------------------+
|                    (i) |  |                    (i) |  |                        |
|       ( )( )           |  |       ( )( )  <- pulse |  |     ( )  ...  ( )      |
|   Asha's phone         |  |   Asha's phone         |  |   Asha's phone         |
|   Connected            |  |   02:14  Asha talking  |  |   is calling           |
|                        |  |                        |  |                        |
|                        |  |  (mic)  (speaker)      |  |                        |
| [  Call  ][ Talk     ] |  |      [ Hang up ]       |  | [Decline]   [ Answer ] |
|      Disconnect        |  |                        |  |                        |
+------------------------+  +------------------------+  +------------------------+
```
- The rings, the name and the status sit centred in the space above the actions, which stay at the
  bottom. On a short screen, or with large text, that space scrolls.
- Diagnostics (link statistics, codec switch, test tone, event log, keys) move behind an (i)
  details sheet.
- Errors take one line on what happened, one on what to do, and a "Try again" button.

### Names
- **This phone's name**: asked for once ("Name this phone", defaulting to the model) and sent to
  the other phone when linking.
- **Paired phones**: can be renamed locally ("Asha's phone").

## Steps
1. Theme, icons (drawn in code; no icon dependency) and the rings composable with its states.
2. Home, connecting and linked screens; details sheet.
3. Call, push-to-talk, incoming and pairing screens; error states.
4. Names.
5. Screenshot review on both phones, light and dark, large font; fix what does not hold up.

## Results (2026-10-27)
Built and checked on both phones (dark mode, which both use):
- **Rings as status**: dashed saffron ring apart (not connected); drawing together (connecting);
  interlocked with teal passing over saffron at the top crossing (linked). A glow follows the voice
  of whoever is talking: the other phone's from its measured loudness (new `MediaStats.peerLevel`),
  this phone's while its talk button is down. An incoming call pulses like ringing.
- **One layout per state**:
  - Home: name, "Not connected", Connect, "Pair a new phone".
  - Pair a new phone: "Wait for the other phone" / "Find the other phone" replace Host / Join.
  - Connecting: Cancel.
  - Linked: Call and Push-to-talk, Disconnect.
  - Incoming: red Decline, teal Answer.
  - Call: timer, route, round Mute and Speaker toggles, red End call.
  - Push-to-talk: talk pad, tap-to-talk switch, Speaker toggle, End.
  - Pairing: big code, "Both show this code".
- **Diagnostics** moved to a Details screen behind the (i) button: link statistics, test tone and
  codec switch, keys, rename and Forget, location status, event log.
- **Names**: this phone's name (Details, and offered on the first-time screen) is sent instead of
  the model, and the other phone's stored name updates on the next link. Paired phones can also be
  given a local nickname. Checked: after naming the phones "Galaxy" and "Redmi", the Redmi showed
  "Galaxy is calling" and its home screen "Galaxy".
- **Own palette** (dynamic colour off), the system font with its own scale, and icons drawn in
  code. No new dependencies.

Fixed after the first screenshot review:
- the dashed ring looked like beads and its saffron went muddy at 75 % opacity;
- the five-line centred paragraph;
- grey labels on outlined buttons;
- "Galaxy is talking" under the name "Galaxy";
- the name and status hugged the top, leaving a dead gap;
- the voice glow was a filled disc that muddied the ring, and then too faint.

Not checked yet (these are system settings for the user to switch, by hand): light mode, the
largest font size, and TalkBack across the new screens.

## Rework after the user's review (2026-10-27)
The user found the first version confusing, with features hard to reach, and asked for a subtle
neon look and a pure black background. Changes:
- **Settings** replaces the (i) Details screen and is reached by a labelled "⚙ Settings" button.
  Sections:
  - This phone (name, Rename);
  - Your phones (each with Rename and Forget, which asks for confirmation; Pair a new phone);
  - Calls (the Opus switch, explained in plain words);
  - troubleshooting information, folded away.
- **Home** lists every other paired phone with its own Connect. "Pair a new phone", "Disconnect",
  "Search again" and "The codes are different" are outlined buttons instead of faint text.
- **Pairing a new phone** is back to **Host** and **Join**, each with one line saying what it does.
- **Neon rings**: a blurred glow under each tube (Android 9+) and a bright core line. Faint when
  apart, stronger when linked, strongest while that phone is talking.
- **Pure black dark theme** (#000000; surfaces #111316 / #1B1E23): AMOLED pixels switch off, and
  the neon reads better.

## Refinements (2026-10-27)
- **Crossfade between screens**: each state is keyed, so connecting → linked → call fades instead
  of cutting. A screen that is fading out draws only if the state still fits it, so it never shows
  half-switched data.
- **Push-to-talk haptics**: a firm tick when the microphone goes live and a light one when it stops,
  so nobody has to look.
- **TalkBack**: status lines are read with the name ("Galaxy, is talking").
- **Settings while linked**: no disabled "Pair a new phone" button (it was nearly invisible on
  black). One line says: "Disconnect first to forget a phone or pair a new one."
- "This phone is called Redmi. Rename": "Rename" is in the accent colour, so it reads as tappable.
- **Launcher icon**: the neon rings on black, woven at the top crossing. The themed icon (Android
  13+) keeps the plain white rings.
- Checked on both phones: a full tour through every state with no crash.

## Appearance, light mode and first-time pairing (2026-10-27)
- **Appearance** in Settings: Follow the phone (default) / Dark (pure black) / Light. It loads at
  start, so a chosen look does not flash. Status and navigation bar icons follow the app's look.
- **Light mode**, checked on both phones (via the new setting): text contrast holds, and "is
  talking" uses the deeper saffron. The neon core line read as a pale double line on a light
  background, so light mode draws plain coloured threads with a softer glow. Neon is for black only.
- **First-time pairing**, walked on both phones after Forget: first-run screen, Host, the join
  list, the system prompt, the code check ("587 269"), linked. No crash. Fixed from it:
  - the hosting title wrapped to two lines and repeated "Waiting…", so it is now "Hosting" with
    "Waiting for the other phone…";
  - "Rescan" is now "Search again", matching the help text.
