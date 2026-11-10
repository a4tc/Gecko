# ADR 0002 — Discord Transport Feasibility Gate

**Status:** Proposed / blocked pending POC  
**Preferred candidate:** Discord Social SDK Android RPC transport  
**Rejected as production dependency:** PreMiD `users/@me/headless-sessions` REST flow

## Context

The supplied PreMiD package contains `HeadlessSessionService`, obtains Discord OAuth account tokens, posts to `users/@me/headless-sessions`, stores returned activity tokens, refreshes them, and posts deletion requests. Static inspection does not establish that this is a public, stable, or independently authorized integration surface.

Discord's official Social SDK release notes state that version 1.10.18247 added Android Rich Presence via an unauthenticated RPC transport to the signed-in Discord Android client. The official SDK is described as a C++ integration for games and applications. Activity documentation says `Playing` is the type generally relevant to the SDK, with other activity type values documented as completeness values.

## Decision

Treat the official Android SDK as a **candidate**, not a confirmed production path. Before implementation beyond a throwaway spike:

1. obtain the current SDK package and read its terms;
2. verify the intended non-game Companion use is eligible;
3. verify the SDK can be integrated into a Kotlin-owned APK through its native C++ Android package;
4. test publish, update, reconnect, and clear behavior against Discord Android;
5. record activity-type, field, image-asset, and app-identity limitations.

If the SDK terms or technical behavior do not support this use case, the project must stop at local activity preview until a documented, permitted transport is available. Do not fall back to private REST endpoints, OAuth token scraping, or reverse-engineered Discord client internals.

## Consequences

- The Discord layer is an interface and POC first, not a fully committed dependency.
- The Discord application identity is fixed to the app registration.
- User-facing templates customize details/state and registered assets; they must not promise arbitrary per-site application identities or unsupported activity verbs.
- SDK access, package version, and NDK requirements are recorded before pinning build dependencies.

## References

- https://discord.com/developers/docs/social-sdk/release_notes.html
- https://discord.com/blog/mobile-platform-support-arrives-in-discord-social-sdk
- https://discord.com/developers/docs/social-sdk/classdiscordpp_1_1Activity.html
- https://discord.com/developers/docs/social-sdk/classdiscordpp_1_1Client.html
