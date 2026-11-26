# Discord Gateway publisher

## Transport layout

`PresenceEngineService` owns one `DiscordGatewayPublisher`. The publisher owns a Ktor 2.3.13 CIO `HttpClient`, a single WebSocket connection loop, one heartbeat job, one conflated presence-update channel, and a serialized outbound send mutex. It never owns Android source-tracker lifecycles.

`DiscordGatewayProtocol` constructs pure Kotlin envelopes. The publisher serializes them to UTF-8 JSON and enforces a 4,096-byte upper bound before sending. Oversized local presence data is rejected rather than truncated silently.

## Message lifecycle

1. Open `wss://gateway.discord.gg/?v=9&encoding=json`.
2. Wait for `HELLO` (opcode 10) and read `heartbeat_interval`.
3. Start heartbeat with randomized initial jitter.
4. Send `IDENTIFY` (opcode 2) for a new session, or `RESUME` (opcode 6) when a previous session id, resume URL, and sequence remain available.
5. Track `READY`/`RESUMED` dispatches and the latest sequence number.
6. Answer server-requested heartbeat opcodes immediately; track heartbeat ACK and reconnect when the ACK deadline is missed.
7. On both `READY` and `RESUMED`, replay the latest queued activity. Publish Presence Update (opcode 3) from the latest winning `PresenceActivity` snapshot. Updates are conflated and throttled. A minimal activity sends only `name` and `type` plus the compatibility display field. Clearing the winner sends `activities: []`.
8. On reconnect, use only a `wss://` resume host under `discord.gg` on the default/443 port. Invalid sessions clear or retain resume state according to the server's resumable flag and wait within Discord's documented randomized retry window.

## User-session compatibility caveat

Discord's public Gateway documentation describes Identify in the context of bot/application clients. This app uses the user-session Identify payload shape observed in the public Kizzy Android project. Fields such as `capabilities` and camelCase `largeThreshold` are not part of the public documented bot Identify contract. This behavior is compatibility-sensitive, unsupported, and may be rejected or changed without notice. The project does not claim official Discord endorsement.

## Presence fields

The protocol mapper can emit:

- `name`, `type`, `details`, `state`, `url`, `application_id`
- `timestamps.start` and `timestamps.end` in Unix milliseconds
- `assets.large_image`, `large_text`, `large_url`, `small_image`, `small_text`, `small_url`
- up to two button labels in `buttons`, with matching URLs in `metadata.button_urls

Current Android source mapping emits Playing for app/browser candidates and Listening for media-session candidates. The manual Studio editor exposes all six activity types, account status, streaming URL, both artwork slots with tooltips, up to two URL buttons, and optional start/end timestamps. Details and State may be left blank when the selected activity type permits it. The selected values are validated, persisted locally, restored by the engine, and passed through the same transport-neutral activity model. The local preview follows Discord's documented type-specific headline formats: Playing uses `name`, Streaming uses `details`, Listening and Watching use `name`, Custom Status uses `state`, and Competing uses `name`. Details and State remain distinct activity fields; for Custom Status, State is the status text. The Gateway activity object's `name` remains the activity name, not a UI alias for `details`. The `application_id` associates the activity with the registered Discord application and its assets. Actual rendering remains subject to Discord's client and the unsupported user-session compatibility path.

Per-app profiles can override activity type, label, details, state, stream URL, artwork, buttons, timestamps, priority, enabled state, and account status independently for each selected package.

## Artwork policy

Artwork supports application asset keys, public HTTPS image URLs, and Gecko gallery uploads. Gallery uploads are registered through Discord external-assets and cached internally as `mp:` references. At the Gateway serialization boundary, Gecko converts those internal references to `https://media.discordapp.net/...` URLs before sending the Activity Update; Discord returns `mp:` references in Gateway events. The external-assets registration endpoint is an undocumented compatibility surface and may change without notice.

## Dependencies

- Ktor 2.3.13: `ktor-client-core`, `ktor-client-cio`, `ktor-client-websockets`.
- The Android project currently uses Kotlin Gradle Plugin 2.0.21. Ktor 3.3+ documentation moves to Kotlin 2.2 and later; do not upgrade the transport independently without coordinating the Kotlin toolchain.

## Source tests

`bash scripts/test-core.sh` tests protocol envelope structure and transport-neutral mapping without credentials or a network connection. These tests do not emulate a real Discord Gateway session and do not establish that Discord will accept the undocumented user-session Identify payload.

## Experimental multi-activity behavior

Multi-Activity Mode is an opt-in experiment in Settings → Experimental and is OFF by default. OFF retains the pre-existing single-winner publication contract. ON selects unexpired candidates using priority descending, start timestamp descending, source type ascending and source key ascending; equivalent mapped activities are deduplicated and the list is capped at three. Manual and automatic sources may coexist, while the top-ranked candidate controls account-level status.

The protocol serializes the selected list into the Gateway `activities` array and preserves update coalescing, revision tracking, rate limiting, reconnect replay, and the 4,096-byte UTF-8 payload guard. If the multi-activity payload exceeds the local guard, Gecko retries the top-ranked activity only. This fallback demonstrates only a local socket write attempt, not Discord server acceptance or client rendering. Normal-user Gateway automation is undocumented/unsupported and carries account-policy risk; the in-app warning must be reviewed before opt-in.
