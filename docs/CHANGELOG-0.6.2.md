# Gecko Presence 0.6.2

## Discord Android bootstrap and JNI loader correction

- Remove Gecko’s wrapper-level `JNI_OnLoad` definition. Android ART invokes the `JNI_OnLoad` symbol resolved for the loaded wrapper handle; Bionic resolves that handle through the wrapper and its dependencies in breadth-first order. A wrapper-local hook therefore takes precedence over the SDK dependency hook and can prevent the SDK’s `ClassMapEmbedded` initialization.
- Acquire Gecko’s `JavaVM*` from the `JNIEnv` supplied to `nativeInit()` using `GetJavaVM()`, so Gecko no longer needs to define a competing `JNI_OnLoad`.
- Register the Android host Activity through `DiscordSocialSdkInit.setEngineActivity(this)` during `MainActivity.onCreate()`, before any explicit Connect action can reach native client construction.
- Add a fail-closed guard: Direct RPC initialization is refused unless Activity bootstrap succeeded.
- Clear the SDK Activity reference when the host Activity is finishing.
- Resolve the SDK bootstrap class reflectively so Preview builds remain compilable without the optional proprietary SDK AAR.
- Extend source-contract verification to check the Activity registration, lifecycle cleanup, initialization gate, absence of a wrapper-level `JNI_OnLoad`, and `GetJavaVM()` acquisition in `nativeInit()`.

## Validation boundary

The source-contract checks and core self-tests are run on the candidate source. The native-loader correction is based on Android ART calling `dlsym(handle, "JNI_OnLoad")`, Bionic searching the handle and its dependencies breadth-first, the exact SDK assertion in the supplied tombstones, and a public Android integration whose bridge does not define its own `JNI_OnLoad`. It is still a best-supported root-cause fix—not device-proven—until the official AAR is linked in CI and the Connect flow is retested on the affected Android 14 device.
