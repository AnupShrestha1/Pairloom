# Pairloom privacy policy

_Last updated: 28 October 2026 (draft, to be published at a public URL before release)_

Pairloom is a voice intercom for Android phones that talk to each other directly over
Wi-Fi Direct, two at a time or as a group. It has no servers, no accounts and no analytics. The developer receives no data from
the app.

## What the app handles, and where it goes
| Data | Why | Where it goes |
|---|---|---|
| Your voice (microphone) | Calls and push-to-talk, only while you are in one | Straight to the other phone over Wi-Fi Direct, encrypted (AES-256-GCM). In a group, it goes to the phone that started the group, which passes it on, still encrypted, to the other members. It is played and not recorded or stored on any phone. |
| Phone name (the model name, such as "SM-A037F") | Shows the other phones who is connecting or talking | Sent to the phones you connect with (in a group, to its members), and stored only if you pair. |
| Pairing keys | Recognise a paired phone and keep the link private | Created on your phone and kept in its secure hardware storage (Android Keystore). Only the public part is shared with phones you connect to. |
| List of paired phones | Connect to them again | Stored only on your phone. |
| Wi-Fi Direct group name and password | Let your paired phones connect without a prompt | Stored on your phone and sent only to phones you have paired with, over the encrypted link. |
| Join code (three words) | Let people join a group without pairing | Made up on the phone that starts the group and shown on its screen. It is never sent over the air: phones prove they know it without revealing it. It is forgotten when the group ends. |
| Group announcement | Let phones with the code find the group | While a group is waiting for members, the phone announces a random group name nearby over Wi-Fi Direct. It contains nothing about you or the code. |

Nothing above is sent to the developer or to anyone else. Pairloom does not use the internet,
even though Android lists the "network" permission: Android requires it for any network
connection, including the direct link between the two phones.

The app's data is excluded from Android cloud backup and from device-to-device transfer. You can
forget a paired phone at any time ("Show details", then "Forget"), and uninstalling the app
removes all of its data.

## Permissions
- **Microphone**: asked for the first time you start or accept a call. Used only during calls and
  push-to-talk.
- **Nearby devices** (Android 13 and later), or **Location** (Android 12 and earlier): Android
  requires one of these to find and connect to nearby phones over Wi-Fi Direct. Pairloom does not
  determine or store your location. On Android 13 and later it declares that it never uses this
  permission to derive location.
- **Wi-Fi state, network, wake lock and audio settings**: to run the Wi-Fi Direct link, keep it
  responsive during calls, and route audio to the earpiece, loudspeaker or headset.
- **Notifications** (Android 13 and later): to show the ongoing call and incoming calls while
  Pairloom is not on screen. Optional; calls work without it.
- **Foreground service**: while connected to your other phone, Pairloom keeps running in the
  background with a visible notification, so calls can continue with the screen off. It stops
  when the connection ends. The microphone is used only during a call or push-to-talk session you
  started or accepted.

## Children
Pairloom is not directed at children under 13.

## Changes
If this policy changes, the new version will be published at the same address with a new date.

## Contact
Anup Shrestha. Contact address: _to be added before publishing_.
