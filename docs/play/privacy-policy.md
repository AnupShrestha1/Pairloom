# Pairloom privacy policy

_Last updated: 26 October 2026 (draft, to be published at a public URL before release)_

Pairloom is a voice intercom for two Android phones that talk to each other directly over
Wi-Fi Direct. It has no servers, no accounts and no analytics. The developer receives no data from
the app.

## What the app handles, and where it goes
| Data | Why | Where it goes |
|---|---|---|
| Your voice (microphone) | Calls and push-to-talk, only while you are in one | Straight to the other phone over Wi-Fi Direct, encrypted (AES-256-GCM). It is played there and not recorded or stored on either phone. |
| Phone name (the model name, such as "SM-A037F") | Shows the other phone who is connecting | Sent to the other phone during a connection, and stored there if you pair. |
| Pairing keys | Recognise a paired phone and keep the link private | Created on your phone and kept in its secure hardware storage (Android Keystore). Only the public part is shared with phones you connect to. |
| List of paired phones | Connect to them again | Stored only on your phone. |
| Wi-Fi Direct group name and password | Let your paired phones connect without a prompt | Stored on your phone and sent only to phones you have paired with, over the encrypted link. |

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

## Children
Pairloom is not directed at children under 13.

## Changes
If this policy changes, the new version will be published at the same address with a new date.

## Contact
Anup Shrestha. Contact address: _to be added before publishing_.
