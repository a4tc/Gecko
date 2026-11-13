# Discord Social SDK — Android Direct Rich Presence

## Required SDK package

Gecko Presence integrates Discord's official Android Social SDK through the AAR and Android Prefab/CMake:

```cmake
find_package(discord_partner_sdk REQUIRED CONFIG)
target_link_libraries(gecko_presence_native PRIVATE discord_partner_sdk::discord_partner_sdk log)
```

Obtain `discord_partner_sdk.aar` from the Discord Developer Portal through the official Social SDK onboarding process. The project cannot generate or redistribute this proprietary binary. Place it at:

```text
GeckoPresenceHost/app/libs/discord_partner_sdk.aar
```

The AAR is not included in this source archive. Keep it in private/access-controlled storage and comply with Discord's SDK terms. Current Discord documentation and onboarding are game-focused; eligibility for a general-purpose Android presence companion has not been confirmed by this project.

## Application ID

The known application ID is configured in `gradle.properties`:

```properties
DISCORD_APP_ID=1569772369344602173
```

A Gradle property or GitHub repository variable named `DISCORD_APP_ID` may override the default. It must be a positive numeric ID. Do not commit a zero or placeholder value.

For GitHub Actions, `DISCORD_SDK_AAR_PATH` may identify an AAR stored at a private path relative to the repository root. Alternatively, the workflow accepts `discord_partner_sdk.aar` at the repository root and copies it into `app/libs/` before the build.

## Direct Rich Presence (no OAuth)

Discord documents unauthenticated Rich Presence for Android from Social SDK 1.10. The Discord Android app must be installed and signed in, and the integration must use a valid Application ID. Direct RPC does not call `Client::Connect()` or the OAuth token APIs.

The Android host must also register its foreground Activity before native client creation by calling `DiscordSocialSdkInit.setEngineActivity(this)` from `MainActivity.onCreate()`. Gecko uses a reflective adapter for this call so the local Preview build remains compilable when the optional proprietary AAR is absent. Direct RPC initialization is blocked when the Activity bootstrap has not succeeded. The reference is cleared with `setEngineActivity(null)` when the Activity is finishing.

### JNI loader ownership

`libgecko_presence_native.so` must not export its own `JNI_OnLoad`. Android ART resolves `JNI_OnLoad` by calling `dlsym()` on the loaded library handle. Bionic's handle lookup searches the wrapper and its `DT_NEEDED` dependency tree breadth-first, so a wrapper-local symbol wins before the Discord SDK dependency's hook. In SDK 1.10.19337, the crash tombstone's `ClassMapEmbedded not initialized. Did JNI_OnLoad run correctly?` assertion is consistent with the wrapper shadowing the dependency hook.

Gecko therefore obtains its `JavaVM*` through `JNIEnv::GetJavaVM()` at the beginning of `nativeInit()`, after ART has had a chance to run the SDK's JNI bootstrap hook. Do not reintroduce a wrapper-level `JNI_OnLoad` just to cache the VM.

The native adapter:

1. Creates a `discordpp::Client`.
2. Calls `SetApplicationId()` with the registered application ID.
3. Pumps `discordpp::RunCallbacks()` while the foreground presence engine is active.
4. Builds a `discordpp::Activity` and submits it with `UpdateRichPresence()`.
5. Submits an empty activity to clear the current presence.

Native client initialization is distinct from an authenticated `Ready` state. Kotlin permits Direct RPC updates after native initialization and reports acceptance/rejection from the SDK update callback.

No OAuth redirect activity, access token, refresh token, account credential, or browser login is used or stored by this runtime.

## Activity field mapping

Discord controls the first line — `Playing [application name]` — using the application name registered in the Developer Portal. That name cannot be customized per activity through `Activity.Name` for the partial Rich Presence object used by the SDK.

The current adapter maps:

- Source/activity label + user details → `Activity.details` (combined into one bounded 128-character line).
- Optional state → `Activity.state`.
- Start timestamp → `Activity.timestamps.start`.
- Activity type → `Playing`.

Artwork, custom buttons, party data, secrets, and other activity types are not currently exposed by the app UI or JNI adapter. Do not describe these as supported until the implementation and actual AAR are verified.

## Build modes

- **Preview mode:** built without the official AAR; local sources, editor, mock preview, automation, and presets can be exercised, but Discord publishing is disabled.
- **Direct RPC mode:** enabled only when the official AAR is present and `DISCORD_APP_ID` is positive.

GitHub Actions installs JDK 17, Android SDK Platform 35, NDK 27.2.12479018, CMake 3.22.1, and Gradle 8.10.2. It runs unit tests and assembles a debug APK. CI success does not prove live Discord publishing; a device test is still required.
