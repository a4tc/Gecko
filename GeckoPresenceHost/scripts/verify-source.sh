#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
APP="$ROOT/app"

sh "$ROOT/scripts/test-core.sh"

python3 - "$ROOT" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1])
app = root / "app"
manifest_path = app / "src/main/AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
android = "{http://schemas.android.com/apk/res/android}"
services = {
    node.attrib.get(android + "name", "").split(".")[-1]
    for node in manifest.findall("./application/service")
}
required_services = {"PresenceEngineService", "PresenceNotificationListener", "BrowserAccessibilityService"}
internal_permission = "app.geckopresence.host.permission.INTERNAL_BROADCAST"
permissions = {node.attrib.get(android + "name", "") for node in manifest.findall("./permission")}
if internal_permission not in permissions:
    raise SystemExit("Signature-protected internal broadcast permission is missing")
missing = required_services - services
if missing:
    raise SystemExit(f"Manifest missing services: {sorted(missing)}")

xml_files = list((app / "src/main").rglob("*.xml"))
for path in xml_files:
    ET.parse(path)

sdk_kt = (app / "src/main/java/app/geckopresence/host/DiscordSdk.kt").read_text()
native_cpp = (app / "src/main/cpp/discord_bridge.cpp").read_text()
external_methods = re.findall(r"private\s+external\s+fun\s+(native[A-Za-z0-9_]+)", sdk_kt)
if set(external_methods) != {"nativeInit", "nativeRunCallbacks", "nativeSetActivity", "nativeClear", "nativeDestroy"}:
    raise SystemExit(f"Unexpected JNI declarations: {external_methods}")
native_activity = re.search(r"nativeSetActivity\s*\((.*?)\): Boolean", sdk_kt, re.S)
if native_activity is None or native_activity.group(1).count(": String") != 2:
    raise SystemExit("nativeSetActivity must accept only details, state, and timestamp")
if "RunCallbacks" not in native_cpp or "std::thread" in native_cpp or "g_callbackThread" in native_cpp:
    raise SystemExit("Discord callbacks must be pumped from the Android main-loop adapter, not a native worker thread")
if re.search(r"\bJNI_OnLoad\s*\(", native_cpp):
    raise SystemExit("Gecko native wrapper must not shadow the Discord SDK dependency's JNI_OnLoad hook")
native_init = re.search(r"Java_app_geckopresence_host_DiscordSdk_nativeInit\s*\(.*?\)\s*\{(.*?)\n\}", native_cpp, re.S)
if native_init is None or "GetJavaVM(&g_javaVm)" not in native_init.group(1):
    raise SystemExit("Gecko must acquire JavaVM from nativeInit rather than defining a competing JNI_OnLoad")
if "postDelayed(this, CALLBACK_INTERVAL_MS)" not in sdk_kt or "nativeRunCallbacks()" not in sdk_kt:
    raise SystemExit("Discord callback pump must be scheduled on the Android main looper")
if "activity.SetName(" in native_cpp:
    raise SystemExit("Activity.Name must remain controlled by the registered Discord application")
mapper = (app / "src/main/java/app/geckopresence/host/core/PresenceActivityMapper.kt").read_text()
if "MAX_DETAILS_LENGTH = 128" not in mapper or "PresenceActivityMapper" not in sdk_kt:
    raise SystemExit("Discord details mapping or length bound is missing")
for method in external_methods:
    symbol = f"Java_app_geckopresence_host_DiscordSdk_{method}"
    if symbol not in native_cpp:
        raise SystemExit(f"JNI implementation missing: {symbol}")

runtime = app / "src/main"
legacy_patterns = re.compile(r"BridgeHttpServer|SecureStore|nativeAuthorize|nativeRefreshAccessToken|AuthenticationActivity|extension runtime|\bXPI\b", re.IGNORECASE)
hits = []
for path in runtime.rglob("*"):
    if path.is_file() and path.suffix in {".kt", ".java", ".cpp", ".h", ".xml", ".gradle", ".kts"}:
        for number, line in enumerate(path.read_text(errors="ignore").splitlines(), 1):
            if legacy_patterns.search(line):
                hits.append(f"{path.relative_to(root)}:{number}: {line.strip()}")
if hits:
    raise SystemExit("Legacy extension/auth runtime references found:\\n" + "\\n".join(hits))

props = (root / "gradle.properties").read_text()
service_kt = (app / "src/main/java/app/geckopresence/host/PresenceEngineService.kt").read_text()
if "INTERNAL_BROADCAST_PERMISSION" not in service_kt:
    raise SystemExit("Presence source receiver is not permission-protected")
if "ACTION_EXTERNAL_" in service_kt or "receiveExternalPresence" in service_kt:
    raise SystemExit("Browser source actions still use obsolete external-bridge naming")
if "ACTION_BROWSER_PRESENCE" not in service_kt or "ACTION_BROWSER_CLEAR" not in service_kt:
    raise SystemExit("Native browser source actions are missing")
service_on_create = re.search(r"override fun onCreate\(\).*?\n    }", service_kt, re.S)
if service_on_create is None or "DiscordSdk.initialize" in service_on_create.group(0):
    raise SystemExit("Local engine startup must not initialize the native Discord SDK")
if service_kt.count("DiscordSdk.initialize(") != 1 or "RpcStartupPolicy.commandFor(action)" not in service_kt:
    raise SystemExit("Discord SDK initialization must be reachable only through explicit RPC commands")
rpc_policy = (app / "src/main/java/app/geckopresence/host/core/RpcStartupPolicy.kt").read_text()
if "ACTION_CONNECT_DISCORD" not in rpc_policy or "ACTION_DISCONNECT_DISCORD" not in rpc_policy:
    raise SystemExit("Explicit Developer Console RPC actions are missing")
notification_listener = (app / "src/main/java/app/geckopresence/host/PresenceNotificationListener.kt").read_text()
browser_service = (app / "src/main/java/app/geckopresence/host/BrowserAccessibilityService.kt").read_text()
if "INTERNAL_BROADCAST_PERMISSION" not in browser_service or "INTERNAL_BROADCAST_PERMISSION" not in notification_listener:
    raise SystemExit("Protected internal broadcasts must include the signature permission")
browser_service = (app / "src/main/java/app/geckopresence/host/BrowserAccessibilityService.kt").read_text()
browser_xml = (app / "src/main/res/xml/browser_accessibility_service.xml").read_text()
if "TYPE_VIEW_TEXT_CHANGED" not in browser_service or "typeViewTextChanged" not in browser_xml:
    raise SystemExit("Browser address-bar text changes are not observed")
activity_ui = (app / "src/main/java/app/geckopresence/host/MainActivity.kt").read_text()
activity_on_create = re.search(r"override fun onCreate\(savedInstanceState: Bundle\?\).*?\n    }", activity_ui, re.S)
activity_on_destroy = re.search(r"override fun onDestroy\(\).*?\n    }", activity_ui, re.S)
if activity_on_create is None or "DiscordSdk.attachEngineActivity(this)" not in activity_on_create.group(0):
    raise SystemExit("MainActivity must register DiscordSocialSdkInit engine Activity before UI/service actions")
if activity_on_create.group(0).index("DiscordSdk.attachEngineActivity(this)") > activity_on_create.group(0).index("buildShell()"):
    raise SystemExit("Discord engine Activity must be registered before the UI can issue an RPC connect action")
if activity_on_destroy is None or "DiscordSdk.detachEngineActivity(this)" not in activity_on_destroy.group(0):
    raise SystemExit("MainActivity must clear the Discord engine Activity when it is finishing")
if any(token not in sdk_kt for token in (
    "Class.forName(", "method.name == \"setEngineActivity\"", "setter.invoke(null, activity)",
    "engineActivityReady", "if (!engineActivityReady", "before System.loadLibrary",
)):
    raise SystemExit("DiscordSdk must invoke and gate native initialization on the Android engine Activity bootstrap")
activity_init = re.search(r"fun initialize\(context: Context, applicationId: Long\).*?\n    }", sdk_kt, re.S)
if activity_init is None or activity_init.group(0).index("if (!engineActivityReady") > activity_init.group(0).index("System.loadLibrary"):
    raise SystemExit("Discord native library loading must be blocked until Android Activity bootstrap succeeds")
if any(token not in activity_ui for token in (
    "setOnApplyWindowInsetsListener", "WindowInsets.Type.systemBars()",
    "WindowInsets.Type.displayCutout()", "WindowInsets.Type.ime()",
)):
    raise SystemExit("Target SDK 35 UI must handle system bars, display cutout, and IME insets")
action_button = re.search(r"private fun actionButton\(.*?= Button\(this\)\.apply \{(.*?)\n    \}", activity_ui, re.S)
if action_button is None or "minHeight = dp(48)" not in action_button.group(1):
    raise SystemExit("Primary action buttons must meet 48dp touch-target height")
if "LinearLayout.LayoutParams(dp(84), dp(48))" not in activity_ui:
    raise SystemExit("Priority action controls must meet 48dp touch-target height")
for token in ("PAGE_DEVELOPER", "PAGE_MORE", "buildDeveloper()", "DEVELOPER CONSOLE", "CONNECT DISCORD RPC", "ENGINE EVENT LOG"):
    if token not in activity_ui:
        raise SystemExit(f"Native navigation / Developer Console is missing: {token}")
if "isFillViewport = true" not in activity_ui or "setSingleLine(true)" not in activity_ui:
    raise SystemExit("Android widget compatibility properties are missing")

if "DISCORD_APP_ID=1569772369344602173" not in props:
    raise SystemExit("Known Discord Application ID default is missing")
application = (app / "src/main/java/app/geckopresence/host/CrashDiagnostics.kt").read_text()
manifest_app = manifest.find("./application")
if manifest_app is None or manifest_app.attrib.get(android + "name") != ".GeckoPresenceApplication":
    raise SystemExit("Application-level crash diagnostics must be installed from the Android Application")
if "getHistoricalProcessExitReasons" not in application or "traceInputStream" not in application:
    raise SystemExit("Crash diagnostics must capture ApplicationExitInfo and native tombstone streams")
if "setDefaultUncaughtExceptionHandler" not in application or "recordCheckpoint" not in application:
    raise SystemExit("Crash diagnostics must persist Java exceptions and pre-JNI checkpoints")
profile_policy = (app / "src/main/java/app/geckopresence/host/core/AppPresenceProfile.kt").read_text()
profile_store = (app / "src/main/java/app/geckopresence/host/AppPresenceProfileStore.kt").read_text()
app_policy = (app / "src/main/java/app/geckopresence/host/AppPresencePolicy.kt").read_text()
if any(token not in profile_policy for token in ("AppPresenceProfilePolicy", "MAX_ACTIVITY_LABEL_LENGTH", "MAX_DETAILS_LENGTH")):
    raise SystemExit("Per-app profile text policy or bounds are missing")
if "get(context, packageName)" not in app_policy or "AppPresenceProfileStore" not in profile_store:
    raise SystemExit("Selected Android app profiles are not wired to the source adapter")
if "PER-APP PROFILES" not in activity_ui or "SAVE APP PROFILE" not in activity_ui:
    raise SystemExit("Presence Studio per-app profile editor is missing")

workflow = (root.parent / ".github/workflows/gecko.yml").read_text()
if "gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:assembleDebug" not in workflow:
    raise SystemExit("Android CI must run unit tests and assemble the debug APK")
if "actions/setup-gradle@v6" not in workflow or "gradle-version: '8.10.2'" not in workflow:
    raise SystemExit("Android CI Gradle setup contract is missing")
print(f"Source contract checks passed: {len(xml_files)} XML files, {len(external_methods)} JNI methods, main-loop RPC callback pump, Android Activity bootstrap, dependency JNI_OnLoad preservation, crash diagnostics, per-app profiles, native-only runtime, Android CI")
PY
