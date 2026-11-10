# ADR 0003 — Local Extension-to-Companion Bridge

**Status:** Proposed; requires device proof of concept  
**Decision:** Use a loopback-only HTTP bridge as the first transport candidate.

## Context

The extension runs in Gecko while the Companion is a separate Android process. The extension needs a small request/response channel to send normalized activity and clear events. A permanent WebSocket is unnecessary for the first protocol and could create avoidable connection/lifecycle complexity.

## Decision

- HTTP on `127.0.0.1` only.
- `GET /v1/health`, `POST /v1/activity`, and `POST /v1/clear`.
- Explicit pairing with a local high-entropy secret.
- Versioned JSON schema, strict input validation, bounded body size, timeout, sequence number, and expiry.
- No public-network listener or cloud relay.
- No arbitrary URLs, commands, file paths, or external asset URLs in messages.

## Consequences and unresolved questions

- Validate extension host permission, CORS/Origin behavior, cleartext policy, and loopback connectivity on Firefox Android and the exact Tor Android build.
- Select a small maintained embedded HTTP implementation after a current dependency review. Do not write an untested custom HTTP parser just to avoid one dependency.
- If loopback networking is unavailable or unreliable, revisit the bridge before implementing application logic; do not tunnel the connection through an external server.
