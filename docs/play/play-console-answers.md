# Play Console: draft answers

These are drafts for the forms in the Play Console. Check each answer against the form's current
wording when you fill it in: Google changes these forms from time to time.

## Store listing
- **App name** (30 characters max): `Pairloom: Local Intercom`
- **Short description** (80 max): `Talk between two phones over Wi-Fi Direct. No internet, no accounts.`
- **Full description**:

> Pairloom turns two Android phones into a private intercom. The phones connect directly over
> Wi-Fi Direct, with no router, no mobile data and no internet, and your voice never leaves the two
> phones.
>
> • Full-duplex calls on the earpiece, loudspeaker or a wired headset
> • Push-to-talk, like a walkie-talkie: hold to talk, or tap to talk
> • Pair once by comparing a 6-digit code; after that, connecting takes one tap on each phone
> • Every link is encrypted: mutual TLS with keys kept in your phone's secure hardware, and
>   AES-256-GCM for the audio
> • No accounts, no ads, no analytics, nothing sent to any server
>
> Good for keeping in touch around the house, across a building site or campsite, on a hike, or
> anywhere without signal. Range depends on the phones, walls and interference.
>
> Calls keep going with the screen off or another app open, and a dropped link reconnects by
> itself. Incoming calls ring as a call notification.

- **Category**: Communication
- **Tags**: intercom, walkie-talkie, offline
- **Contact email**: _yours_. **Privacy policy URL**: _where `privacy-policy.md` is published_.

## Data safety
- **Does your app collect or share any of the required user data types?** No.
  - Audio goes directly from one user's phone to the other user's phone, end-to-end encrypted, and
    the developer never receives it. Phone names, pairing keys and group credentials stay on the
    two phones. Nothing is sent to the developer or to third parties. If Google's current
    definitions treat device-to-device audio as "collected", declare **Audio > Voice or sound
    recordings**: not shared, processed ephemerally, required for the app's function, encrypted in
    transit.
- **Is all of the user data collected by your app encrypted in transit?** Yes (TLS 1.3 / 1.2 for
  control, AES-256-GCM for audio).
- **Do you provide a way for users to request that their data is deleted?** Data stays on the
  device. Forgetting a paired phone, or uninstalling, deletes it.

## App access
All functions work without login. Reviewers need **two phones** with Wi-Fi Direct. Instructions
for reviewers: "Install on two phones. Tap Host on one and Join on the other, pick the first phone,
confirm the 6-digit code on both, then tap Call."

## Ads
No ads.

## Content rating (IARC questionnaire)
- Violence, sexuality, language, controlled substances, gambling: none.
- **Users can interact or exchange information**: yes. Voice between two phones that the users
  paired in person, over a direct local link. No contact with strangers, no public content, no
  user-generated content beyond live voice.
- **Shares the user's location with other users**: no.
- **Digital purchases**: no.

## Target audience and content
- Target age: 13 and over (not designed for children; avoids the Families policy requirements).
- News app: no. COVID-19 app: no. Government app: no. Financial features: none.

## Foreground service declaration (Phase 5)
Pairloom runs a foreground service only while it is connected to the user's other phone:
- **connectedDevice**: "Keeps the user-initiated Wi-Fi Direct link to the user's other phone
  open, so a call or push-to-talk session can continue and incoming calls can be received while
  the app is not on screen. The service shows an ongoing notification and stops as soon as the
  link ends."
- **microphone**: "Captures the microphone only during a call or push-to-talk session the user
  started or accepted inside the app, so the call continues with the screen off or another app
  open."
- Video of the feature (Play asks for one): link two phones, start a call, press Home and show
  the ongoing call notification with Hang up; then end the call from the notification.

## Permissions declarations
- **Location** (`ACCESS_FINE_LOCATION`, only up to Android 12, `maxSdkVersion` 32): requested only
  in the foreground, because Android requires it for Wi-Fi Direct discovery on those versions. No
  background location, so no background-location declaration is needed.
- **Foreground service**: see the declaration above (connected device and microphone types).
