# Phase 2: peer authentication and secure session

Status: implemented on branch `phase2-secure-session`. This document is the G1 security gate for
the pairing protocol and records the evidence from the disposable M0 spike
(`spike/keystore-tls`, never merged).

## Goals
- Only phones the user has explicitly paired can open a Pairloom session with each other.
- Everything after the Wi-Fi Direct TCP connection runs inside mutually authenticated TLS.
- Pairing needs no internet, accounts or servers, and resists a man in the middle on the
  Wi-Fi Direct link.
- A changed identity for a paired phone is always a hard stop.

Out of scope for Phase 2: audio, background operation, protection against a compromised phone.

## M0 evidence (spike, both test phones)
| Finding | Evidence |
|---|---|
| Platform TLS (Conscrypt) signs with Keystore EC keys via `NONEwithECDSA`, so the key must allow `DIGEST_NONE`. A SHA-256-only key fails with `Incompatible digest` (-13). | Conscrypt `CryptoUpcalls` source (Android 8.0 to current); A03s Keystore log; `KeyGenParameterSpec.Builder.setDigests` documentation. |
| A NONE + SHA-256 P-256 key gets a valid self-signed certificate and completes mutual TLS 1.3 and 1.2 on Android 13 (A03s) and 16 (Redmi). | `local-dual-digest`, both phones. |
| `getPeerSupportedSignatureAlgorithms()` returns a hard-coded placeholder, so a key-manager rule based on it (spike K2) cannot work. | Conscrypt source (android13 and android16 branches); both phones. |
| Pinning in the trust manager rejects impostor keys and flipped or random pins during TLS 1.3 and 1.2 handshakes, with no application data exchanged. | `local-wrong-pin`, 14/14 cases on each phone. |
| Mutual TLS terminates on the phones in both directions; exporters match. | Cross-phone diagnostic over the adb tunnel, 20/20 connections. |
| The handshake protocol is readable from the trust-manager callback, on the socket path. | `tmProto` observed on every rejecting side. |

## Design

### Identity
- One Keystore key per install: alias `pairloom.identity.v1`, EC P-256, `PURPOSE_SIGN`,
  digests `NONE` and `SHA-256`, non-exportable, with a Keystore self-signed certificate.
- The key is used **only** for TLS client and server authentication. Nothing else verifies
  signatures from it; any future application-level signature needs a separate SHA-256-only key.
  This contains the `DIGEST_NONE` cost: only code running as the app can use the key at all, and
  TLS signatures cover fresh handshake randoms, so they cannot be replayed elsewhere.
- Fingerprint = SHA-256 of the certificate's SubjectPublicKeyInfo.
- A random 16-byte install ID identifies the install (stored in `noBackupFilesDir`).

### Transport
- Mutual TLS on the Wi-Fi Direct TCP socket. The group owner is the TLS server and requires a
  client certificate. TLS 1.3 where available (API 29+), else TLS 1.2 with ECDHE-ECDSA AEAD suites.
- A new `SSLContext` for every connection, and session tickets turned off where the API exists
  (API 29+), so there is no session resumption.
- The trust manager accepts exactly one EC P-256 self-signed certificate, records its fingerprint,
  and rejects anything else. Whether the key belongs to a paired phone is decided right after the
  handshake, before any application data, so an unknown phone can be offered pairing. The
  fingerprint is cross-checked against the TLS session's peer certificate.
- The server speaks first. In TLS 1.3 the client's handshake can finish before the server has
  checked the client certificate, so the client sends nothing until it has received the server's
  HELLO.

### Session start (control protocol version 2)
1. Server sends HELLO (version, install ID, device name); client replies with its HELLO.
2. Each side classifies the peer against its paired-device store:
   - fingerprint and install ID both match one record: **authenticated**;
   - fingerprint known with a different install ID, or install ID known with a different key,
     or the peer presents this phone's own key: **conflict** (hard stop);
   - neither known: **needs pairing**.
3. Server sends STATUS, client replies with STATUS. Any conflict on either side stops the session
   with an "identity changed" error and nothing is stored. Both authenticated: the heartbeat
   starts. Otherwise: pairing.

### Pairing (numeric comparison with commitment)
Roles follow TLS: the server is the responder. `Fs`/`Fc` are the server's and client's key
fingerprints **as each side observed them in its own TLS session** (own certificate plus peer
certificate).
1. Server picks a 32-byte random `Ns`, sends `COMMIT C = SHA-256("pairloom-commit-v1" ‖ Fs ‖ Fc ‖ Ns)`.
2. Client picks `Nc`, sends `NONCE Nc`.
3. Server sends `REVEAL Ns`. The client checks `C`; a mismatch stops pairing.
4. Both compute `V = SHA-256("pairloom-sas-v1" ‖ Fs ‖ Fc ‖ Ns ‖ Nc)` and show the 6-digit code
   `uint64(V[0..8]) mod 10^6`.
5. Each user confirms the codes match (or not). Each side sends `CONFIRM`. The peer is stored only
   if both sides accepted. A rejection, a timeout (120 s) or a broken commitment stores nothing.

Why it resists a man in the middle M: each victim's code depends on the key M presented to it,
so M must make two independent codes equal. The responder commits to its nonce before seeing the
other nonce, so on each leg one of the two nonces is fixed before M can see the other. M therefore
cannot search for a match and succeeds with probability about 10^-6 per attempt, and every failed
attempt is visible to the users as mismatching codes. This is the Bluetooth Secure Simple Pairing
numeric-comparison argument, with TLS providing the key-possession proof.

### Storage
- Paired devices (fingerprint, install ID, name, time paired) in a versioned file in
  `noBackupFilesDir`, written atomically. A corrupt store fails closed: sessions are refused rather
  than silently treating every phone as new.
- Cloud backup is disabled and the data-extraction rules exclude all data from cloud backup and
  device-to-device transfer.
- The user can forget a paired phone. That is the only way to pair again with a phone whose
  identity changed.

### Direct join of paired phones (added in Phase 3)
- Each phone keeps its own Wi-Fi Direct group name and passphrase (16 random alphanumeric
  characters, about 95 bits) in `noBackupFilesDir`. It hosts with them instead of fresh random ones
  each time.
- After a link is authenticated (mutual TLS with a paired key), each phone sends its group to the
  other (GROUP_INFO, control protocol version 5, inside TLS only). The receiver stores it with that
  paired phone's record (paired-device file format v2). Values that are not in Pairloom's own
  format (`DIRECT-xx-Pairloom`, 8-63 alphanumerics) are rejected on the wire and in storage.
- "Join <name> directly" then joins that group by name and passphrase, so the host shows no system
  connection prompt. The passphrase only admits a phone to the Wi-Fi Direct group. The control
  link is still mutual TLS against the pinned key, and media is still authenticated per packet.
- Forgetting a paired phone replaces this phone's group, so the forgotten phone can no longer join
  directly. The remaining paired phones receive the new group on their next link.
- The passphrase is never logged or shown (`toString` hides it everywhere).
- GROUP_INFO also carries the sender's current Wi-Fi channel (protocol version 6), stored with
  the pairing (file v3). A host uses it to keep the group on the other phone's channel. It reveals
  only which channel that phone's own Wi-Fi network uses, and only to a paired phone.
- "Connect" on both phones picks the host by comparing the two key fingerprints. These are already
  known to both phones, so nothing new is exchanged.

## Residual risks and limits
- `DIGEST_NONE` widens what the key can sign for code running as the app; accepted because the
  key is single-purpose (see Identity).
- A reinstall creates a new key and install ID, so the phone appears as a new, unpaired phone
  (pairing with a fresh code) rather than as a conflict. The stale record stays until forgotten.
- Certificate validity dates are not enforced; trust is the pinned key alone.
- API 26-28 (no TLS 1.3) and API 29-30 are not covered by the test phones.
- The 6-digit comparison only protects users who actually compare the codes.
- Group passphrases are stored unencrypted in app-private, no-backup storage. A phone that has
  them (a paired phone, or anyone who extracts them from a rooted device) can join the Wi-Fi Direct
  group while it is up. It can disturb the link, but cannot pass TLS or forge media.
