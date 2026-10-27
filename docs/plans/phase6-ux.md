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
| Indigo night | #16213E | dark background |
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
- Status and the rings sit in the top half, actions in the bottom third. Content is left-aligned,
  except the rings, which are centred.
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
