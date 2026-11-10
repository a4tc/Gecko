# Implementation Plan

## Milestones

### M0 — Architecture and risk closure

- [ ] Review capability map and PRD.
- [ ] Select the exact Firefox Android and Android OS versions for the first target device.
- [ ] Inspect current Discord Social SDK terms and developer portal availability.
- [ ] Verify the intended app category is eligible for Social SDK integration.
- [ ] Decide how the local server will be implemented after evaluating a minimal maintained HTTP server/parser.

### M1 — Gecko detector proof of concept

- [x] Create a minimal MV2 event-page extension.
- [x] Register tab listeners synchronously at top level.
- [x] Read active tab metadata and normalize to a small `ActivityEvent`.
- [x] Add HTML media event listeners for the active YouTube page (play, pause, ended).
- [x] Persist only title-sharing preference and the latest semantic activity event.
- [x] Add Node.js unit and background-controller tests for active-tab selection, privacy normalization, URL rejection, media transitions, active-tab message filtering, tab-resume state, and manifest wiring.
- [ ] Run `web-ext lint` and test on Firefox Android.

**Current status:** source and generated extension compile; 17 automated tests pass. `web-ext lint` and real-device testing remain outstanding. The milestone exit criteria are not met until Android runtime and lint checks pass.

### M2 — Android loopback bridge proof of concept

- [ ] Create a minimal Kotlin APK with Start/Stop and health status.
- [ ] Start a user-visible local bridge only after a visible user action.
- [ ] Pair the extension with a high-entropy local secret.
- [ ] Test extension-origin CORS, Android cleartext/network-security behavior, loopback-only binding, and payload validation.
- [ ] Add clear/expiry and latest-state-wins behavior.

**Exit:** paired extension sends one valid event to the Companion on a real device and can stop/clear it.

### M3 — Discord SDK feasibility proof of concept

- [ ] Download official Social SDK package via developer portal.
- [ ] Record terms and verify non-game companion use eligibility.
- [ ] Build minimal Android C++ SDK integration with supported ABI/NDK versions.
- [ ] Call SDK callbacks on a controlled thread/loop as required by documentation.
- [ ] Test Android RPC publish/clear with Discord installed and signed in.
- [ ] Document exact activity type and asset limitations.

**Exit:** official and permitted Android path works. If not, record no-go and stop SDK implementation.

### M4 — Vertical slice

- [ ] Connect detector → bridge → `DiscordTransport`.
- [ ] Add notification Stop action and lifecycle cleanup.
- [ ] Add basic standard/private profile redaction.
- [ ] Add one YouTube adapter.
- [ ] Run bridge/security tests and real-device performance baseline.

### M5 — Customization and adapters

- [ ] Custom details/state templates.
- [ ] Registered asset-key management and validation.
- [ ] Twitch and Netflix adapters after tests.
- [ ] Per-domain rules and privacy profiles.
- [ ] Export/import local settings only if requested.

### M6 — Tor validation

- [ ] Test exact Tor Browser Android release and add-on install path.
- [ ] Review Tor Project add-on warning and user-facing disclosure.
- [ ] Verify no change to Tor security level, circuit behavior, fingerprint defenses, or browser protections is required.
- [ ] Keep unsupported install/permission paths as explicit blockers rather than recommending policy bypasses.

## Initial task backlog

- [ ] T001: record exact target versions and device ABI.
  - Acceptance: `TARGETS.md` lists browser build, Tor build, Android API, ABI, and SDK toolchain.
  - Verify: compare installed app/build versions on the device.
- [ ] T002: create Gecko MV2 event-page skeleton.
  - Acceptance: manifest contains minimum permissions and `persistent: false`.
  - Verify: `web-ext lint` plus Firefox Android load.
- [ ] T003: implement active-tab reducer and tests.
  - Acceptance: only active tab is eligible; stale states clear.
  - Verify: unit tests for activation/update/remove/order cases.
- [ ] T004: implement media state adapter contract and one HTML media test.
  - Acceptance: play/pause/end maps to normalized states.
  - Verify: automated fixture tests.
- [ ] T005: perform Companion loopback connectivity spike.
  - Acceptance: extension successfully reaches `127.0.0.1` with pairing validation on a real device.
  - Verify: integration test and Android log capture.
- [ ] T006: perform Discord SDK terms/access and Android RPC spike.
  - Acceptance: explicit go/no-go report for this non-game companion use.
  - Verify: signed-in Discord Android receives and clears one test activity, or exact blocker is documented.

## Sequencing constraints

- Do not build production Discord integration before M3 passes.
- Do not claim Tor support before M6 passes.
- Do not add broad host permissions to fix a failed site adapter; first identify the exact missing permission and request only that domain.
- Do not optimize based on assumptions; record the baseline before changing polling, caching, or thread strategy.
