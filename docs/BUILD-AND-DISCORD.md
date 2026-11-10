# Build, XPI export and Discord SDK setup

## One-APK build

The Android APK contains the browser extension as assets. The suite build workflow performs this sequence:

1. Extract the suite source ZIP.
2. Install Node dependencies and run extension tests.
3. Compile the extension TypeScript.
4. Copy `GeckoPresenceBridge/extension/` into `GeckoPresenceHost/app/src/main/assets/gpb-extension/`.
5. Run Android unit tests and build one APK.
6. Upload the APK and an optional unsigned XPI as CI artifacts.

The app's **EXPORT FIREFOX XPI** button writes the embedded extension from assets to a user-selected file destination. It does not install the XPI into Firefox automatically.

## Firefox installation

Regular Firefox for Android blocks unsigned add-ons. The app exports an unsigned XPI. The supported self-distribution route is to submit the add-on to addons.mozilla.org as an unlisted add-on, complete signing, then install the signed XPI in Firefox from file. Temporary/debug extension installation is for development and may not persist across restarts.

The Tor Browser lane remains unverified. Do not reduce Tor security settings to work around an installation restriction.

## Discord Social SDK

The app can run Android tracking and the localhost bridge in Preview mode. Discord publishing is conditional on both of these inputs:

- Official `discord_partner_sdk.aar`, downloaded from the Discord Developer Portal after completing the relevant onboarding/approval.
- A numeric `DISCORD_APP_ID` from the user's Discord Developer Portal application.

Do not synthesize or download the AAR from an unofficial mirror. Do not put the SDK binary in a public repository unless Discord's SDK license explicitly allows it. Prefer an access-controlled/private repository or private artifact storage.

In GitHub repository settings, create a repository **variable**:

`DISCORD_APP_ID = <numeric application id>`

Set the optional repository variable `DISCORD_SDK_AAR_PATH` to the AAR's path relative to the repository root (for example `private-sdk/discord_partner_sdk.aar`). The workflow copies that file into `GeckoPresenceHost/app/libs/discord_partner_sdk.aar` before building. Alternatively, the AAR may be present in the repository root as `discord_partner_sdk.aar`.

The included workflow passes `DISCORD_APP_ID` to Gradle as `ORG_GRADLE_PROJECT_DISCORD_APP_ID`. When either input is missing, it builds Preview mode. Do not set the Gradle property to `0`.

Social SDK is currently documented primarily as a game SDK and its onboarding asks for game details. Confirm eligibility with Discord for this companion use before building a release around it.

## Updating from a previous debug APK

Each GitHub-hosted debug build can be signed with a different ephemeral debug certificate. If Android refuses an in-place update, first open the old app and use **COPY KEY**. Then uninstall the old APK, install the new one, open **IMPORT**, paste the saved key, and start the bridge. The key is re-encrypted with the new install's Android Keystore. You may still need to reselect the Android app allowlist, regrant Usage Access, and sign in to Discord again. If you did not copy the old key, generate/use the new key and paste it into the browser add-on's settings.
