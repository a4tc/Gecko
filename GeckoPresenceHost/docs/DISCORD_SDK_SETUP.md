# Discord Social SDK Setup Notes

## SDK package and approval

The Android integration uses Discord's official Social SDK Android AAR with Android Prefab and CMake. The required CMake import is:

```cmake
find_package(discord_partner_sdk REQUIRED CONFIG)
target_link_libraries(gecko_presence_native PRIVATE discord_partner_sdk::discord_partner_sdk log)
```

The SDK AAR is a Discord-distributed binary. This project cannot generate it. Obtain it from the official Discord Developer Portal after completing the Social SDK onboarding and receiving access for the application.

Discord currently positions the Social SDK as a game integration, including its mobile support announcement. Whether a general-purpose Android Rich Presence companion qualifies is not confirmed. Ask Discord for eligibility before spending time on a release build. Do not use unofficial mirrors or undocumented Discord endpoints as a substitute.

## GitHub Actions configuration

- `DISCORD_APP_ID`: repository variable containing the numeric Application ID from your Discord Developer Portal application.
- `DISCORD_SDK_AAR_PATH`: optional repository variable containing a path relative to the repository root, such as `private-sdk/discord_partner_sdk.aar`.

The root suite workflow copies the AAR to `app/libs/discord_partner_sdk.aar` and passes the Application ID to Gradle. If either input is absent, the build is Preview mode. Do not put `DISCORD_APP_ID=0` in `gradle.properties`.

## OAuth2 / PKCE flow

The native integration follows the documented public-client pattern:

1. `Client::CreateAuthorizationCodeVerifier()` creates the PKCE verifier/challenge.
2. `AuthorizationArgs` uses the app ID, `Client::GetDefaultPresenceScopes()`, and the S256 challenge.
3. `Client::Authorize()` launches the SDK authorization flow and receives the code through the configured mobile deep link.
4. `Client::GetToken()` exchanges the code and verifier for tokens.
5. `Client::UpdateToken()` must succeed before `Client::Connect()` is called.
6. The SDK callback pump runs while the foreground service is active.

The refresh token is encrypted through Android Keystore. The access token is not persisted by the app; it is passed to the native SDK and refreshed in memory. Discord credentials never pass through the browser extension bridge.

## Mobile redirect

Use this URI in the Developer Portal and keep the Android manifest's scheme consistent:

`discord-YOUR_APP_ID:/authorize/callback`

Replace `YOUR_APP_ID` with the numeric application ID. The manifest registers `com.discord.socialsdk.AuthenticationActivity`. The app calls `DiscordSocialSdkInit.setEngineActivity(activity)` before invoking the native authorization flow.

## Activity mapping and current limits

Incoming activity events map to a Discord `Activity`:

- `name` → activity name
- `details` → details
- optional `state` → state
- `startTimestamp` → activity start timestamp

The current native transport uses `ActivityTypes::Playing`; UAM activity types are retained in extension previews, but Watching/Listening/Streaming/Competing are not published as native Discord activity types by this SDK adapter. Registered artwork assets, buttons, party data and slideshow/dependency parity are not implemented in 0.5.0. Do not claim those features until added and tested against the approved SDK version.

The extension may send activity updates before Discord reaches `Client::Status::Ready`. The host retains only the most recent activity and applies it after the SDK reports Ready; it does not queue an unbounded event history.
