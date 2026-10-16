# Pairloom

A local voice intercom for two Android phones. No internet, cloud servers, or accounts at runtime.

The phones connect over Wi-Fi Direct. Every session runs inside mutual TLS with a per-install
Android Keystore key; the first connection between two phones pairs them by comparing a 6-digit
code. See [docs/security/phase2-secure-session.md](docs/security/phase2-secure-session.md).

Status: initial project scaffold (blank Compose screen; no voice, networking, or Wi-Fi Direct yet).
