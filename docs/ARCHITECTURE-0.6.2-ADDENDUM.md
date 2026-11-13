# Gecko Presence 0.6.2 — Android SDK bootstrap addendum

## Problem evidence

The supplied 0.6.1 process-exit records show repeated `NATIVE_CRASH` / `SIGABRT` exits at the transition from `System.loadLibrary` to `nativeInit`. The protobuf tombstones place the main thread in `discord::Client::ClientInit()` / `discord::Client::Client()` and a separate `WebSocketClient` thread in `abort()` inside `libdiscord_partner_sdk.so`. The protobuf's dedicated `abort_message` field is empty, but the captured thread-memory snapshot contains the exact assertion: `class_map_embedded_jni.cpp`, line 17; `Check failed: s_classMap`; `ClassMapEmbedded not initialized. Did JNI_OnLoad run correctly?`

## Root-cause analysis and change

Gecko 0.6.1 defined its own exported `JNI_OnLoad` in `libgecko_presence_native.so`. That hook only saved Gecko's `JavaVM*`; it did not run the Discord SDK's embedded JNI bootstrap. Android ART loads the requested native library with `dlopen()` and resolves one `JNI_OnLoad` using `dlsym(handle, "JNI_OnLoad")`. Bionic documents that a handle lookup searches the specified shared object and its dependencies in breadth-first order. Therefore, when the wrapper itself exports `JNI_OnLoad`, its symbol is found before the dependency's symbol. This is a direct match for the SDK tombstone assertion that `ClassMapEmbedded` was not initialized and asks whether `JNI_OnLoad` ran correctly.

Version 0.6.2 removes the wrapper-level `JNI_OnLoad`. Gecko now obtains its `JavaVM*` from the `JNIEnv` passed to `nativeInit()` using `GetJavaVM()`, which does not require a competing load hook. With the wrapper no longer shadowing the symbol, Android's lookup can reach the SDK dependency's own `JNI_OnLoad` and allow its class-map bootstrap to run.

A separate official Discord Android C++ setup requirement is also added: `DiscordSocialSdkInit.setEngineActivity(this)` is invoked in the main Activity's `onCreate()` before the UI can trigger native initialization. Native initialization is blocked unless this Java-side Activity bootstrap succeeds, and the Activity reference is cleared when the host Activity is finishing. Reflection is used because the proprietary SDK AAR is optional in Preview builds and is not included in the source archive.

The native-loader explanation is the strongest root-cause hypothesis supported by the precise tombstone assertion and Android ART/Bionic loader behavior. The Activity registration independently satisfies the documented Android integration contract. The fix remains unproven on the affected phone until CI links the real SDK AAR and the rebuilt APK is tested there.

## Scope and verification

The unauthenticated Android Rich Presence transport is unchanged: construct `discordpp::Client`, call `SetApplicationId`, process `RunCallbacks`, and call `UpdateRichPresence`; no OAuth or `Client::Connect()` is introduced. Source checks verify that Activity bootstrap precedes UI setup, detach is present in `onDestroy`, native init is gated, the wrapper does not export `JNI_OnLoad`, and Gecko acquires the VM through `GetJavaVM()` inside `nativeInit()`.

This is the highest-priority documented setup omission matching the failure timing, not a proven root cause. The official AAR and an Android device test are required to determine whether the native abort is eliminated.

## References

- Discord Android C++ standalone setup, including `DiscordSocialSdkInit.setEngineActivity(this)`: https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-on-mobile
- Discord unauthenticated Android Rich Presence requirements and direct setup: https://docs.discord.com/developers/discord-social-sdk/development-guides/setting-rich-presence
- Android ART JNI library loading and `JNI_OnLoad` dispatch (`dlsym(handle, "JNI_OnLoad")`): https://android.googlesource.com/platform/art/+/184e322/runtime/jni_internal.cc
- Android Bionic dynamic-linker handle lookup order (the requested object and its dependencies, breadth-first): https://android.googlesource.com/platform/bionic/+/76351e3e3fbbd386c059251dbc6b1081a8f77088/linker/linker.cpp
- Independent Android integration example using the Activity setter in `onCreate()` and clearing it when finishing: https://github.com/MetrolistGroup/Metrolist/pull/3874
