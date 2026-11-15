# Discord Gateway publisher

## Transport layout

`PresenceEngineService` owns one `DiscordGatewayPublisher`. The publisher owns a Ktor 2.3.13 CIO `HttpClient`, a single WebSocket connection loop, one heartbeat job, one conflated presence-update channel, and a serialized outbound send mutex. It never owns Android source-tracker lifecycles.

`DiscordGatewayProtocol` constructs pure Kotlin envelopes. The publisher serializes them to UTF-8 JSON and enforces a 4,096-byte upper bound before sending. Oversized local presence data is rejected rather than truncated silently.

## Message lifecycle

1. Open `wss://gateway.discord.gg/?v=10&encoding=json`.
2. Wait for `HELLO` (opcode 10) and read `heartbeat_interval`.
3. Start heartbeat with randomized initial jitter.
4. Send `IDENTIFY` (opcode 2) for a new session, or `RESUME` (opcode 6) when a previous session id, resume URL, and sequence remain available.
5. Track `READY`/`RESUMED` dispatches and the latest sequence number.
6. Answer server-requested heartbeat opcodes immediately; track heartbeat ACK and reconnect when the ACK deadline is missed.
7. Publish Presence Update (opcode 3) from the latest winning `PresenceActivity` snapshot. Updates are conflated and throttled to avoid excess dispatches. The current minimum interval is 4.1 seconds, below the documented ceiling of five game-status updates per 20 seconds.
8. On reconnect, use only a `wss://` resume host under `discord.gg` on the default/443 port. Invalid sessions clear or retain resume state according to the server's resumable flag and wait within Discord's documented randomized retry window.

## User-session compatibility caveat

Discord's public Gateway documentation describes Identify in the context of bot/application clients. This app uses the user-session Identify payload shape observed in the public Kizzy Android project. Fields such as `capabilities` and camelCase `largeThreshold` are not part of the public documented bot Identify contract. This behavior is compatibility-sensitive, unsupported, and may be rejected or changed without notice. The project does not claim official Discord endorsement.

## Presence fields

The protocol mapper can emit:

- `name`, `type`, `details`, `state`, `url`, `application_id`
- `timestamps.start` and `timestamps.end` in Unix milliseconds
- `assets.large_image`, `large_text`, `large_url`, `small_image`, `small_text`, `small_url`
- up to two `{label, url}` button objects

Current Android source mapping emits Playing for app/browser candidates and Listening for media-session candidates. The manual Studio editor exposes all six activity types, account status, streaming URL, both artwork slots with tooltips, up to two URL buttons, and optional start/end timestamps. Details and State may be left blank when the selected activity type permits it. The selected values are validated, persisted locally, restored by the engine, and passed through the same transport-neutral activity model. The local preview follows Discord's documented type-specific headline formats: Playing uses `name`, Streaming uses `details`, Listening and Watching use `name`, Custom Status uses `state`, and Competing uses `name`. Details and State remain distinct activity fields; for Custom Status, State is the status text. The Gateway activity object's `name` remains the activity name, not a UI alias for `details`. The `application_id` associates the activity with the registered Discord application and its assets. Actual rendering remains subject to Discord's client and the unsupported user-session compatibility path.

Per-app profiles currently customize the activity label, details, and state. They do not yet override activity type, artwork, buttons, or timestamps independently per package.

## Artwork policy

No gallery asset is uploaded as part of the current implementation. Public external media references can be passed through the protocol model; the Discord client/media proxy must be able to resolve them. Any future gallery upload/registration feature must show the destination and obtain explicit confirmation before transmitting the selected image.

## Dependencies

- Ktor 2.3.13: `ktor-client-core`, `ktor-client-cio`, `ktor-client-websockets`.
- The Android project currently uses Kotlin Gradle Plugin 2.0.21. Ktor 3.3+ documentation moves to Kotlin 2.2 and later; do not upgrade the transport independently without coordinating the Kotlin toolchain.

## Source tests

`bash scripts/test-core.sh` tests protocol envelope structure and transport-neutral mapping without credentials or a network connection. These tests do not emulate a real Discord Gateway session and do not establish that Discord will accept the undocumented user-session Identify payload.
